(ns xtdb.authn-test
  (:require [clojure.test :as t]
            [xtdb.authn :as authn]
            [xtdb.node :as xtn]
            [xtdb.test-util :as tu])
  (:import dasniko.testcontainers.keycloak.KeycloakContainer
           [java.net URI]
           (org.keycloak.representations.idm ClientRepresentation CredentialRepresentation UserRepresentation)
           xtdb.api.Authenticator))

(defonce ^KeycloakContainer container
  (KeycloakContainer. "quay.io/keycloak/keycloak:26.0"))

(defn- seed! [^KeycloakContainer c]
  ;; we can in theory do this with a realm export/import
  (with-open [admin-client (.getKeycloakAdminClient c)]
    {:users (let [users (-> admin-client
                            (.realm "master")
                            (.users))]

              (.create users
                       (doto (UserRepresentation.)
                         (.setEnabled true)
                         (.setUsername "test-user")
                         (.setEmail "test@example.com")))

              (let [user-id (some-> ^UserRepresentation (first (.search users "test-user"))
                                    (.getId))]
                (-> (.get users user-id)
                    (.resetPassword (doto (CredentialRepresentation.)
                                      (.setType "password")
                                      (.setValue "password124"))))
                {:test-user user-id}))

     :client (let [clients (-> admin-client
                               (.realm "master")
                               (.clients))]
               (.create clients (doto (ClientRepresentation.)
                                  (.setName "xtdb")
                                  (.setClientId "xtdb")
                                  (.setSecret "xtdb-secret")
                                  (.setDirectAccessGrantsEnabled true)))
               {:client-id "xtdb", :client-secret "xtdb-secret"})}))

(comment
  ;; start these once when you're developing,
  ;; save the time of starting the container for each run
  (.start container)

  (seed! container)

  (.getAuthServerUrl container)

  (.stop container))

(t/use-fixtures :once
  (fn [f]
    (tu/with-container container
      (fn [_]
        (f)))))

(t/deftest test-oidc
  (with-open [node (xtn/start-node)]
    (let [test-user-id (-> (seed! container)
                           (get-in [:users :test-user]))
          ^Authenticator authn (authn/->OpenIdConnect (.toURL (URI. (str (.getAuthServerUrl container) "/realms/master")))
                                                      "xtdb" "xtdb-secret"
                                                      [{:method #xt.authn/method :password}])]
      (t/is (= #xt.authn/method :password
               (.methodFor authn "test-user" "127.0.0.1" nil)))

      (t/is (= test-user-id (.verifyPassword authn node "test-user" "password124")))
      (t/is (nil? (.verifyPassword authn node "test-user" "password123")))
      (t/is (nil? (.verifyPassword authn node "testy-mcgee" "password123"))))))
