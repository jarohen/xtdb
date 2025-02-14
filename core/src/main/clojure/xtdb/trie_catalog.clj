(ns xtdb.trie-catalog
  (:require [integrant.core :as ig]
            [xtdb.metadata :as meta]
            [xtdb.trie :as trie]
            [xtdb.util :as util])
  (:import [java.util Map]
           [java.util.concurrent ConcurrentHashMap]
           (xtdb BufferPool)
           xtdb.api.storage.ObjectStore$StoredObject
           xtdb.log.proto.AddedTrie
           xtdb.metadata.IMetadataManager))

(set! *unchecked-math* :warn-on-boxed)

(defprotocol PTrieCatalog
  (trie-state [trie-cat table-name]))

(def ^:const branch-factor 4)

(def ^:dynamic ^{:tag 'long} *file-size-target* (* 100 1024 1024))

(defn ->added-trie ^xtdb.log.proto.AddedTrie [table-name, trie-key, ^long data-file-size]
  (.. (AddedTrie/newBuilder)
      (setTableName table-name)
      (setTrieKey trie-key)
      (setDataFileSize data-file-size)
      (build)))

(defn- stale-l0-msg? [{:keys [l0-tries l1c-tries]} {:keys [^long block-idx]}]
  (or (when-let [{^long l0-block-idx :block-idx} (first l0-tries)]
        (>= l0-block-idx block-idx))

      (when-let [{^long l1-block-idx :block-idx} (first l1c-tries)]
        (>= l1-block-idx block-idx))))

(defn- stale-l1c-msg? [{:keys [l1c-tries ln-tries]} {:keys [^long block-idx]}]
  (or (when-let [{^long l1-block-idx :block-idx} (first l1c-tries)]
        (>= l1-block-idx block-idx))

      (->> (map (or ln-tries {})
                (for [p (range branch-factor)]
                  [2 [p]]))
           (every? (comp (fn [{^long l2-block-idx :block-idx, :as l2-trie}]
                           (and l2-trie
                                (>= l2-block-idx block-idx)))
                         first)))))

(defn- stale-l1h-msg? [{:keys [l1c-tries l1h-tries lnh-tries]} {:keys [recency ^long block-idx]}]
  (or (when-let [{^long l1c-block-idx :block-idx} (first l1c-tries)]
        (>= l1c-block-idx block-idx))

      (when-let [{^long l1h-block-idx :block-idx, l1h-recency :recency} (first l1h-tries)]
        (or (> l1h-block-idx block-idx)
            (and (= l1h-block-idx block-idx)
                 (not (neg? (compare l1h-recency recency))))))

      (when-let [{^long l2h-block-idx :block-idx} (first (get lnh-tries [2 recency]))]
        (>= l2h-block-idx block-idx))))

(defn stale-ln-msg [{:keys [ln-tries]} {:keys [^long level, ^long block-idx part]}]
  (or (when-let [{^long ln-block-idx :block-idx} (first (get ln-tries [level part]))]
        (>= ln-block-idx block-idx))

      (->> (map (or ln-tries {})
                (for [p (range branch-factor)]
                  [(inc level) (conj part p)]))
           (every? (comp (fn [{^long lnp1-block-idx :block-idx, :as lnp1-trie}]
                           (when lnp1-trie
                             (>= lnp1-block-idx block-idx)))
                         first)))))

(defn- stale-msg? [table-tries {:keys [level recency] :as trie}]
  (case (long level)
    0 (stale-l0-msg? table-tries trie)
    1 (if recency
        (stale-l1h-msg? table-tries trie)
        (stale-l1c-msg? table-tries trie))
    (stale-ln-msg table-tries trie)))

(defn- supersede-partial-tries [tries {:keys [^long block-idx]} {:keys [^long file-size-target]}]
  (->> (or tries {})
       (map (fn [{^long other-block-idx :block-idx, ^long other-size :data-file-size, other-state :state, :as other-trie}]
              (cond-> other-trie
                (and (= other-state :live)
                     (< other-size file-size-target)
                     (>= block-idx other-block-idx))
                (assoc :state :garbage))))))

(defn- conj-levelled-trie [tries trie trie-cat]
  (-> (or tries '())
      (supersede-partial-tries trie trie-cat)
      (conj (assoc trie :state :live))))

(defn- supersede-l0-tries [l0-tries {:keys [^long block-idx]}]
  (->> l0-tries
       (map (fn [{^long l0-block-idx :block-idx, :as l0-trie}]
              (cond-> l0-trie
                (<= l0-block-idx block-idx)
                (assoc :state :garbage))))))

(defn- conj-l1c-trie [table-tries {:keys [^long block-idx] :as trie} trie-cat]
  (-> table-tries
      (update :l1h-tries (fn [l1h-tries]
                           (->> l1h-tries
                                (map (fn [{^long l1h-block-idx :block-idx, :keys [state] :as trie}]
                                       (cond-> trie
                                         (and (not= state :live)
                                              (= block-idx l1h-block-idx))
                                         (assoc :state :live)))))))
      (update :l1c-tries
              (fnil (fn [l1c-tries trie]
                      (-> l1c-tries
                          (supersede-partial-tries trie trie-cat)
                          (conj (assoc trie :state :live))))
                    '())
              trie)
      (update :l0-tries supersede-l0-tries trie)))

(defn- conj-nascent-ln-trie [ln-tries {:keys [level part] :as trie}]
  (-> ln-tries
      (update [level part]
              (fnil (fn [ln-part-tries]
                      (conj ln-part-tries (assoc trie :state :nascent)))
                    '()))))

(defn- completed-ln-group? [{:keys [ln-tries]} {:keys [^long level, ^long block-idx, part]}]
  (->> (map (comp first (or ln-tries {}))
            (let [pop-part (pop part)]
              (for [p (range branch-factor)]
                [level (conj pop-part p)])))

       (every? (fn [{^long ln-block-idx :block-idx, ln-state :state, :as ln-trie}]
                 (and ln-trie
                      (or (> ln-block-idx block-idx)
                          (= :nascent ln-state)))))))

(defn- mark-ln-group-live [ln-tries {:keys [block-idx level part]}]
  (reduce (fn [ln-tries part]
            (-> ln-tries
                (update part
                        (fn [ln-part-tries]
                          (->> ln-part-tries
                               (map (fn [{ln-state :state, ln-block-idx :block-idx, :as ln-trie}]
                                      (cond-> ln-trie
                                        (and (= ln-state :nascent)
                                             (= ln-block-idx block-idx))
                                        (assoc :state :live)))))))))
          ln-tries
          (let [pop-part (pop part)]
            (for [p (range branch-factor)]
              [level (conj pop-part p)]))))

(defn- supersede-lnm1-tries [lnm1-tries {:keys [^long block-idx]}]
  (->> lnm1-tries
       (map (fn [{lnm1-state :state, ^long lnm1-block-idx :block-idx, :as lnm1-trie}]
              (cond-> lnm1-trie
                (and (= lnm1-state :live)
                     (<= lnm1-block-idx block-idx))
                (assoc :state :garbage))))))

(defn- conj-trie [table-tries {:keys [^long level, recency, part] :as trie} trie-cat]
  (case (long level)
    0 (-> table-tries
          (update :l0-tries (fnil conj '()) (assoc trie :state :live)))

    1 (if recency
        (-> table-tries
            (update :l1h-tries (fnil conj '()) (assoc trie :state :nascent)))

        (-> table-tries
            (conj-l1c-trie trie trie-cat)))

    2 (if recency
        (-> table-tries
            (update :l1h-tries supersede-lnm1-tries trie)
            (update :l2h-tries conj-levelled-trie trie trie-cat))

        (-> table-tries
            (update :ln-tries conj-nascent-ln-trie trie)
            (as-> table-tries (cond-> table-tries
                                (completed-ln-group? table-tries trie)
                                (-> (update :ln-tries mark-ln-group-live trie)
                                    (update :l1c-tries supersede-lnm1-tries trie)
                                    (supersede-lnm1-tries trie))))))

    ;; TODO duplication
    (-> table-tries
        (update :ln-tries conj-nascent-ln-trie trie)
        (as-> table-tries (cond-> table-tries
                            (completed-ln-group? table-tries trie)
                            (-> (update :ln-tries mark-ln-group-live trie)
                                (update [:ln-tries [(dec level) (pop part)]]
                                        supersede-lnm1-tries trie)))))))

(defn apply-trie-notification [trie-cat tries trie]
  (let [trie (-> trie
                 (update :part vec))]
    (cond-> tries
      (not (stale-msg? tries trie)) (conj-trie trie trie-cat))))

(defn current-tries [{:keys [l0-tries l1c-tries l1h-tries ln-tries]}]
  (->> (concat l0-tries l1h-tries l1c-tries (sequence cat (vals ln-tries)))
       (into [] (filter #(= (:state %) :live)))
       (sort-by :block-idx)))

(defrecord TrieCatalog [^Map !table-tries, ^long file-size-target]
  xtdb.trie.TrieCatalog
  (addTries [this added-tries]
    (doseq [[table-name added-tries] (->> added-tries
                                          (group-by #(.getTableName ^AddedTrie %)))]
      (.compute !table-tries table-name
                (fn [_table-name tries]
                  (reduce (fn [table-tries ^AddedTrie added-trie]
                            (if-let [parsed-key (trie/parse-trie-key (.getTrieKey added-trie))]
                              (apply-trie-notification this table-tries
                                                       (-> parsed-key
                                                           (update :part vec)
                                                           (assoc :data-file-size (.getDataFileSize added-trie))))
                              table-tries))
                          (or tries {})
                          added-tries)))))

  (getTableNames [_] (set (keys !table-tries)))

  PTrieCatalog
  (trie-state [_ table-name] (.get !table-tries table-name)))

(defmethod ig/prep-key :xtdb/trie-catalog [_ opts]
  (into {:buffer-pool (ig/ref :xtdb/buffer-pool)
         :metadata-mgr (ig/ref ::meta/metadata-manager)}
        opts))

(defmethod ig/init-key :xtdb/trie-catalog [_ {:keys [^BufferPool buffer-pool, ^IMetadataManager metadata-mgr]}]
  (doto (TrieCatalog. (ConcurrentHashMap.) *file-size-target*)
    (.addTries (for [table-name (.allTableNames metadata-mgr)
                     ^ObjectStore$StoredObject obj (.listAllObjects buffer-pool (trie/->table-meta-dir table-name))]
                 (->added-trie table-name
                               (str (.getFileName (.getKey obj)))
                               (.getSize obj))))))

(defn trie-catalog ^xtdb.trie.TrieCatalog [node]
  (util/component node :xtdb/trie-catalog))
