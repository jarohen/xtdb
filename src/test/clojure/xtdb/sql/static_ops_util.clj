(ns xtdb.sql.static-ops-util
  "Test helper for `sql/sql->static-ops`, which now yields the eager core `TxOp`s (with a `RelationReader` of docs)
  rather than value-comparable deferred ops. `static-ops` opens them against a throwaway allocator and renders them
  back to plain EDN for comparison; `->inst` expresses the valid-times after the eager-ise step's UTC coercion."
  (:require [xtdb.sql :as sql]
            [xtdb.util :as util])
  (:import (java.time Instant)
           (org.apache.arrow.memory RootAllocator)
           (xtdb.api.query IKeyFn$KeyFn)
           (xtdb.tx TxOp$PutDocs TxOp$PatchDocs)))

(defn ->inst
  "The instant a bare date coerces to under the UTC default-tz that `static-ops` pins."
  ^Instant [date-str]
  (Instant/parse (str date-str "T00:00:00Z")))

(defn- op->edn [op]
  (condp instance? op
    TxOp$PutDocs (let [^TxOp$PutDocs op op]
                   {:put (symbol (.getSchema op) (.getTable op))
                    :docs (mapv #(into {} %) (.toMaps (.getDocs op) IKeyFn$KeyFn/SNAKE_CASE_STRING))
                    :valid-from (.getValidFrom op), :valid-to (.getValidTo op)})
    TxOp$PatchDocs (let [^TxOp$PatchDocs op op]
                     {:patch (symbol (.getSchema op) (.getTable op))
                      :docs (mapv #(into {} %) (.toMaps (.getPatches op) IKeyFn$KeyFn/SNAKE_CASE_STRING))
                      :valid-from (.getValidFrom op), :valid-to (.getValidTo op)})))

(defn static-ops
  "Run `sql->static-ops` against a throwaway allocator (default-tz UTC) and render the eager ops to EDN; nil when the
  statement isn't statically expandable. Propagates op-validation errors (e.g. missing `_id`)."
  ([sql arg-rows] (static-ops sql arg-rows {}))
  ([sql arg-rows opts]
   (with-open [al (RootAllocator.)]
     (when-let [ops (sql/sql->static-ops sql arg-rows al (assoc opts :default-tz #xt/zone "UTC"))]
       (try
         (mapv op->edn ops)
         (finally (run! util/close ops)))))))
