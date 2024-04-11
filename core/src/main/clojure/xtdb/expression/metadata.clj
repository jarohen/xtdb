(ns xtdb.expression.metadata
  (:require [xtdb.bloom :as bloom]
            [xtdb.expression :as expr]
            [xtdb.expression.form :as form]
            [xtdb.metadata :as meta]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.writer :as vw])
  (:import java.util.function.IntPredicate
           (xtdb.expression CallExpr IfExpr LetExpr Literal Param Variable)
           (xtdb.metadata IMetadataPredicate ITableMetadata)
           (xtdb.vector IVectorReader RelationReader)))

(set! *unchecked-math* :warn-on-boxed)

(defn- simplify-and-or-expr [{:keys [f args] :as expr}]
  (let [args (filterv some? args)]
    (case (count args)
      0 (expr/->Literal (case f :and true, :or false))
      1 (first args)
      (-> expr (assoc :args args)))))

(declare meta-expr)

(def ^:private bool-metadata-types #{:null :bool :fixed-size-binary :transit})

(declare ->TestMetadata)

(defn call-meta-expr [{:keys [f args] :as expr} {:keys [col-types] :as opts}]
  (letfn [(var-value-expr [f meta-value field value-type value-expr]
            ;; TODO adapt for boolean metadata writer
            (when-not (contains? bool-metadata-types value-type)
              (let [base-col-types (-> (get col-types field)
                                       types/flatten-union-types)]
                (simplify-and-or-expr
                 ;; TODO this seems like it could make better use
                 ;; of the polymorphic expr patterns?
                 (expr/->CallExpr :or
                   (vec
                    (for [col-type (cond
                                     (isa? types/col-type-hierarchy value-type :num)
                                     (filterv types/num-types base-col-types)

                                     (and (vector? value-type) (isa? types/col-type-hierarchy (first value-type) :date-time))
                                     (filterv (comp types/date-time-types types/col-type-head) base-col-types)

                                     (contains? base-col-types value-type)
                                     [value-type])]

                      (->TestMetadata f meta-value field value-expr col-type
                                      (when (= meta-value :bloom-filter)
                                        (gensym 'bloom-hashes))))))))))

          (bool-expr [var-value-f var-value-meta-fn
                      value-var-f value-var-meta-fn]
            (let [[x-arg y-arg] args]
              (condp = [(class x-arg) (class y-arg)]
                [Param Param] expr
                [Literal Literal] expr
                [Literal Param] expr
                [Param Literal] expr

                [Variable Literal] (var-value-expr var-value-f var-value-meta-fn (:variable x-arg)
                                                   (vw/value->col-type (:literal y-arg)) y-arg)

                [Variable Param] (var-value-expr var-value-f var-value-meta-fn (:variable x-arg)
                                                 (:param-type y-arg) y-arg)

                [Literal Variable] (var-value-expr var-value-f var-value-meta-fn (:variable x-arg)
                                                   (vw/value->col-type (:literal y-arg)) y-arg)

                [Param Variable] (var-value-expr value-var-f value-var-meta-fn (:variable y-arg)
                                                 (:param-type x-arg) x-arg)

                nil)))]

    (or (case f
          :and (-> (expr/->CallExpr :and (map #(meta-expr % opts) args))
                   simplify-and-or-expr)
          :or (-> (expr/->CallExpr :or (map #(meta-expr % opts) args))
                  simplify-and-or-expr)
          :< (bool-expr :< :min, :> :max)
          :<= (bool-expr :<= :min, :>= :max)
          :> (bool-expr :> :max, :< :min)
          :>= (bool-expr :>= :max, :<= :min)
          := (-> (expr/->CallExpr :and
                   (->> [(meta-expr (expr/->CallExpr :and
                                      [(expr/->CallExpr :<= args)
                                       (expr/->CallExpr :>= args)])
                                    opts)

                         (bool-expr nil :bloom-filter, nil :bloom-filter)]
                        (filterv some?)))
                 simplify-and-or-expr)
          nil)

        ;; we can't check this call at the metadata level, have to pull the block and look.
        (expr/->Literal true))))

(defn meta-expr [expr opts]
  (condp = (class expr)
    ;; expected to be filtered out by the caller, using simplify-and-or-expr
    Literal nil, Param nil, LetExpr nil

    Variable (expr/->Literal true)

    IfExpr (-> (expr/->CallExpr :or
                 [(meta-expr (:then expr) opts)
                  (meta-expr (:else expr) opts)])
               simplify-and-or-expr)

    CallExpr (call-meta-expr expr opts)))

(defn- ->bloom-hashes [expr ^RelationReader params]
  (vec
    (for [{:keys [value-expr col-type]} (->> (expr/expr-seq expr)
                                             (filter :bloom-hash-sym))]
      (bloom/literal-hashes params value-expr col-type))))

(def ^:private table-metadata-sym (gensym "table-metadata"))
(def ^:private metadata-rdr-sym (gensym "metadata-rdr"))
(def ^:private cols-rdr-sym (gensym "cols-rdr"))
(def ^:private col-rdr-sym (gensym "col-rdr"))
(def ^:private page-idx-sym (gensym "page-idx"))
(def ^:private types-rdr-sym (gensym "types-rdr"))
(def ^:private bloom-rdr-sym (gensym "bloom-rdr"))

(defrecord TestMetadata [f meta-value field value-expr col-type bloom-hash-sym]
  expr/Expr
  (direct-child-exprs [_] [value-expr])

  (walk-expr [_ inner outer]
    (outer (->TestMetadata f meta-value field (inner value-expr) col-type bloom-hash-sym)))

  (codegen-expr [_ opts]
    (let [field-name (util/str->normal-form-str (str field))

          idx-code `(.rowIndex ~table-metadata-sym ~field-name ~page-idx-sym)]

      (if (= meta-value :bloom-filter)
        {:return-type :bool
         :continue (fn [cont]
                     (cont :bool
                           `(boolean
                             (when-let [~expr/idx-sym ~idx-code]
                               (bloom/bloom-contains? ~bloom-rdr-sym ~expr/idx-sym ~bloom-hash-sym)))))}

        (let [col-sym (gensym 'meta_col)
              col-field (types/col-type->field col-type)

              val-sym (gensym 'val)

              {:keys [continue] :as emitted-expr}
              (expr/codegen-expr (expr/->CallExpr :boolean
                                   [(expr/->IfSomeExpr val-sym (expr/->Variable col-sym)
                                      (expr/->CallExpr f
                                        [(expr/->Local val-sym) value-expr])
                                      (expr/->Literal false))])
                                 (-> opts
                                     (assoc-in [:var->col-type col-sym] (types/merge-col-types col-type :null))))]
          {:return-type :bool
           :batch-bindings [[(-> col-sym (expr/with-tag IVectorReader))
                             `(some-> (.structKeyReader ~types-rdr-sym ~(.getName col-field))
                                      (.structKeyReader ~(name meta-value)))]]
           :children [emitted-expr]
           :continue (fn [cont]
                       (cont :bool
                             `(when ~col-sym
                                (when-let [~expr/idx-sym ~idx-code]
                                  ~(continue (fn [_ code] code))))))})))))

(def ^:private compile-meta-expr
  (-> (fn [expr opts]
        (let [expr (-> expr
                       (expr/prepare-expr)
                       (meta-expr opts)
                       (or (expr/->Literal true))
                       (expr/prepare-expr))
              {:keys [continue] :as emitted-expr} (expr/codegen-expr expr opts)]
          {:expr expr
           :f (-> `(fn [~(-> table-metadata-sym (expr/with-tag ITableMetadata))
                        ~(-> expr/params-sym (expr/with-tag RelationReader))
                        [~@(keep :bloom-hash-sym (expr/expr-seq expr))]]
                     (let [~metadata-rdr-sym (.metadataReader ~table-metadata-sym)
                           ~(-> cols-rdr-sym (expr/with-tag IVectorReader)) (.structKeyReader ~metadata-rdr-sym "columns")
                           ~(-> col-rdr-sym (expr/with-tag IVectorReader)) (.listElementReader ~cols-rdr-sym)
                           ~(-> types-rdr-sym (expr/with-tag IVectorReader)) (.structKeyReader ~col-rdr-sym "types")
                           ~(-> bloom-rdr-sym (expr/with-tag IVectorReader)) (.structKeyReader ~col-rdr-sym "bloom")

                           ~@(expr/batch-bindings emitted-expr)]
                       (reify IntPredicate
                         (~'test [_ ~page-idx-sym]
                          (boolean ~(continue (fn [_ code] code)))))))
                  #_(doto clojure.pprint/pprint)
                  (eval))}))

      (util/lru-memoize)))

(defn ->metadata-selector ^xtdb.metadata.IMetadataPredicate [form col-types params]
  (let [param-types (expr/->param-types params)
        {:keys [expr f]} (compile-meta-expr (form/form->expr form {:param-types param-types,
                                                                   :col-types col-types})
                                            {:param-types param-types
                                             :col-types col-types
                                             :extract-vecs-from-rel? false})
        bloom-hashes (->bloom-hashes expr params)]
    (reify IMetadataPredicate
      (build [_ table-metadata]
        (f table-metadata params bloom-hashes)))))
