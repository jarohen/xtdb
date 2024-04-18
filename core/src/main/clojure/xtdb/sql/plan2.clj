(ns xtdb.sql.plan2
  (:require [clojure.string :as str]
            [xtdb.error :as err]
            [xtdb.logical-plan :as lp]
            [xtdb.types :as types]
            [xtdb.util :as util])
  (:import clojure.lang.MapEntry
           (java.time Duration LocalDate LocalDateTime LocalTime OffsetTime Period ZoneId ZoneOffset ZonedDateTime)
           (java.util HashMap Map)
           java.util.function.Function
           (org.antlr.v4.runtime CharStreams CommonTokenStream ParserRuleContext)
           (xtdb.antlr SqlLexer SqlParser SqlParser$SetClauseContext SqlParser$BaseTableContext SqlParser$IntervalQualifierContext SqlParser$JoinSpecificationContext SqlParser$JoinTypeContext SqlParser$ObjectNameAndValueContext SqlParser$SearchedWhenClauseContext SqlParser$SimpleWhenClauseContext SqlParser$WhenOperandContext SqlParser$WithTimeZoneContext SqlVisitor)
           (xtdb.types IntervalMonthDayNano)))

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

(extend-protocol Scope nil
  (available-cols [_ _])
  (find-decl [_ _])
  (find-decl [_ _ _])

  (plan-scope [_]
    [:table [{}]]))

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
      (when (or (contains? cols col-norm)
                (types/temporal-column? col-norm))
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

(defn seconds-fraction->nanos ^long [seconds-fraction]
  (if seconds-fraction
    (* (Long/parseLong seconds-fraction)
       (long (Math/pow 10 (- 9 (count seconds-fraction)))))
    0))

(defrecord CannotParseDate [d-str msg])

(defn parse-date-literal [d-str env]
  (try
    (LocalDate/parse d-str)
    (catch Exception e
      (add-err! env (->CannotParseDate d-str (.getMessage e))))))

(defrecord CannotParseTime [t-str msg])

(defn parse-time-literal [t-str env]
  (if-let [[_ h m s sf offset-str] (re-matches #"(\d{1,2}):(\d{1,2}):(\d{1,2})(?:\.(\d+))?([+-]\d{2}:\d{2})?" t-str)]
    (try
      (let [local-time (LocalTime/of (parse-long h) (parse-long m) (parse-long s) (seconds-fraction->nanos sf))]
        (if offset-str
          (OffsetTime/of local-time (ZoneOffset/of ^String offset-str))
          local-time))
      (catch Exception e
        (add-err! env (->CannotParseTime t-str (.getMessage e)))))

    (add-err! env (->CannotParseTime t-str nil))))

(defrecord CannotParseTimestamp [ts-str msg])

(defn parse-timestamp-literal [ts-str env]
  (if-let [[_ y mons d h mins s sf ^String offset zone] (re-matches #"(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})?(?:\[([\w\/]+)\])?" ts-str)]
    (try
      (let [ldt (LocalDateTime/of (parse-long y) (parse-long mons) (parse-long d)
                                  (parse-long h) (parse-long mins) (parse-long s) (seconds-fraction->nanos sf))]
        (cond
          zone (ZonedDateTime/ofLocal ldt (ZoneId/of zone) (some-> offset ZoneOffset/of))
          offset (ZonedDateTime/of ldt (ZoneOffset/of offset))
          :else ldt))
      (catch Exception e
        (add-err! env (->CannotParseTimestamp ts-str (.getMessage e)))))

    (add-err! env (->CannotParseTimestamp ts-str nil))))

(defrecord CannotParseInterval [i-str msg])

(defn parse-iso-interval-literal [i-str env]
  (if-let [[_ p-str d-str] (re-matches #"P([-\dYMWD]+)?(?:T([-\dHMS\.]+)?)?" i-str)]
    (try
      (IntervalMonthDayNano. (if p-str
                               (Period/parse (str "P" p-str))
                               Period/ZERO)
                             (if d-str
                               (Duration/parse (str "PT" d-str))
                               Duration/ZERO))
      (catch Exception e
        (add-err! env (->CannotParseInterval i-str (.getMessage e)))))

    (add-err! env (->CannotParseInterval i-str nil))))

(defrecord CannotParseDuration [d-str msg])

(defn- parse-duration-literal [d-str env]
  (try
    (Duration/parse d-str)
    (catch Exception e
      (add-err! env (->CannotParseDuration d-str (.getMessage e))))))

(defn fn-with-precision [fn-symbol ^ParserRuleContext precision-ctx]
  (if-let [precision (some-> precision-ctx (.getText) (parse-long))]
    (list fn-symbol precision)
    (list fn-symbol)))

(defn ->interval-expr [ve {:keys [start-field end-field leading-precision fractional-precision]}]
  (if end-field
    (list 'multi-field-interval ve start-field leading-precision end-field fractional-precision)
    (list 'single-field-interval ve start-field leading-precision fractional-precision)))

(defn iq-context->iq-map [^SqlParser$IntervalQualifierContext ctx]
  (if-let [sdf (.singleDatetimeField ctx)]
    (let [field (-> (.getChild sdf 0) (.getText) (str/upper-case))
          fp (some-> (.intervalFractionalSecondsPrecision sdf) (.getText) (parse-long))]
      {:start-field field
       :end-field nil
       :leading-precision 2
       :fractional-precision (or fp 6)})

    (let [start-field (-> (.startField ctx) (.nonSecondPrimaryDatetimeField) (.getText) (str/upper-case))
          ef (-> (.endField ctx) (.singleDatetimeField))
          end-field (if-let [non-sec-ef (.nonSecondPrimaryDatetimeField ef)]
                      (-> (.getText non-sec-ef) (str/upper-case))
                      "SECOND")
          fp (some-> (.intervalFractionalSecondsPrecision ef) (.getText) (parse-long))]
      {:start-field start-field
       :end-field end-field
       :leading-precision 2
       :fractional-precision (or fp 6)})))

(defn- trim-quotes-from-string [string]
  (subs string 1 (dec (count string))))

(defrecord CastArgsVisitor [env]
  SqlVisitor
  (visitIntegerType [_ ctx]
    {:cast-type (case (str/lower-case (.getText ctx))
                  "smallint" :i16
                  ("int" "integer") :i32
                  "bigint" :i64)})

  (visitFloatType [_ _] {:cast-type :f32})
  (visitRealType [_ _] {:cast-type :f32})
  (visitDoubleType [_ _] {:cast-type :f64})

  (visitDateType [_ _] {:cast-type [:date :day]})
  (visitTimeType [_ ctx]
    (let [precision (some-> (.precision ctx) (.getText) (parse-long))
          time-unit (if precision
                      (if (<= precision 6) :micro :nano)
                      :micro)]
      (if (instance? SqlParser$WithTimeZoneContext
                     (.withOrWithoutTimeZone ctx))
        {:->cast-fn (fn [ve]
                      (list* 'cast-tstz ve
                             (when precision
                               [{:precision precision :unit time-unit}])))}

        {:cast-type [:time-local time-unit]
         :cast-opts (when precision
                      {:precision precision})})))

  (visitTimestampType [_ ctx]
    (let [precision (some-> (.precision ctx) (.getText) (parse-long))
          time-unit (if precision
                      (if (<= precision 6) :micro :nano)
                      :micro)]
      (if (instance? SqlParser$WithTimeZoneContext
                     (.withOrWithoutTimeZone ctx))
        {:->cast-fn (fn [ve]
                      (list* 'cast-tstz ve
                             [{:precision precision :unit time-unit}]))}

        {:cast-type [:timestamp-local time-unit]
         :cast-opts (when precision
                      {:precision precision})})))

  (visitDurationType [_ ctx]
    (let [precision (some-> (.precision ctx) (.getText) (parse-long))
          time-unit (if precision
                      (if (<= precision 6) :micro :nano)
                      :micro)]
      {:cast-type [:duration time-unit]
       :cast-opts (when precision {:precision precision})}))

  (visitIntervalType [_ ctx]
    (let [interval-qualifier (.intervalQualifier ctx)]
      {:cast-type :interval
       :cast-opts (when interval-qualifier (iq-context->iq-map interval-qualifier))}))

  (visitCharacterStringType [_ _] {:cast-type :utf8}))

(defrecord ExprPlanVisitor [env scope]
  SqlVisitor
  (visitSearchCondition [this ctx] (-> (.expr ctx) (.accept this)))
  (visitWrappedExpr [this ctx] (-> (.expr ctx) (.accept this)))

  (visitLiteralExpr [this ctx] (-> (.literal ctx) (.accept this)))
  (visitFloatLiteral [_ ctx] (parse-double (.getText ctx)))
  (visitIntegerLiteral [_ ctx] (parse-long (.getText ctx)))

  (visitCharacterStringLiteral [this ctx] (-> (.characterString ctx) (.accept this)))

  (visitCharacterString [_ ctx]
    (trim-quotes-from-string (.getText ctx)))

  (visitDateLiteral [this ctx] (parse-date-literal (.accept (.characterString ctx) this) env))
  (visitTimeLiteral [this ctx] (parse-time-literal (.accept (.characterString ctx) this) env))
  (visitTimestampLiteral [this ctx] (parse-timestamp-literal (.accept (.characterString ctx) this) env))

  (visitIntervalLiteral [this ctx]
    (let [csl (some-> (.characterString ctx) (.accept this))
          iq-map (some-> (.intervalQualifier ctx) (iq-context->iq-map))
          interval-expr (if iq-map
                          (->interval-expr csl iq-map)
                          (parse-iso-interval-literal csl env))]
      (if (.MINUS ctx)
        (list '- interval-expr)
        interval-expr)))

  (visitDurationLiteral [this ctx] (parse-duration-literal (.accept (.characterString ctx) this) env))

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

  (visitFieldAccess [this ctx]
    (let [ve (-> (.expr ctx) (.accept this))
          field-name (-> (.fieldName ctx) (.identifier) (identifier-str))]
      (list '. ve (keyword field-name))))

  (visitArrayAccess [this ctx]
    (let [ve (-> (.expr ctx 0) (.accept this))
          n (-> (.expr ctx 1) (.accept this))]
      (list 'nth ve (list '- n 1))))

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

  (visitConcatExpr [this ctx]
    (list 'concat
          (-> (.expr ctx 0) (.accept this))
          (-> (.expr ctx 1) (.accept this))))

  (visitIsBooleanValueExpr [this ctx]
    (let [boolean-value (-> (.booleanValue ctx) (.getText) (str/upper-case))
          expr (-> (.expr ctx) (.accept this))
          boolean-fn (case boolean-value
                       "TRUE" (list 'true? expr)
                       "FALSE" (list 'false? expr)
                       "UNKNOWN" (list 'nil? expr))]
      (if (.NOT ctx)
        (list 'not boolean-fn)
        boolean-fn)))
  
  (visitExtractFunction [this ctx]
    (let [extract-field (-> (.extractField ctx) (.getText) (str/upper-case))
          extract-source (-> (.extractSource ctx) (.expr) (.accept this))]
      (list 'extract extract-field extract-source)))

  (visitPositionFunction [this ctx]
    (let [needle (-> (.expr ctx 0) (.accept this))
          haystack (-> (.expr ctx 1) (.accept this))
          units (or (some-> (.charLengthUnits ctx) (.getText)) "CHARACTERS")]
      (list (case units
              "CHARACTERS" 'position
              "OCTETS" 'octet-position)
            needle haystack)))

  (visitCharacterLengthFunction [this ctx]
    (let [nve (-> (.expr ctx) (.accept this))
          units (or (some-> (.charLengthUnits ctx) (.getText)) "CHARACTERS")]
      (list (case units
              "CHARACTERS" 'character-length
              "OCTETS" 'octet-length)
            nve)))

  (visitOctetLengthFunction [this ctx]
    (let [nve (-> (.expr ctx) (.accept this))]
      (list 'octet-length nve)))

  (visitLengthFunction [this ctx]
    (let [nve (-> (.expr ctx) (.getChild 0) (.accept this))]
      (list 'length nve)))

  (visitCardinalityFunction [this ctx]
    (let [nve (-> (.expr ctx) (.accept this))]
      (list 'cardinality nve)))

  (visitAbsFunction [this ctx]
    (let [nve (-> (.expr ctx) (.accept this))]
      (list 'abs nve)))

  (visitModFunction [this ctx]
    (let [nve1 (-> (.expr ctx 0) (.accept this))
          nve2 (-> (.expr ctx 1) (.accept this))]
      (list 'mod nve1 nve2)))

  (visitTrigonometricFunction [this ctx]
    (let [nve (-> (.expr ctx) (.accept this))
          fn-name (-> (.trigonometricFunctionName ctx) (.getText) (str/lower-case))]
      (list (symbol fn-name) nve)))

  (visitLogFunction [this ctx]
    (let [nve1 (-> (.generalLogarithmBase ctx) (.expr) (.accept this))
          nve2 (-> (.generalLogarithmArgument ctx) (.expr) (.accept this))]
      (list 'log nve1 nve2)))

  (visitLog10Function [this ctx]
    (let [nve (-> (.expr ctx) (.accept this))]
      (list 'log10 nve)))

  (visitLnFunction [this ctx]
    (let [nve (-> (.expr ctx) (.accept this))]
      (list 'ln nve)))

  (visitExpFunction [this ctx]
    (let [nve (-> (.expr ctx) (.accept this))]
      (list 'exp nve)))

  (visitPowerFunction [this ctx]
    (let [nve1 (-> (.expr ctx 0) (.accept this))
          nve2 (-> (.expr ctx 1) (.accept this))]
      (list 'power nve1 nve2)))

  (visitSqrtFunction [this ctx]
    (let [nve (-> (.expr ctx) (.accept this))]
      (list 'sqrt nve)))

  (visitFloorFunction [this ctx]
    (let [nve (-> (.expr ctx) (.accept this))]
      (list 'floor nve)))

  (visitCeilingFunction [this ctx]
    (let [nve (-> (.expr ctx) (.accept this))]
      (list 'ceil nve)))

  (visitLeastFunction [this ctx]
    (let [nves (mapv #(.accept ^ParserRuleContext % this) (.expr ctx))]
      (list* 'least nves)))

  (visitGreatestFunction [this ctx]
    (let [nves (mapv #(.accept ^ParserRuleContext % this) (.expr ctx))]
      (list* 'greatest nves)))

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

  (visitBetweenPredicatePart2 [{:keys [pt1] :as this} ctx]
    (let [lower (-> (.expr ctx 0) (.accept this))
          upper (-> (.expr ctx 1) (.accept this))
          not? (.NOT ctx)
          f (cond
              (.SYMMETRIC ctx) 'between-symmetric
              (.ASYMMETRIC ctx) 'between
              :else 'between)]
      (if not?
        (list 'not (list f pt1 lower upper))
        (list f pt1 lower upper))))

  (visitLikePredicatePart2 [{:keys [pt1] :as this} ctx]
    (let [cp (-> (.likePattern ctx) (.expr) (.accept this))]
      (if (.NOT ctx)
        (list 'not (list 'like pt1 cp))
        (list 'like pt1 cp))))

  (visitLikeRegexPredicatePart2 [{:keys [pt1] :as this} ctx]
    (let [xqp (-> (.xqueryPattern ctx) (.expr) (.accept this))
          flag (or (some-> (.xqueryOptionFlag ctx) (.expr) (.accept this)) "")]
      (if (.NOT ctx)
        (list 'not (list 'like-regex pt1 xqp flag))
        (list 'like-regex pt1 xqp flag))))

  (visitPostgresRegexPredicatePart2 [{:keys [pt1] :as this} ctx]
    (let [pro (-> (.postgresRegexOperator ctx) (.getText))
          xqp (-> (.xqueryPattern ctx) (.expr) (.accept this))
          not? (#{"!~" "!~*"} pro)
          flag (if (#{"~*" "!~*"} pro) "i" "")]
      (if not?
        (list 'not (list 'like-regex pt1 xqp flag))
        (list 'like-regex pt1 xqp flag))))

  (visitNullPredicatePart2 [{:keys [pt1]} ctx]
    (if (.NOT ctx)
      (list 'not (list 'nil? pt1))
      (list 'nil? pt1)))

  ;; TODO
  ;; (visitInPredicate [this ctx])
  ;; (visitQuantifiedComparisonPredicate [this ctx])
  ;; (visitExistsPredicate [this ctx])

  (visitPeriodOverlapsPredicate [this ctx]
    (let [p1 (-> (.periodPredicand ctx 0) (.accept this))
          p2 (-> (.periodPredicand ctx 1) (.accept this))]
      (list 'and (list '< (:from p1) (:to p2)) (list '> (:to p1) (:from p2)))))

  (visitPeriodEqualsPredicate [this ctx]
    (let [p1 (-> (.periodPredicand ctx 0) (.accept this))
          p2 (-> (.periodPredicand ctx 1) (.accept this))]
      (list 'and (list '= (:from p1) (:from p2)) (list '= (:to p1) (:to p2)))))

  (visitPeriodContainsPredicate [this ctx]
    (let [p1 (-> (.periodPredicand ctx) (.accept this))
          p2 (-> (.periodOrPointInTimePredicand ctx) (.accept this))]
      (list 'and (list '<= (:from p1) (:from p2)) (list '>= (:to p1) (:to p2)))))

  (visitPeriodPrecedesPredicate [this ctx]
    (let [p1 (-> (.periodPredicand ctx 0) (.accept this))
          p2 (-> (.periodPredicand ctx 1) (.accept this))]
      (list '<= (:to p1) (:from p2))))

  (visitPeriodSucceedsPredicate [this ctx]
    (let [p1 (-> (.periodPredicand ctx 0) (.accept this))
          p2 (-> (.periodPredicand ctx 1) (.accept this))]
      (list '>= (:from p1) (:to p2))))

  (visitPeriodImmediatelyPrecedesPredicate [this ctx]
    (let [p1 (-> (.periodPredicand ctx 0) (.accept this))
          p2 (-> (.periodPredicand ctx 1) (.accept this))]
      (list '= (:to p1) (:from p2))))

  (visitPeriodImmediatelySucceedsPredicate [this ctx]
    (let [p1 (-> (.periodPredicand ctx 0) (.accept this))
          p2 (-> (.periodPredicand ctx 1) (.accept this))]
      (list '= (:from p1) (:to p2))))

  (visitPeriodColumnReference [_ ctx]
    (let [tn (some-> (.tableName ctx) (identifier-str))
          pcn (-> (.periodColumnName ctx) (.getText) (str/upper-case))]
      (case pcn
        ;; TODO split on nil tn?
        "VALID_TIME" {:from (find-decl scope tn "xt$valid_from")
                      :to (find-decl scope tn "xt$valid_to")}
        "SYSTEM_TIME" {:from (find-decl scope tn "xt$system_from")
                       :to (find-decl scope tn "xt$system_to")})))

  (visitPeriodValueConstructor [this ctx]
    (let [sv (some-> (.periodStartValue ctx) (.expr) (.accept this))
          ev (some-> (.periodEndValue ctx) (.expr) (.accept this))]
      {:from sv :to ev}))

  (visitPeriodOrPointInTimePredicand [this ctx] (.accept (.getChild ctx 0) this))

  (visitPointInTimePredicand [this ctx]
    (let [pit (-> (.expr ctx) (.accept this))]
      {:from pit :to pit}))

  (visitHasTablePrivilegePredicate [_ _] true)
  (visitHasSchemaPrivilegePredicate [_ _23] true)

  (visitCurrentDateFunction [_ _] '(current-date))
  (visitCurrentTimeFunction [_ ctx] (fn-with-precision 'current-time (.precision ctx)))
  (visitCurrentTimestampFunction [_ ctx] (fn-with-precision 'current-timestamp (.precision ctx)))
  (visitLocalTimeFunction [_ ctx] (fn-with-precision 'local-time (.precision ctx)))
  (visitLocalTimestampFunction [_ ctx] (fn-with-precision 'local-timestamp (.precision ctx)))
  (visitEndOfTimeFunction [_ _] 'xtdb/end-of-time)

  (visitDateTruncFunction [this ctx]
    (let [dtp (-> (.dateTruncPrecision ctx) (.getText) (str/upper-case))
          dts (-> (.dateTruncSource ctx) (.expr) (.accept this))
          dt-tz (some-> (.dateTruncTimeZone ctx) (.characterString) (.accept this))]
      (if dt-tz
        (list 'date_trunc dtp dts dt-tz)
        (list 'date_trunc dtp dts))))

  (visitAgeFunction [this ctx]
    (let [ve1 (-> (.expr ctx 0) (.accept this))
          ve2 (-> (.expr ctx 1) (.accept this))]
      (list 'age ve1 ve2)))

  (visitObjectExpr [this ctx] (.accept (.objectConstructor ctx) this))

  (visitObjectConstructor [this ctx]
    (->> (for [^SqlParser$ObjectNameAndValueContext kv (.objectNameAndValue ctx)]
           (MapEntry/create (keyword (-> (.objectName kv) (.accept this)))
                            (-> (.expr kv) (.accept this))))
         (into {})))

  (visitObjectName [this ctx] (-> (.characterString ctx) (.accept this)))

  (visitArrayExpr [this ctx] (.accept (.arrayValueConstructor ctx) this))

  (visitArrayValueConstructorByEnumeration [this ctx]
    (mapv #(.accept ^ParserRuleContext % this) (.expr ctx)))

  (visitTrimArrayFunction [this ctx]
    (let [ve-1 (-> (.expr ctx 0) (.accept this))
          ve-2 (-> (.expr ctx 1) (.accept this))]
      (list 'trim-array ve-1 ve-2)))

  (visitCharacterSubstringFunction [this ctx]
    (let [cve (-> (.expr ctx) (.accept this))
          sp (-> (.startPosition ctx) (.expr) (.accept this))
          sl (some-> (.stringLength ctx) (.expr) (.accept this))]
      (if sl
        (list 'substring cve sp sl)
        (list 'substring cve sp))))

  (visitLowerFunction [this ctx] (list 'lower (-> (.expr ctx) (.accept this))))
  (visitUpperFunction [this ctx] (list 'upper (-> (.expr ctx) (.accept this))))

  (visitTrimFunction [this ctx]
    (let [trim-fn (case (some-> (.trimSpecification ctx) (.getText) (str/upper-case))
                    "LEADING" 'trim-leading
                    "TRAILING" 'trim-trailing
                    'trim)
          trim-char (some-> (.trimCharacter ctx) (.expr) (.accept this))
          nve (-> (.trimSource ctx) (.expr) (.accept this))]
      (list trim-fn nve (or trim-char " "))))

  (visitOverlayFunction [this ctx]
    (let [target (-> (.expr ctx 0) (.accept this))
          placing (-> (.expr ctx 1) (.accept this))
          pos (-> (.startPosition ctx) (.expr) (.accept this))
          len (some-> (.stringLength ctx) (.expr) (.accept this))]
      (if len
        (list 'overlay target placing pos len)
        (list 'overlay target placing pos (list 'default-overlay-length placing)))))

  (visitCurrentUserFunction [_ _] '(current-user))
  (visitCurrentSchemaFunction [_ _] '(current-schema))
  (visitCurrentDatabaseFunction [_ _] '(current-database))

  (visitSimpleCaseExpr [this ctx]
    (let [case-operand (-> (.expr ctx) (.accept this))
          when-clauses (->> (.simpleWhenClause ctx)
                            (mapv #(.accept ^SqlParser$SimpleWhenClauseContext % this))
                            (reduce into []))
          else-clause (some-> (.elseClause ctx) (.accept this))]
      (list* 'case case-operand (cond-> when-clauses
                                  else-clause (conj else-clause)))))

  (visitSearchedCaseExpr [this ctx]
    (let [when-clauses (->> (.searchedWhenClause ctx)
                            (mapv #(.accept ^SqlParser$SearchedWhenClauseContext % this))
                            (reduce into []))
          else-clause (some-> (.elseClause ctx) (.accept this))]
      (list* 'cond (cond-> when-clauses
                     else-clause (conj else-clause)))))

  (visitSimpleWhenClause [this ctx]
    (let [when-operands (-> (.whenOperandList ctx) (.whenOperand))
          when-exprs (mapv #(.accept (.getChild ^SqlParser$WhenOperandContext % 0) this) when-operands)
          then-expr (-> (.expr ctx) (.accept this))]
      (->> (for [when-expr when-exprs]
             [when-expr then-expr])
           (reduce into []))))

  (visitSearchedWhenClause [this ctx]
    (let [expr1 (-> (.expr ctx 0) (.accept this))
          expr2 (-> (.expr ctx 1) (.accept this))]
      [expr1 expr2]))

  (visitElseClause [this ctx] (-> (.expr ctx) (.accept this)))

  (visitNullIfExpr [this ctx]
    (list 'nullif
          (-> (.expr ctx 0) (.accept this))
          (-> (.expr ctx 1) (.accept this))))

  (visitCoalesceExpr [this ctx]
    (list* 'coalesce (mapv #(.accept ^ParserRuleContext % this) (.expr ctx))))

  (visitCastExpr [this ctx]
    (let [ve (-> (.expr ctx) (.accept this))
          {:keys [cast-type cast-opts ->cast-fn]} (-> (.dataType ctx) (.accept (->CastArgsVisitor env)))]
      (if ->cast-fn
        (->cast-fn ve)
        (cond-> (list 'cast ve cast-type)
          (not-empty cast-opts) (concat [cast-opts]))))))

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
                        (mapv #(symbol unique-table-alias (str %)))))))

  (visitInsertValues [this ctx]
    (let [out-col-syms (->> (.columnName (.columnNameList ctx))
                            (mapv (comp symbol util/str->normal-form-str identifier-str)))
          {:keys [plan col-syms] :as inner} (-> (.tableValueConstructor ctx)
                                                (.accept (assoc this :out-col-syms out-col-syms)))]
      (if (some (comp types/temporal-column? str) col-syms)
        (->QueryExpr [:project (mapv (fn [col-sym]
                                       {col-sym
                                        (cond->> col-sym
                                          (types/temporal-column? (str col-sym)) (list 'cast-tstz))})
                                     col-syms)
                      plan]
                     col-syms)

        inner)))

  (visitInsertFromSubquery [this ctx]
    (let [out-col-syms (some->> (.columnNameList ctx) .columnName
                                (mapv (comp symbol util/str->normal-form-str identifier-str)))
          {:keys [plan col-syms] :as inner} (-> (.queryExpression ctx)
                                                (.accept (cond-> this
                                                           out-col-syms (assoc :out-col-syms out-col-syms))))
          norm-col-syms (mapv util/symbol->normal-form-symbol col-syms)]
      (if (some (comp types/temporal-column? str) norm-col-syms)
        (->QueryExpr [:project (mapv (fn [norm-col-sym col-sym]
                                       {norm-col-sym
                                        (cond->> col-sym
                                          (types/temporal-column? (str norm-col-sym)) (list 'cast-tstz))})
                                     norm-col-syms
                                     col-syms)
                      plan]
                     col-syms)
        inner))))

(defrecord DmlTableRef [table-name table-alias for-valid-time cols !reqd-cols]
  Scope
  (available-cols [_ table-name]
    (when-not (and table-name (not= table-name table-alias))
      cols))

  (find-decl [_ col-name]
    (let [col-norm (util/str->normal-form-str col-name)]
      (when (or (contains? cols col-norm) (types/temporal-column? col-norm))
        (swap! !reqd-cols conj (symbol col-name))
        (symbol col-name))))

  (find-decl [this table-name col-name]
    (when (= table-name table-alias)
      (find-decl this col-name)))

  (plan-scope [_]
    [:scan {:table (symbol table-name)
            :for-valid-time for-valid-time}
     (vec @!reqd-cols)]))

(defrecord DmlValidTimeExtentsVisitor [env scope]
  SqlVisitor
  (visitDmlStatementValidTimeExtents [this ctx] (-> (.getChild ctx 0) (.accept this)))

  (visitDmlStatementValidTimeAll [_ _]
    {:for-valid-time :all-time,
     :projection '[xt$valid_from xt$valid_to]})

  (visitDmlStatementValidTimePortion [{{:keys [default-all-valid-time?]} :env} ctx]
    (let [expr-visitor (->ExprPlanVisitor env scope)
          from-expr (-> (.expr ctx 0) (.accept expr-visitor))
          to-expr (-> (.expr ctx 1) (.accept expr-visitor))]
      {:for-valid-time [:between from-expr to-expr]
       :projection [{'xt$valid_from (cond
                                      from-expr (list 'greatest 'xt$valid_from (list 'cast-tstz from-expr))
                                      default-all-valid-time? 'xt$valid_from
                                      :else (list 'greater 'xt$valid_from '(cast-tstz (current-timestamp))))}

                    {'xt$valid_to (if to-expr
                                    (list 'least 'xt$valid_to (list 'cast-tstz to-expr))
                                    'xt$valid_to)}]})))

(defn- default-vt-extents-projection [{:keys [default-all-valid-time?]}]
  [{'xt$valid_from (if default-all-valid-time?
                     'xt$valid_from
                     (list 'greatest 'xt$valid_from '(cast-tstz (current-timestamp))))}
   'xt$valid_to])

(defrecord EraseTableRef [table-name table-alias cols !reqd-cols]
  Scope
  (available-cols [_ table-name]
    (when-not (and table-name (not= table-name table-alias))
      cols))

  (find-decl [_ col-name]
    (let [col-norm (util/str->normal-form-str col-name)]
      (when (or (contains? cols col-norm) (types/temporal-column? col-norm))
        (swap! !reqd-cols conj (symbol col-name))
        (symbol col-name))))

  (find-decl [this table-name col-name]
    (when (= table-name table-alias)
      (find-decl this col-name)))

  (plan-scope [_]
    [:scan {:table (symbol table-name)
            :for-system-time :all-time
            :for-valid-time :all-time}
     (vec @!reqd-cols)]))

(defrecord InsertStmt [table query-plan]
  Optimise (optimise [this] (update this :query-plan lp/rewrite-plan)))

(defrecord UpdateStmt [table query-plan]
  Optimise (optimise [this] (update this :query-plan lp/rewrite-plan)))

(defrecord DeleteStmt [table query-plan]
  Optimise (optimise [this] (update this :query-plan lp/rewrite-plan)))

(defrecord EraseStmt [table query-plan]
  Optimise (optimise [this] (update this :query-plan lp/rewrite-plan)))

(defrecord StmtVisitor [env scope]
  SqlVisitor
  (visitDirectSqlStatement [this ctx] (-> (.directlyExecutableStatement ctx) (.accept this)))
  (visitDirectlyExecutableStatement [this ctx] (-> (.getChild ctx 0) (.accept this)))

  (visitQueryExpression [_ ctx] (-> ctx (.accept (->QueryPlanVisitor env scope))))

  (visitInsertStatement [_ ctx]
    (->InsertStmt (symbol (util/str->normal-form-str (identifier-str (.tableName ctx))))
                  (-> (.insertColumnsAndSource ctx)
                      (.accept (->QueryPlanVisitor env scope)))))

  (visitUpdateStatementSearched [_ ctx]
    (let [internal-cols '[xt$iid xt$valid_from xt$valid_to]
          table-name (util/str->normal-form-str (identifier-str (.tableName ctx)))
          table-alias (some-> (.correlationName ctx) identifier-str)

          {:keys [for-valid-time], vt-projection :projection} (some-> (.dmlStatementValidTimeExtents ctx)
                                                                      (.accept (->DmlValidTimeExtentsVisitor env scope)))

          dml-scope (->DmlTableRef table-name (or table-alias table-name) for-valid-time
                                   (or (get-in env [:table-info table-name])
                                       (throw (UnsupportedOperationException. "TODO")))
                                   (atom (set internal-cols)))

          expr-visitor (->ExprPlanVisitor env dml-scope)

          set-clauses (->> (for [^SqlParser$SetClauseContext set-clause (->> (.setClauseList ctx) (.setClause))
                                 :let [set-target (.setTarget set-clause)]]
                             (if (.UNSIGNED_INTEGER set-target)
                               (throw (UnsupportedOperationException. "TODO"))
                               (MapEntry/create (identifier-str (.columnName (.objectColumn set-target)))
                                                (.accept (.expr (.updateSource set-clause)) expr-visitor))))
                           (into {}))

          where-selection (some-> (.searchCondition ctx)
                                  (.accept expr-visitor))

          available-cols (available-cols dml-scope nil)

          projection (vec (concat '[xt$iid]
                                  (or vt-projection (default-vt-extents-projection env))
                                  (for [col available-cols
                                        :let [col-sym (symbol col)]]
                                    (if-let [expr (get set-clauses col)]
                                      {col-sym expr}
                                      col-sym))))]

      (->UpdateStmt (symbol table-name)
                    (->QueryExpr [:project projection
                                  (cond-> (plan-scope dml-scope)
                                    where-selection ((fn [plan]
                                                       [:select where-selection
                                                        plan])))]
                                 (into internal-cols available-cols)))))

  (visitDeleteStatementSearched [_ ctx]
    (let [internal-cols '[xt$iid xt$valid_from xt$valid_to]
          table-name (util/str->normal-form-str (identifier-str (.tableName ctx)))
          table-alias (some-> (.correlationName ctx) identifier-str)

          {:keys [for-valid-time], vt-projection :projection} (some-> (.dmlStatementValidTimeExtents ctx)
                                                                      (.accept (->DmlValidTimeExtentsVisitor env scope)))

          dml-scope (->DmlTableRef table-name (or table-alias table-name) for-valid-time
                                   (or (get-in env [:table-info table-name])
                                       (throw (UnsupportedOperationException. "TODO")))
                                   (atom (set internal-cols)))

          where-selection (some-> (.searchCondition ctx)
                                  (.accept (->ExprPlanVisitor env dml-scope)))

          projection (into '[xt$iid] (or vt-projection (default-vt-extents-projection env)))]

      (->DeleteStmt (symbol table-name)
                    (->QueryExpr [:project projection
                                  (cond-> (plan-scope dml-scope)
                                    where-selection ((fn [plan]
                                                       [:select where-selection
                                                        plan])))]
                                 internal-cols))))

  (visitEraseStatementSearched [_ ctx]
    (let [table-name (util/str->normal-form-str (identifier-str (.tableName ctx)))
          table-alias (some-> (.correlationName ctx) identifier-str)
          dml-scope (->EraseTableRef table-name (or table-alias table-name)
                                     (or (get-in env [:table-info table-name])
                                         (throw (UnsupportedOperationException. "TODO")))
                                     (atom '#{xt$iid}))

          where-selection (some-> (.searchCondition ctx)
                                  (.accept (->ExprPlanVisitor env dml-scope)))]

      (->EraseStmt (symbol table-name)
                   (->QueryExpr [:distinct
                                 [:project '[xt$iid]
                                  (cond-> (plan-scope dml-scope)
                                    where-selection ((fn [plan]
                                                       [:select where-selection
                                                        plan])))]]
                                '[xt$iid])))))

(defn ->parser ^xtdb.antlr.SqlParser [sql]
  (-> (CharStreams/fromString sql)
      (SqlLexer.)
      (CommonTokenStream.)
      (SqlParser.)))

(defn plan-expr
  ([sql] (plan-expr sql {}))

  ([sql {:keys [scope table-info]}]
   (let [!errors (atom [])
         env {:!errors !errors
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
  QueryExpr (->logical-plan [{:keys [plan]}] plan)

  InsertStmt
  (->logical-plan [{:keys [table query-plan]}]
    [:insert {:table table}
     (->logical-plan query-plan)])

  UpdateStmt
  (->logical-plan [{:keys [table query-plan]}]
    [:update {:table table}
     (->logical-plan query-plan)])

  DeleteStmt
  (->logical-plan [{:keys [table query-plan]}]
    [:delete {:table table}
     (->logical-plan query-plan)])

  EraseStmt
  (->logical-plan [{:keys [table query-plan]}]
    [:erase {:table table}
     (->logical-plan query-plan)]))

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
  (plan-statement "UPDATE foo SET bar = baz WHERE baz = 4"
                  {:table-info {"foo" #{"bar" "baz"}
                                "bar" #{"quux"}}}))
