(ns xtdb.indexer.replica-log
  (:require [integrant.core :as ig]
            [xtdb.util :as util])
  (:import [xtdb.database DatabaseState Database$Mode ReplicaIndexer]))

(defn- replica-system [{:keys [replica-log] :as opts} indexer-conf tx-source-conf]
  (let [child-opts (dissoc opts :replica-log :indexer-conf :mode :tx-source-conf)]
    (-> {:xtdb/block-catalog child-opts
         :xtdb/table-catalog child-opts
         :xtdb/trie-catalog child-opts
         :xtdb.indexer/live-index (assoc child-opts :indexer-conf indexer-conf)
         :xtdb.indexer/crash-logger child-opts
         :xtdb.db-catalog/state child-opts
         :xtdb.tx-source/for-db (assoc child-opts :tx-source-conf tx-source-conf)
         :xtdb.indexer/for-db child-opts
         ;; READ_ONLY forces NOOP compactor
         :xtdb.compactor/for-db (assoc child-opts :mode Database$Mode/READ_ONLY)
         ;; replica LP is always read-only: it waits for blocks written by the source
         :xtdb.log/processor (assoc child-opts :log replica-log :indexer-conf indexer-conf :mode Database$Mode/READ_ONLY :subscribe-mode :tail-all)}
        (doto ig/load-namespaces))))

(defmethod ig/expand-key :xtdb.indexer/replica-log [k opts]
  {k (into {:allocator (ig/ref :xtdb.db-catalog/allocator)
            :buffer-pool (ig/ref :xtdb/buffer-pool)
            :db-storage (ig/ref :xtdb.db-catalog/storage)
            :replica-log (ig/ref :xtdb/replica-log)}
           opts)})

(defmethod ig/init-key :xtdb.indexer/replica-log
  [_ {:keys [replica-log indexer-conf tx-source-conf] :as opts}]
  (let [sys (-> (replica-system opts indexer-conf tx-source-conf)
                ig/expand ig/init)
        lp (:processor (:xtdb.log/processor sys))
        ^DatabaseState state (:xtdb.db-catalog/state sys)]
    {:replica-indexer (ReplicaIndexer. lp (:xtdb.tx-source/for-db sys) state)
     :sys sys}))

(defmethod ig/halt-key! :xtdb.indexer/replica-log [_ {:keys [sys]}]
  (ig/halt! sys))
