(ns xtdb.sql.plan2
  (:require [clojure.string :as str]
            [xtdb.error :as err]
            [xtdb.logical-plan :as lp]
            [xtdb.types :as types]
            [xtdb.util :as util])
  (:import clojure.lang.MapEntry
           (java.util HashMap Map)
           java.util.function.Function
           (org.antlr.v4.runtime CharStreams CommonTokenStream ParserRuleContext)
           (xtdb.antlr SqlLexer SqlParser SqlParser$BaseTableContext SqlParser$JoinSpecificationContext SqlParser$JoinTypeContext SqlVisitor)))

(defn- add-err! [{:keys [!errors]} err]
  (swap! !errors conj err)
  nil)

(declare ->ExprPlanVisitor ->QueryPlanVisitor)

(defn identifier-str [^ParserRuleContext ctx]
  (.accept ctx (reify SqlVisitor
                 (visitSchemaName [_ ctx] (.getText ctx))
                 (visitAsClause [this ctx] (-> (.columnName ctx) (.accept this)))
                 (visitTableName [this ctx] (-> (.identifier ctx) (.accept this)))
                 (visitTableAlias [this ctx] (-> (.correlationName ctx) (.accept this)))
                 (visitColumnName [this ctx] (-> (.identifier ctx) (.accept this)))
                 (visitCorrelationName [this ctx] (-> (.identifier ctx) (.accept this)))

                 (visitRegularIdentifier [_ ctx] (.getText ctx))
                 (visitDelimitedIdentifier [_ ctx]
                   (let [di-str (.getText ctx)]
                     (subs di-str 1 (dec (count di-str))))))))

(defprotocol Scope
  (available-cols [scope table-name])
  (find-decl [scope col-name] [scope table-name col-name])
  (plan-scope [scope]))

(defrecord AmbiguousColumnReference [col-name])
(defrecord ColumnNotFound [col-name])

(defrecord TableTimePeriodSpecificationVisitor [expr-visitor]
  SqlVisitor
  (visitQueryValidTimePeriodSpecification [this ctx]
    (if (.ALL ctx)
      :all-time
      (-> (.tableTimePeriodSpecification ctx)
          (.accept this))))

  (visitQuerySystemTimePeriodSpecification [this ctx]
    (if (.ALL ctx)
      :all-time
      (-> (.tableTimePeriodSpecification ctx)
          (.accept this))))

  (visitTableAllTime [_ _] :all-time)

  (visitTableAsOf [_ ctx]
    [:at (-> ctx (.expr) (.accept expr-visitor))])

  (visitTableBetween [_ ctx]
    [:between
     (-> ctx (.expr 0) (.accept expr-visitor))
     (-> ctx (.expr 1) (.accept expr-visitor))])

  (visitTableFromTo [_ ctx]
    [:in
     (-> ctx (.expr 0) (.accept expr-visitor))
     (-> ctx (.expr 1) (.accept expr-visitor))]))

(defrecord BaseTable [env, ^SqlParser$BaseTableContext ctx
                      schema-name table-name table-alias unique-table-alias cols
                      ^Map !reqd-cols]
  Scope
  (available-cols [_ table-name]
    (when-not (and table-name (not= table-name table-alias))
      cols))

  (find-decl [_ col-name]
    (let [col-norm (util/str->normal-form-str col-name)]
      (when (or (contains? cols col-norm) (types/temporal-column? col-norm))
        (.computeIfAbsent !reqd-cols (symbol col-name)
                          (reify Function
                            (apply [_ col]
                              (symbol unique-table-alias (str col))))))))

  (find-decl [this table-name col-name]
    (when (= table-name table-alias)
      (or (find-decl this col-name)
          (add-err! env (->ColumnNotFound col-name)))))

  (plan-scope [this]
    (let [expr-visitor (->ExprPlanVisitor env this)]
      (letfn [(<-table-time-period-specification [specs]
                (case (count specs)
                  0 nil
                  1 (.accept ^ParserRuleContext (first specs) (->TableTimePeriodSpecificationVisitor expr-visitor))
                  (throw (UnsupportedOperationException. "multiple time period specifications"))))]
        (let [for-vt (<-table-time-period-specification (.queryValidTimePeriodSpecification ctx))
              for-st (<-table-time-period-specification (.querySystemTimePeriodSpecification ctx))]

          [:rename (symbol unique-table-alias)
           [:scan (cond-> {:table (symbol table-name)}
                    for-vt (assoc :for-valid-time for-vt)
                    for-st (assoc :for-system-time for-st))
            (vec (.keySet !reqd-cols))]])))))

(defrecord JoinTable [env
                      ^SqlParser$JoinTypeContext join-type-ctx
                      ^SqlParser$JoinSpecificationContext join-spec-ctx
                      l r]
  Scope
  (available-cols [_ table-name]
    (->> [l r]
         (into [] (comp (mapcat #(available-cols % table-name))
                        (distinct)))))

  (find-decl [_ col-name]
    (let [matches (->> [l r]
                       (keep (fn [scope]
                               (find-decl scope env col-name))))]
      (when (> (count matches) 1)
        (add-err! env (->AmbiguousColumnReference col-name)))

      (first matches)))

  (find-decl [_ table-name col-name]
    (or (find-decl r table-name col-name)
        (find-decl l table-name col-name)))

  (plan-scope [this-scope]
    (let [join-type (case (some-> join-type-ctx
                                  (.outerJoinType)
                                  (.getText))
                      "left" :left-outer-join
                      "right" :right-outer-join
                      "full" :full-outer-join
                      :join)
          join-cond (or (some-> join-spec-ctx
                                (.accept
                                 (reify SqlVisitor
                                   (visitJoinCondition [_ ctx]
                                     [(-> (.expr ctx)
                                          (.accept (->ExprPlanVisitor env this-scope)))]))))
                        [])
          planned-l (plan-scope l)
          planned-r (plan-scope r)]
      (if (= :right-outer-join join-type)
        [:left-outer-join join-cond planned-r planned-l]
        [join-type join-cond planned-l planned-r]))))

(defrecord DerivedTable [plan table-alias unique-table-alias available-cols col-syms]
  Scope
  (available-cols [_ table-name]
    (when-not (and table-name (not= table-name table-alias))
      available-cols))

  (find-decl [_ col-name]
    (some->> (get available-cols col-name)
             (symbol unique-table-alias)))

  (find-decl [this table-name col-name]
    (when (= table-name table-alias)
      (find-decl this col-name)))

  (plan-scope [_]
    [:rename (symbol unique-table-alias)
     plan]))

(defrecord FromClauseScope [env table-ref-scopes]
  Scope
  (available-cols [_ table-name]
    (->> table-ref-scopes
         (into [] (comp (mapcat #(available-cols % table-name)) (distinct)))))

  (find-decl [_ col-name]
    (let [matches (->> table-ref-scopes
                       (keep (fn [scope]
                               (find-decl scope col-name))))]
      (when (> (count matches) 1)
        (add-err! env (->AmbiguousColumnReference col-name)))

      (first matches)))

  (find-decl [_ table-name col-name]
    (let [matches (->> table-ref-scopes
                       (keep (fn [scope]
                               (find-decl scope table-name col-name))))]
      (when (> (count matches) 1)
        (add-err! env (->AmbiguousColumnReference col-name)))

      (first matches)))

  (plan-scope [_]
    (case (count table-ref-scopes)
      0 [:table [{}]]
      1 (plan-scope (first table-ref-scopes))
      [:mega-join [] (mapv plan-scope table-ref-scopes)])))

(defn- ->table-projection [^ParserRuleContext ctx]
  (some-> ctx
          (.accept
           (reify SqlVisitor
             (visitTableProjection [_ ctx]
               (some->> (.columnNameList ctx) (.columnName)
                        (mapv identifier-str)))))))

(defrecord ProjectedCol [projection col-sym])

(defrecord ScopeVisitor [env scope]
  SqlVisitor
  (visitFromClause [this ctx]
    (->FromClauseScope env (->> (.tableReference ctx)
                                (mapv #(.accept ^ParserRuleContext % this)))))

  (visitBaseTable [{{:keys [!id-count table-info]} :env} ctx]
    (let [tn (some-> (.tableOrQueryName ctx) (.tableName))
          sn (some-> (.schemaName tn) identifier-str)
          tn (identifier-str (.identifier tn))
          table-alias (or (some-> (.tableAlias ctx) identifier-str) tn)
          cols (some-> (.tableProjection ctx) (->table-projection))]
      (->BaseTable env ctx sn tn table-alias (str table-alias "." (swap! !id-count inc))
                   (or cols (get table-info (util/str->normal-form-str tn)))
                   (HashMap.))))

  (visitJoinTable [this ctx]
    (->JoinTable env (.joinType ctx) (.joinSpecification ctx)
                 (-> (.tableReference ctx 0) (.accept this))
                 (-> (.tableReference ctx 1) (.accept this))))

  (visitDerivedTable [{{:keys [!id-count]} :env} ctx]
    (let [{:keys [plan col-syms]} (-> (.subquery ctx) (.queryExpression)
                                      (.accept (-> (->QueryPlanVisitor env scope)
                                                   (assoc :out-col-syms (some->> (->table-projection (.tableProjection ctx))
                                                                                 (mapv symbol))))))

          table-alias (identifier-str (.tableAlias ctx))]

      (->DerivedTable plan table-alias
                      (str table-alias "." (swap! !id-count inc))
                      (into #{} (map str) col-syms)
                      col-syms)))

  (visitWrappedTableReference [this ctx] (-> (.tableReference ctx) (.accept this)))

  (visitWhereClause [_ ctx]
    (reify Scope
      (available-cols [_ table-name] (available-cols scope table-name))

      (find-decl [_ table-name col-name]
        (find-decl scope table-name col-name))

      (find-decl [_ col-name]
        (find-decl scope col-name))

      (plan-scope [this]
        [:select (-> (.expr ctx)
                     (.accept (->ExprPlanVisitor env this)))
         (plan-scope scope)])))

  (visitSelectClause [_ ctx]
    (let [sl-ctx (.selectList ctx)
          projected-cols (if (or (nil? ctx) (.ASTERISK sl-ctx))
                           (vec (for [col-name (available-cols scope nil)
                                      :let [sym (find-decl scope col-name)]]
                                  (->ProjectedCol sym sym)))

                           (->> (.selectSublist sl-ctx)
                                (into [] (comp (map-indexed
                                                (fn [col-idx ^ParserRuleContext sl-elem]
                                                  (.accept (.getChild sl-elem 0)
                                                           (reify SqlVisitor
                                                             (visitDerivedColumn [_ ctx]
                                                               [(let [expr (.accept (.expr ctx) (->ExprPlanVisitor env scope))]
                                                                  (if-let [as-clause (.asClause ctx)]
                                                                    (let [col-name (symbol (identifier-str as-clause))]
                                                                      (->ProjectedCol {col-name expr} col-name))

                                                                    (if (symbol? expr)
                                                                      (->ProjectedCol expr expr)
                                                                      (let [col-name (symbol (str "xt$column_" (inc col-idx)))]
                                                                        (->ProjectedCol {col-name expr} col-name)))))])

                                                             (visitQualifiedAsterisk [_ ctx]
                                                               (let [table-name (identifier-str (.identifier ctx))]
                                                                 (if-let [table-cols (available-cols scope table-name)]
                                                                   (for [col-name table-cols
                                                                         :let [sym (find-decl scope table-name col-name)]]
                                                                     (->ProjectedCol sym sym))
                                                                   (throw (UnsupportedOperationException. (str "Table not found: " table-name))))))))))
                                               cat))))]
      (reify Scope
        (plan-scope [_]
          (-> [:project (mapv :projection projected-cols)
               (plan-scope scope)]
              (with-meta {:col-syms (mapv :col-sym projected-cols)})))))))

(defrecord ExprPlanVisitor [env scope]
  SqlVisitor
  (visitSearchCondition [this ctx] (-> (.expr ctx) (.accept this)))
  (visitWrappedExpr [this ctx] (-> (.expr ctx) (.accept this)))

  (visitLiteralExpr [this ctx] (-> (.literal ctx) (.accept this)))
  (visitFloatLiteral [_ ctx] (parse-double (.getText ctx)))
  (visitIntegerLiteral [_ ctx] (parse-long (.getText ctx)))

  (visitCharacterStringLiteral [this ctx] (-> (.characterString ctx) (.accept this)))

  (visitCharacterString [_ ctx]
    (let [str (.getText ctx)]
      (subs str 1 (dec (count str)))))

  (visitBooleanLiteral [_ ctx]
    (case (-> (.getText ctx) str/lower-case)
      "true" true
      "false" false
      "unknown" nil))

  (visitNullLiteral [_ _ctx] nil)

  (visitColumnExpr [this ctx] (-> (.columnReference ctx) (.accept this)))

  (visitColumnReference [_ ctx]
    (let [schema-name (some-> (.schemaName ctx) identifier-str)
          table-name (some-> (.tableName ctx) identifier-str)
          col-name (identifier-str (.columnName ctx))]
      (when schema-name (throw (UnsupportedOperationException. "schema")))

      (or (if table-name
            (find-decl scope table-name col-name)
            (find-decl scope col-name))
          (add-err! env (->ColumnNotFound col-name)))))

  (visitParamExpr [this ctx] (-> (.parameterSpecification ctx) (.accept this)))

  (visitDynamicParameter [{{:keys [!param-count]} :env} _]
    (-> (symbol (str "?_" (dec (swap! !param-count inc))))
        (vary-meta assoc :param? true)))

  (visitPostgresParameter [{{:keys [!param-count]} :env} ctx]
    (-> (symbol (str "?_" (let [param-idx (dec (parse-long (subs (.getText ctx) 1)))]
                            (swap! !param-count min param-idx)
                            param-idx)))
        (vary-meta assoc :param? true)))

  (visitUnaryPlusExpr [this ctx] (-> (.expr ctx) (.accept this)))
  (visitUnaryMinusExpr [this ctx] (list '- (-> (.expr ctx) (.accept this))))

  (visitAddExpr [this ctx]
    (list '+
          (-> (.expr ctx 0) (.accept this))
          (-> (.expr ctx 1) (.accept this))))

  (visitSubtractExpr [this ctx]
    (list '-
          (-> (.expr ctx 0) (.accept this))
          (-> (.expr ctx 1) (.accept this))))

  (visitMultiplyExpr [this ctx]
    (list '*
          (-> (.expr ctx 0) (.accept this))
          (-> (.expr ctx 1) (.accept this))))

  (visitDivideExpr [this ctx]
    (list '/
          (-> (.expr ctx 0) (.accept this))
          (-> (.expr ctx 1) (.accept this))))

  (visitOrExpr [this ctx]
    (list 'or
          (-> (.expr ctx 0) (.accept this))
          (-> (.expr ctx 1) (.accept this))))

  (visitAndExpr [this ctx]
    (list 'and
          (-> (.expr ctx 0) (.accept this))
          (-> (.expr ctx 1) (.accept this))))

  (visitUnaryNotExpr [this ctx] (list 'not (-> (.expr ctx) (.accept this))))

  (visitPredicatePart2Expr [this ctx]
    (let [pt1 (.accept (.expr ctx) this)]
      (.accept (.predicatePart2 ctx)
               (assoc this :pt1 pt1))))

  (visitComparisonPredicatePart2 [{:keys [pt1] :as this} ctx]
    (list (symbol (.getText (.compOp ctx)))
          pt1
          (-> (.expr ctx) (.accept (dissoc this :pt1)))))

  (visitNullPredicatePart2 [{:keys [pt1]} ctx]
    (if (.NOT ctx)
      (list 'not (list 'nil? pt1))
      (list 'nil? pt1))))

(defrecord ColumnCountMismatch [expected given])

(defprotocol Optimise
  (optimise [stmt]))

(defrecord QueryExpr [plan col-syms]
  Optimise (optimise [this] (update this :plan lp/rewrite-plan)))

(defrecord QueryPlanVisitor [env scope]
  SqlVisitor
  (visitQueryExpression [this ctx]
    (as-> (.accept (.queryExpressionBody ctx) this)
        {:keys [plan col-syms]}

      (let [out-projections (->> col-syms
                                 (into [] (map (fn [col-sym]
                                                 (if (namespace col-sym)
                                                   (let [out-sym (symbol (name col-sym))]
                                                     (->ProjectedCol {out-sym col-sym}
                                                                     out-sym))
                                                   (->ProjectedCol col-sym col-sym))))))]

        (->QueryExpr [:project (mapv :projection out-projections)
                      plan]
                     (mapv :col-sym out-projections)))))

  (visitUnionQuery [_ _] (throw (UnsupportedOperationException. "UNION")))
  (visitExceptQuery [_ _] (throw (UnsupportedOperationException. "EXCEPT")))
  (visitIntersectQuery [_ _] (throw (UnsupportedOperationException. "INTERSECT")))

  (visitQuerySpecification [{:keys [out-col-syms]} ctx]
    (let [qs-scope (if-let [from (.fromClause ctx)]
                     (.accept from (->ScopeVisitor env scope))
                     scope)

          qs-scope (if-let [where-clause (.whereClause ctx)]
                     (.accept where-clause (->ScopeVisitor env qs-scope))
                     qs-scope)

          qs-scope (if-let [select-clause (.selectClause ctx)]
                     (.accept select-clause (->ScopeVisitor env qs-scope))
                     (throw (UnsupportedOperationException. "select *")))

          plan (plan-scope qs-scope)
          {:keys [col-syms]} (meta plan)]

      (as-> (->QueryExpr plan col-syms)
          {:keys [plan col-syms] :as query-expr}

        (if out-col-syms
          (->QueryExpr [:rename (zipmap out-col-syms col-syms)
                        plan]
                       out-col-syms)
          query-expr))))

  (visitValuesQuery [this ctx] (-> (.tableValueConstructor ctx) (.accept this)))
  (visitTableValueConstructor [this ctx] (-> (.rowValueList ctx) (.accept this)))

  (visitRowValueList [{{:keys [!id-count]} :env, :keys [out-col-syms]} ctx]
    (let [expr-plan-visitor (->ExprPlanVisitor env scope)
          col-syms (or out-col-syms
                       (->> (.expr (.rowValueConstructor ctx 0))
                            (into [] (map-indexed (fn [idx _]
                                                    (symbol (str "xt$column_" (inc idx))))))))

          col-keys (mapv keyword col-syms)

          unique-table-alias (str "xt.values." (swap! !id-count inc))

          col-count (count col-keys)

          row-visitor (reify SqlVisitor
                        (visitRowValueConstructor [_ ctx]
                          (let [exprs (.expr ctx)]
                            (if (not= (count exprs) col-count)
                              (add-err! env (->ColumnCountMismatch col-count (count exprs)))
                              (->> (map (fn [col ^ParserRuleContext expr]
                                          (MapEntry/create col
                                                           (.accept expr expr-plan-visitor)))
                                        col-keys
                                        exprs)
                                   (into {}))))))]

      (->QueryExpr [:rename (symbol unique-table-alias)
                    [:table col-syms
                     (->> (.rowValueConstructor ctx)
                          (mapv #(.accept ^ParserRuleContext % row-visitor)))]]

                   (->> col-syms
                        (mapv #(symbol unique-table-alias (str %))))))))

(defrecord StmtVisitor [env scope]
  SqlVisitor
  (visitDirectSqlStatement [this ctx] (-> (.directlyExecutableStatement ctx) (.accept this)))
  (visitDirectlyExecutableStatement [this ctx] (-> (.getChild ctx 0) (.accept this)))

  (visitQueryExpression [_ ctx] (-> ctx (.accept (->QueryPlanVisitor env scope)))))

(defn ->parser ^xtdb.antlr.SqlParser [sql]
  (-> (CharStreams/fromString sql)
      (SqlLexer.)
      (CommonTokenStream.)
      (SqlParser.)))

(defn plan-expr
  ([sql] (plan-expr sql {}))

  ([sql {:keys [scope table-info]}]
   (let [!errors (atom [])
         env {:scope scope
              :!errors !errors
              :!id-count (atom 0)
              :!param-count (atom 0)
              :table-info table-info}
         parser (->parser sql)
         plan (-> (.expr parser)
                  #_(doto (-> (.toStringTree parser) read-string (clojure.pprint/pprint))) ; <<no-commit>>
                  (.accept (->ExprPlanVisitor env scope)))]

     (if-let [errs (not-empty @!errors)]
       (throw (err/illegal-arg :xtdb/sql-error {:errors errs}))
       plan))))

;; eventually these data structures will be used as logical plans,
;; we won't need an adapter
(defprotocol AdaptPlan
  (->logical-plan [stmt]))

(extend-protocol AdaptPlan
  QueryExpr (->logical-plan [{:keys [plan]}] plan))

(defn plan-statement
  ([sql] (plan-statement sql {}))

  ([sql {:keys [scope table-info optimise?], :or {optimise? true}}]
   (let [!errors (atom [])
         !param-count (atom 0)
         env {:!errors !errors
              :!id-count (atom 0)
              :!param-count !param-count
              :table-info table-info}
         parser (->parser sql)
         stmt (-> (.directSqlStatement parser)
                  #_(doto (-> (.toStringTree parser) read-string (clojure.pprint/pprint))) ; <<no-commit>>
                  (.accept (->StmtVisitor env scope)))]
     (-> (if-let [errs (not-empty @!errors)]
           (throw (err/illegal-arg :xtdb/sql-error {:errors errs}))
           (cond-> stmt
             optimise? (optimise))) ;; <<no-commit>>
         (vary-meta assoc :param-count @!param-count)))))

(comment
  (plan-statement "SELECT foo.baz FROM foo WHERE bar + baz = 3"
                  {:table-info {"foo" #{"bar" "baz"}
                                "bar" #{"quux"}}}))
