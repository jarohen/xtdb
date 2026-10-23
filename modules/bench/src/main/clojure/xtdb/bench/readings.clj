(ns xtdb.bench.readings
  (:require [clojure.test :as t]
            [clojure.tools.logging :as log]
            [xtdb.api :as xt]
            [xtdb.bench :as b]
            [xtdb.util :as util])
  (:import (java.time Duration Instant)))

(def ^:private ^Instant start-instant #xt/instant "2020-01-01T00:00:00Z")
(def ^:private ^Duration interval (Duration/ofMinutes 5))
(def ^:private intervals-per-day 288)
(def ^:private devices-per-tx 5000)

(def ^:private all-metrics [:temperature :humidity :pressure :battery])

(defn- metrics-for [n]
  (vec (take n (concat all-metrics (map #(keyword (str "metric" %)) (range))))))

(defn- ->interval-start ^Instant [^long idx]
  (.plus start-instant (.multipliedBy interval idx)))

(defn- submit-interval! [node metrics devices idx]
  (let [^Instant valid-from (->interval-start idx)
        valid-to (.plus valid-from interval)]
    (doseq [dev-start (range 0 devices devices-per-tx)
            :let [dev-ids (range dev-start (min devices (+ dev-start devices-per-tx)))]]
      (xt/submit-tx node
                    (for [metric metrics]
                      (into [:put-docs {:into metric :valid-from valid-from :valid-to valid-to}]
                            (for [i dev-ids]
                              {:xt/id i :value (- (* 200 (rand)) 100)})))))))

(defn- scan-rows-read [node sql args]
  (->> (xt/q node (into [(str "EXPLAIN ANALYZE " sql)] args))
       (filter #(= :scan (:op %)))
       (keep #(get-in % [:attributes :scan-rows-read]))
       (reduce + 0)))

(defn- agg-sql [metric]
  (format "SELECT AVG(value) AS avg, MIN(value) AS min, MAX(value) AS max
           FROM %s FOR VALID_TIME BETWEEN ? AND ?" (name metric)))

(defn- cross-metric-sql [metrics]
  (->> metrics
       (map #(format "SELECT '%s' AS metric, AVG(value) AS avg FROM %s FOR VALID_TIME BETWEEN ? AND ?"
                     (name %) (name %)))
       (interpose " UNION ALL ")
       (apply str)))

(defn- ->query-stage [stage-name sql n-tables ->window]
  {:t :call
   :stage stage-name
   :f (fn [{:keys [node !state]}]
        (let [[from to] (->window (:end @!state))
              args (vec (take (* 2 n-tables) (cycle [from to])))
              rows-read (scan-rows-read node sql args)
              start-ms (System/currentTimeMillis)
              res (xt/q node (into [sql] args))
              q-ms (- (System/currentTimeMillis) start-ms)]
          (log/infof "QUERY-RESULT stage=%s window=[%s, %s] rows-scanned=%d query-ms=%d result=%s"
                     (name stage-name) from to rows-read q-ms (pr-str (take 4 res)))
          (println (format "QUERY-RESULT stage=%s rows-scanned=%d query-ms=%d" (name stage-name) rows-read q-ms))))})

(defn- window [^Duration len ^Duration offset]
  (fn [^Instant end]
    (let [to (.minus end offset)]
      [(.minus to len) to])))

(defn- ->ingestion-stage [{:keys [devices intervals metrics]}]
  [{:t :call, :stage :ingest
    :f (fn [{:keys [node]}]
         (log/infof "Inserting %d intervals for %d devices across %d metrics (%d rows)"
                    intervals devices (count metrics) (* intervals devices (count metrics)))
         (doseq [idx (range intervals)]
           (when (zero? (mod idx 100))
             (log/infof "Submitting readings from %s (interval %d/%d)" (->interval-start idx) idx intervals))
           (submit-interval! node metrics devices idx)))}
   {:t :call, :stage :sync
    :f (fn [{:keys [node]}] (b/sync-node node (Duration/ofHours 6)))}
   {:t :call, :stage :compact
    :f (fn [{:keys [node]}]
         (b/flush-block! node)
         (b/compact! node))}])

(defmethod b/cli-flags :readings [_]
  [[nil "--devices devices" "device count"
    :parse-fn parse-long
    :default 100000]
   [nil "--days DAYS" "days of readings (one reading per device per metric every 5 minutes)"
    :parse-fn parse-long
    :default 90]
   [nil "--metrics METRICS" "metric count, each in its own table"
    :parse-fn parse-long
    :default 4]
   [nil "--within-file-recency-branching BOOL" "enable compactor.withinFileRecencyBranching (true/false)"
    :id :within-file-recency-branching?
    :parse-fn parse-boolean
    :default false]
   ["-h" "--help"]])

(defmethod b/->benchmark :readings [_ {:keys [devices days metrics within-file-recency-branching? seed no-load?]
                                       :or {devices 100000, days 90, metrics 4, seed 0}}]
  (let [metrics (metrics-for metrics)
        intervals (* days intervals-per-day)
        end (->interval-start intervals)
        q-metric (first metrics)
        hour (Duration/ofHours 1)
        day (Duration/ofDays 1)]
    (log/info {:devices devices :days days :metrics metrics :intervals intervals
               :within-file-recency-branching? within-file-recency-branching? :seed seed})
    {:title "Readings benchmarks"
     :benchmark-type :readings
     :seed seed
     :parameters {:devices devices :days days :metrics (count metrics) :seed seed :no-load? no-load?
                  :within-file-recency-branching? (boolean within-file-recency-branching?)}
     :->state #(do {:!state (atom {:end end})})
     :tasks (concat (when-not no-load?
                      (->ingestion-stage {:devices devices :intervals intervals :metrics metrics}))
                    [(->query-stage :query-last-hour (agg-sql q-metric) 1 (window hour Duration/ZERO))
                     (->query-stage :query-last-day (agg-sql q-metric) 1 (window day Duration/ZERO))
                     (->query-stage :query-hour-7d-back (agg-sql q-metric) 1 (window hour (Duration/ofDays 7)))
                     (->query-stage :query-hour-30d-back (agg-sql q-metric) 1 (window hour (Duration/ofDays 30)))
                     (->query-stage :query-cross-metric-last-hour (cross-metric-sql metrics) (count metrics)
                                    (window hour Duration/ZERO))])}))

;; not intended to be run as a test - more for ease of REPL dev
(t/deftest ^:benchmark run-readings
  (util/with-tmp-dirs #{node-tmp-dir}
    (-> (b/->benchmark :readings {:devices 100, :days 1, :metrics 2})
        (b/run-benchmark {:node-dir node-tmp-dir}))))
