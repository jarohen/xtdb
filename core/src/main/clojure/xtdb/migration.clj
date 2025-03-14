(ns xtdb.migration
  (:require [integrant.core :as ig]
            [xtdb.error :as err]
            [xtdb.migration.v05 :as v05]
            [xtdb.node :as xtn]
            xtdb.node.impl
            [xtdb.util :as util])
  (:import [xtdb.api Xtdb$Config]
           [xtdb.api.storage Storage Storage$Factory]
           xtdb.BufferPool))

(defmethod ig/prep-key ::storage [_ {:keys [factory storage-version], :or {storage-version Storage/VERSION}}]
  {:allocator (ig/ref :xtdb/allocator)
   :factory factory
   :storage-version storage-version
   :metrics-registry (ig/ref :xtdb.metrics/registry)})

(defmethod ig/init-key ::storage [_ {:keys [allocator ^Storage$Factory factory, metrics-registry, ^long storage-version]}]
  (.open factory allocator metrics-registry storage-version))

(defmethod ig/halt-key! ::storage [_ ^BufferPool buffer-pool]
  (util/close buffer-pool))

(derive ::source ::storage)
(derive ::target ::storage)

(defn migration-system [^Xtdb$Config opts, ^long from-version]
  (-> {:xtdb/config opts
       :xtdb/allocator {}
       :xtdb.metrics/registry {}
       ::source {:factory (.getStorage opts), :storage-version from-version}
       ::target {:factory (.getStorage opts)}}

      (doto ig/load-namespaces)))

(defn migrate-from [^long from-version node-opts]
  (let [{::keys [source target] :as system} (-> (migration-system (xtn/->config node-opts) from-version)
                                                ig/prep
                                                ig/init)]
    (try
      (case from-version
        5 (v05/migrate->v06! source target)
        (throw (err/illegal-arg :unsupported-migration-version
                                {::err/message (format "Unsupported migration version: %d" from-version)})))
      (finally
        (ig/halt! system)))))

