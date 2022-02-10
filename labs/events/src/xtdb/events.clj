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
           (org.apache.kafka.common.errors TopicExistsException)
           (org.apache.kafka.common.serialization Deserializer Serializer)))

(defprotocol CommandRepository
  (submit-handler [cmd-repo cmd-type handler])
  (get-handler [cmd-repo cmd-id])
  (get-latest-id [cmd-repo cmd-type]))

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

(defn consume-cmds [!cmds !caught-up tp]
  (let [^Deserializer deserializer (->TransitDeserializer)]
    (with-open [consumer (KafkaConsumer. ^Map (into broker-opts
                                                    {"enable.auto.commit" "false"
                                                     "isolation.level" "read_committed"
                                                     "auto.offset.reset" "earliest"})
                                         deserializer deserializer)]
      (try
        (.assign consumer ^Collection [tp])

        (while (not (Thread/interrupted))
          (when-let [records (seq (.poll consumer (Duration/ofSeconds 1)))]
            (swap! !cmds (fn [cmds]
                           (reduce (fn [cmds ^ConsumerRecord record]
                                     (let [cmd-type (.key record)
                                           {:keys [version cmd-handler]} (.value record)]
                                       (-> cmds
                                           (assoc-in [:latest-ids cmd-type] version)
                                           (assoc-in [:cmd-handlers version] (eval cmd-handler)))))
                                   cmds
                                   records)))
            (deliver !caught-up true)))
        (catch Throwable e
          (deliver !caught-up false)
          (throw e))))))

(defn ->cmd-repo ^java.lang.AutoCloseable [{:keys [cmd-topic-name]}]
  (with-open [admin-client (AdminClient/create ^Map broker-opts)]
    (ensure-topics-created admin-client (->new-topic cmd-topic-name command-topic-opts)))

  (let [^Serializer serializer (->TransitSerializer)

        !cmds (atom {})

        tp (TopicPartition. cmd-topic-name 0)

        producer (KafkaProducer. ^Map (into broker-opts
                                            {"enable.idempotence" "true"
                                             "acks" "all"})
                                 serializer serializer)]
    (try
      (let [!caught-up (promise)
            ^Future !consumer-fut (future
                                    (consume-cmds !cmds !caught-up tp))]
        (or @!caught-up (throw (RuntimeException.)))

        (reify
          CommandRepository
          (submit-handler [_ cmd-type cmd-handler]
            (let [fut (CompletableFuture.)]
              (.send producer (ProducerRecord. cmd-topic-name cmd-type
                                               {:id (UUID/randomUUID)
                                                :cmd-handler cmd-handler})
                     (reify Callback
                       (onCompletion [_ record e]
                         (if e
                           (.completeExceptionally fut e)
                           (.complete fut record)))))
              fut))

          (get-handler [_ cmd-id] (get-in @!cmds [:cmd-handlers cmd-id]))
          (get-latest-id [_ cmd-type] (get-in @!cmds [:latest-ids cmd-type]))

          AutoCloseable
          (close [_]
            (.close producer)
            (.cancel !consumer-fut true))))

      (catch Throwable e
        (.close producer)
        (throw e)))))
