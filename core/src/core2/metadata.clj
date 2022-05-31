(ns core2.metadata
  (:require [core2.blocks :as blocks]
            [core2.bloom :as bloom]
            core2.buffer-pool
            [core2.expression.comparator :as expr.comp]
            core2.object-store
            core2.tx
            [core2.types :as t]
            [core2.util :as util]
            [core2.vector.indirect :as iv]
            [juxt.clojars-mirrors.integrant.core :as ig])
  (:import core2.buffer_pool.IBufferPool
           core2.object_store.ObjectStore
           core2.tx.Watermark
           (core2.types LegType LegType$StructLegType)
           core2.ICursor
           java.io.Closeable
           (java.util HashMap List SortedSet)
           (java.util.concurrent ConcurrentSkipListSet)
           (java.util.function BiFunction Consumer Function)
           (org.apache.arrow.memory ArrowBuf BufferAllocator)
           (org.apache.arrow.vector BigIntVector BitVector IntVector ValueVector VarBinaryVector VarCharVector VectorSchemaRoot)
           (org.apache.arrow.vector.complex DenseUnionVector ListVector StructVector)
           (org.apache.arrow.vector.types.pojo ArrowType$Binary ArrowType$Bool ArrowType$Date ArrowType$ExtensionType ArrowType$FloatingPoint
                                               ArrowType$Int ArrowType$List ArrowType$Null ArrowType$Struct ArrowType$Timestamp ArrowType$Utf8
                                               ExtensionTypeRegistry Field FieldType Schema)
           (org.apache.arrow.vector.types TimeUnit Types Types$MinorType)
           org.apache.arrow.vector.util.Text
           org.roaringbitmap.RoaringBitmap))

(set! *unchecked-math* :warn-on-boxed)

#_{:clj-kondo/ignore [:unused-binding]}
(definterface IMetadataManager
  (^void registerNewChunk [^java.util.Map roots, ^long chunk-idx, ^long max-rows-per-block])
  (^java.util.SortedSet knownChunks [])
  (^java.util.concurrent.CompletableFuture withMetadata [^long chunkIdx, ^java.util.function.BiFunction f])
  (^org.apache.arrow.vector.types.pojo.Field columnField [^String colName]))

#_{:clj-kondo/ignore [:unused-binding]}
(definterface IMetadataIndices
  (^java.util.Set columnNames [])
  (^Long columnIndex [^String columnName])
  (^Long blockIndex [^String column-name, ^int blockIdx])
  (^long blockCount []))

(defrecord ChunkMatch [^long chunk-idx, ^RoaringBitmap block-idxs])

(defn- ->metadata-obj-key [chunk-idx]
  (format "metadata-%016x.arrow" chunk-idx))

(defn ->chunk-obj-key [chunk-idx column-name]
  (format "chunk-%016x-%s.arrow" chunk-idx column-name))

(defn- obj-key->chunk-idx [obj-key]
  (some-> (second (re-matches #"metadata-(\p{XDigit}{16}).arrow" obj-key))
          (Long/parseLong 16)))

(def ^org.apache.arrow.vector.types.pojo.Schema metadata-schema
  (Schema. [(t/->field "column" t/varchar-type false)
            (t/->field "block-idx" t/int-type true)

            (t/->field "root-column" t/struct-type true
                       ;; here because they're only easily accessible for non-nested columns.
                       ;; and we happen to need a marker for root columns anyway.
                       (t/->field "min-row-id" t/bigint-type true)
                       (t/->field "max-row-id" t/bigint-type true))

            (t/->field "count" t/bigint-type false)

            (t/->field "types" t/struct-type true)

            (t/->field "bloom" t/varbinary-type true)]))

(definterface ContentMetadataWriter
  (^void writeContentMetadata [^int typesVecIdx]))

(definterface NestedMetadataWriter
  (appendNestedMetadata ^core2.metadata.ContentMetadataWriter [^org.apache.arrow.vector.ValueVector contentVector]))

#_{:clj-kondo/ignore [:unused-binding]}
(defmulti type->metadata-writer (fn [write-col-meta! types-vec ^LegType leg-type] (class (.arrowType leg-type))))

(defmulti type->type-metadata (fn [^LegType leg-type] (class (.arrowType leg-type))))

(defmethod type->type-metadata :default [^LegType leg-type]
  {"minor-type" (str (Types/getMinorTypeForArrowType (.arrowType leg-type)))})

(defmulti type-metadata->arrow-type
  (fn [type-metadata]
    (Types$MinorType/valueOf (get type-metadata "minor-type"))))

(defmethod type-metadata->arrow-type :default [type-metadata]
  (.getType (Types$MinorType/valueOf (get type-metadata "minor-type"))))

(defn- add-struct-child ^org.apache.arrow.vector.ValueVector [^StructVector parent, ^Field field]
  (doto (.addOrGet parent (.getName field) (.getFieldType field) ValueVector)
    (.initializeChildrenFromFields (.getChildren field))
    (.setValueCount (.getValueCount parent))))

(defn- ->bool-type-handler [^VectorSchemaRoot metadata-root, ^LegType leg-type]
  (let [^StructVector types-vec (.getVector metadata-root "types")
        ^BitVector bit-vec (add-struct-child types-vec
                                             (Field. (t/type->field-name (.arrowType leg-type))
                                                     (FieldType. true t/bool-type nil (type->type-metadata leg-type))
                                                     []))]
    (reify NestedMetadataWriter
      (appendNestedMetadata [_ _content-vec]
        (reify ContentMetadataWriter
          (writeContentMetadata [_ types-vec-idx]
            (.setSafeToOne bit-vec types-vec-idx)))))))

(defmethod type->metadata-writer ArrowType$Null [_write-col-meta! metadata-root leg-type] (->bool-type-handler metadata-root leg-type))
(defmethod type->metadata-writer ArrowType$Bool [_write-col-meta! metadata-root leg-type] (->bool-type-handler metadata-root leg-type))

;; TODO anything we can do for extension types? I suppose we should probably special case our own...
;; they'll get included in the bloom filter, which is sufficient for UUIDs/keywords/etc.
(defmethod type->metadata-writer ArrowType$ExtensionType [_write-col-meta! metadata-root leg-type] (->bool-type-handler metadata-root leg-type))

(defmethod type->type-metadata ArrowType$ExtensionType [^LegType leg-type]
  (let [^ArrowType$ExtensionType arrow-type (.arrowType leg-type)]
    {"minor-type" (str (Types/getMinorTypeForArrowType arrow-type))
     "extension-name" (.extensionName arrow-type)
     "extension-metadata" (.serialize arrow-type)}))

(defmethod type-metadata->arrow-type Types$MinorType/EXTENSIONTYPE [tm]
  (let [^ArrowType$ExtensionType arrow-type (ExtensionTypeRegistry/lookup (get tm "extension-name"))]
    (.deserialize arrow-type
                  (.storageType arrow-type)
                  (get tm "extension-metadata"))))

(defn- ->min-max-type-handler [^VectorSchemaRoot metadata-root, ^LegType leg-type]
  (let [arrow-type (.arrowType leg-type)
        ^StructVector types-vec (.getVector metadata-root "types")
        ^StructVector struct-vec (add-struct-child types-vec
                                                   (Field. (t/type->field-name arrow-type)
                                                           (FieldType. true t/struct-type nil (type->type-metadata leg-type))
                                                           [(t/->field "min" arrow-type true)
                                                            (t/->field "max" arrow-type true)]))
        min-vec (.getChild struct-vec "min")
        max-vec (.getChild struct-vec "max")]
    (reify NestedMetadataWriter
      (appendNestedMetadata [_ content-vec]
        (reify ContentMetadataWriter
          (writeContentMetadata [_ types-vec-idx]
            (.setIndexDefined struct-vec types-vec-idx)

            (let [min-comparator (expr.comp/->comparator (iv/->direct-vec content-vec) (iv/->direct-vec min-vec) :nulls-last)
                  max-comparator (expr.comp/->comparator (iv/->direct-vec content-vec) (iv/->direct-vec max-vec) :nulls-last)]

              (dotimes [values-idx (.getValueCount content-vec)]
                (when (or (.isNull min-vec types-vec-idx)
                          (and (not (.isNull content-vec values-idx))
                               (neg? (.applyAsInt min-comparator values-idx types-vec-idx))))
                  (.copyFromSafe min-vec values-idx types-vec-idx content-vec))

                (when (or (.isNull max-vec types-vec-idx)
                          (and (not (.isNull content-vec values-idx))
                               (pos? (.applyAsInt max-comparator values-idx types-vec-idx))))
                  (.copyFromSafe max-vec values-idx types-vec-idx content-vec))))))))))

(defmethod type->metadata-writer ArrowType$Int [_write-col-meta! metadata-root leg-type] (->min-max-type-handler metadata-root leg-type))
(defmethod type->metadata-writer ArrowType$FloatingPoint [_write-col-meta! metadata-root leg-type] (->min-max-type-handler metadata-root leg-type))
(defmethod type->metadata-writer ArrowType$Utf8 [_write-col-meta! metadata-root leg-type] (->min-max-type-handler metadata-root leg-type))
(defmethod type->metadata-writer ArrowType$Binary [_write-col-meta! metadata-root leg-type] (->min-max-type-handler metadata-root leg-type))
(defmethod type->metadata-writer ArrowType$Timestamp [_write-col-meta! metadata-root leg-type] (->min-max-type-handler metadata-root leg-type))
(defmethod type->metadata-writer ArrowType$Date [_write-col-meta! metadata-root leg-type] (->min-max-type-handler metadata-root leg-type))

(defmethod type->type-metadata ArrowType$Timestamp [^LegType leg-type]
  (let [^ArrowType$Timestamp arrow-type (.arrowType leg-type)]
    {"minor-type" (str (Types/getMinorTypeForArrowType arrow-type))
     "tz" (.getTimezone arrow-type)}))

(defmethod type-metadata->arrow-type Types$MinorType/TIMESTAMPMICROTZ [tm]
  (ArrowType$Timestamp. TimeUnit/MICROSECOND (get tm "tz")))

(defmethod type->metadata-writer ArrowType$List [write-col-meta! ^VectorSchemaRoot metadata-root ^LegType leg-type]
  (let [^StructVector types-vec (.getVector metadata-root "types")
        ^IntVector list-meta-vec (add-struct-child types-vec
                                                   (Field. (t/type->field-name (.arrowType leg-type))
                                                           (FieldType. true t/int-type nil (type->type-metadata leg-type))
                                                           []))]
    (reify NestedMetadataWriter
      (appendNestedMetadata [_ content-vec]
        (let [^ListVector content-vec content-vec]
          (write-col-meta! (.getDataVector content-vec))
          (let [data-meta-idx (dec (.getRowCount metadata-root))]
            (reify ContentMetadataWriter
              (writeContentMetadata [_ types-vec-idx]
                (.setSafe list-meta-vec types-vec-idx data-meta-idx)))))))))

(defmethod type->type-metadata ArrowType$Struct [^LegType$StructLegType leg-type]
  {"minor-type" (str (Types/getMinorTypeForArrowType (.arrowType leg-type)))
   "key-set" (pr-str (into (sorted-set) (.keys leg-type)))})

(defmethod type->metadata-writer ArrowType$Struct [write-col-meta! ^VectorSchemaRoot metadata-root ^LegType leg-type]
  (let [^StructVector types-vec (.getVector metadata-root "types")
        ^ListVector struct-meta-vec (add-struct-child types-vec
                                                      (Field. (str (t/type->field-name (.arrowType leg-type)) "-" (count (seq types-vec)))
                                                              (FieldType. true t/list-type nil (type->type-metadata leg-type))
                                                              [(t/->field "$data" t/int-type true)]))
        ^IntVector nested-col-idxs-vec (.getDataVector struct-meta-vec)]
    (reify NestedMetadataWriter
      (appendNestedMetadata [_ content-vec]
        (let [^StructVector content-vec content-vec
              sub-cols (vec content-vec)
              sub-col-count (count sub-cols)
              sub-col-idxs (int-array sub-col-count)]

          (dotimes [n sub-col-count]
            (write-col-meta! (nth sub-cols n))
            (aset sub-col-idxs n (dec (.getRowCount metadata-root))))

          (reify ContentMetadataWriter
            (writeContentMetadata [_ types-vec-idx]
              (let [start-idx (.startNewValue struct-meta-vec types-vec-idx)]
                (dotimes [n sub-col-count]
                  (.setSafe nested-col-idxs-vec (+ start-idx n) (aget sub-col-idxs n)))
                (.endValue struct-meta-vec types-vec-idx sub-col-count)))))))))

(defn write-meta [^VectorSchemaRoot metadata-root, live-roots, ^long chunk-idx, ^long max-rows-per-block]
  (let [^VarCharVector column-name-vec (.getVector metadata-root "column")

        ^IntVector block-idx-vec (.getVector metadata-root "block-idx")

        ^StructVector root-col-vec (.getVector metadata-root "root-column")
        ^BigIntVector min-row-id-vec (.getChild root-col-vec "min-row-id")
        ^BigIntVector max-row-id-vec (.getChild root-col-vec "max-row-id")

        ^BigIntVector count-vec (.getVector metadata-root "count")

        ^StructVector types-vec (.getVector metadata-root "types")

        ^VarBinaryVector bloom-vec (.getVector metadata-root "bloom")

        type-metadata-writers (HashMap.)]

    (letfn [(write-root-col-row-ids! [^BigIntVector row-id-vec]
              (let [value-count (.getValueCount row-id-vec)]
                (when-not (zero? value-count)
                  (let [meta-idx (dec (.getRowCount metadata-root))]
                    (.setIndexDefined root-col-vec meta-idx)
                    (.setSafe min-row-id-vec meta-idx (.get row-id-vec 0))
                    (.setSafe max-row-id-vec meta-idx (.get row-id-vec (dec value-count)))))))

            (write-col-meta! [^DenseUnionVector content-vec]
              (let [content-writers (->> (seq content-vec)
                                         (into [] (keep (fn [^ValueVector values-vec]
                                                          (when-not (zero? (.getValueCount values-vec))
                                                            (let [^NestedMetadataWriter nested-meta-writer
                                                                  (.computeIfAbsent type-metadata-writers (t/field->leg-type (.getField values-vec))
                                                                                    (reify Function
                                                                                      (apply [_ leg-type]
                                                                                        (type->metadata-writer write-col-meta! metadata-root leg-type))))]

                                                              (.appendNestedMetadata nested-meta-writer values-vec)))))))

                    meta-idx (.getRowCount metadata-root)]
                (.setRowCount metadata-root (inc meta-idx))
                (.setSafe column-name-vec meta-idx (Text. (.getName content-vec)))
                (.setSafe count-vec meta-idx (.getValueCount content-vec))
                (bloom/write-bloom bloom-vec meta-idx content-vec)

                (.setIndexDefined types-vec meta-idx)

                (doseq [^ContentMetadataWriter content-writer content-writers]
                  (.writeContentMetadata content-writer meta-idx))))]

      (doseq [[^String col-name, ^VectorSchemaRoot live-root] live-roots]
        (write-col-meta! (.getVector live-root col-name))
        (write-root-col-row-ids! (.getVector live-root "_row-id"))

        (let [block-row-counts (blocks/row-id-aligned-blocks live-root chunk-idx max-rows-per-block)]
          (with-open [^ICursor slices (blocks/->slices live-root block-row-counts)]
            (loop [block-idx 0]
              (when (.tryAdvance slices
                                 (reify Consumer
                                   (accept [_ live-slice]
                                     (let [^VectorSchemaRoot live-slice live-slice]
                                       (when-not (zero? (.getRowCount live-slice))
                                         (write-col-meta! (.getVector live-slice col-name))
                                         (write-root-col-row-ids! (.getVector live-slice "_row-id"))
                                         (.setSafe block-idx-vec (dec (.getRowCount metadata-root)) block-idx))))))
                (recur (inc block-idx))))))))

    (.syncSchema metadata-root)))

(defn ->metadata-idxs ^core2.metadata.IMetadataIndices [^VectorSchemaRoot metadata-root]
  (let [col-idx-cache (HashMap.)
        block-idx-cache (HashMap.)
        ^VarCharVector column-name-vec (.getVector metadata-root "column")
        ^IntVector block-idx-vec (.getVector metadata-root "block-idx")
        root-col-vec (.getVector metadata-root "root-column")]
    (dotimes [meta-idx (.getRowCount metadata-root)]
      (let [col-name (str (.getObject column-name-vec meta-idx))]
        (when-not (.isNull root-col-vec meta-idx)
          (if (.isNull block-idx-vec meta-idx)
            (.put col-idx-cache col-name meta-idx)
            (.put block-idx-cache [col-name (.get block-idx-vec meta-idx)] meta-idx)))))

    (let [block-count (->> (keys block-idx-cache)
                           (map second)
                           ^long (apply max)
                           inc)]
      (reify IMetadataIndices
        IMetadataIndices
        (columnNames [_] (set (keys col-idx-cache)))
        (columnIndex [_ col-name] (get col-idx-cache col-name))
        (blockIndex [_ col-name block-idx] (get block-idx-cache [col-name block-idx]))
        (blockCount [_] block-count)))))

(defn- with-metadata* [^IBufferPool buffer-pool, ^long chunk-idx, ^BiFunction f]
  (-> (.getBuffer buffer-pool (->metadata-obj-key chunk-idx))
      (util/then-apply
        (fn [^ArrowBuf metadata-buffer]
          (assert metadata-buffer)

          (when metadata-buffer
            (let [res (promise)]
              (try
                (with-open [chunk (util/->chunks metadata-buffer)]
                  (.tryAdvance chunk
                               (reify Consumer
                                 (accept [_ metadata-root]
                                   (deliver res (.apply f chunk-idx metadata-root))))))

                (assert (realized? res))
                @res

                (finally
                  (.close metadata-buffer)))))))))

(defn- ->column-fields [^VectorSchemaRoot metadata-root]
  (let [^VarCharVector col-name-vec (.getVector metadata-root "column")
        ^StructVector types-vec (.getVector metadata-root "types")
        meta-idxs (->metadata-idxs metadata-root)
        type-vecs (vec
                   (for [^ValueVector type-vec (seq types-vec)]
                     {:type-vec type-vec
                      :arrow-type (type-metadata->arrow-type (.getMetadata (.getField type-vec)))}))]
    (letfn [(->field [col-name ^long col-idx]
              (let [type-vecs (->> type-vecs
                                   (remove (fn [{:keys [^ValueVector type-vec]}]
                                             (.isNull type-vec col-idx))))]
                (letfn [(->field* [col-name arrow-type type-vec]
                          (cond
                            (instance? ArrowType$List arrow-type)
                            (t/->field col-name arrow-type false
                                       (->field "$data" (.get ^IntVector type-vec col-idx)))

                            (instance? ArrowType$Struct arrow-type)
                            (apply t/->field col-name arrow-type false
                                   (let [^ListVector type-vec type-vec
                                         ^IntVector type-vec-data (.getDataVector type-vec)]
                                     (for [type-vec-data-idx (range (.getElementStartIndex type-vec col-idx)
                                                                    (.getElementEndIndex type-vec col-idx))
                                           :let [col-idx (.get type-vec-data type-vec-data-idx)]]
                                       (->field (str (.getObject col-name-vec col-idx)) col-idx))))

                            :else
                            (t/->field col-name arrow-type (= arrow-type t/null-type))))]

                  (if (= 1 (count type-vecs))
                    (let [{:keys [arrow-type type-vec]} (first type-vecs)]
                      (->field* col-name arrow-type type-vec))

                    (apply t/->field col-name t/dense-union-type false
                           (for [{:keys [^ValueVector type-vec arrow-type]} type-vecs]
                             (->field* (.getName type-vec) arrow-type type-vec)))))))]

      (->> (for [col-name (.columnNames meta-idxs)]
             [col-name (->field col-name (.columnIndex meta-idxs col-name))])
           (into {})))))

(defn- merge-column-fields [column-fields new-column-fields]
  (merge-with t/merge-fields column-fields new-column-fields))

(defn- load-column-fields [^IBufferPool buffer-pool, known-chunks]
  (let [cf-futs (doall (for [chunk-idx known-chunks]
                         (with-metadata* buffer-pool chunk-idx
                           (reify BiFunction
                             (apply [_ _chunk-idx metadata-root]
                               (->column-fields metadata-root))))))]
    (->> cf-futs
         (transduce (map deref)
                    (completing
                     (fn
                       ([] {})
                       ([cfs new-cfs] (merge-column-fields cfs new-cfs))))))))

(deftype MetadataManager [^BufferAllocator allocator
                          ^ObjectStore object-store
                          ^IBufferPool buffer-pool
                          ^SortedSet known-chunks
                          ^:unsynchronized-mutable column-fields]
  IMetadataManager
  (registerNewChunk [this live-roots chunk-idx max-rows-per-block]
    (let [metadata-buf (with-open [metadata-root (VectorSchemaRoot/create metadata-schema allocator)]
                         (write-meta metadata-root live-roots chunk-idx max-rows-per-block)
                         (set! (.column-fields this) (merge-column-fields column-fields (->column-fields metadata-root)))
                         (util/root->arrow-ipc-byte-buffer metadata-root :file))]

      @(.putObject object-store (->metadata-obj-key chunk-idx) metadata-buf)

      (.add known-chunks chunk-idx)))

  (withMetadata [_ chunk-idx f] (with-metadata* buffer-pool chunk-idx f))

  (knownChunks [_] known-chunks)

  (columnField [_ col-name] (get column-fields col-name))

  Closeable
  (close [_]
    (.clear known-chunks)))

(defn with-metadata [^IMetadataManager metadata-mgr, ^long chunk-idx, f]
  (.withMetadata metadata-mgr chunk-idx (util/->jbifn f)))

(defn matching-chunks [^IMetadataManager metadata-mgr, ^Watermark watermark, metadata-pred]
  (->> (for [^long chunk-idx (.knownChunks metadata-mgr)
             :while (or (nil? watermark) (< chunk-idx (.chunk-idx watermark)))]
         (with-metadata metadata-mgr chunk-idx metadata-pred))
       vec
       (into [] (keep deref))))

(defmethod ig/prep-key ::metadata-manager [_ opts]
  (merge {:allocator (ig/ref :core2/allocator)
          :object-store (ig/ref :core2/object-store)
          :buffer-pool (ig/ref :core2.buffer-pool/buffer-pool)}
         opts))

(defmethod ig/init-key ::metadata-manager [_ {:keys [allocator ^ObjectStore object-store buffer-pool]}]
  (let [known-chunks (ConcurrentSkipListSet. ^List (keep obj-key->chunk-idx (.listObjects object-store "metadata-")))
        column-fields (load-column-fields buffer-pool known-chunks)]
    (MetadataManager. allocator object-store buffer-pool known-chunks column-fields)))

(defmethod ig/halt-key! ::metadata-manager [_ ^MetadataManager mgr]
  (.close mgr))