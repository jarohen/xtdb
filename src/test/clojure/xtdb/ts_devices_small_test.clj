(ns xtdb.ts-devices-small-test
  (:require [clojure.test :as t]
            [clojure.tools.logging :as log]
            [xtdb.log :as xt-log]
            [xtdb.protocols :as xtp]
            [xtdb.test-util :as tu]
            [xtdb.ts-devices :as tsd]
            [xtdb.util :as util])
  (:import java.time.Duration))

(def ^:private ^:dynamic *node* nil)

(t/use-fixtures :once
  (fn [f]
    (let [node-dir (util/->path "target/can-ingest-ts-devices-small")]
      (util/delete-dir node-dir)

      (with-open [node (tu/->local-node {:node-dir node-dir})]
        (binding [*node* node]
          (t/is (= {"xtdb" [nil]} (xtp/latest-completed-txs node)))

          (let [last-tx-key (tsd/submit-ts-devices node {:size :small})]

            (log/info "transactions submitted, last tx" (pr-str last-tx-key))
            (xt-log/await-db node last-tx-key (Duration/ofMinutes 15))
            (t/is (= {"xtdb" [last-tx-key]} (xtp/latest-completed-txs node)))
            (tu/finish-block! node))

          (f))))))

(t/deftest ^:timescale test-recent-battery-temperatures
  (t/is (= [{:time #inst "2016-11-15T18:39:00.000-00:00",
             :device-id "demo000000",
             :battery-temperature 91.9}
            {:time #inst "2016-11-15T18:39:00.000-00:00",
             :device-id "demo000001",
             :battery-temperature 92.6}
            {:time #inst "2016-11-15T18:39:00.000-00:00",
             :device-id "demo000002",
             :battery-temperature 87.2}
            {:time #inst "2016-11-15T18:39:00.000-00:00",
             :device-id "demo000003",
             :battery-temperature 90.5}
            {:time #inst "2016-11-15T18:39:00.000-00:00",
             :device-id "demo000004",
             :battery-temperature 88.9}
            {:time #inst "2016-11-15T18:39:00.000-00:00",
             :device-id "demo000005",
             :battery-temperature 87.4}
            {:time #inst "2016-11-15T18:39:00.000-00:00",
             :device-id "demo000006",
             :battery-temperature 88.9}
            {:time #inst "2016-11-15T18:39:00.000-00:00",
             :device-id "demo000007",
             :battery-temperature 87.4}
            {:time #inst "2016-11-15T18:39:00.000-00:00",
             :device-id "demo000008",
             :battery-temperature 91.1}
            {:time #inst "2016-11-15T18:39:00.000-00:00",
             :device-id "demo000009",
             :battery-temperature 91.1}]
           (tu/query-ra tsd/query-recent-battery-temperatures {:node tu/*node*}))))

(t/deftest ^:timescale test-busiest-low-battery-devices
  #_ ; TODO will fill these in once we've resolved issues in ts-devices ingest
  (t/is (= []
           (tu/query-ra tsd/query-busiest-low-battery-devices {:node tu/*node*}))))

(t/deftest ^:timescale test-min-max-battery-levels-per-hour
  #_ ; TODO will fill these in once we've resolved issues in ts-devices ingest
  (t/is (= []
           (tu/query-ra tsd/query-min-max-battery-levels-per-hour {:node tu/*node*}))))
