(ns xtdb.indexer.source-log
  (:require [integrant.core :as ig]
            [xtdb.util :as util])
  (:import [xtdb.database DatabaseState SourceIndexer]))

(defn- source-system [{:keys [source-log] :as opts} indexer-conf mode tx-source-conf]
  (let [child-opts (dissoc opts :source-log :indexer-conf :mode :tx-source-conf)]
    (-> {:xtdb/block-catalog child-opts
         :xtdb/table-catalog child-opts
         :xtdb/trie-catalog child-opts
         :xtdb.indexer/live-index (assoc child-opts :indexer-conf indexer-conf)
         :xtdb.indexer/crash-logger child-opts
         :xtdb.db-catalog/state child-opts
         :xtdb.tx-source/for-db (assoc child-opts :tx-source-conf tx-source-conf)
         :xtdb.indexer/for-db child-opts
         :xtdb.compactor/for-db (assoc child-opts :mode mode)
         :xtdb.log/processor (assoc child-opts :log source-log :indexer-conf indexer-conf :mode mode)}
        (doto ig/load-namespaces))))

(defmethod ig/expand-key :xtdb.indexer/source-log [k opts]
  {k (into {:allocator (ig/ref :xtdb.db-catalog/allocator)
            :buffer-pool (ig/ref :xtdb/buffer-pool)
            :db-storage (ig/ref :xtdb.db-catalog/storage)
            :source-log (ig/ref :xtdb/source-log)}
           opts)})

(defmethod ig/init-key :xtdb.indexer/source-log
  [_ {:keys [source-log indexer-conf mode tx-source-conf] :as opts}]
  (let [sys (-> (source-system opts indexer-conf mode tx-source-conf)
                ig/expand ig/init)
        lp (:processor (:xtdb.log/processor sys))
        ^DatabaseState state (:xtdb.db-catalog/state sys)]
    {:source-indexer (SourceIndexer. lp (:xtdb.compactor/for-db sys) (:xtdb.tx-source/for-db sys) state)
     :sys sys}))

(defmethod ig/halt-key! :xtdb.indexer/source-log [_ {:keys [sys]}]
  (ig/halt! sys))
