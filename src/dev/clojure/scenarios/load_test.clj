(ns scenarios.load-test
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [print-table]]
            [clojure.string :as str]
            [java-time.api :as jt]
            [mount.core :as mount :refer [defstate]]
            [xtdb.api :as xt]
            [xtdb.node :as xtnode]
            [xtdb.util :as xtutil])
  (:import (java.nio.file CopyOption Files Path)
           (java.time LocalDate)))

(defn make-node-conf [node-name]
  (let [mk-path (fn [folder] [:local {:path (io/file "dev" "scenarios" node-name folder)}])]
    (merge dev/standalone-config
           {:log (mk-path "log")
            :storage (mk-path "objects")})))

(defstate ^{:on-reload :noop}
  node-load1
  "Node with random system readings for year 2024, for 1000 systems"
  :start (xtnode/start-node (make-node-conf "load1"))
  :stop (xtutil/close node-load1))

(defn every-5-min [year]
  (let [start-date (jt/local-date-time year 1 1 0 0)
        end-date (jt/plus start-date (jt/years 1))]
    (->> (iterate #(jt/plus % (jt/duration 5 :minutes)) start-date)
      (take-while #(jt/before? % end-date))
      (map #(jt/instant % (jt/zone-id "UTC"))))))

(comment
  (take 10 (every-5-min 2024)))

(defn rand-int-between [minv maxv]
  (+ minv (rand-int (- maxv minv))))

(defn seed-readings [node]
  (doseq [id (range 1 1000)]
    (println "id" id)
    (let [dates (every-5-min 2024)
          values (repeatedly #(rand-int-between 1000 5000))
          txs (for [[from to value] (map vector dates (next dates) values)]
                [:put-docs {:into :reading
                            :valid-from from
                            :valid-to to}
                 {:xt/id id
                  :value value}])]
      (xt/submit-tx node txs))))

(defn sum-system-readings--sql [{:keys [systems days]}]
  (let [start-date (LocalDate/of 2024 1 1)
        end-date (-> start-date (.plusDays days))
        render-date (fn [d] (str "DATE '" d "'"))]
    (str/join " "
      ["SELECT _valid_from, SUM(value)"
       "FROM reading FOR VALID_TIME FROM" (render-date start-date) "TO" (render-date end-date)
       "WHERE _id <=" systems
       "ORDER BY _valid_from"])))

(comment
  (sum-system-readings--sql {:systems 10, :days 1}))

(defn run-readings
  "Runs load test, storing results in a file.
   Results previously found in the file will be reused and not re-queried. This is convenient
   for interrupting the test anytime and resuming it later."
  [node {:keys [timeout results-file]}]
  (doseq [systems (range 1 1002 200)
          days (range 1 62 15)]
    (let [cache-k {:systems systems
                   :days days}
          cache (io/file results-file)
          cached-map (if (.exists cache)
                       (read-string (slurp cache))
                       {})]
      (println "--------------------")
      (println cache-k)
      (if-let [result (get cached-map cache-k)]
        (do
          (println "[from cache]")
          (println result))
        (let [sql (sum-system-readings--sql {:systems systems, :days days})]
          (println sql)
          (let [completed (promise)
                task (future
                       (try
                         (let [t (System/nanoTime)
                               records (xt/plan-q node sql)
                               records-count (reduce (fn [count _] (inc count)) 0 records)
                               ellapsed (-> (System/nanoTime) (- t) double (/ 1000000.0))]
                           {:records-count records-count
                            :ellapsed ellapsed})
                         (finally
                           (deliver completed true))))
                result (deref task timeout :timeout)]
            (case result
              :timeout (do
                         (println "[timed out, aborting]")
                         (future-cancel task)
                         (deref completed) ; ensure task has completed totally before running next
                         (println "[aborted]"))
              (do
                (spit cache (assoc cached-map cache-k result))
                (println result)))))))))

(defn print-results [{:keys [filename]}]
  (->> (read-string (slurp filename))
    (map (fn [[k v]] (merge k v)))
    (sort-by (fn [{:keys [days systems]}]
               [days systems]))
    (print-table [:days :systems :ellapsed :records-count])))

(defn mv [path1 path2]
  (Files/move (Path/of path1 (make-array String 0))
    (Path/of path2 (make-array String 0))
    (make-array CopyOption 0)))


; Manual use
(comment
  (mount/start #'node-load1)
  (xt/status node-load1)
  (mount/stop #'node-load1)

  (time (seed-readings node-load1))
  (xtdb.api/q node-load1 "SELECT COUNT(*) FROM reading FOR VALID_TIME ALL")
  (xtdb.api/q node-load1 "SELECT DISTINCT _id FROM reading FOR VALID_TIME ALL")

  (mv "readings-results.edn"
      "readings-results.backup3.edn")

  (.delete (io/file "system-readings-backup.edn"))

  (time (count (xt/q node-load1 (sum-system-readings--sql {:systems 2, :days 2})))) ; warm-up

  (run-readings node-load1 {:results-file "readings-results.edn"
                            :timeout (* 1 60 1000)})

  (print-results {:filename "readings-results.edn"}))
