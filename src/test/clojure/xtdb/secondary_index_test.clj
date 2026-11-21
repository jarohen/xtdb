(ns xtdb.secondary-index-test
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [next.jdbc :as jdbc]
            [xtdb.api :as xt]
            [xtdb.db-catalog :as db]
            [xtdb.log :as xt-log]
            [xtdb.node :as xtn]
            [xtdb.test-util :as tu]
            [xtdb.util :as util])
  (:import (java.time LocalDate LocalDateTime ZoneOffset ZonedDateTime)
           (org.postgresql.util PSQLException)
           (xtdb.api TableRef Xtdb)
           (xtdb.api.storage ObjectStore$StoredObject)
           (xtdb.database Database)
           (xtdb.index IndexState$Building IndexState$Dropped IndexState$Ready IndexTrie SecondaryIndex)))

(t/use-fixtures :each tu/with-node)

(defn- indices [^Database db]
  (->> (.getIndices (.getIndexCatalog (.getQueryState db)))
       (map (fn [^SecondaryIndex index]
              (let [index-def (.getDef index)
                    table (.getTable index-def)
                    state (.getState index)]
                {:name (.getName index-def)
                 :table [(.getSchemaName table) (.getTableName table)]
                 :column (.getColumn index-def)
                 :state (condp instance? state
                          IndexState$Building :building
                          IndexState$Ready :ready
                          IndexState$Dropped :dropped)})))
       (sort-by (juxt :name :state))))

(defn- await-indices
  "The database's indices, once they equal `expected` or after 5s: Ready reaches a node's catalog behind the declaring transaction, not with it."
  [db expected]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (let [actual (indices db)]
        (if (or (= expected actual) (> (System/currentTimeMillis) deadline))
          actual
          (do (Thread/sleep 10) (recur)))))))

(t/deftest create-and-drop-index
  (jdbc/execute! tu/*node* ["CREATE INDEX by_email ON users (email)"])

  (let [expected [{:name "by_email", :table ["public" "users"], :column "email", :state :ready}]]
    (t/is (= expected (await-indices (db/primary-db tu/*node*) expected))
          "an absent table and column are accepted, and the index, owing no blocks, becomes Ready"))

  (t/is (thrown-with-msg? PSQLException #"Index already exists"
                          (jdbc/execute! tu/*node* ["CREATE INDEX by_email ON orders (email)"])))

  (jdbc/execute! tu/*node* ["DROP INDEX by_email"])

  (t/is (thrown-with-msg? PSQLException #"Index does not exist"
                          (jdbc/execute! tu/*node* ["DROP INDEX by_email"])))

  (jdbc/execute! tu/*node* ["CREATE INDEX by_email ON crm.users (email)"])

  (let [expected [{:name "by_email", :table ["public" "users"], :column "email", :state :dropped}
                  {:name "by_email", :table ["crm" "users"], :column "email", :state :ready}]]
    (t/is (= expected (await-indices (db/primary-db tu/*node*) expected))
          "the dropped index stays in the catalog, and its name is free to declare again")))

(t/deftest an-index-on-a-column-no-document-holds-is-rejected
  (with-open [conn (.connect ^Xtdb tu/*node*)]
    (doseq [col ["_iid" "_valid_from" "_valid_to" "_system_from" "_system_to"]]
      (t/is (anomalous? [:incorrect :xtdb/index-on-system-column nil]
                        (.createIndex conn "by_col" (TableRef. "public" "users") col))
            col)))

  (t/is (thrown-with-msg? PSQLException #"Cannot index column '_valid_from'"
                          (jdbc/execute! tu/*node* ["CREATE INDEX by_vf ON users (_valid_from)"])))

  (jdbc/execute! tu/*node* ["CREATE INDEX by_id ON users (_id)"])

  (t/is (= ["by_id"] (map :name (indices (db/primary-db tu/*node*))))
        "`_id` is a document's column, so it takes an index"))

(t/deftest index-belongs-to-the-connections-database
  (jdbc/execute! tu/*node* ["ATTACH DATABASE new_db"])

  (with-open [new-db-conn (.build (-> (.createConnectionBuilder tu/*node*) (.database "new_db")))]
    (jdbc/execute! new-db-conn ["CREATE INDEX by_email ON users (email)"]))

  (t/is (= [] (indices (db/primary-db tu/*node*))))
  (t/is (= ["by_email"] (map :name (indices (.databaseOrNull (db/<-node tu/*node*) "new_db"))))))

(t/deftest index-ddl-is-rejected-in-a-transaction
  (with-open [conn (jdbc/get-connection tu/*node*)]
    (jdbc/execute! conn ["BEGIN READ WRITE"])
    (t/is (thrown-with-msg? PSQLException #"Cannot create an index in a transaction"
                            (jdbc/execute! conn ["CREATE INDEX by_email ON users (email)"])))))

(t/deftest index-ddl-over-the-simple-query-protocol
  (with-open [conn (.build (-> (.createConnectionBuilder tu/*node*) (.option "preferQueryMode" "simple")))]
    (jdbc/execute! conn ["CREATE INDEX by_email ON users (email)"])
    (jdbc/execute! conn ["DROP INDEX by_email"]))

  (t/is (= [:dropped] (map :state (indices (db/primary-db tu/*node*))))))

(t/deftest connection-index-ddl-is-rejected-in-a-transaction
  (with-open [conn (.connect ^Xtdb tu/*node*)]
    (.begin conn)

    (t/is (anomalous? [:incorrect :xtdb/tx-already-in-progress nil]
                      (.createIndex conn "by_email" (TableRef. "public" "users") "email")))

    (t/is (anomalous? [:incorrect :xtdb/tx-already-in-progress nil]
                      (.dropIndex conn "by_email"))))

  (t/is (= [] (indices (db/primary-db tu/*node*)))))

(t/deftest restarted-node-holds-the-indices-its-peers-hold
  (util/with-tmp-dirs #{path}
    (let [node-opts {:log [:local {:path (.resolve path "log")}]
                     :storage [:local {:path (.resolve path "objects")}]
                     :compactor {:threads 0}}]
      (with-open [node (xtn/start-node node-opts)]
        (jdbc/execute! node ["INSERT INTO users RECORDS {_id: 1, email: 'ada@example.com'}"])
        (jdbc/execute! node ["CREATE INDEX by_email ON users (email)"])
        (jdbc/execute! node ["CREATE INDEX by_sku ON orders (sku)"])
        (jdbc/execute! node ["CREATE INDEX by_name ON users (name)"])
        (jdbc/execute! node ["DROP INDEX by_name"])
        (jdbc/execute! node ["CREATE INDEX by_total ON orders (total)"])
        (tu/flush-block! node)

        (jdbc/execute! node ["DROP INDEX by_total"]))

      (with-open [node (xtn/start-node node-opts)]
        (xt-log/sync-node node #xt/duration "PT5S")

        (let [expected [{:name "by_email", :table ["public" "users"], :column "email", :state :ready}
                        {:name "by_name", :table ["public" "users"], :column "name", :state :dropped}
                        {:name "by_sku", :table ["public" "orders"], :column "sku", :state :ready}
                        {:name "by_total", :table ["public" "orders"], :column "total", :state :dropped}]]
          (t/is (= expected (await-indices (db/primary-db node) expected))
                "the block's indices, including one on a table no block has written, with the drop after it replayed onto them"))

        (t/is (thrown-with-msg? PSQLException #"Index already exists"
                                (jdbc/execute! node ["CREATE INDEX by_email ON users (email)"])))

        (t/is (thrown-with-msg? PSQLException #"Index already exists"
                                (jdbc/execute! node ["CREATE INDEX by_sku ON orders (sku)"])))

        (jdbc/execute! node ["DROP INDEX by_email"])

        (t/is (= :dropped (->> (indices (db/primary-db node))
                               (filter (comp #{"by_email"} :name))
                               first :state)))))))

(t/deftest only-an-index-owing-no-blocks-becomes-ready
  (util/with-tmp-dirs #{path}
    (let [node-opts {:log [:local {:path (.resolve path "log")}]
                     :storage [:local {:path (.resolve path "objects")}]
                     :compactor {:threads 0}}
          expected [{:name "by_email", :table ["public" "users"], :column "email", :state :building}
                    {:name "by_name", :table ["public" "people"], :column "name", :state :ready}
                    {:name "by_sku", :table ["public" "orders"], :column "sku", :state :ready}]]

      (with-open [node (xtn/start-node node-opts)
                  follower (-> (xtn/->config node-opts) (.readOnlyDatabases true) (.open))]
        (jdbc/execute! node ["INSERT INTO users RECORDS {_id: 1, email: 'ada@example.com'}"])
        (tu/flush-block! node)
        (jdbc/execute! node ["INSERT INTO orders RECORDS {_id: 1, sku: 'X-1'}"])

        (jdbc/execute! node ["CREATE INDEX by_email ON users (email)"])
        (jdbc/execute! node ["CREATE INDEX by_sku ON orders (sku)"])
        (jdbc/execute! node ["CREATE INDEX by_name ON people (name)"])

        (t/is (= expected (await-indices (db/primary-db node) expected))
              "Ready on a table first written in the open block and on one never written; Building on one with a flushed block")

        (tu/flush-block! node)

        (xt-log/sync-node follower #xt/duration "PT5S")
        (t/is (= expected (await-indices (db/primary-db follower) expected))
              "a follower holds the leader's decision"))

      (with-open [node (xtn/start-node node-opts)]
        (xt-log/sync-node node #xt/duration "PT5S")
        (t/is (= expected (await-indices (db/primary-db node) expected))
              "a restarted node holds the same states")))))

(defn- index-tries
  "Each index's tries, by name, with the files under its directory."
  [^Database db]
  (let [files (->> (.listAllObjects (.getBufferPool db) (util/->path "tables"))
                   (map (comp str #(.getKey ^ObjectStore$StoredObject %)))
                   (filter #(str/includes? % "/indices/")))]
    (->> (.getIndices (.getIndexCatalog (.getQueryState db)))
         (into {} (map (fn [^SecondaryIndex index]
                         (let [index-id (str (.getId (.getDef index)))]
                           [(.getName (.getDef index))
                            {:tries (mapv #(str (.getKey ^IndexTrie %)) (.getTries index))
                             :files (->> files
                                         (filter #(str/includes? % index-id))
                                         (map #(str/replace % index-id "<id>"))
                                         sort vec)}])))))))

(t/deftest flushed-blocks-write-index-l0s
  (util/with-tmp-dirs #{path}
    (let [node-opts {:log [:local {:path (.resolve path "log")}]
                     :storage [:local {:path (.resolve path "objects")}]
                     :compactor {:threads 0}}
          expected {"by_email" {:tries ["l00-rc-b00"]
                                :files ["tables/public$users/indices/<id>/data/l00-rc-b00.arrow"
                                        "tables/public$users/indices/<id>/meta/l00-rc-b00.arrow"]}
                    "by_name" {:tries [], :files []}
                    "by_sku" {:tries ["l00-rc-b01"]
                              :files ["tables/public$orders/indices/<id>/data/l00-rc-b01.arrow"
                                      "tables/public$orders/indices/<id>/meta/l00-rc-b01.arrow"]}}]

      (with-open [node (xtn/start-node node-opts)
                  follower (-> (xtn/->config node-opts) (.readOnlyDatabases true) (.open))]
        (jdbc/execute! node ["CREATE INDEX by_email ON users (email)"])
        (jdbc/execute! node ["CREATE INDEX by_name ON users (name)"])
        (jdbc/execute! node ["DROP INDEX by_name"])
        (jdbc/execute! node ["CREATE INDEX by_sku ON orders (sku)"])

        (jdbc/execute! node ["INSERT INTO users RECORDS {_id: 1, email: 'ada@example.com', name: 'Ada'}, {_id: 2, email: NULL}"])
        (jdbc/execute! node ["INSERT INTO orders RECORDS {_id: 1, total: 3}"])
        (tu/flush-block! node)

        (t/is (= {"by_email" (get expected "by_email")
                  "by_name" {:tries [], :files []}
                  "by_sku" {:tries [], :files []}}
                 (index-tries (db/primary-db node)))
              "an L0 for the Building index whose column a put gave a value, and none for a dropped index or for a block with no such put")

        (jdbc/execute! node ["INSERT INTO users RECORDS {_id: 3, name: 'Carol'}"])
        (jdbc/execute! node ["INSERT INTO orders RECORDS {_id: 2, sku: 'X-1'}"])
        (tu/flush-block! node)

        (t/is (= expected (index-tries (db/primary-db node))))

        (xt-log/sync-node follower #xt/duration "PT5S")
        (t/is (= expected (index-tries (db/primary-db follower)))
              "a follower adopts the same index tries off the replica log"))

      (with-open [node (xtn/start-node node-opts)]
        (xt-log/sync-node node #xt/duration "PT5S")
        (t/is (= expected (index-tries (db/primary-db node)))
              "a restarted node reloads the index tries from the latest block")))))

(t/deftest an-index-on-a-populated-table-is-backfilled-to-ready
  (util/with-tmp-dirs #{path}
    (let [node-opts {:log [:local {:path (.resolve path "log")}]
                     :storage [:local {:path (.resolve path "objects")}]}
          expected {:state :ready
                    :tries ["l00-rc-b00" "l00-rc-b03"]
                    :files ["tables/public$users/indices/<id>/data/l00-rc-b00.arrow"
                            "tables/public$users/indices/<id>/data/l00-rc-b03.arrow"
                            "tables/public$users/indices/<id>/meta/l00-rc-b00.arrow"
                            "tables/public$users/indices/<id>/meta/l00-rc-b03.arrow"]}
          by-email (fn [db]
                     (let [{:keys [tries files]} (get (index-tries db) "by_email")]
                       {:state (:state (first (indices db)))
                        :tries (vec (sort tries))
                        :files files}))
          await-by-email (fn [db]
                           (let [deadline (+ (System/currentTimeMillis) 10000)]
                             (loop []
                               (let [actual (by-email db)]
                                 (if (or (= expected actual) (> (System/currentTimeMillis) deadline))
                                   actual
                                   (do (Thread/sleep 10) (recur)))))))]

      (with-open [node (xtn/start-node node-opts)
                  follower (-> (xtn/->config node-opts) (.readOnlyDatabases true) (.open))]
        (jdbc/execute! node ["INSERT INTO users RECORDS {_id: 1, email: 'ada@example.com'}"])
        (tu/flush-block! node)
        (jdbc/execute! node ["INSERT INTO users RECORDS {_id: 2, name: 'Bob'}, {_id: 3, email: NULL}"])
        (tu/flush-block! node)
        (jdbc/execute! node ["INSERT INTO orders RECORDS {_id: 1, sku: 'X-1'}"])
        (tu/flush-block! node)
        (jdbc/execute! node ["INSERT INTO users RECORDS {_id: 4, email: 'dee@example.com'}"])
        (tu/flush-block! node)

        (jdbc/execute! node ["CREATE INDEX by_email ON users (email)"])

        (t/is (= expected (await-by-email (db/primary-db node)))
              "L0s for the blocks whose puts gave the column a value, none for one whose puts didn't or that the table didn't write in, then Ready")

        (xt-log/sync-node follower #xt/duration "PT5S")
        (t/is (= expected (await-by-email (db/primary-db follower)))
              "a follower applies the same backfills and Ready off the replica log")

        (tu/flush-block! node))

      (with-open [node (xtn/start-node node-opts)]
        (xt-log/sync-node node #xt/duration "PT5S")
        (t/is (= expected (await-by-email (db/primary-db node)))
              "a restarted node holds the same index")))))

(defn- execute-on-both!
  "`docs` carries the index and `plain` doesn't, so a query answered differently by the two is the index's doing."
  [node ->ops]
  (xt/execute-tx node (->ops :docs))
  (xt/execute-tx node (->ops :plain)))

(defn- matching
  [node table where args]
  (try
    (set (xt/q node (into [(format "SELECT _id, v FROM %s WHERE %s" (name table) where)] args)))
    (catch Exception e
      [:error (ex-message e)])))

(defn- scan-pages-used [node table where args]
  (->> (xt/q node (into [(format "EXPLAIN ANALYZE SELECT _id, v FROM %s WHERE %s" (name table) where)] args))
       (filter #(= :scan (:op %)))
       first :attributes :scan-pages-used))

(defn- await-ready [node]
  (let [expected [{:name "by_v", :table ["public" "docs"], :column "v", :state :ready}]]
    (t/is (= expected (await-indices (db/primary-db node) expected)))))

(def ^:private mixed-docs
  [{:xt/id 10000, :v 1}
   {:xt/id 10001, :v 1.0}
   {:xt/id 10002, :v 1.0M}
   {:xt/id 10003, :v 0.0}
   {:xt/id 10004, :v -0.0}
   {:xt/id 10005, :v 2.5}
   {:xt/id 10006, :v 12345678901234567}
   {:xt/id 10007, :v "dup"}
   {:xt/id 10008, :v "dup"}
   {:xt/id 10009, :v true}
   {:xt/id 10010, :v #uuid "00000000-0000-0000-0000-000000000001"}
   {:xt/id 10011, :v (LocalDate/of 2020 1 1)}
   {:xt/id 10012, :v (ZonedDateTime/of 2020 1 1 0 0 0 0 ZoneOffset/UTC)}
   {:xt/id 10013, :v (LocalDateTime/of 2020 1 1 0 0)}
   {:xt/id 10014, :v [1 2]}
   {:xt/id 10015, :v nil}
   {:xt/id 10016}])

(t/deftest an-equality-scan-returns-the-same-rows-with-an-index-as-without
  (let [node tu/*node*]
    (execute-on-both! node (fn [table] [(into [:put-docs table] (for [i (range 3000)] {:xt/id i, :v (str "user-" i)}))
                                        (into [:put-docs table] mixed-docs)]))
    (tu/flush-block! node)

    (jdbc/execute! node ["CREATE INDEX by_v ON docs (v)"])
    (await-ready node)

    (execute-on-both! node (fn [table] [[:put-docs table {:xt/id 7, :v "user-7-renamed"} {:xt/id 20000, :v 1} {:xt/id 20001, :v -0.0}]
                                        [:delete-docs table 8]]))
    (tu/flush-block! node)

    (execute-on-both! node (fn [table] [[:put-docs table {:xt/id 9, :v "user-9-live"} {:xt/id 30000, :v "live"} {:xt/id 30001, :v 1.0M} {:xt/id 30002, :v false}]
                                        [:delete-docs table 10]]))

    (t/is (= #{{:xt/id 0, :v "user-0"}} (matching node :docs "v = ?" ["user-0"])))
    (t/is (= #{{:xt/id 30000, :v "live"}} (matching node :docs "v = ?" ["live"])))

    (doseq [v ["user-0" "user-1234" "user-2999" "user-7" "user-7-renamed" "user-8" "user-9" "user-9-live" "user-10"
               "dup" "nope" "live"
               1 (int 1) 1.0 1.0M 0 0.0 -0.0 2.5 12345678901234567 12345678901234568
               true false #uuid "00000000-0000-0000-0000-000000000001" nil
               (LocalDate/of 2020 1 1) (ZonedDateTime/of 2020 1 1 0 0 0 0 ZoneOffset/UTC) (LocalDateTime/of 2020 1 1 0 0)]]
      (t/is (= (matching node :plain "v = ?" [v]) (matching node :docs "v = ?" [v]))
            (str "param " (pr-str v))))

    (doseq [lit ["1" "1.0" "0" "0.0" "-0.0" "2.5" "12345678901234567" "TRUE" "FALSE" "NULL"
                 "DATE '2020-01-01'" "TIMESTAMP '2020-01-01 00:00:00'" "TIMESTAMP '2020-01-01 00:00:00Z'" "[1, 2]"]]
      (t/is (= (matching node :plain (str "v = " lit) []) (matching node :docs (str "v = " lit) []))
            (str "literal " lit)))))

(t/deftest an-index-reads-fewer-pages-of-a-flushed-block-until-it-is-dropped
  (with-open [node (xtn/start-node {:compactor {:threads 0}})]
    (jdbc/execute! node ["CREATE INDEX by_v ON docs (v)"])
    (await-ready node)

    (execute-on-both! node (fn [table] [(into [:put-docs table] (for [i (range 3000)] {:xt/id i, :v (str "user-" i)}))]))
    (tu/flush-block! node)

    (let [plain-pages (scan-pages-used node :plain "v = ?" ["user-1234"])]
      (t/is (= #{{:xt/id 1234, :v "user-1234"}} (matching node :docs "v = ?" ["user-1234"])))

      (t/is (< (scan-pages-used node :docs "v = ?" ["user-1234"]) plain-pages)
            "the index's own page, and the data pages holding its candidates, are fewer than the pages a flushed block's metadata can't prune")

      (jdbc/execute! node ["DROP INDEX by_v"])

      (t/is (= plain-pages (scan-pages-used node :docs "v = ?" ["user-1234"]))
            "a scan after the drop reads without it"))))
