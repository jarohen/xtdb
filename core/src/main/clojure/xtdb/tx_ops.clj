(ns xtdb.tx-ops
  "Eager-ises the deferred client tx-ops (`xtdb.tx.{Sql,PutDocs,…}`, row-major args/docs)
   into the core `xtdb.tx.TxOp$*` ops the indexer consumes, opening the backing Arrow
   relations. Sits below the SQL/log/node stack so both can require it without a cycle."
  (:require [xtdb.error :as err]
            [xtdb.time :as time]
            [xtdb.util :as util])
  (:import org.apache.arrow.memory.BufferAllocator
           (xtdb.arrow Relation Vector)
           (xtdb.tx DeleteDocs EraseDocs PatchDocs PutDocs PutRel Sql SqlByteArgs
                    TxOp$DeleteDocs TxOp$EraseDocs TxOp$PatchDocs TxOp$PutDocs TxOp$Sql)))

(set! *unchecked-math* :warn-on-boxed)

(defprotocol OpenTxOp
  (open-tx-op [tx-op al opts]))

(extend-protocol OpenTxOp
  Sql
  (open-tx-op [^Sql op al _opts]
    (let [arg-rows (.getArgRows op)]
      (TxOp$Sql. (.getSql op) (when (seq arg-rows)
                                 (Relation/openFromRows al
                                                        (for [arg-row arg-rows]
                                                          (into {}
                                                                (map-indexed (fn [idx v] [(str "?_" idx) v]))
                                                                arg-row)))))))

  SqlByteArgs
  (open-tx-op [^SqlByteArgs op al _opts]
    (TxOp$Sql. (.getSql op) (when-let [arg-bytes (.getArgBytes op)]
                               (Relation/openFromArrowStream al arg-bytes))))

  PutDocs
  (open-tx-op [^PutDocs op al opts]
    (let [docs (.getDocs op)]
      (doseq [doc docs]
        (when-not (or (:xt/id doc) (get doc "_id"))
          (throw (err/incorrect :missing-id "missing '_id'" {:doc doc}))))

      (let [table-name (.getTableName op)]
        (TxOp$PutDocs. (or (namespace table-name) "public") (name table-name)
                       (some-> (.getValidFrom op) (time/->instant opts)) (some-> (.getValidTo op) (time/->instant opts))
                       (Relation/openFromRows al docs)))))

  PutRel
  (open-tx-op [^PutRel op ^BufferAllocator al _opts]
    (util/with-open [ldr (Relation/streamLoader al (.getRelBytes op))]
      (util/with-close-on-catch [rel (Relation. al (.getSchema ldr))]
        (or (.loadNextPage ldr rel)
            (throw (AssertionError. "No data in PutRel rel-bytes")))
        (let [table-name (.getTableName op)]
          (TxOp$PutDocs. (or (namespace table-name) "public") (name table-name)
                         nil nil
                         rel)))))

  PatchDocs
  (open-tx-op [^PatchDocs op al opts]
    (let [table-name (.getTableName op)]
      (TxOp$PatchDocs. (or (namespace table-name) "public") (name table-name)
                       (some-> (.getValidFrom op) (time/->instant opts)) (some-> (.getValidTo op) (time/->instant opts))
                       (Relation/openFromRows al (.getDocs op)))))

  DeleteDocs
  (open-tx-op [^DeleteDocs op ^BufferAllocator al opts]
    (let [table-name (.getTableName op)]
      (TxOp$DeleteDocs. (or (namespace table-name) "public") (name table-name)
                        (some-> (.getValidFrom op) (time/->instant opts)) (some-> (.getValidTo op) (time/->instant opts))
                        (Vector/fromList al "_id" (.getDocIds op)))))

  EraseDocs
  (open-tx-op [^EraseDocs op ^BufferAllocator al _opts]
    (let [table-name (.getTableName op)]
      (TxOp$EraseDocs. (or (namespace table-name) "public") (name table-name)
                       (Vector/fromList al "_id" (.getDocIds op))))))
