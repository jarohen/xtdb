(ns generate-readings
  (:require [xtdb.client :as xtc]
            [xtdb.api :as xt]
            [java-time.api :as jt]))

(defonce dev-node (xtc/start-client "http://localhost:3000"))

(comment
  (xt/status dev-node))


;; >> Random readings

(defn rand-delta [delta]
  (- (rand (* 2 delta)) delta))

(defn rand-walk
  ([delta]
   (rand-walk 0 delta))
  ([start delta]
   (iterate #(+ % (rand-delta delta)) start)))

(defn intervals [start stop step]
  (let [stop (jt/plus stop step)
        dates (take-while #(jt/after? stop %) (jt/iterate jt/plus start step))
        starts (butlast dates)
        ends (drop 1 dates)]
    (map (fn [start end]
           {:start start
            :end end})
         starts
         ends)))

(comment
  (intervals (jt/instant "2023-01-01T00:00:00Z")
             (jt/instant "2024-01-01T00:00:00Z")
             (jt/hours 1)))

(defn rand-readings [system-id created-intervals {:keys [init delta]}]
  (let [rand-values (rand-walk init delta)]
    (map (fn [{:keys [start end]} value]
           {:system-id system-id
            :value value
            :valid-from start
            :valid-to end})
         created-intervals
         rand-values)))

(defn system-readings [start-date end-date duration num-systems]
  (let [created-intervals (intervals start-date end-date duration)]
    (->> (range num-systems)
         (mapcat #(rand-readings %
                                 created-intervals
                                 {:init (+ 500 (rand 10))
                                  :delta (rand-delta 10)})))))

(comment
  (system-readings (jt/instant "2024-01-01T00:00:00Z")
                   (jt/instant "2024-01-01T00:10:00Z")
                   (jt/minutes 5)
                   2))

(defn year-readings []
  (system-readings (jt/instant "2023-01-01T00:00:00Z")
                   (jt/instant "2024-01-01T00:00:00Z")
                   (jt/minutes 5)
                   10))

(defn week-readings []
  (system-readings (jt/instant "2024-01-01T00:00:00Z")
                   (jt/instant "2024-01-08T00:00:00Z")
                   (jt/minutes 5)
                   1000))

(defn ->put-docs [table {:keys [system-id value valid-from valid-to]}]
  [:put-docs {:into table
              :valid-from valid-from
              :valid-to valid-to}
   {:xt/id system-id
    :value value}])

(defn execute-readings [node readings]
  (doseq [[batch-idx batch] (map vector (range) (partition 1000 readings))]
    (when (zero? (mod batch-idx 100))
      (println "Inserted" (* 1000 batch-idx) "rows"))
    (xt/execute-tx node batch)))

(comment
  (execute-readings dev-node
                    (concat
                     (map (partial ->put-docs :readings_year) (year-readings))
                     (map (partial ->put-docs :readings_week) (week-readings))))

  (time
   (xt/q dev-node "SELECT COUNT(*) FROM readings_week FOR ALL VALID_TIME")))

(defn downsample-insert [in-table out-table trunc-fn duration-str]
  (str "INSERT INTO " out-table "
WITH data AS (
  SELECT _id, value, DATE_TRUNC(" trunc-fn ", _valid_from) AS _valid_from
  FROM " in-table " FOR VALID_TIME ALL
)
SELECT _id, AVG(value) AS value, _valid_from, (_valid_from + DURATION '" duration-str "') AS _valid_to
FROM data
GROUP BY _id, _valid_from
ORDER BY _id, _valid_from"))

(defn -main [_]
  (println "Inserting readings")
  (let [node (xtc/start-client "http://xtdb-sidecar:3000")]
    (execute-readings
      node
      (concat
        (map (partial ->put-docs :readings_year) (year-readings))
        (map (partial ->put-docs :readings_week) (week-readings))))
    (println "Inserting downsampled readings")
    (xt/execute-tx node
     [[:sql (downsample-insert "readings_year" "readings_year_1h" "HOUR" "PT1H")]
      [:sql (downsample-insert "readings_week" "readings_week_1h" "HOUR" "PT1H")]])
    (println "Done")))
