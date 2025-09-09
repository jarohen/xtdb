(ns xtdb.table-catalog-test
  (:require [clojure.test :as t :refer [deftest]]
            [xtdb.api :as xt]
            [xtdb.db-catalog :as db]
            [xtdb.object-store :as os]
            [xtdb.table :as table]
            [xtdb.table-catalog :as table-cat]
            [xtdb.test-util :as tu]
            [xtdb.time :as time]
            [xtdb.trie :as trie]
            [xtdb.trie-catalog :as trie-cat]
            [xtdb.util :as util])
  (:import [java.time Instant]
           [xtdb.arrow VectorReader]
           [xtdb.block.proto TableBlock]
           [xtdb.bloom BloomUtils]
           [xtdb.log.proto TrieDetails]
           (xtdb.util HyperLogLog)))

(defn trie-details->edn [^TrieDetails trie]
  (cond-> {:table (table/->ref "xtdb" (.getTableName trie)) ; TODO multi-db, db is likely going to be part of the TrieDetails msg
           :trie-key (.getTrieKey trie)
           :data-file-size (.getDataFileSize trie)}
    (.hasTrieMetadata trie) (assoc :trie-metadata (trie-cat/<-trie-metadata (.getTrieMetadata trie)))))

(defn- ->singleton-rdr [v]
  (reify VectorReader
    (hashCode [_ _ hasher]
      (let [bb (util/->iid v)
            ba (byte-array (.remaining bb))]
        (.get bb ba)
        (.hash hasher ba)))))

(deftest current-tries-on-finish-block
  (let [node-dir (util/->path "target/table-catalog-test/current-tries-on-finish-block")]
    (util/delete-dir node-dir)

    (with-open [node (tu/->local-node {:node-dir node-dir, :compactor-threads 0})]
      (let [db (db/primary-db node)
            bp (.getBufferPool db)
            trie-catalog (.getTrieCatalog db)]
        (xt/execute-tx node [[:put-docs :foo {:xt/id 1}]])
        (tu/finish-block! node)

        (xt/execute-tx node [[:put-docs :foo {:xt/id 2}]])
        (tu/finish-block! node)

        (t/is (= [(os/->StoredObject "tables/public$foo/blocks/b00.binpb" 4430)
                  (os/->StoredObject "tables/public$foo/blocks/b01.binpb" 4596)]
                 (.listAllObjects bp (table-cat/->table-block-dir #xt/table foo))))

        (let [{hlls1 :hlls :as _table-block1} (->> (.getByteArray bp (util/->path "tables/public$foo/blocks/b00.binpb"))
                                                   TableBlock/parseFrom
                                                   table-cat/<-table-block)
              {hlls2 :hlls :as table-block2} (->> (.getByteArray bp (util/->path "tables/public$foo/blocks/b01.binpb"))
                                                  TableBlock/parseFrom
                                                  table-cat/<-table-block)

              current-tries (->> table-block2
                                 :tries
                                 (mapv trie-details->edn))
              trie-metas (map :trie-metadata current-tries)
              [trie1-bloom _trie2-bloom] (map :iid-bloom trie-metas)]
          (t/is (= [{:table #xt/table foo,
                     :trie-key "l00-rc-b00",
                     :data-file-size 2182}
                    {:table #xt/table foo,
                     :trie-key "l00-rc-b01",
                     :data-file-size 2182}]
                   (map #(dissoc % :trie-metadata) current-tries)))

          (t/is (= [{:min-valid-from #xt/instant "2020-01-01T00:00:00Z",
                     :max-valid-from #xt/instant "2020-01-01T00:00:00Z",
                     :min-valid-to #xt/instant "+294247-01-10T04:00:54.775807Z",
                     :max-valid-to #xt/instant "+294247-01-10T04:00:54.775807Z",
                     :max-bound-valid-to time/start-of-time
                     :min-system-from #xt/instant "2020-01-01T00:00:00Z",
                     :max-system-from #xt/instant "2020-01-01T00:00:00Z",
                     :max-recency nil,
                     :row-count 1}
                    {:min-valid-from #xt/instant "2020-01-02T00:00:00Z",
                     :max-valid-from #xt/instant "2020-01-02T00:00:00Z",
                     :min-valid-to #xt/instant "+294247-01-10T04:00:54.775807Z",
                     :max-valid-to #xt/instant "+294247-01-10T04:00:54.775807Z",
                     :max-bound-valid-to time/start-of-time
                     :min-system-from #xt/instant "2020-01-02T00:00:00Z",
                     :max-system-from #xt/instant "2020-01-02T00:00:00Z",
                     :max-recency nil,
                     :row-count 1}]
                   (map #(dissoc % :iid-bloom) trie-metas)))

          (t/is (true? (BloomUtils/contains trie1-bloom (BloomUtils/bloomHashes (->singleton-rdr 1) 0))))
          (t/is (false? (BloomUtils/contains trie1-bloom (BloomUtils/bloomHashes (->singleton-rdr 2) 0))))

          (t/is (<= 0.99 (HyperLogLog/estimate (get hlls1 "_id")) 1.01))
          (t/is (<= 1.99 (HyperLogLog/estimate (get hlls2 "_id")) 2.01)))

        (t/testing "artifically adding tries (simulating another node finishing and compacting these)"
          (.addTries trie-catalog #xt/table foo
                     (->> [["l00-rc-b00" 1] ["l00-rc-b01" 1] ["l00-rc-b02" 1] ["l00-rc-b03" 1]
                           ["l01-rc-b00" 2] ["l01-rc-b01" 2] ["l01-rc-b02" 2]
                           ["l02-rc-p0-b01" 4] ["l02-rc-p1-b01" 4] ["l02-rc-p2-b01" 4] ["l02-rc-p3-b01"4]]
                          (map #(apply trie/->trie-details #xt/table foo %)))
                     (Instant/now))

          (tu/finish-block! node)

          (t/is (= ["l00-rc-b00" "l00-rc-b01" "l00-rc-b02" "l00-rc-b03"
                    "l01-rc-b00" "l01-rc-b01" "l01-rc-b02"
                    "l02-rc-p0-b01" "l02-rc-p1-b01" "l02-rc-p2-b01" "l02-rc-p3-b01"]
                   (->> (.getByteArray bp (util/->path "tables/public$foo/blocks/b02.binpb"))
                        TableBlock/parseFrom
                        table-cat/<-table-block
                        :tries
                        (mapv (comp :trie-key trie-details->edn))))))))))


(t/deftest trie-file-order-in-table-block-files
  (let [node-dir (util/->path "target/table-catalog-test/trie-file-order")]
    (util/delete-dir node-dir)

    (t/testing "artifically adding tries"
      (with-open [node (tu/->local-node {:node-dir node-dir, :compactor-threads 0})]
        ;; need some dummy tx for latest-completed-txt
        (xt/execute-tx node [[:put-docs :foo {:xt/id 1}]])
        (let [cat (.getTrieCatalog (db/primary-db node))]
          (.addTries cat #xt/table foo
                     (->> [["l00-rc-b00" 1] ["l00-rc-b01" 1] ["l00-rc-b02" 1] ["l00-rc-b03" 1]
                           ["l01-r20200101-b00" 5] ["l01-rc-b00" 2]
                           ["l01-r20200102-b01" 5] ["l01-rc-b01" 2]
                           ["l01-r20200101-b02" 5] ["l01-r20200102-b02" 5] ["l01-rc-b02" 2]
                           ["l02-rc-p0-b01" 4] ["l02-rc-p2-b01" 4]]
                          (map #(apply trie/->trie-details #xt/table foo %)))
                     (Instant/now))
          (tu/finish-block! node))))

    (with-open [node (tu/->local-node {:node-dir node-dir, :compactor-threads 0})]
      (let [bp (.getBufferPool (db/primary-db node))]
        (t/is (= ["l00-rc-b00"
                  "l00-rc-b01"
                  "l00-rc-b02"
                  "l00-rc-b03"
                  "l01-r20200101-b00"
                  "l01-rc-b00"
                  "l01-r20200102-b01"
                  "l01-rc-b01"
                  "l01-r20200101-b02"
                  "l01-r20200102-b02"
                  "l01-rc-b02"
                  "l02-rc-p0-b01"
                  "l02-rc-p2-b01"]
                 (->> (.getByteArray bp (util/->path "tables/public$foo/blocks/b00.binpb"))
                      TableBlock/parseFrom
                      table-cat/<-table-block
                      :tries
                      (mapv (comp :trie-key trie-details->edn)))))))))
