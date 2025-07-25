(ns xtdb.expression.map
  (:require [xtdb.expression :as expr]
            [xtdb.expression.walk :as ewalk]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.reader :as vr]
            [xtdb.vector.writer :as vw])
  (:import (java.lang AutoCloseable)
           java.util.function.IntBinaryOperator
           (java.util List Map)
           (org.apache.arrow.memory BufferAllocator)
           (org.apache.arrow.memory.util.hash MurmurHasher)
           (org.apache.arrow.vector NullVector VectorSchemaRoot)
           (org.apache.arrow.vector.types.pojo Schema)
           (org.roaringbitmap IntConsumer RoaringBitmap)
           (xtdb.arrow RelationReader VectorReader)
           (xtdb.util Hasher$Xx)
           (com.carrotsearch.hppc IntObjectHashMap)))

#_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
(definterface IIndexHasher
  (^int hashCode [^int idx]))

(defn ->hasher ^xtdb.expression.map.IIndexHasher [^List #_<VectorReader> cols]
  (let [hasher (Hasher$Xx.)]
    (case (.size cols)
      1 (let [^VectorReader col (.get cols 0)]
          (reify IIndexHasher
            (hashCode [_ idx]
              (.hashCode col idx hasher))))

      (reify IIndexHasher
        (hashCode [_ idx]
          (loop [n 0
                 hash-code 0]
            (if (< n (.size cols))
              (let [^VectorReader col (.get cols n)]
                (recur (inc n) (MurmurHasher/combineHashCode hash-code (.hashCode col idx hasher))))
              hash-code)))))))

#_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
(definterface IRelationMapBuilder
  (^void add [^int inIdx])
  (^int addIfNotPresent [^int inIdx]))

#_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
(definterface IRelationMapProber
  (^int indexOf [^int inIdx, ^boolean removeOnMatch])
  (^void forEachMatch [^int inIdx, ^java.util.function.IntConsumer c])
  (^int matches [^int inIdx]))

#_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
(definterface IRelationMap
  (^java.util.Map buildFields [])
  (^java.util.List buildKeyColumnNames [])
  (^java.util.Map probeFields [])
  (^java.util.List probeKeyColumnNames [])

  (^xtdb.expression.map.IRelationMapBuilder buildFromRelation [^xtdb.arrow.RelationReader inRelation])
  (^xtdb.expression.map.IRelationMapProber probeFromRelation [^xtdb.arrow.RelationReader inRelation])
  (^xtdb.arrow.RelationReader getBuiltRelation []))

(defn- andIBO
  ([]
   (reify IntBinaryOperator
     (applyAsInt [_ _l _r]
       1)))

  ([^IntBinaryOperator p1, ^IntBinaryOperator p2]
   (reify IntBinaryOperator
     (applyAsInt [_ l r]
       (let [l-res (.applyAsInt p1 l r)]
         (if (= -1 l-res)
           -1
           (Math/min l-res (.applyAsInt p2 l r))))))))

(def ^:private left-rel (gensym 'left-rel))
(def ^:private left-vec (gensym 'left-vec))
(def ^:private left-idx (gensym 'left-idx))

(def ^:private right-rel (gensym 'right-rel))
(def ^:private right-vec (gensym 'right-vec))
(def ^:private right-idx (gensym 'right-idx))

(def build-comparator
  (-> (fn [expr input-opts]
        (let [{:keys [continue], :as emitted-expr}
              (expr/codegen-expr expr input-opts)]

          (-> `(fn [~(expr/with-tag left-rel RelationReader)
                    ~(expr/with-tag right-rel RelationReader)
                    ~(-> expr/schema-sym (expr/with-tag Map))
                    ~(-> expr/args-sym (expr/with-tag RelationReader))]
                 (let [~@(expr/batch-bindings emitted-expr)]
                   (reify IntBinaryOperator
                     (~'applyAsInt [_# ~left-idx ~right-idx]
                      ~(continue (fn [res-type code]
                                   (case res-type
                                     :null 0
                                     :bool `(if ~code 1 -1))))))))

              #_(doto clojure.pprint/pprint)
              (eval))))
      (util/lru-memoize)))

(def ^:private pg-class-schema-hack
  {"pg_catalog/pg_class" #{}})

(defn- ->equi-comparator [^VectorReader left-col, ^VectorReader right-col, params
                          {:keys [nil-keys-equal? param-types]}]
  (let [f (build-comparator {:op :call, :f (if nil-keys-equal? :null-eq :=)
                             :args [{:op :variable, :variable left-vec, :rel left-rel, :idx left-idx}
                                    {:op :variable, :variable right-vec, :rel right-rel, :idx right-idx}]}
                            {:var->col-type {left-vec (types/field->col-type (.getField left-col))
                                             right-vec (types/field->col-type (.getField right-col))}
                             :param-types param-types})]
    (f (vr/rel-reader [(.withName left-col (str left-vec))])
       (vr/rel-reader [(.withName right-col (str right-vec))])
       pg-class-schema-hack
       params)))

(defn- ->theta-comparator [probe-rel build-rel theta-expr params {:keys [build-fields probe-fields param-types]}]
  (let [col-types (update-vals (merge build-fields probe-fields) types/field->col-type)
        f (build-comparator (->> (expr/form->expr theta-expr {:col-types col-types, :param-types param-types})
                                 (expr/prepare-expr)
                                 (ewalk/postwalk-expr (fn [{:keys [op] :as expr}]
                                                        (cond-> expr
                                                          (= op :variable)
                                                          (into (let [{:keys [variable]} expr]
                                                                  (if (contains? probe-fields variable)
                                                                    {:rel left-rel, :idx left-idx}
                                                                    {:rel right-rel, :idx right-idx})))))))
                            {:var->col-type col-types, :param-types param-types})]
    (f probe-rel
       build-rel
       pg-class-schema-hack
       params)))

(defn- find-in-hash-bitmap ^long [^RoaringBitmap hash-bitmap, ^IntBinaryOperator comparator, ^long idx, remove-on-match?]
  (if-not hash-bitmap
    -1
    (let [it (.getIntIterator hash-bitmap)]
      (loop []
        (if-not (.hasNext it)
          -1
          (let [test-idx (.next it)]
            (if (= 1 (.applyAsInt comparator idx test-idx))
              (do
                (when remove-on-match?
                  (.remove hash-bitmap test-idx))
                test-idx)
              (recur))))))))

(defn returned-idx ^long [^long inserted-idx]
  (-> inserted-idx - dec))

(defn inserted-idx ^long [^long returned-idx]
  (cond-> returned-idx
    (neg? returned-idx) (-> inc -)))

(defn ->nil-rel
  "Returns a single row relation where all columns are nil. (Useful for outer joins)."
  ^xtdb.arrow.RelationReader [col-names]
  (vr/rel-reader (for [col-name col-names]
                   (vr/vec->reader (doto (NullVector. (str col-name))
                                     (.setValueCount 1))))))

(defn ->nillable-rel-writer
  "Returns a relation with a single row where all columns are nil, but the schema is nillable."
  ^xtdb.arrow.RelationWriter [^BufferAllocator allocator fields]
  (let [schema (Schema. (mapv (fn [[field-name field]]
                                (-> field
                                    (types/field-with-name (str field-name))
                                    (types/->nullable-field)))
                              fields))]
    (util/with-close-on-catch [root (VectorSchemaRoot/create schema allocator)]
      (let [rel-writer (vw/root->writer root)]
        (doto (.rowCopier rel-writer (->nil-rel (keys fields)))
          (.copyRow 0))
        rel-writer))))

(def nil-row-idx 0)

(defn ->relation-map ^xtdb.expression.map.IRelationMap
  [^BufferAllocator allocator,
   {:keys [key-col-names store-full-build-rel?
           build-fields probe-fields
           with-nil-row? nil-keys-equal?
           theta-expr param-fields args]
    :as opts}]
  (let [param-types (update-vals param-fields types/field->col-type)
        build-key-col-names (get opts :build-key-col-names key-col-names)
        probe-key-col-names (get opts :probe-key-col-names key-col-names)

        hash->bitmap (IntObjectHashMap.)
        schema (Schema. (-> build-fields
                            (cond-> (not store-full-build-rel?) (select-keys build-key-col-names))
                            (->> (mapv (fn [[field-name field]]
                                         (cond-> (-> field (types/field-with-name (str field-name)))
                                           with-nil-row? types/->nullable-field))))))]

    (util/with-close-on-catch [root (VectorSchemaRoot/create schema allocator)]
      (let [rel-writer (vw/root->writer root)]
        (when with-nil-row?
          (doto (.rowCopier rel-writer (->nil-rel (keys build-fields)))
            (.copyRow 0)))

        (let [build-key-cols (mapv #(vw/vec-wtr->rdr (.vectorFor rel-writer (str %))) build-key-col-names)]
          (letfn [(compute-hash-bitmap [^long row-hash]
                    (or (.get hash->bitmap row-hash)
                        (let [bitmap (RoaringBitmap.)]
                          (.put hash->bitmap (int row-hash) bitmap)
                          bitmap)))]
            (reify
              IRelationMap
              (buildFields [_] build-fields)
              (buildKeyColumnNames [_] build-key-col-names)
              (probeFields [_] probe-fields)
              (probeKeyColumnNames [_] probe-key-col-names)

              (buildFromRelation [_ in-rel]
                (let [^RelationReader in-rel (if store-full-build-rel?
                                               in-rel
                                               (->> (set build-key-col-names)
                                                    (mapv #(.vectorForOrNull in-rel (str %)))
                                                    vr/rel-reader))

                      in-key-cols (mapv #(.vectorForOrNull in-rel (str %))
                                        build-key-col-names)

                      ;; NOTE: we might not need to compute `comparator` if the caller never requires `addIfNotPresent` (e.g. joins)
                      !comparator (delay
                                    (->> (map (fn [build-col in-col]
                                                (->equi-comparator in-col build-col args
                                                                   {:nil-keys-equal? nil-keys-equal?,
                                                                    :param-types param-types}))
                                              build-key-cols
                                              in-key-cols)
                                         (reduce andIBO)))

                      hasher (->hasher in-key-cols)

                      row-copier (.rowCopier rel-writer in-rel)]

                  (letfn [(add ^long [^RoaringBitmap hash-bitmap, ^long idx]
                            (let [out-idx (.copyRow row-copier idx)]
                              (.add hash-bitmap out-idx)
                              (returned-idx out-idx)))]

                    (reify IRelationMapBuilder
                      (add [_ idx]
                        (add (compute-hash-bitmap (.hashCode hasher idx)) idx))

                      (addIfNotPresent [_ idx]
                        (let [^RoaringBitmap hash-bitmap (compute-hash-bitmap (.hashCode hasher idx))
                              out-idx (find-in-hash-bitmap hash-bitmap @!comparator idx false)]
                          (if-not (neg? out-idx)
                            out-idx
                            (add hash-bitmap idx))))))))

              (probeFromRelation [this probe-rel]
                (let [build-rel (.getBuiltRelation this)
                      probe-key-cols (mapv #(.vectorForOrNull probe-rel (str %))
                                           probe-key-col-names)

                      ^IntBinaryOperator
                      comparator (->> (cond-> (map (fn [build-col probe-col]
                                                     (->equi-comparator probe-col build-col args
                                                                        {:nil-keys-equal? nil-keys-equal?
                                                                         :param-types param-types}))
                                                   build-key-cols
                                                   probe-key-cols)

                                        (some? theta-expr)
                                        (conj (->theta-comparator probe-rel build-rel theta-expr args
                                                                  {:build-fields build-fields
                                                                   :probe-fields probe-fields
                                                                   :param-types param-types})))
                                      (reduce andIBO))

                      hasher (->hasher probe-key-cols)]

                  (reify IRelationMapProber
                    (indexOf [_ idx remove-on-match?]
                      (-> ^RoaringBitmap (.get hash->bitmap (.hashCode hasher idx))
                          (find-in-hash-bitmap comparator idx remove-on-match?)))

                    (forEachMatch [_ idx c]
                      (some-> ^RoaringBitmap (.get hash->bitmap (.hashCode hasher idx))
                              (.forEach (reify IntConsumer
                                          (accept [_ out-idx]
                                            (when (= 1 (.applyAsInt comparator idx out-idx))
                                              (.accept c out-idx)))))))


                    (matches [_ probe-idx]
                      ;; TODO: this doesn't use the hashmaps, still a nested loop join
                      (let [acc (int-array [-1])]
                        (loop [build-idx 0]
                          (if (= build-idx (.getRowCount build-rel))
                            (aget acc 0)
                            (let [res (.applyAsInt comparator probe-idx build-idx)]
                              (if (= 1 res)
                                1
                                (do
                                  (aset acc 0 (Math/max (aget acc 0) res))
                                  (recur (inc build-idx))))))))))))

              (getBuiltRelation [_] (vw/rel-wtr->rdr rel-writer))

              AutoCloseable
              (close [_] (.close rel-writer)))))))))
