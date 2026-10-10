(ns xtdb.bench.pageload
  "SPIKE (#6164) — throwaway. Measures what loading a data page costs on TPC-H lineitem."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [xtdb.api :as xt]
            [xtdb.bench :as b]
            [xtdb.datasets.tpch :as tpch]
            [xtdb.datasets.tpch.ra :as tpch-ra]
            [xtdb.db-catalog :as db]
            [xtdb.test-util :as tu]
            [xtdb.util :as util])
  (:import (io.micrometer.core.instrument Counter MeterRegistry)
           (java.nio.channels FileChannel)
           (java.nio.file Files OpenOption Path StandardOpenOption)
           (java.time Duration)
           (jdk.jfr Configuration Recording)
           (jdk.jfr.consumer RecordedEvent RecordedFrame RecordedStackTrace RecordedThread RecordingFile)
           (java.nio ByteBuffer ByteOrder)
           (org.apache.arrow.flatbuf Footer Message MessageHeader RecordBatch)
           (org.apache.arrow.vector TypeLayout)
           (org.apache.arrow.vector.ipc ReadChannel)
           (org.apache.arrow.vector.ipc.message ArrowBlock ArrowFooter MessageSerializer)
           (org.apache.arrow.vector.types.pojo Field Schema)
           (xtdb.trie TrieCatalog)))

;; ---------- counters

(defn- counter-total ^double [^MeterRegistry reg ^String n]
  (reduce + 0.0 (map #(.count ^Counter %) (.counters (.find reg n)))))

(defn- counters [node]
  (let [reg (.getMeterRegistry (util/node-base node))]
    {:record-batch-requests (long (counter-total reg "record-batch-requests"))
     :memory-cache-misses (long (counter-total reg "memory-cache-misses"))}))

(defn- proc-faults []
  (let [s (String. (Files/readAllBytes (util/->path "/proc/self/stat")))
        fields (str/split (subs s (+ 2 (str/last-index-of s ")"))) #" ")]
    ;; fields from here start at stat field 3: minflt is field 10, majflt field 12
    {:minflt (parse-long (nth fields 7)) :majflt (parse-long (nth fields 9))}))

(defn- diff-maps [a b] (into {} (for [[k v] b] [k (- v (get a k))])))

;; ---------- queries

(def ^:private scan-cols-all
  '[l_orderkey l_partkey l_suppkey l_linenumber l_quantity l_extendedprice l_discount l_tax
    l_returnflag l_linestatus l_shipdate l_commitdate l_receiptdate l_shipinstruct l_shipmode l_comment])

(defn- count-scan [cols]
  [:group-by '{:columns [{n (row-count)}]}
   [:scan {:db-name "xtdb", :table '#xt/table lineitem, :columns cols}]])

(def ^:private sql-q6
  "SELECT SUM(l.l_extendedprice * l.l_discount) AS revenue
   FROM lineitem AS l
   WHERE l.l_shipdate >= DATE '1994-01-01'
     AND l.l_shipdate < DATE '1994-01-01' + INTERVAL '1' YEAR
     AND l.l_discount BETWEEN 0.06 - 0.01 AND 0.06 + 0.01
     AND l.l_quantity < 24")

(defn- ra-q [v] (let [q @v] [q (::tpch-ra/args (meta q))]))

(def queries
  {:q1 #(ra-q #'tpch-ra/q1-pricing-summary-report)
   :q6 #(ra-q #'tpch-ra/q6-forecasting-revenue-change)
   :scan-1col #(vector (count-scan '[l_shipdate]) {})
   :scan-4col #(vector (count-scan '[l_shipdate l_extendedprice l_discount l_quantity]) {})
   :scan-all #(vector (count-scan scan-cols-all) {})
   :sql-q6 :sql})

(defn- run-q [node k]
  (let [c0 (counters node) f0 (proc-faults) t0 (System/nanoTime)
        n (if (= :sql-q6 k)
            (count (xt/q node sql-q6))
            (let [[q args] ((get queries k))]
              (count (tu/query-ra q {:node node, :args args}))))
        t1 (System/nanoTime)]
    (merge {:q k :ms (Math/round (/ (- t1 t0) 1e6)) :rows n}
           (diff-maps c0 (counters node))
           (diff-maps f0 (proc-faults)))))

;; ---------- JFR

(defn- with-jfr* [^Path out f]
  (let [rec (Recording. (Configuration/getConfiguration "profile"))]
    (-> (.enable rec "jdk.ExecutionSample") (.withPeriod (Duration/ofMillis 1)))
    (-> (.enable rec "jdk.NativeMethodSample") (.withPeriod (Duration/ofMillis 1)))
    (.start rec)
    (try
      (f)
      (finally
        (.stop rec)
        (.dump rec out)
        (.close rec)))))

(defn- frame-str [^RecordedFrame fr]
  (let [m (.getMethod fr)]
    (str (.getName (.getType m)) "." (.getName m) ":" (.getLineNumber fr))))

(defn- read-samples [^Path path]
  (with-open [rf (RecordingFile. path)]
    (loop [acc (transient [])]
      (if (.hasMoreEvents rf)
        (let [^RecordedEvent e (.readEvent rf)
              en (.getName (.getEventType e))]
          (if (#{"jdk.ExecutionSample" "jdk.NativeMethodSample"} en)
            (let [^RecordedStackTrace st (.getStackTrace e)
                  ^RecordedThread th (.getThread e "sampledThread")]
              (recur (conj! acc {:event en
                                 :thread (some-> th .getJavaName)
                                 :frames (if st (mapv frame-str (.getFrames st)) [])})))
            (recur acc)))
        (persistent! acc)))))

(defn- pct [n d] (if (zero? d) 0.0 (/ (Math/round (* 1000.0 (/ (double n) d))) 10.0)))

(defn- has-frame? [re s] (some #(re-find re %) (:frames s)))

(def ^:private patterns
  [[:scan-tryAdvance #"ScanCursor\.tryAdvance"]
   [:load-runBlocking #"ScanCursor\$tryAdvance\$loadedPages|BuildersKt.*runBlocking"]
   [:bp-segment-loadDataPage #"BufferPoolSegment\.loadDataPage"]
   [:local-getRecordBatch #"LocalStorage\.getRecordBatch"]
   [:memcache-get #"MemoryCache\.get"]
   [:arrowBufToRecordBatch #"ArrowUtil\.arrowBufToRecordBatch"]
   [:deserializeRecordBatch #"MessageSerializer\.deserializeRecordBatch"]
   [:flatbuf-header #"Message\.header|RecordBatch\.__assign|RecordBatch\.buffers|RecordBatch\.nodes"]
   [:relation-load #"xtdb\.arrow\.Relation\.load"]
   [:transferOwnership #"transferOwnership"]
   [:buffer-close-release #"ArrowBuf\.close|BufferLedger\.release|decrement"]
   [:files-exists #"Files\.exists|checkAccess"]
   [:path-map #"PathLoader|FileChannelImpl\.map"]
   [:arena-close #"Arena.*close|release0|unmap"]
   [:fetchloop-handle #"MemoryCache\$Fetch(Req|Done)\.handle"]
   [:entity-merge #"EntityMerge|resolveEntity|AsOfResolver|PolygonResolver"]
   [:bitemporal-consumer #"BitemporalConsumer"]
   [:event-row-pointer #"EventRowPointer"]
   [:colpred-select #"SelectionSpec|select\$xtdb|expression"]
   [:openSlice #"openSlice"]])

(defn- summarise [samples]
  (let [exec (filter #(= "jdk.ExecutionSample" (:event %)) samples)
        native (filter #(= "jdk.NativeMethodSample" (:event %)) samples)
        n-exec (count exec)
        scan (filter (partial has-frame? #"ScanCursor\.tryAdvance") exec)
        load (filter (partial has-frame? #"BufferPoolSegment\.loadDataPage") exec)]
    {:exec-samples n-exec
     :native-samples (count native)
     :inclusive-pct-of-all-exec (into {} (for [[k re] patterns]
                                           [k (pct (count (filter (partial has-frame? re) exec)) n-exec)]))
     :threads (->> exec (map :thread) frequencies (sort-by val >) (take 10) vec)
     :top-self-all (->> exec (keep (comp first :frames)) frequencies (sort-by val >) (take 30)
                        (mapv (fn [[f n]] [f (pct n n-exec)])))
     :top-self-in-loadDataPage (->> load (keep (comp first :frames)) frequencies (sort-by val >) (take 25)
                                    (mapv (fn [[f n]] [f (pct n n-exec)])))
     :top-self-in-scan (->> scan (keep (comp first :frames)) frequencies (sort-by val >) (take 25)
                            (mapv (fn [[f n]] [f (pct n n-exec)])))
     :non-scan-threads-top-self (->> exec (remove (partial has-frame? #"ScanCursor\.tryAdvance"))
                                     (keep (fn [s] (when-let [f (first (:frames s))] [(:thread s) f])))
                                     frequencies (sort-by val >) (take 20)
                                     (mapv (fn [[f n]] [f (pct n n-exec)])))
     :native-top (->> native (keep (fn [s] (str/join " < " (take 4 (:frames s))))) frequencies (sort-by val >) (take 15)
                      (mapv (fn [[f n]] [f n])))}))

;; ---------- M2: column bytes

(defn- field-layout [^Field f prefix]
  (let [p (conj prefix (.getName f))]
    (cons [p (TypeLayout/getTypeBufferCount (.getType f))]
          (mapcat #(field-layout % p) (.getChildren f)))))

(defn- read-footer ^ArrowFooter [^FileChannel ch]
  (let [size (.size ch)
        tail (doto (ByteBuffer/allocate 10) (.order ByteOrder/LITTLE_ENDIAN))
        _ (.read ch tail (- size 10))
        footer-len (.getInt tail 0)
        fbuf (doto (ByteBuffer/allocate footer-len) (.order ByteOrder/LITTLE_ENDIAN))]
    (.read ch fbuf (- size 10 footer-len))
    (.flip fbuf)
    (ArrowFooter. (Footer/getRootAsFooter fbuf))))

(defn- file-col-bytes [^Path path]
  (with-open [ch (FileChannel/open path (into-array OpenOption [StandardOpenOption/READ]))]
    (let [^ArrowFooter footer (read-footer ch)
          ^Schema schema (.getSchema footer)
          layout (vec (mapcat #(field-layout % []) (.getFields schema)))
          acc (volatile! {})]
      (doseq [^ArrowBlock blk (.getRecordBatches footer)]
        (.position ch (.getOffset blk))
        (let [res (MessageSerializer/readMessage (ReadChannel. ch))
              ^Message msg (.getMessage res)
              rb (RecordBatch.)]
          (assert (= MessageHeader/RecordBatch (.headerType msg)))
          (.header msg rb)
          (vswap! acc update "_meta-bytes" (fnil + 0) (.getMetadataLength blk))
          (vswap! acc update "_body-bytes" (fnil + 0) (.getBodyLength blk))
          (vswap! acc update "_rows" (fnil + 0) (.length rb))
          (vswap! acc update "_pages" (fnil + 0) 1)
          (loop [[[fp n] & more] layout, bi 0]
            (when fp
              (vswap! acc update (str/join "/" fp) (fnil + 0)
                      (reduce + (for [i (range bi (+ bi n))] (.length (.buffers rb i)))))
              (recur more (+ bi n))))))
      {:bytes @acc :file-size (Files/size path)})))

(defn- find-dir [^Path root suffix]
  (with-open [s (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
    (->> (iterator-seq (.iterator s))
         (filter #(and (Files/isDirectory % (make-array java.nio.file.LinkOption 0))
                       (str/ends-with? (str %) suffix)))
         first)))

(defn- lineitem-col-bytes [node ^Path node-dir]
  (let [^TrieCatalog cat (.getTrieCatalog (db/primary-db node))
        table (->> (.getTables cat) (filter #(= "lineitem" (.getTableName ^xtdb.api.TableRef %))) first)
        keys (.listLiveAndNascentTrieKeys cat table)
        data-dir (find-dir node-dir "lineitem/data")
        res (for [k keys] (file-col-bytes (.resolve data-dir (str k ".arrow"))))]
    {:live-tries (vec keys)
     :file-bytes (reduce + (map :file-size res))
     :bytes (into (sorted-map) (apply merge-with + (map :bytes res)))}))

;; ---------- benchmark

(defmethod b/cli-flags :pageload [_]
  [["-s" "--scale-factor SCALE_FACTOR" "TPC-H scale factor" :parse-fn parse-double :default 1.0]
   [nil "--reps REPS" "hot reps per query" :parse-fn parse-long :default 5]
   [nil "--out-dir OUT_DIR" "where to write results" :default "/tmp/pageload"]
   [nil "--qs QS" "comma-separated query keys" :default "scan-1col,scan-4col,scan-all,q6,sql-q6,q1"]
   [nil "--jfr-qs QS" "comma-separated query keys to profile" :default "q6,scan-4col,q1"]
   [nil "--soak-secs SECS" "seconds to loop scan-4col for an external strace (0 = skip)" :parse-fn parse-long :default 0]
   ["-h" "--help"]])

(defn- spit-edn [^Path out-dir fname x]
  (let [f (io/file (str out-dir) fname)]
    (with-open [w (io/writer f)] (pp/pprint x w))
    (log/info "wrote" (str f))))

(defmethod b/->benchmark :pageload [_ {:keys [scale-factor no-load? reps out-dir qs jfr-qs soak-secs node-dir]}]
  (let [out-dir (util/->path out-dir)
        qs (mapv keyword (str/split qs #","))
        jfr-qs (mapv keyword (str/split jfr-qs #","))]
    (Files/createDirectories out-dir (make-array java.nio.file.attribute.FileAttribute 0))
    {:title "pageload spike"
     :benchmark-type :pageload
     :parameters {:scale-factor scale-factor}
     :->state #(do {:!state (atom {})})
     :tasks [{:t :do, :stage :ingest
              :tasks (when-not no-load?
                       [{:t :call, :stage :submit-rels, :f (fn [{:keys [node]}] (tpch/submit-rels! node scale-factor))}
                        {:t :call, :stage :sync, :f (fn [{:keys [node]}] (b/sync-node node (Duration/ofHours 5)))}
                        {:t :call, :stage :finish-block, :f (fn [{:keys [node]}] (b/flush-block! node))}
                        {:t :call, :stage :compact, :f (fn [{:keys [node]}] (b/compact! node))}])}

             {:t :call, :stage :measure
              :f (fn [{:keys [node]}]
                   (log/info "env" {:unsafe (System/getProperty "arrow.enable_unsafe_memory_access")
                                    :java (System/getProperty "java.version")
                                    :xmx (.maxMemory (Runtime/getRuntime))
                                    :pid (.pid (java.lang.ProcessHandle/current))})

                   (spit-edn out-dir "col-bytes.edn"
                             (try (lineitem-col-bytes node (or node-dir (util/->path "/tmp")))
                                  (catch Throwable t (log/warn t "col-bytes failed") {:error (str t)})))

                   (let [cold (vec (for [k qs] (doto (run-q node k) (->> (log/info "cold")))))
                         hot (vec (for [k qs, _ (range reps)] (doto (run-q node k) (->> (log/info "hot")))))]
                     (spit-edn out-dir "timings.edn" {:cold cold :hot hot}))

                   (doseq [k jfr-qs]
                     (let [jfr (.resolve out-dir (str (name k) ".jfr"))
                           runs (atom [])]
                       (with-jfr* jfr #(dotimes [_ 3] (swap! runs conj (run-q node k))))
                       (spit-edn out-dir (str (name k) "-profile.edn")
                                 {:runs @runs :summary (summarise (read-samples jfr))})))

                   (when (pos? soak-secs)
                     (spit (str (.resolve out-dir "soak-start")) (str (.pid (java.lang.ProcessHandle/current))))
                     (let [deadline (+ (System/currentTimeMillis) (* 1000 soak-secs))
                           runs (atom [])]
                       (while (< (System/currentTimeMillis) deadline)
                         (swap! runs conj (run-q node :scan-4col)))
                       (spit-edn out-dir "soak.edn" @runs))))}]}))
