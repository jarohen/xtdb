(ns xtdb.pgwire.types-test
  (:require [clojure.test :as t]
            [xtdb.test-util :as tu])
  (:import (xtdb.pgwire PgType)))

(t/use-fixtures :each tu/with-allocator)

(t/deftest test-pg-datetime-binary-roundtrip
  (doseq [{:keys [^PgType pg-type val]} [{:val #xt/date "2018-07-25" :pg-type PgType/PG_DATE}
                                         {:val #xt/date-time "1441-07-25T18:00:11.888842" :pg-type PgType/PG_TIMESTAMP}
                                         {:val #xt/offset-date-time "1441-07-25T18:00:11.211142Z" :pg-type PgType/PG_TIMESTAMPTZ}]]
    (with-open [rdr (tu/open-vec "val" [val])]
      (t/is (= val (.readBinary pg-type (.write (.binaryWriter pg-type rdr) {} (.next (.rowIterator rdr)))))))))

(t/deftest test-pg-timestamp-binary-sends-the-micro-text-shows
  (doseq [[vec-type val expected] [[#xt/type [:timestamp-local :nano] #xt/date-time "1901-12-13T20:45:52.888842999" #xt/date-time "1901-12-13T20:45:52.888842"]
                                   [#xt/type [:timestamp-local :second] #xt/date-time "2020-01-01T00:00:01" #xt/date-time "2020-01-01T00:00:01"]
                                   [#xt/type [:timestamp-local :milli] #xt/date-time "2020-01-01T00:00:01.123" #xt/date-time "2020-01-01T00:00:01.123"]]]
    (with-open [rdr (tu/open-vec "val" vec-type [val])]
      (t/is (= expected (.readBinary PgType/PG_TIMESTAMP (.write (.binaryWriter PgType/PG_TIMESTAMP rdr) {} (.next (.rowIterator rdr)))))
            (str vec-type)))))
