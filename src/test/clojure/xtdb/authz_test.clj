(ns xtdb.authz-test
  (:require [clojure.test :as t]
            [next.jdbc :as jdbc]
            [xtdb.node :as xtn]
            [xtdb.pgwire-test :as pgw-test]
            [xtdb.test-util :as tu]
            [xtdb.util :as util])
  (:import java.sql.Connection))

(def ^:private membership-q
  "SELECT r.rolname AS role, u.rolname AS member
   FROM pg_auth_members m
   JOIN pg_roles r ON r.oid = m.roleid
   JOIN pg_roles u ON u.oid = m.member
   ORDER BY role, member")

(t/deftest grant-revoke-round-trip
  (with-open [node (xtn/start-node)
              conn (jdbc/get-connection node)]
    (jdbc/execute! conn ["GRANT analyst TO alice"])
    (jdbc/execute! conn ["GRANT analyst TO bob"])
    (jdbc/execute! conn ["GRANT admin TO alice"])

    (t/is (= [{:role "admin", :member "alice"}
              {:role "analyst", :member "alice"}
              {:role "analyst", :member "bob"}]
             (pgw-test/q conn [membership-q])))

    (t/is (= [{:user "alice", :role "admin"}
              {:user "alice", :role "analyst"}
              {:user "bob", :role "analyst"}]
             (pgw-test/q conn ["SELECT \"user\", role FROM xt.role_membership ORDER BY \"user\", role"])))

    (t/testing "granted roles and member users appear in pg_roles"
      (t/is (= [{:rolname "admin", :rolsuper false, :rolcanlogin false}
                {:rolname "alice", :rolsuper false, :rolcanlogin true}
                {:rolname "analyst", :rolsuper false, :rolcanlogin false}
                {:rolname "bob", :rolsuper false, :rolcanlogin true}
                {:rolname "xtdb", :rolsuper true, :rolcanlogin true}]
               (pgw-test/q conn ["SELECT rolname, rolsuper, rolcanlogin FROM pg_roles ORDER BY rolname"]))))

    (jdbc/execute! conn ["REVOKE analyst FROM alice"])

    (t/is (= [{:role "admin", :member "alice"}
              {:role "analyst", :member "bob"}]
             (pgw-test/q conn [membership-q])))

    (t/testing "GRANT/REVOKE on the same pair supersede - re-grant restores the membership"
      (jdbc/execute! conn ["GRANT analyst TO alice"])
      (t/is (= [{:role "admin", :member "alice"}
                {:role "analyst", :member "alice"}
                {:role "analyst", :member "bob"}]
               (pgw-test/q conn [membership-q]))))

    (t/testing "GRANT and REVOKE are idempotent"
      (jdbc/execute! conn ["GRANT analyst TO alice"])
      (jdbc/execute! conn ["REVOKE reporter FROM carol"])
      (t/is (= [{:role "admin", :member "alice"}
                {:role "analyst", :member "alice"}
                {:role "analyst", :member "bob"}]
               (pgw-test/q conn [membership-q]))))))

;; the acceptance test on #5683: REVOKE is a system-time soft-close, so membership
;; history stays queryable as-of any past system-time.
(t/deftest membership-queryable-as-of-system-time
  (with-open [node (xtn/start-node)
              conn (jdbc/get-connection node)]
    (jdbc/execute! conn ["GRANT analyst TO alice"])
    (jdbc/execute! conn ["REVOKE analyst FROM alice"])

    (let [[{t1 :t1, t2 :t2}] (pgw-test/q conn ["SELECT _system_from AS t1, _system_to AS t2
                                                FROM xt.role_membership FOR ALL SYSTEM_TIME"])]
      (t/is (some? t2) "the revoke closed the row in system time")

      (t/is (= [{:user "alice", :role "analyst"}]
               (pgw-test/q conn [(str "SELECT \"user\", role FROM xt.role_membership FOR SYSTEM_TIME AS OF TIMESTAMP '" t1 "'")]))
            "between grant and revoke: membership in force")

      (t/is (= []
               (pgw-test/q conn [(str "SELECT \"user\", role FROM xt.role_membership FOR SYSTEM_TIME AS OF TIMESTAMP '" t2 "'")]))
            "after the revoke: membership closed")

      (t/is (= []
               (pgw-test/q conn ["SELECT \"user\", role FROM xt.role_membership"]))
            "current view: membership closed"))))

(t/deftest non-superuser-cannot-alter-role-membership
  ;; default !SingleRootUser without a password runs TRUST, so 'eve' can connect -
  ;; but only the root 'xtdb' user is a superuser.
  (with-open [node (xtn/start-node)
              conn (.build (-> (.createConnectionBuilder node)
                               (.user "eve")))]
    (t/is (= {:sql-state "08P01",
              :message "Only a superuser may GRANT/REVOKE role membership.",
              :detail #xt/error [:incorrect :xtdb.pgwire/not-authorized
                                 "Only a superuser may GRANT/REVOKE role membership."
                                 {:user "eve"}]}
             (pgw-test/reading-ex
              (jdbc/execute! conn ["GRANT analyst TO alice"]))))))

(t/deftest disallow-role-membership-in-tx
  (with-open [node (xtn/start-node)
              conn (jdbc/get-connection node)]
    (jdbc/execute! conn ["BEGIN"])
    (try
      (t/is (= {:sql-state "08P01",
                :message "Cannot GRANT/REVOKE a role in a transaction.",
                :detail #xt/error [:incorrect :xtdb.pgwire/role-membership-in-tx
                                   "Cannot GRANT/REVOKE a role in a transaction."
                                   {:role "analyst", :user "alice"}]}
               (pgw-test/reading-ex
                (jdbc/execute! conn ["GRANT analyst TO alice"]))))
      (finally
        (jdbc/execute! conn ["ROLLBACK"])))))

(t/deftest disallow-role-membership-on-secondary-db
  (with-open [node (xtn/start-node)]
    (with-open [conn (jdbc/get-connection node)]
      (jdbc/execute! conn ["ATTACH DATABASE new_db"]))

    (with-open [^Connection conn (.build (-> (.createConnectionBuilder node)
                                             (.database "new_db")))]
      (t/is (= {:sql-state "08P01",
                :message "Can only manage role membership when connected to the primary 'xtdb' database.",
                :detail #xt/error [:incorrect :xtdb.pgwire/role-membership-on-secondary
                                   "Can only manage role membership when connected to the primary 'xtdb' database."
                                   {:db "new_db"}]}
               (pgw-test/reading-ex
                (jdbc/execute! conn ["GRANT analyst TO alice"])))))))

(t/deftest disallow-dml-on-role-membership-table
  (with-open [node (xtn/start-node)
              conn (jdbc/get-connection node)]
    (t/is (thrown-with-msg? Exception #"Cannot write to table: xt[./]role_membership"
                            (jdbc/execute! conn ["INSERT INTO xt.role_membership RECORDS {_id: 1, \"user\": 'mallory', role: 'admin'}"])))))

(t/deftest membership-survives-restart
  (let [node-dir (util/->path "target/authz-test/restart")
        node-opts {:log [:local {:path (.resolve node-dir "log")}]
                   :storage [:local {:path (.resolve node-dir "storage")}]}]
    (util/delete-dir node-dir)

    (t/testing "memberships only on the log: replay rebuilds the catalog"
      (with-open [node (xtn/start-node node-opts)
                  conn (jdbc/get-connection node)]
        (jdbc/execute! conn ["GRANT analyst TO alice"])
        (jdbc/execute! conn ["GRANT analyst TO bob"])
        (jdbc/execute! conn ["REVOKE analyst FROM bob"]))

      (with-open [node (xtn/start-node node-opts)
                  conn (jdbc/get-connection node)]
        (t/is (= [{:role "analyst", :member "alice"}]
                 (pgw-test/q conn [membership-q])))))

    (t/testing "memberships flushed to a block: the startup scan rebuilds the catalog"
      (with-open [node (xtn/start-node node-opts)
                  conn (jdbc/get-connection node)]
        (jdbc/execute! conn ["GRANT admin TO alice"])
        (tu/flush-block! node))

      (with-open [node (xtn/start-node node-opts)
                  conn (jdbc/get-connection node)]
        (t/is (= [{:role "admin", :member "alice"}
                  {:role "analyst", :member "alice"}]
                 (pgw-test/q conn [membership-q])))))))
