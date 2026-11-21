(ns xtdb.secondary-index-test
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [next.jdbc :as jdbc]
            [xtdb.db-catalog :as db]
            [xtdb.log :as xt-log]
            [xtdb.node :as xtn]
            [xtdb.test-util :as tu]
            [xtdb.util :as util])
  (:import (org.postgresql.util PSQLException)
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
