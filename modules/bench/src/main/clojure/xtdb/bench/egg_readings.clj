(ns xtdb.bench.egg-readings
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [xtdb.api :as xt]
            [xtdb.bench :as bench]
            [xtdb.bench.xtdb2 :as bxt]
            [xtdb.node :as xtn]
            [xtdb.test-util :as tu]
            [xtdb.time :as time]
            [xtdb.util :as util])
  (:import [java.lang AutoCloseable]
           (java.time Duration LocalDate Period ZonedDateTime)))

(defn every-5-min [start, ^Period len]
  (let [start (time/->zdt start)
        end-date (.plus start len)]
    (->> (iterate #(.plus ^ZonedDateTime % #xt/duration "PT5M") start)
         (take-while #(.isBefore ^ZonedDateTime % end-date))
         (map time/->instant))))

(comment
  (every-5-min #inst "2024" #xt/period "P1Y"))

(defn rand-int-between [minv maxv]
  (+ minv (rand-int (- maxv minv))))

(defn seed-readings [node {:keys [total-system-count total-readings-range]
                           :or {total-system-count 1000, total-readings-range #xt/period "P1Y"}}]
  (doseq [id (range total-system-count)]
    (when (Thread/interrupted)
      (throw (InterruptedException.)))

    (let [txs (for [[from to] (->> (every-5-min #inst "2024" total-readings-range)
                                   (partition 2 1))
                    :let [value (rand-int-between 1000 5000)]]
                [:put-docs {:into :readings
                            :valid-from from
                            :valid-to to}
                 {:xt/id id, :value value}])]
      (xt/submit-tx node txs))))

(defn sum-system-readings--sql [{:keys [systems days]}]
  (let [start-date (LocalDate/of 2024 1 1)
        end-date (-> start-date (.plusDays days))]
    (str/join "\n"
              ["SELECT _valid_from, SUM(value)"
               (format "FROM readings FOR VALID_TIME FROM DATE '%s' TO DATE '%s'" start-date end-date)
               (format "WHERE _id <= %d" systems)
               "ORDER BY _valid_from"])))

(comment
  (sum-system-readings--sql {:systems 10, :days 1}))

(defn run-readings
  "Runs load test, storing results in a file.
   Results previously found in the file will be reused and not re-queried. This is convenient
   for interrupting the test anytime and resuming it later."
  [node {:keys [^Duration timeout q-systems q-days], :or {timeout #xt/duration "PT10S", q-systems 1, q-days 15}}]
  (let [sql (doto (sum-system-readings--sql {:systems q-systems, :days q-days})
              (->> (log/trace "sql:")))

        !job (future
               (log/debugf "query {:sys %d, :days %d}, count: %d"
                           q-systems q-days (count (xt/q node [sql]))))]

    (when (= :timeout (deref !job (.toMillis timeout) :timeout))
      (try
        (future-cancel !job)
        @!job
        (catch java.util.concurrent.CancellationException _)))))

(defn benchmark [{:keys [seed load-phase? eager-compact? q-systems q-days], :as opts
                  :or {seed 0, load-phase? true, eager-compact? true
                       q-systems [1 5 10 100 1000]
                       q-days [1 7 14 30 90 180]}}]
  {:title "Readings queries"
   :seed seed
   :tasks (->> (concat (when load-phase?
                         [{:t :call, :stage :submit
                           :f (fn [{node :sut}]
                                (seed-readings node opts))}

                          {:t :call, :stage :finish-chunk,
                           :f (fn [{node :sut}]
                                (bxt/sync-node node #xt/duration "PT5H")
                                (bxt/finish-chunk! node))}

                          (when eager-compact?
                            {:t :call, :stage :compact,
                             :f (fn [{node :sut}]
                                  (bxt/sync-node node #xt/duration "PT5H")
                                  (bxt/finish-chunk! node))})])

                       [{:t :call, :stage :count-query,
                         :f (fn [{node :sut}]
                              (doto (xt/q node ["SELECT COUNT(*) AS table_size FROM readings FOR ALL VALID_TIME"])
                                (->> first :table-size (log/debug "total table size:"))))}]

                       (for [q-systems q-systems
                             q-days q-days]
                         {:t :call, :stage [:readings {:systems q-systems, :days q-days}]
                          :f (fn [{node :sut}]
                               (when (Thread/interrupted)
                                 (throw (InterruptedException.)))

                               (run-readings node {:q-systems q-systems, :q-days q-days}))}))

               (filterv some?))})

(t/deftest run-bench
  (let [load-phase? true
        f (bench/compile-benchmark (benchmark {:load-phase? load-phase? :total-system-count 100, :total-readings-range #xt/period "P1Y"})
                                   @(requiring-resolve `xtdb.bench.measurement/wrap-task))]
    (with-open [in-mem (xtn/start-node)

                ^AutoCloseable
                node (case :xt-local
                       :xt-memory (xtn/start-node)

                       :xt-local (let [path (util/->path "/tmp/xt-tx-overhead-bench")]
                                   (when load-phase?
                                     (util/delete-dir path))
                                   (tu/->local-node {:node-dir path}))

                       :xt-conn (jdbc/get-connection in-mem)

                       :pg-conn (jdbc/get-connection {:dbtype "postgresql"
                                                      :dbname "postgres"
                                                      :user "postgres"
                                                      :password "postgres"}))]
      (f node))

    #_
    (f dev/node)))
