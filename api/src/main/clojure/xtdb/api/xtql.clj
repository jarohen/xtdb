(ns xtdb.api.xtql
  (:require [clojure.string :as str]
            [xtdb.error :as err])
  (:import [java.io Writer]))

(defprotocol Unparse
  (unparse [this]))

(declare parse-arg-specs parse-query parse-expr)

;;;; Expr

(definterface Expr)

(defmethod print-dup Expr [expr ^Writer w]
  (.write w (format "#xtql/expr %s" (pr-str (unparse expr)))))

(defmethod print-method Expr [expr ^Writer w]
  (print-dup expr w))

(do
  (defrecord Null []
    Expr
    Unparse (unparse [_] nil))

  (def null (Null.)))

(do
  (defrecord Bool [^boolean value]
    Expr
    Unparse (unparse [_] value))

  (def t (Bool. true))
  (def f (Bool. false)))

(defrecord Lng [^long value]
  Expr
  Unparse (unparse [_] value))

(defrecord Dbl [^double value]
  Expr
  Unparse (unparse [_] value))

(defrecord Obj [value]
  Expr
  Unparse (unparse [_] value))

(defrecord LogicVar [lv]
  Expr
  Unparse (unparse [_] lv))

(defrecord Param [param]
  Expr
  Unparse (unparse [_] param))

(defrecord Call [f args]
  Expr
  Unparse (unparse [_] (list* f (map unparse args))))

(defrecord GetField [expr field]
  Expr
  Unparse (unparse [_] (list '. (unparse expr) field)))

(defrecord Subquery [query args]
  Expr
  Unparse
  (unparse [_]
    (list* 'q (unparse query)
           (when args
             [{:args (mapv unparse args)}]))))

(defrecord Exists [query args]
  Expr
  Unparse
  (unparse [_]
    (list* 'exists? (unparse query)
           (when args
             [{:args (mapv unparse args)}]))))

(defrecord NestOne [query args]
  Expr
  Unparse
  (unparse [_]
    (list* 'pull (unparse query)
           (when args
             [{:args (mapv unparse args)}]))))

(defrecord NestMany [query args]
  Expr
  Unparse
  (unparse [_]
    (list* 'pull* (unparse query)
           (when args
             [{:args (mapv unparse args)}]))))

(defrecord List [exprs]
  Expr Unparse (unparse [_] (into [] (map unparse) exprs)))

(defrecord Set [exprs]
  Expr Unparse (unparse [_] (into #{} (map unparse) exprs)))

(defrecord Map [entries]
  Expr Unparse (unparse [_] (into {} (map (juxt key (comp unparse val))) entries)))

(defn parse-expr [form]
  (cond
    (nil? form) null
    (true? form) t
    (false? form) f
    (int? form) (Lng. form)
    (double? form) (Dbl. form)

    (symbol? form) (if (= 'xtdb/end-of-time form)
                     (Obj. 'xtdb/end-of-time)
                     (if (str/starts-with? (str form) "$")
                       (Param. form)
                       (LogicVar. form)))

    (vector? form) (List. (mapv parse-expr form))
    (set? form) (Set. (mapv parse-expr form))

    (map? form) (Map. (->> form
                           (into {} (map (juxt (comp (fn [k]
                                                       (when-not (keyword? k)
                                                         (throw (err/illegal-arg :xtdb/malformed-key {:key k})))
                                                       k)
                                                     key)
                                               (comp parse-expr val))))))

    (seq? form) (do
                  (when (empty? form)
                    (throw (err/illegal-arg :xtql/malformed-call {:call form})))

                  (let [[f & args] form]
                    (when-not (symbol? f)
                      (throw (err/illegal-arg :xtql/malformed-call {:call form})))

                    (case f
                      .
                      (do
                        (when-not (and (= (count args) 2)
                                       (first args)
                                       (symbol? (second args)))
                          (throw (err/illegal-arg :xtql/malformed-get {:expr form})))

                        (GetField. (parse-expr (first args)) (second args)))

                      (exists? q pull pull*)
                      (do
                        (when-not (and (<= 1 (count args) 2)
                                       (or (nil? (second args))
                                           (map? (second args))))
                          (throw (err/illegal-arg :xtql/malformed-subquery {:expr form})))

                        (let [[query {:keys [args]}] args
                              parsed-query (parse-query query)
                              parsed-args (some-> args (parse-arg-specs form))]
                          (case f
                            exists? (Exists. parsed-query parsed-args)
                            q (Subquery. parsed-query parsed-args)
                            pull (NestOne. parsed-query parsed-args)
                            pull* (NestMany. parsed-query parsed-args))))

                      (Call. f (mapv parse-expr args)))))

    :else (Obj. form)))

;;;; Binding

(defrecord Binding [binding expr])

(defmethod print-dup Binding [binding ^Writer w]
  (.write w (format "#xtql/binding %s" (pr-str (unparse binding)))))

(defmethod print-method Binding [binding ^Writer w]
  (print-dup binding w))

(defn parse-arg-specs [args form]
  (letfn [(parse-arg-spec [spec]
            (cond
              (symbol? spec) [(Binding. spec (LogicVar. spec))]
              (map? spec) (for [[attr expr] spec]
                            (if-not (keyword? attr)
                              (throw (err/illegal-arg :xtql/malformed-binding
                                                      {:binding attr
                                                       :form form}))

                              (Binding. attr (parse-expr expr))))))]
    (->> args
         (into [] (mapcat parse-arg-spec)))))

;;;; Query

(definterface Query)

(defmethod print-dup Query [query ^Writer w]
  (.write w (format "#xtql/query %s" (pr-str (unparse query)))))

(defmethod print-method Query [query ^Writer w]
  (print-dup query w))

(defmulti parse-query
  (fn [form]
    (when (seq? form)
      (first form)))

  :default ::invalid-query)

(defmulti parse-query-tail
  (fn [form]
    (when (seq? form)
      (first form)))

  :default ::invalid-query-tail)

(defmulti parse-unify-clause
  (fn [form]
    (when (seq? form)
      (first form)))

  :default ::invalid-unify-clause)

(defrecord Pipeline [query tails]
  Query
  Unparse (unparse [_] (list* '-> (unparse query) (map unparse tails))))

(defrecord Unify [clauses]
  Query
  Unparse (unparse [_] (list* 'unify (map unparse clauses))))

(defrecord From [table bindings project-all-cols?
                 for-valid-time for-system-time]
  Query
  )

(defrecord Where [preds]
  Query)

(defrecord With [bindings]
  Query)

(defrecord Without [cols]
  Query)

(defrecord Return [bindings]
  Query)

(defrecord Join [query args bindings]
  Query)

(defrecord LeftJoin [query args bindings]
  Query)

(defrecord Aggregate [cols]
  Query)

(defrecord OrderSpec [expr direction nulls])

(defrecord OrderBy [order-specs]
  Query)

(defrecord UnionAll [queries]
  Query)

(defrecord Limit [^long length]
  Query)

(defrecord Offset [^long length]
  Query)

(defrecord DocsRelation [documents bindings]
  Query)

(defrecord ParamRelation [param bindings]
  Query)

(defrecord Unnest [binding]
  Query)

(doseq [m [print-dup print-method]
        c [java.util.Map clojure.lang.IPersistentCollection clojure.lang.IRecord]]
  (prefer-method m Expr c)
  (prefer-method m Query c))
