(ns xtdb.tx-ops
  (:require [clojure.spec.alpha :as s]
            [xtdb.error :as err]
            [xtdb.time :as time]
            [xtdb.xtql.edn :as xtql.edn])
  (:import [java.io Writer]
           [java.util List]
           (xtdb.api.tx TxOp TxOp$AssertExists TxOp$AssertNotExists TxOp$Delete TxOp$Erase TxOp$Insert TxOp$Update TxOp$XtqlAndArgs TxOps)
           xtdb.types.ClojureForm
           xtdb.util.NormalForm))

(defprotocol Unparse
  (unparse-tx-op [this]))

(defrecord PutDocs [table-name docs valid-from valid-to]
  TxOp
  Unparse
  (unparse-tx-op [_]
    (into [:put-docs (if (or valid-from valid-to)
                       {:into table-name
                        :valid-from valid-from
                        :valid-to valid-to}
                       table-name)]
          docs)))

(defmethod print-dup PutDocs [op ^Writer w]
  (.write w (format "#xt.tx/put-docs %s" (pr-str (into {} op)))))

(defmethod print-method PutDocs [op ^Writer w]
  (print-dup op w))

(defrecord DeleteDocs [table-name doc-ids valid-from valid-to]
  TxOp
  Unparse
  (unparse-tx-op [_]
    (into [:delete-docs (if (or valid-from valid-to)
                          {:from table-name
                           :valid-from valid-from
                           :valid-to valid-to}
                          table-name)]
          doc-ids)))

(defmethod print-dup DeleteDocs [op ^Writer w]
  (.write w (format "#xt.tx/delete-docs %s" (pr-str (into {} op)))))

(defmethod print-method DeleteDocs [op ^Writer w]
  (print-dup op w))

(defrecord EraseDocs [table-name doc-ids]
  TxOp
  Unparse
  (unparse-tx-op [_]
    (into [:erase-docs table-name] doc-ids)))

(defmethod print-dup EraseDocs [op ^Writer w]
  (.write w (format "#xt.tx/erase-docs %s" (pr-str (into {} op)))))

(defmethod print-method EraseDocs [op ^Writer w]
  (print-dup op w))

(defmulti parse-tx-op
  (fn [tx-op]
    (when-not (vector? tx-op)
      (throw (err/illegal-arg :xtql/malformed-tx-op
                              {::err/message "expected vector for tx-op", :tx-op tx-op})))

    (let [[op] tx-op]
      (when-not (keyword? op)
        (throw (err/illegal-arg :xtql/malformed-tx-op
                                {::err/message "expected keyword for op", :tx-op tx-op, :op op})))

      op))
  :default ::default)

(defmethod parse-tx-op ::default [[op]]
  (throw (err/illegal-arg :xtql/unknown-tx-op {:op op})))

(def ^:private eid? (some-fn uuid? integer? string? keyword?))

(def ^:private table? keyword?)

(defn- expect-table-name ^String [table-name]
  (when-not (table? table-name)
    (throw (err/illegal-arg :xtdb.tx/invalid-table
                            {::err/message "expected table name" :table table-name})))

  (str (NormalForm/normalTableName table-name)))

(defn- expect-eid [eid]
  (if-not (eid? eid)
    (throw (err/illegal-arg :xtdb.tx/invalid-eid
                            {::err/message "expected xt/id", :xt/id eid}))
    eid))

(defn- expect-doc [doc]
  (when-not (map? doc)
    (throw (err/illegal-arg :xtdb.tx/expected-doc
                            {::err/message "expected doc map", :doc doc})))

  (expect-eid (or (:xt/id doc) (get doc "xt/id")))

  doc)

(defn- expect-instant ^java.time.Instant [instant]
  (when-not (s/valid? ::time/datetime-value instant)
    (throw (err/illegal-arg :xtdb/invalid-date-time
                            {::err/message "expected date-time"
                             :timestamp instant})))

  (time/->instant instant))

(defmethod parse-tx-op :sql [[_ sql & arg-rows]]
  (if-not (string? sql)
    (throw (err/illegal-arg :xtdb.tx/expected-sql
                            {::err/message "Expected SQL query",
                             :sql sql}))

    (cond-> (TxOps/sql sql)
      (seq arg-rows) (.argRows ^List (vec arg-rows)))))

(defmethod parse-tx-op :put-docs [[_ table-or-opts & docs]]
  (let [{table :into, :keys [valid-from valid-to]} (cond
                                                     (map? table-or-opts) table-or-opts
                                                     (keyword? table-or-opts) {:into table-or-opts})]
    (->PutDocs (expect-table-name table)
               (mapv expect-doc docs)
               (some-> valid-from expect-instant)
               (some-> valid-to expect-instant))))

(defn- expect-fn-id [fn-id]
  (if-not (eid? fn-id)
    (throw (err/illegal-arg :xtdb.tx/invalid-fn-id {::err/message "expected fn-id", :fn-id fn-id}))
    fn-id))

(defn- expect-tx-fn [tx-fn]
  (or tx-fn
      (throw (err/illegal-arg :xtdb.tx/invalid-tx-fn {::err/message "expected tx-fn", :tx-fn tx-fn}))))

(defmethod parse-tx-op :put-fn [[_ id-or-opts tx-fn]]
  (let [{:keys [fn-id valid-from valid-to]} (if (map? id-or-opts)
                                              id-or-opts
                                              {:fn-id id-or-opts})]
    (->PutDocs "xt/tx_fns"
               [{"_id" (expect-fn-id fn-id)
                 "fn" (ClojureForm. (expect-tx-fn tx-fn))}]
               (some-> valid-from expect-instant)
               (some-> valid-to expect-instant))))

(defmethod parse-tx-op :insert-into [[_ table query & arg-rows :as this]]
  (when-not (keyword? table)
    (throw (err/illegal-arg :xtql/malformed-table
                            {::err/message "expected keyword", :table table, :insert this})))

  (cond-> (TxOps/insert (str (symbol table)) (xtql.edn/parse-query query))
    (seq arg-rows) (.argRows ^List arg-rows)))

(defmethod parse-tx-op :update [[_ opts & arg-rows :as this]]
  (when-not (map? opts)
    (throw (err/illegal-arg :xtql/malformed-opts
                            {::err/message "expected map", :opts opts, :update this})))

  (let [{:keys [table for-valid-time bind unify], set-specs :set} opts]

    (when-not (keyword? table)
      (throw (err/illegal-arg :xtql/malformed-table
                              {::err/message "expected keyword", :table table, :update this})))

    (when-not (map? set-specs)
      (throw (err/illegal-arg :xtql/malformed-set
                              {:err/message "expected map", :set set-specs, :update this})))

    (when-not (or (nil? bind) (vector? bind))
      (throw (err/illegal-arg :xtql/malformed-bind
                              {::err/message "expected nil or vector", :bind bind, :update this})))

    (cond-> (TxOps/update (str (symbol table)) (xtql.edn/parse-col-specs set-specs this))
      for-valid-time (.forValidTime (xtql.edn/parse-temporal-filter for-valid-time :for-valid-time this))
      bind (.binding (xtql.edn/parse-out-specs bind this))
      (seq unify) (.unify (mapv xtql.edn/parse-unify-clause unify))
      (seq arg-rows) (.argRows ^List arg-rows))))

(defmethod parse-tx-op :delete [[_ {table :from, :keys [for-valid-time bind unify]} & arg-rows :as this]]
  (when-not (keyword? table)
    (throw (err/illegal-arg :xtql/malformed-table
                            {::err/message "expected keyword", :from table, :delete this})))

  (cond-> (TxOps/delete (str (symbol table)))
    for-valid-time (.forValidTime (xtql.edn/parse-temporal-filter for-valid-time :for-valid-time this))
    bind (.binding (xtql.edn/parse-out-specs bind this))
    unify (.unify (mapv xtql.edn/parse-unify-clause unify))
    (seq arg-rows) (.argRows ^List arg-rows)))

(defmethod parse-tx-op :delete-docs [[_ table-or-opts & doc-ids]]
  (let [{table :from, :keys [valid-from valid-to]} (cond
                                                     (map? table-or-opts) table-or-opts
                                                     (keyword? table-or-opts) {:from table-or-opts})]
    (->DeleteDocs (expect-table-name table) (mapv expect-eid doc-ids)
                  (some-> valid-from expect-instant)
                  (some-> valid-to expect-instant))))

(defmethod parse-tx-op :erase [[_ {table :from, :keys [bind unify]} & arg-rows :as this]]
  (when-not (keyword? table)
    (throw (err/illegal-arg :xtql/malformed-table
                            {::err/message "expected keyword", :table table, :erase this})))

  (cond-> (TxOps/erase (str (symbol table)))
    bind (.binding (xtql.edn/parse-out-specs bind this))
    unify (.unify (mapv xtql.edn/parse-unify-clause unify))
    (seq arg-rows) (.argRows ^List arg-rows)))

(defmethod parse-tx-op :erase-docs [[_ table & doc-ids]]
  (->EraseDocs (expect-table-name table) (mapv expect-eid doc-ids)))

(defmethod parse-tx-op :assert-exists [[_ query & arg-rows]]
  (cond-> (TxOps/assertExists (xtql.edn/parse-query query))
    (seq arg-rows) (TxOp$XtqlAndArgs. arg-rows)))

(defmethod parse-tx-op :assert-not-exists [[_ query & arg-rows]]
  (cond-> (TxOps/assertNotExists (xtql.edn/parse-query query))
    (seq arg-rows) (TxOp$XtqlAndArgs. arg-rows)))

(defmethod parse-tx-op :call [[_ f & args]]
  (TxOps/call (expect-fn-id f) (or args [])))

(extend-protocol Unparse
  TxOp$Insert
  (unparse-tx-op [query]
    [:insert-into (keyword (.table query)) (xtql.edn/unparse-query (.query query))])

  TxOp$Update
  (unparse-tx-op [query]
    (let [for-valid-time (some-> (.forValidTime query) xtql.edn/unparse)
          bind (some->> (.bindSpecs query) (mapv xtql.edn/unparse-out-spec))
          unify (some->> (.unifyClauses query) (mapv xtql.edn/unparse-unify-clause))]
      [:update (cond-> {:table (keyword (.table query))
                        :set (into {} (map xtql.edn/unparse-col-spec) (.setSpecs query))}
                 for-valid-time (assoc :for-valid-time for-valid-time)
                 bind (assoc :bind bind)
                 unify (assoc :unify unify))]))

  TxOp$Delete
  (unparse-tx-op [query]
    (let [for-valid-time (some-> (.forValidTime query) xtql.edn/unparse)
          bind (some->> (.bindSpecs query) (mapv xtql.edn/unparse-out-spec))
          unify (some->> (.unifyClauses query) (mapv xtql.edn/unparse-unify-clause))]
      [:delete (cond-> {:from (keyword (.table query))}
                 for-valid-time (assoc :for-valid-time for-valid-time)
                 bind (assoc :bind bind)
                 unify (assoc :unify unify))]))

  TxOp$Erase
  (unparse-tx-op [query]
    (let [bind (some->> (.bindSpecs query) (mapv xtql.edn/unparse-out-spec))
          unify (some->> (.unifyClauses query) (mapv xtql.edn/unparse-unify-clause))]
      [:erase (cond-> {:from (keyword (.table query))}
                bind (assoc :bind bind)
                unify (assoc :unify unify))]))

  TxOp$AssertExists
  (unparse-tx-op [query]
    [:assert-exists (xtql.edn/unparse-query (.query query))])

  TxOp$AssertNotExists
  (unparse-tx-op [query]
    [:assert-not-exists (xtql.edn/unparse-query (.query query))])

  TxOp$XtqlAndArgs
  (unparse-tx-op [query+args]
    (into (unparse-tx-op (.op query+args))
          (.argRows query+args))))
