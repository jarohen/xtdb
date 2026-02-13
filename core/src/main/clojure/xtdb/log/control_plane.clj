(ns xtdb.log.control-plane
  (:require [integrant.core :as ig]
            [xtdb.util :as util])
  (:import [xtdb.catalog BlockCatalog]
           [xtdb.database DatabaseStorage]
           xtdb.indexer.ControlPlaneConsumer))

(defmethod ig/expand-key :xtdb.log/control-plane [k {:keys [base]}]
  {k {:base base
      :storage (ig/ref :xtdb.db-catalog/storage)}})

(defmethod ig/init-key :xtdb.log/control-plane
  [_ {{:keys [db-catalog]} :base
      :keys [^DatabaseStorage storage]}]
  (let [source-log (.getSourceLog storage)
        latest-block (BlockCatalog/getLatestBlock (.getBufferPool storage))
        latest-msg-id (or (when latest-block
                            (if (.hasLatestProcessedMsgId latest-block)
                              (.getLatestProcessedMsgId latest-block)
                              (some-> (.getLatestCompletedTx latest-block)
                                      (.getTxId))))
                          -1)
        cp (ControlPlaneConsumer. db-catalog (.getEpoch source-log) latest-msg-id)]
    {:consumer cp
     :subscription (.tailAll source-log cp (.getLatestProcessedOffset cp))}))

(defmethod ig/resolve-key :xtdb.log/control-plane [_ {:keys [consumer]}]
  consumer)

(defmethod ig/halt-key! :xtdb.log/control-plane [_ {:keys [consumer subscription]}]
  (util/close subscription)
  (util/close consumer))
