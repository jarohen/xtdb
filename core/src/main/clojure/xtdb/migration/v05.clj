(ns xtdb.migration.v05
  (:require [clojure.tools.logging :as log]
            xtdb.node.impl
            [xtdb.object-store :as os]
            [xtdb.serde :as serde]
            [xtdb.trie :as trie]
            [xtdb.util :as util])
  (:import [java.nio ByteBuffer]
           [java.nio.channels ClosedByInterruptException]
           [java.nio.file Path]
           xtdb.BufferPool))

(defn migrate->v06! [^BufferPool src, ^BufferPool target]
  (letfn [(copy-file! [^Path src-path, ^Path target-path]
            (try
              (->> (.getByteArray src src-path)
                   (ByteBuffer/wrap)
                   (.putObject target target-path))

              (catch InterruptedException e (throw e))
              (catch ClosedByInterruptException _ (throw (InterruptedException.)))
              (catch Throwable e
                (log/errorf e "error copying file: '%s' -> '%s" src-path target-path))))]
    (dorun
     (->> (.listAllObjects src (util/->path "chunk-metadata"))
          (map-indexed (fn [block-idx obj]
                         (when (Thread/interrupted) (throw (InterruptedException.)))

                         (let [{obj-key :key} (os/<-StoredObject obj)
                               {:keys [tables]} (-> (.getByteArray src obj-key)
                                                    (serde/read-transit :json))]
                           (doseq [[table-name {:keys [trie-key]}] tables
                                   :let [table-path (trie/table-name->table-path table-name)
                                         data-path (.resolve table-path "data")
                                         meta-path (.resolve table-path "meta")
                                         new-trie-key (trie/->l0-trie-key block-idx)]]
                             (log/tracef "Copying '%s' '%s' -> '%s'" table-name trie-key new-trie-key)

                             (copy-file! (.resolve data-path (str trie-key ".arrow"))
                                         (.resolve data-path (str new-trie-key ".arrow")))

                             (copy-file! (.resolve meta-path (str trie-key ".arrow"))
                                         (.resolve meta-path (str new-trie-key ".arrow")))))))))))
