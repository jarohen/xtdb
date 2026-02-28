(ns xtdb.log.processor
  (:require [integrant.core :as ig]
            [xtdb.util :as util])
  (:import [xtdb.api IndexerConfig]
           [xtdb.api.log Log]
           [xtdb.database Database$Mode DatabaseState DatabaseStorage]
           [xtdb.indexer LeaderLogProcessor LeaderLogProcessor$StartResult
                         LogProcessor LogProcessor$System LogProcessor$SystemFactory
                         FollowerLogProcessor SourceLogProcessor]
           [xtdb.util MsgIdUtil]))

(defn- open-source-processor [{:keys [allocator ^DatabaseStorage db-storage ^DatabaseState db-state
                                      indexer-for-db compactor-for-db tx-source-for-db watchers db-catalog
                                      ^IndexerConfig indexer-conf block-flush-duration]
                                {:keys [meter-registry]} :base}
                              read-only?]
  (SourceLogProcessor. allocator meter-registry
                       db-storage db-state
                       indexer-for-db (.getLiveIndex db-state)
                       watchers compactor-for-db (set (.getSkipTxs indexer-conf))
                       read-only?
                       db-catalog
                       tx-source-for-db
                       block-flush-duration))

(defn- subscribe-source-log [^DatabaseStorage db-storage ^DatabaseState db-state ^SourceLogProcessor src-proc]
  (let [source-log (.getSourceLog db-storage)
        epoch (.getEpoch source-log)
        latest-processed-msg-id (.getLatestProcessedMsgId (.getBlockCatalog db-state))
        latest-offset (if latest-processed-msg-id
                        (if (= (MsgIdUtil/msgIdToEpoch latest-processed-msg-id) epoch)
                          (MsgIdUtil/msgIdToOffset latest-processed-msg-id)
                          -1)
                        -1)]
    (Log/tailAll source-log latest-offset src-proc)))

(defmethod ig/expand-key :xtdb.log.processor/source [k opts]
  {k (into {:allocator (ig/ref :xtdb.db-catalog/allocator)
            :buffer-pool (ig/ref :xtdb/buffer-pool)
            :db-storage (ig/ref :xtdb.db-catalog/storage)
            :db-state (ig/ref :xtdb.db-catalog/state)
            :watchers (ig/ref :xtdb.db-catalog/watchers)
            :compactor-for-db (ig/ref :xtdb.compactor/for-db)
            :tx-source-for-db (ig/ref :xtdb.tx-source/for-db)
            :indexer-for-db (ig/ref :xtdb.indexer/for-db)}
           opts)})

(defmethod ig/init-key :xtdb.log.processor/source [_ {:keys [^IndexerConfig indexer-conf ^Database$Mode mode] :as opts}]
  (when (.getEnabled indexer-conf)
    (let [read-only? (= mode Database$Mode/READ_ONLY)
          src-proc (open-source-processor opts read-only?)
          subscription (subscribe-source-log (:db-storage opts) (:db-state opts) src-proc)]
      {:source-processor src-proc, :subscription subscription})))

(defmethod ig/halt-key! :xtdb.log.processor/source [_ {:keys [subscription source-processor]}]
  (util/close subscription)
  (util/close source-processor))

;;; Follower log processor

(defn- open-follower-processor [{:keys [allocator ^DatabaseStorage db-storage ^DatabaseState db-state
                                        indexer-for-db compactor-for-db watchers db-catalog
                                        ^IndexerConfig indexer-conf]
                                  {:keys [meter-registry]} :base}]
  (FollowerLogProcessor. allocator meter-registry
                         (.getReplicaLog db-storage) (.getBufferPool db-storage)
                         db-state
                         indexer-for-db (.getLiveIndex db-state)
                         compactor-for-db (set (.getSkipTxs indexer-conf))
                         watchers
                         1024 ;; maxBufferedRecords
                         db-catalog))

(defn- subscribe-replica-log [^DatabaseStorage db-storage ^FollowerLogProcessor follower-proc]
  (let [replica-log (.getReplicaLog db-storage)]
    ;; latestReplicaMsgId isn't in the block proto yet, start from -1
    (Log/tailAll replica-log -1 follower-proc)))

(defn- open-follower-system [opts]
  (let [follower-proc (open-follower-processor opts)
        subscription (subscribe-replica-log (:db-storage opts) follower-proc)]
    (reify LogProcessor$System
      (close [_]
        (util/close subscription)
        (util/close follower-proc)))))

(defn- open-leader-system [{:keys [allocator ^DatabaseStorage db-storage ^DatabaseState db-state
                                   indexer-for-db compactor-for-db tx-source-for-db watchers db-catalog
                                   ^IndexerConfig indexer-conf block-flush-duration]
                            {:keys [meter-registry]} :base}]
  (let [source-log (.getSourceLog db-storage)
        replica-log (.getReplicaLog db-storage)
        buffer-pool (.getBufferPool db-storage)
        ^LeaderLogProcessor$StartResult start-result
        (LeaderLogProcessor/start allocator meter-registry
                                  source-log replica-log
                                  buffer-pool db-state
                                  indexer-for-db (.getLiveIndex db-state)
                                  watchers compactor-for-db
                                  (set (.getSkipTxs indexer-conf))
                                  tx-source-for-db
                                  db-catalog
                                  (or block-flush-duration (java.time.Duration/ofMinutes 5)))
        leader-proc (.getProcessor start-result)
        source-resume-offset (.getSourceResumeOffset start-result)
        subscription (Log/tailAll source-log source-resume-offset leader-proc)]
    (reify LogProcessor$System
      (close [_]
        (util/close subscription)
        (util/close leader-proc)))))

(defmethod ig/expand-key :xtdb.log/processor [k opts]
  {k (into {:allocator (ig/ref :xtdb.db-catalog/allocator)
            :db-storage (ig/ref :xtdb.db-catalog/storage)
            :db-state (ig/ref :xtdb.db-catalog/state)
            :watchers (ig/ref :xtdb.db-catalog/watchers)
            :compactor-for-db (ig/ref :xtdb.compactor/for-db)
            :indexer-for-db (ig/ref :xtdb.indexer/for-db)}
           opts)})

(defmethod ig/init-key :xtdb.log/processor [_ {:keys [^IndexerConfig indexer-conf] :as opts}]
  (when (.getEnabled indexer-conf)
    (let [factory (reify LogProcessor$SystemFactory
                    (openFollower [_] (open-follower-system opts))
                    (openLeader [_] (open-leader-system opts)))]
      (LogProcessor. factory))))

(defmethod ig/halt-key! :xtdb.log/processor [_ processor]
  (util/close processor))
