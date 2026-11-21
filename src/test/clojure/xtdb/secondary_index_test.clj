(ns xtdb.secondary-index-test
  (:require [clojure.test :as t]
            [next.jdbc :as jdbc]
            [xtdb.db-catalog :as db]
            [xtdb.log :as xt-log]
            [xtdb.node :as xtn]
            [xtdb.test-util :as tu]
            [xtdb.util :as util])
  (:import (org.postgresql.util PSQLException)
           (xtdb.api TableRef Xtdb)
           (xtdb.database Database)
           (xtdb.index IndexState$Building IndexState$Dropped SecondaryIndex)))

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
                          IndexState$Dropped :dropped)})))
       (sort-by (juxt :name :state))))

(t/deftest create-and-drop-index
  (jdbc/execute! tu/*node* ["CREATE INDEX by_email ON users (email)"])

  (t/is (= [{:name "by_email", :table ["public" "users"], :column "email", :state :building}]
           (indices (db/primary-db tu/*node*)))
        "an absent table and column are accepted, and the index starts Building")

  (t/is (thrown-with-msg? PSQLException #"Index already exists"
                          (jdbc/execute! tu/*node* ["CREATE INDEX by_email ON orders (email)"])))

  (jdbc/execute! tu/*node* ["DROP INDEX by_email"])

  (t/is (thrown-with-msg? PSQLException #"Index does not exist"
                          (jdbc/execute! tu/*node* ["DROP INDEX by_email"])))

  (jdbc/execute! tu/*node* ["CREATE INDEX by_email ON crm.users (email)"])

  (t/is (= [{:name "by_email", :table ["crm" "users"], :column "email", :state :building}
            {:name "by_email", :table ["public" "users"], :column "email", :state :dropped}]
           (indices (db/primary-db tu/*node*)))
        "the dropped index stays in the catalog, and its name is free to declare again"))

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

        (t/is (= [{:name "by_email", :table ["public" "users"], :column "email", :state :building}
                  {:name "by_name", :table ["public" "users"], :column "name", :state :dropped}
                  {:name "by_sku", :table ["public" "orders"], :column "sku", :state :building}
                  {:name "by_total", :table ["public" "orders"], :column "total", :state :dropped}]
                 (indices (db/primary-db node)))
              "the block's indices, including one on a table no block has written, with the drop after it replayed onto them")

        (t/is (thrown-with-msg? PSQLException #"Index already exists"
                                (jdbc/execute! node ["CREATE INDEX by_email ON users (email)"])))

        (t/is (thrown-with-msg? PSQLException #"Index already exists"
                                (jdbc/execute! node ["CREATE INDEX by_sku ON orders (sku)"])))

        (jdbc/execute! node ["DROP INDEX by_email"])

        (t/is (= :dropped (->> (indices (db/primary-db node))
                               (filter (comp #{"by_email"} :name))
                               first :state)))))))
