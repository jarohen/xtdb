(ns xtdb.api.xtql
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [xtdb.error :as err])
  (:import clojure.lang.MapEntry
           [java.io Writer]))

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

(defn- ->binding [sym]
  (Binding. sym (LogicVar. sym)))

;; NOTE out-specs and arg-specs are currently indentical structurally,
;; but one is an input binding, the other an output binding.
;; I could see remerging these into a single binding-spec,
;; that being said, its possible we don't want arbitrary exprs in the from of arg specs, only plain vars

;; TODO binding-spec-errs
(defn parse-out-specs
  "[{:from to-var} from-col-to-var {:col (pred)}]"
  ^List [specs _query]
  (letfn [(parse-out-spec [[attr expr]]
            (when-not (keyword? attr)
              (throw (err/illegal-arg :xtql/malformed-bind-spec
                                      {:attr attr :expr expr ::err/message "Attribute in bind spec must be keyword"})))

            (Binding. (str (symbol attr)) (parse-expr expr)))]

    (if (vector? specs)
      (->> specs
           (into [] (mapcat (fn [spec]
                              (cond
                                (symbol? spec) [(->binding spec)]
                                (map? spec) (map parse-out-spec spec))))))

      (throw (UnsupportedOperationException.)))))

(defn parse-arg-specs [args form]
  (letfn [(parse-arg-spec [spec]
            (cond
              (symbol? spec) [(->binding spec)]
              (map? spec) (for [[attr expr] spec]
                            (if-not (keyword? attr)
                              (throw (err/illegal-arg :xtql/malformed-binding
                                                      {:binding attr
                                                       :form form}))

                              (Binding. attr (parse-expr expr))))))]
    (->> args
         (into [] (mapcat parse-arg-spec)))))

(defn- parse-var-specs
  "[{to-var (from-expr)}]"
  [specs _query]
  (letfn [(parse-var-spec [[attr expr]]
            (when-not (symbol? attr)
              (throw (err/illegal-arg :xtql/malformed-var-spec
                                      {:attr attr :expr expr
                                       ::err/message "Attribute in var spec must be symbol"})))

            (Binding. attr (parse-expr expr)))]

    (cond
      (map? specs) (mapv parse-var-spec specs)
      (sequential? specs) (->> specs
                               (into [] (mapcat (fn [spec]
                                                  (if (map? spec)
                                                    (map parse-var-spec spec)
                                                    (throw (err/illegal-arg :xtql/malformed-var-spec
                                                                            {:spec spec
                                                                             ::err/message "Var specs must be pairs of bindings"})))))))
      :else (throw (UnsupportedOperationException.)))))

(defn parse-col-specs
  "[{:to-col (from-expr)} :col ...]"
  [specs _query]
  (letfn [(parse-col-spec [[attr expr]]
            (when-not (keyword? attr)
              (throw (err/illegal-arg :xtql/malformed-col-spec
                                      {:attr attr, :expr expr
                                       ::err/message "Attribute in col spec must be keyword"})))

            (Binding. attr (parse-expr expr)))]

    (cond
      (map? specs) (mapv parse-col-spec specs)
      (sequential? specs) (->> specs
                               (into [] (mapcat (fn [spec]
                                                  (cond
                                                    (symbol? spec) [(->binding spec)]
                                                    (map? spec) (map parse-col-spec spec)

                                                    :else (throw (err/illegal-arg :xtql/malformed-col-spec
                                                                                  {:spec spec ::err/message "Short form of col spec must be a symbol"})))))))
      :else (throw (UnsupportedOperationException.)))))


(defn unparse-binding [base-type nested-type binding]
  (let [attr (.getBinding binding)
        expr (.getExpr binding)]
    (if base-type
      (if (and (instance? LogicVar expr)
               (= (:lv expr) attr))
        (base-type attr)
        {(nested-type attr) (unparse expr)})
      {(nested-type attr) (unparse expr)})))

(def unparse-out-spec (partial unparse-binding symbol keyword))
(def unparse-col-spec (partial unparse-binding symbol keyword))
(def unparse-arg-spec (partial unparse-binding symbol keyword))
(def unparse-var-spec (partial unparse-binding nil symbol))

;;;; Temporal filter

(definterface TemporalFilter)

(defrecord AllTime []
  TemporalFilter
  Unparse (unparse [_] :all-time))

(defrecord AtTime [expr]
  TemporalFilter
  Unparse (unparse [_] (list 'at (unparse expr))))

(defrecord InTime [from-expr to-expr]
  TemporalFilter
  Unparse (unparse [_] (list 'in (unparse from-expr) (unparse to-expr))))

(defn parse-temporal-filter [v k query]
  (let [ctx {:v v, :filter k, :query query}]
    (if (= :all-time v)
      (AllTime.)

      (do
        (when-not (and (seq? v) (not-empty v))
          (throw (err/illegal-arg :xtql/malformed-temporal-filter ctx)))

        (let [[tag & args] v]
          (when-not (symbol? tag)
            (throw (err/illegal-arg :xtql/malformed-temporal-filter ctx)))

          (letfn [(assert-arg-count [expected args]
                    (when-not (= expected (count args))
                      (throw (err/illegal-arg :xtql/malformed-temporal-filter (into ctx {:tag tag, :at args}))))

                    args)]
            (case tag
              at (let [[at] (assert-arg-count 1 args)]
                   (->AtTime (parse-expr at)))

              in (let [[from to] (assert-arg-count 2 args)]
                   (->InTime (parse-expr from) (parse-expr to)))

              from (let [[from] (assert-arg-count 1 args)]
                     (->InTime (parse-expr from) nil))

              to (let [[to] (assert-arg-count 1 args)]
                   (->InTime nil (parse-expr to)))

              (throw (err/illegal-arg :xtql/malformed-temporal-filter (into ctx {:tag tag}))))))))))

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

  :default ::unknown-query)

(defmulti parse-query-tail
  (fn [form]
    (when (seq? form)
      (first form)))

  :default ::unknown-query-tail)

(defmethod parse-query-tail ::unknown-query-tail [[op]]
  (throw (err/illegal-arg :xtql/unknown-query-tail {:op op})))

(defmulti parse-unify-clause
  (fn [form]
    (when (seq? form)
      (first form)))

  :default ::unknown-unify-clause)

(defmethod parse-unify-clause ::unknown-unify-clause [[op]]
  (throw (err/illegal-arg :xtql/unknown-unify-clause {:op op})))

(defrecord Pipeline [query tails]
  Query
  Unparse (unparse [_] (list* '-> (unparse query) (map unparse tails))))

(defmethod parse-query '-> [[_ head & tails :as this]]
  (when-not head
    (throw (err/illegal-arg :xtql/malformed-pipeline
                            {:pipeline this
                             :message "Pipeline most contain at least one operator"})))
  (->Pipeline (parse-query head) (mapv parse-query-tail tails)))

(defrecord Unify [clauses]
  Query
  Unparse (unparse [_] (list* 'unify (map unparse clauses))))

(defmethod parse-query 'unify [[_ & clauses :as this]]
  (when (> 1 (count clauses))
    (throw (err/illegal-arg :xtql/malformed-unify
                            {:unify this
                             :message "Unify most contain at least one sub clause"})))
  (->Unify (mapv parse-unify-clause clauses)))

(defrecord From [table bindings project-all-cols?
                 for-valid-time for-system-time]
  Query
  Unparse
  (unparse [_]
    (let [bind (mapv unparse-out-spec bindings)
          bind (if project-all-cols? (vec (cons '* bind)) bind)]
      (list 'from table
            (if (or for-valid-time for-system-time)
              (cond-> {:bind bind}
                for-valid-time (assoc :for-valid-time (unparse for-valid-time))
                for-system-time (assoc :for-system-time (unparse for-system-time)))
              bind)))))

(defn check-opt-keys [valid-keys opts]
  (when-let [invalid-opt-keys (not-empty (set/difference (set (keys opts)) valid-keys))]
    (throw (err/illegal-arg :invalid-opt-keys
                            {:opts opts, :valid-keys valid-keys
                             :invalid-keys invalid-opt-keys
                             ::err/message "Invalid keys provided to option map"}))))

(def from-opt-keys #{:bind :for-valid-time :for-system-time})

(defn find-star-projection [star-ident bindings]
  ;;TODO worth checking here or in parse-out-specs for * in col/attr position? {* var}??
  ;;Arguably not checking for this could allow * to be a valid col name?
  (->> bindings
       (reduce (fn [acc binding]
                 (if (= binding star-ident)
                   (assoc acc :project-all-cols true)
                   (update acc :bind conj binding)))
               {:project-all-cols false
                :bind []})))

(defn parse-from [[_ table opts :as this]]
  (if-not (keyword? table)
    (throw (err/illegal-arg :xtql/malformed-table {:table table, :from this}))

    (let [q (->From table)]
      (cond
        (or (nil? opts) (map? opts))

        (do
          (check-opt-keys from-opt-keys opts)

          (let [{:keys [for-valid-time for-system-time bind]} opts]
            (cond
              (nil? bind)
              (throw (err/illegal-arg :xtql/missing-bind {:opts opts, :from this}))

              (not (vector? bind))
              (throw (err/illegal-arg :xtql/malformed-bind {:opts opts, :from this}))

              :else
              (let [{:keys [bind project-all-cols]} (find-star-projection '* bind)]
                (-> (cond-> (doto q (.setBindings (parse-out-specs bind this)))
                      for-valid-time (doto (.forValidTime (parse-temporal-filter for-valid-time :for-valid-time this)))
                      for-system-time (doto (.forSystemTime (parse-temporal-filter for-system-time :for-system-time this)))
                      project-all-cols (doto (.projectAllCols true)))
                    (.build))))))

        (vector? opts)
        (let [{:keys [bind project-all-cols]} (find-star-projection '* opts)]
          (-> (cond-> (doto q (.setBindings (parse-out-specs bind this)))
                project-all-cols (doto (.projectAllCols)))
              (.build)))

        :else (throw (err/illegal-arg :xtql/malformed-table-opts {:opts opts, :from this}))))))

(defmethod parse-query 'from [this] (parse-from this))
(defmethod parse-unify-clause 'from [this] (parse-from this))

(defrecord Where [preds]
  Query)

(defn parse-where [[_ & preds :as this]]
  (when (> 1 (count preds))
    (throw (err/illegal-arg :xtql/malformed-where
                            {:where this
                             :message "Where most contain at least one predicate"})))
  (->Where (mapv parse-expr preds)))

(defmethod parse-query-tail 'where [this] (parse-where this))
(defmethod parse-unify-clause 'where [this] (parse-where this))

(defrecord With [bindings]
  Query
  Unparse (unparse [_] (list* 'with (mapv unparse-var-spec bindings))))

(defmethod parse-query-tail 'with [[_ & cols :as this]]
  ;;TODO with uses col-specs but doesn't support short form, this needs handling
  (->With (parse-col-specs cols this)))

(defmethod parse-unify-clause 'with [[_ & vars :as this]]
  (->With (parse-var-specs vars this)))

(defrecord Without [cols]
  Query)

(defmethod parse-query-tail 'without [[_ & cols :as this]]
  (when-not (every? keyword? cols)
    (throw (err/illegal-arg :xtql/malformed-without
                            {:without this
                             ::err/message "Columns must be keywords in without"})))
  (->Without cols))

(defrecord Return [bindings]
  Query)

(defmethod parse-query-tail 'return [[_ & cols :as this]]
  (->Return (parse-col-specs cols this)))

(defrecord Join [query args bindings]
  Query
  Unparse
  (unparse [_]
    (let [bind (mapv unparse-col-spec bindings)]
      (list 'join (unparse query)
            (if args
              (cond-> {:bind bind}
                (seq args) (assoc :args (mapv unparse-arg-spec args)))
              bind)))))

(defrecord LeftJoin [query args bindings]
  Query
  Unparse
  (unparse [_]
    (let [bind (mapv unparse-col-spec bindings)]
      (list 'left-join (unparse query)
            (if args
              (cond-> {:bind bind}
                (seq args) (assoc :args (mapv unparse-arg-spec args)))
              bind)))))

(def join-clause-opt-keys #{:args :bind})

(defmethod parse-unify-clause 'join [[_ query opts :as join]]
  (cond
    (nil? opts) (throw (err/illegal-arg :missing-join-opts {:opts opts, :join join}))

    (map? opts) (do
                  (check-opt-keys join-clause-opt-keys opts)
                  (let [{:keys [args bind]} opts]
                    (->Join (parse-query query)
                            (parse-arg-specs args join)
                            (some-> bind (parse-out-specs join)))))

    :else (->Join (parse-query query) nil (parse-out-specs opts join))))

(defmethod parse-unify-clause 'left-join [[_ query opts :as left-join]]
  (cond
    (nil? opts) (throw (err/illegal-arg :missing-join-opts {:opts opts, :left-join left-join}))

    (map? opts) (do
                  (check-opt-keys join-clause-opt-keys opts)
                  (let [{:keys [args bind]} opts]
                    (->LeftJoin (parse-query query)
                                (parse-arg-specs args left-join)
                                (some-> bind (parse-out-specs left-join)))))

    :else (->LeftJoin (parse-query query) nil
                      (parse-out-specs opts left-join))))

(defrecord Aggregate [cols]
  Query)

(defmethod parse-query-tail 'aggregate [[_ & cols :as this]]
  (->Aggregate (parse-col-specs cols this)))

(defrecord OrderSpec [expr dir nulls]
  Unparse
  (unparse [_]
    (let [expr (unparse expr)]
      (if (and (nil? dir) (nil? nulls))
        expr
        (cond-> {:val expr}
          dir (assoc :dir dir)
          nulls (assoc :nulls nulls))))))

(def order-spec-opt-keys #{:val :dir :nulls})

(defn- parse-order-spec [order-spec this]
  (if (map? order-spec)
    (let [{:keys [val dir nulls]} order-spec]
      (check-opt-keys order-spec-opt-keys order-spec)
      (when-not (contains? order-spec :val)
        (throw (err/illegal-arg :xtql/order-by-val-missing
                                {:order-spec order-spec, :query this})))

      (->OrderSpec (parse-expr val)
                   (case dir
                     (nil :asc :desc) dir

                     (throw (err/illegal-arg :xtql/malformed-order-by-direction
                                             {:direction dir, :order-spec order-spec, :query this})))
                   (case nulls
                     (nil :first :last) nulls

                     (throw (err/illegal-arg :xtql/malformed-order-by-nulls
                                             {:nulls nulls, :order-spec order-spec, :query this})))))

    (->OrderSpec (parse-expr order-spec) nil nil)))

(defrecord OrderBy [order-specs]
  Query
  Unparse
  (unparse [_]
    (list* 'order-by (mapv unparse order-specs))))



(defmethod parse-query-tail 'order-by [[_ & order-specs :as this]]
  (Queries/orderBy ^List (mapv #(parse-order-spec % this) order-specs)))


(defrecord UnionAll [queries]
  Query)

(defmethod parse-query 'union-all [[_ & queries :as this]]
  (when (> 1 (count queries))
    (throw (err/illegal-arg :xtql/malformed-union
                            {:union this
                             :message "Union must contain a least one sub query"})))
  (->UnionAll (mapv parse-query queries)))

(defrecord Limit [^long length]
  Query)

(defmethod parse-query-tail 'limit [[_ length :as this]]
  (when-not (= 2 (count this))
    (throw (err/illegal-arg :xtql/limit
                            {:limit this :message "Limit can only take a single value"})))
  (->Limit length))

(defrecord Offset [^long length]
  Query)

(defmethod parse-query-tail 'offset [[_ length :as this]]
  (when-not (= 2 (count this))
    (throw (err/illegal-arg :xtql/offset {:offset this :message "Offset can only take a single value"})))
  (->Offset length))

(defrecord DocsRelation [documents bindings]
  Query)

(defrecord ParamRelation [param-expr bindings]
  Query)

(defn- keyword-map? [m]
  (every? keyword? (keys m)))

(defn parse-rel [[_ param-or-docs bind :as this]]
  (when-not (= 3 (count this))
    (throw (err/illegal-arg :xtql/rel {:rel this :message "`rel` takes exactly 3 arguments"})))
  (when-not (or (symbol? param-or-docs)
                (and (vector? param-or-docs) (every? keyword-map? param-or-docs)))
    (throw (err/illegal-arg :xtql/rel {:rel this :message "`rel` takes a param or an explicit relation"})))
  (let [parsed-bind (parse-out-specs bind this)]
    (if (symbol? param-or-docs)
      (let [parsed-expr (parse-expr param-or-docs)]
        (if (instance? Param parsed-expr)
          (->ParamRelation parsed-expr parsed-bind)
          (throw (err/illegal-arg :xtql/rel {::err/message "Illegal second argument to `rel`"
                                             :arg param-or-docs}))))
      (->DocsRelation (mapv #(into {} (map (fn [[k v]] (MapEntry/create (subs (str k) 1) (parse-expr v)))) %) param-or-docs) parsed-bind))))

(defmethod parse-query 'rel [this] (parse-rel this))
(defmethod parse-unify-clause 'rel [this] (parse-rel this))

(defrecord Unnest [binding]
  Query
  Unparse (unparse [_] (list 'unnest (unparse-var-spec binding))))

(defn check-unnest [binding unnest]
  (when-not (and (= 2 (count unnest))
                 (map? binding)
                 (= 1 (count binding)))
    (throw (err/illegal-arg :xtql/unnest {:unnest unnest ::err/message "Unnest takes only a single binding"}))))

(defmethod parse-query-tail 'unnest [[_ binding :as this]]
  (check-unnest binding this)
  (->Unnest (first (parse-col-specs binding this))))

(defmethod parse-unify-clause 'unnest [[_ binding :as this]]
  (check-unnest binding this)
  (->Unnest (first (parse-var-specs binding this))))


(doseq [m [print-dup print-method]
        c [java.util.Map clojure.lang.IPersistentCollection clojure.lang.IRecord]]
  (prefer-method m Expr c)
  (prefer-method m Query c))
