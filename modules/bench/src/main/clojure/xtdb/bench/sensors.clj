(ns xtdb.bench.sensors
  (:require [malli.generator :as mgen]
            [xtdb.api :as xt]
            [xtdb.bench :as bench]
            [xtdb.bench.xtdb2 :as bxt]
            [xtdb.node :as xtn]
            [xtdb.time :as time])
  (:import [java.time Duration Period ZonedDateTime]
           [java.util Random]))

(def Model
  [:map
   [:xt/id uuid?]
   [:name [:string {:gen/min 8, :gen/max 15}]]])

(def Manufacturer
  [:map
   [:xt/id uuid?]
   [:name [:string {:gen/min 3, :gen/max 10}]]
   [:models [:set {:gen/min 1, :gen/max 6}
             Model]]])

(defn Sensor [model-ids]
  [:map
   [:xt/id uuid?]
   [:model {:gen/elements model-ids} Model]])

(defn SensorSystem [model-ids]
  [:map
   [:xt/id uuid?]
   [:upstream-id [:string {:gen/min 7, :gen/max 8}]]
   [:sensors [:set {:gen/min 1, :gen/max 2}
              (Sensor model-ids)]]])

(defn benchmark [{:keys [seed sensor-count
                         ^Duration reading-resolution
                         ^Period readings-period]
                  :or {seed 0
                       sensor-count #=(long 1e5)
                       reading-resolution #time/duration "PT5M"
                       readings-period #time/period "P1Y"}}]
  (let [random (Random. seed)
        manufacturers (mgen/sample Manufacturer {:size 10, :seed (.nextLong random)})
        model-ids (into #{} (comp (mapcat :models) (map :xt/id)) manufacturers)
        systems (mgen/sample (SensorSystem model-ids) {:size sensor-count, :seed (.nextLong random)})]
    {:title "Sensors"
     :seed seed
     :tasks
     [{:t :do
       :stage :ingest
       :tasks [{:t :call
                :stage :submit-systems
                :f (fn [{node :sut}]
                     (xt/submit-tx node
                                   [(into [:put-docs :manufacturers] (mapv #(dissoc % :models) manufacturers))
                                    (into [:put-docs :models] (mapcat :models manufacturers))]
                                   {:system-time #inst "2000"})

                     (->> systems
                          (partition-all 25)
                          (map-indexed (fn [idx systems]
                                         (when (Thread/interrupted)
                                           (throw (InterruptedException.)))

                                         (xt/submit-tx node
                                                       [(into [:put-docs :systems] (map #(dissoc % :sensors)) systems)
                                                        (into [:put-docs :sensors] (mapcat :sensors) systems)]
                                                       {:system-time (.plusMillis (.toInstant #inst "2000") (inc idx))})))
                          dorun))}

               {:t :call
                :stage :submit-readings
                :f (fn [{node :sut}]
                     (let [sensor-ids (into [] (comp (mapcat :sensors) (map :xt/id)) systems)
                           readings-start (time/->zdt (.toInstant #inst "2001"))
                           readings-end (.plus readings-start readings-period)]
                       (doseq [^ZonedDateTime reading-start (->> (iterate #(.plus ^ZonedDateTime % reading-resolution) readings-start)
                                                                 (take-while #(.isAfter readings-end %)))
                               :let [reading-end (.plus reading-start reading-resolution)]]
                         (->> (shuffle sensor-ids)
                              (partition-all 100)
                              (map-indexed (fn [idx batch]
                                             (xt/submit-tx node
                                                           [(into [:put-docs {:into :readings
                                                                              :valid-from reading-start
                                                                              :valid-to reading-end}]
                                                                  (for [sensor-id batch]
                                                                    {:xt/id sensor-id
                                                                     :metric1 (.nextDouble random)
                                                                     :metric2 (.nextDouble random)}))]
                                                           {:system-time (.plusMillis (.toInstant reading-end) (* 20 (inc idx)))})))
                              dorun))))}

               {:t :call
                :stage :sync
                :f (fn [{node :sut}]
                     (bxt/sync-node node (Duration/ofMinutes 5)))}]}

      {:t :call
       :stage :query
       :f (fn [{:keys [sut]}]
            (prn :readings (xt/q sut "SELECT COUNT(*) FROM readings FOR VALID_TIME AS OF TIMESTAMP '2001-01-08T12:00:00Z'")))}]}))

(comment
  (let [f (bench/compile-benchmark (benchmark {:sensor-count 1e3
                                               :reading-resolution #time/duration "PT5M"
                                               :readings-period #time/period "P10D"})
                                   @(requiring-resolve `xtdb.bench.measurement/wrap-task))]
    (with-open [node (xtn/start-node {:server {:port 0}})]
      (f node))

    #_
    (f dev/node)))
