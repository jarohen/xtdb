(ns xtdb.events
  (:require [cognitect.transit :as transit])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.lang AutoCloseable)
           (java.time Duration)
           (java.util Collection Map UUID)
           (java.util.concurrent CompletableFuture ExecutionException Future)
           (org.apache.kafka.clients.admin AdminClient NewTopic)
           (org.apache.kafka.clients.consumer ConsumerRecord KafkaConsumer)
           (org.apache.kafka.clients.producer Callback KafkaProducer ProducerRecord)
           (org.apache.kafka.common TopicPartition)
           (org.apache.kafka.common.errors InterruptException TopicExistsException)
           (org.apache.kafka.common.serialization Deserializer Serializer)))

(defprotocol CommandRepository
  (-submit-handler [cmd-repo cmd-type handler])
  (->cmd-handler [cmd-repo cmd-id])
  (latest-cmd-handler-id [cmd-repo cmd-type]))

(defprotocol EventNode
  (submit-handler [node cmd-type handler])
  (submit-cmd [node cmd-type cmd])
  (submit-evt [node evt-type evt]))

;; ser/de pinched from https://github.com/ymilky/franzy-transit

(defrecord TransitSerializer []
  Serializer
  (configure [_ _ _])
  (serialize [_ _ data]
    (when data
      (let [baos (ByteArrayOutputStream.)]
        (-> (transit/writer baos :json)
            (transit/write data))
        (.toByteArray baos))))
  (close [_]))

(defrecord TransitDeserializer []
  Deserializer
  (configure [_ _ _])
  (deserialize [_ _ data]
    (when data
      (-> (transit/reader (ByteArrayInputStream. data) :json)
          (transit/read))))
  (close [_]))

(def ^java.util.Map broker-opts
  {"bootstrap.servers" ["127.0.0.1:9092"]})

(def command-topic-opts
  {:partitions 1
   :replication-factor 1
   :topic-properties {"retention.ms" (str -1)}})

(defn ->new-topic
  ^org.apache.kafka.clients.admin.NewTopic
  [^String topic-name {:keys [^int partitions ^short replication-factor ^Map topic-properties]}]

  (-> (NewTopic. topic-name partitions (short replication-factor))
      (.configs topic-properties)))

(defn ensure-topics-created [^AdminClient admin-client & new-topics]
  (try
    @(.all (.createTopics admin-client new-topics))
    (catch ExecutionException e
      (try
        (throw (.getCause e))
        (catch TopicExistsException _)))))

(defn consume-cmds [!cmds !caught-up {:keys [cmd-topic-name]}]
  (try
    (let [tp (TopicPartition. cmd-topic-name 0)
          ^Deserializer deserializer (->TransitDeserializer)]
      (with-open [consumer (KafkaConsumer. ^Map (into broker-opts
                                                      {"enable.auto.commit" "false"
                                                       "isolation.level" "read_committed"
                                                       "auto.offset.reset" "earliest"})
                                           deserializer deserializer)]
        (try
          (.assign consumer ^Collection [tp])

          (let [target-offset (-> (.endOffsets consumer [tp])
                                  (get tp))]

            (while (not (Thread/interrupted))
              (when (and (not (realized? !caught-up))
                         (or (nil? target-offset)
                             (>= (.position consumer tp)
                                 target-offset)))
                (deliver !caught-up true))

              (when-let [records (seq (.poll consumer (Duration/ofSeconds 1)))]
                (swap! !cmds (fn [cmds]
                               (reduce (fn [cmds ^ConsumerRecord record]
                                         (let [cmd-type (.key record)
                                               {:keys [id cmd-handler]} (.value record)]
                                           (-> cmds
                                               (assoc-in [:latest-ids cmd-type] id)
                                               (assoc-in [:cmd-handlers id] (eval cmd-handler)))))
                                       cmds
                                       records))))))
          (catch Throwable e
            (deliver !caught-up false)
            (throw e)))))
    (catch InterruptException _)
    (catch InterruptedException _)
    (catch Throwable e
      (.printStackTrace e)
      (throw e))))

(defn- ->producer ^org.apache.kafka.clients.producer.KafkaProducer []
  (let [^Serializer serializer (->TransitSerializer)]
    (KafkaProducer. ^Map (into broker-opts
                               {"enable.idempotence" "true"
                                "acks" "all"})
                    serializer serializer)))

(defn- fut+callback []
  (let [fut (CompletableFuture.)]
    [fut (reify Callback
           (onCompletion [_ record e]
             (if e
               (.completeExceptionally fut e)
               (.complete fut record))))]))

(defn ->cmd-repo ^java.lang.AutoCloseable [{:keys [cmd-topic-name]}]
  (with-open [admin-client (AdminClient/create ^Map broker-opts)]
    (ensure-topics-created admin-client (->new-topic cmd-topic-name command-topic-opts)))

  (let [producer (->producer)
        !cmds (atom {})]
    (try
      (let [!caught-up (promise)
            ^Future !consumer-fut (future
                                    (consume-cmds !cmds !caught-up {:cmd-topic-name cmd-topic-name}))]
        (try
          (or (deref !caught-up 2000 false)
              (throw (RuntimeException.)))

          (reify
            CommandRepository
            (-submit-handler [_ cmd-type cmd-handler]
              (let [[fut callback] (fut+callback)]
                (.send producer
                       (ProducerRecord. cmd-topic-name cmd-type
                                        {:id (UUID/randomUUID)
                                         :cmd-handler cmd-handler})
                       callback)
                fut))

            (->cmd-handler [_ cmd-id] (get-in @!cmds [:cmd-handlers cmd-id]))
            (latest-cmd-handler-id [_ cmd-type] (get-in @!cmds [:latest-ids cmd-type]))

            AutoCloseable
            (close [_]
              (.close producer)
              (.cancel !consumer-fut true)))

          (catch Throwable e
            (.cancel !consumer-fut true)
            (throw e))))

      (catch Throwable e
        (.close producer)
        (throw e)))))

(def topic-opts
  {:partitions 1
   :replication-factor 1
   ;; we likely want some kind of deletion here, to clear up in case of accidents
   :topic-properties {"retention.ms" (str -1)}})

(defn ->node ^java.lang.AutoCloseable [{:keys [cmd-repo]} {:keys [topic-name]}]
  (with-open [admin-client (AdminClient/create ^Map broker-opts)]
    (ensure-topics-created admin-client (->new-topic topic-name topic-opts)))

  (let [producer (->producer)]
    (letfn [(submit-msg [v]
              (let [[fut callback] (fut+callback)]
                (.send producer (ProducerRecord. topic-name (UUID/randomUUID) v) callback)
                fut))]
      (reify
        EventNode
        (submit-handler [_ cmd-type handler]
          (-submit-handler cmd-repo cmd-type handler))

        (submit-cmd [_ cmd-type cmd]
          (if-let [handler-id (latest-cmd-handler-id cmd-repo cmd-type)]
            (submit-msg [:cmd cmd-type handler-id cmd])
            (throw (UnsupportedOperationException. "can't find command handler"))))

        (submit-evt [_ evt-type evt]
          (submit-msg [:evt evt-type evt]))

        AutoCloseable
        (close [_]
          (.close producer))))))

(comment
  (with-open [cmd-repo (->cmd-repo {:cmd-topic-name "_cmds"})
              node (->node {:cmd-repo cmd-repo} {:topic-name "my-stream"})]
    #_
    (submit-handler node :create-user! '(fn [{:keys [name]}]
                                          [[:user-created {:user-id (java.util.UUID/randomUUID)
                                                           :name name}]]))
    #_
    (submit-evt node :user-created {:user-id (UUID/randomUUID)
                                    :name "James"})

    #_
    (latest-cmd-handler-id cmd-repo :create-user!)

    #_
    (submit-cmd node :create-user! {:name "James"})))
