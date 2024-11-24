(ns xtdb.authn
  (:require [buddy.hashers :as hashers]
            [juxt.clojars-mirrors.hato.v0v8v2.hato.client :as http]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.api :as xt]
            [xtdb.node :as xtn])
  (:import [java.io Writer]
           [java.net URI]
           [java.time Duration]
           (xtdb.api Authenticator Authenticator$DeviceAuthResponse Authenticator$Factory Authenticator$Factory$OpenIdConnect Authenticator$Factory$UserTable Authenticator$Method Authenticator$MethodRule Xtdb$Config)))

(defn encrypt-pw [pw]
  (hashers/derive pw {:alg :argon2id}))

(defn verify-pw [node user password]
  (when password
    (when-let [{:keys [encrypted]} (first (xt/q node ["SELECT passwd AS encrypted FROM pg_user WHERE username = ?" user]))]
      (when (:valid (hashers/verify password encrypted))
        user))))

(defn- method-for [rules {:keys [remote-addr user]}]
  (some (fn [{rule-user :user, rule-address :address, :keys [method]}]
          (when (and (or (nil? rule-user) (= user rule-user))
                     (or (nil? rule-address) (= remote-addr rule-address)))
            method))
        rules))

(defn read-authn-method [method]
  (case method
    :trust Authenticator$Method/TRUST
    :password Authenticator$Method/PASSWORD
    :device-auth Authenticator$Method/DEVICE_AUTH))

(defmethod print-dup Authenticator$Method [^Authenticator$Method m, ^Writer w]
  (.write w "#xt.authn/method ")
  (print-method (case (str m) "TRUST" :trust, "PASSWORD" :password, "DEVICE_AUTH" :device-auth) w))

(defmethod print-method Authenticator$Method [^Authenticator$Method m, ^Writer w]
  (print-dup m w))

(defn ->rules-cfg [rules]
  (vec
   (for [{:keys [method user remote-addr]} rules]
     (Authenticator$MethodRule. (read-authn-method method)
                                user remote-addr))))

(defn <-rules-cfg [rules-cfg]
  (vec
   (for [^Authenticator$MethodRule auth-rule rules-cfg]
     {:method (.getMethod auth-rule)
      :user (.getUser auth-rule)
      :remote-addr (.getRemoteAddress auth-rule)})))

(defmethod xtn/apply-config! :xtdb/authn [^Xtdb$Config config, _, [tag opts]]
  (xtn/apply-config! config
                     (case tag
                       :user-table ::user-table-authn
                       :openid-connect ::openid-connect-authn
                       tag)
                     opts))

(defmethod xtn/apply-config! ::user-table-authn [^Xtdb$Config config, _, {:keys [rules]}]
  (.authn config (Authenticator$Factory$UserTable. (->rules-cfg rules))))

(defrecord UserTableAuthn [rules]
  Authenticator
  (methodFor [_ user remote-addr]
    (method-for rules {:user user, :remote-addr remote-addr}))

  (verifyPassword [_ node user password]
    (verify-pw node user password)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn ->user-table-authn [^Authenticator$Factory$UserTable cfg]
  (->UserTableAuthn (<-rules-cfg (.getRules cfg))))

(defn oauth-token [{:keys [realm-url client-id client-secret]} opts]
  (http/post (str realm-url "/protocol/openid-connect/token")
             {:form-params (into {:grant_type "password"
                                  :client_id client-id
                                  :client_secret client-secret}
                                 opts)
              :throw-exceptions false
              :coerce :always
              :as :json}))

(defn oauth-userinfo [{:keys [realm-url]} token]
  (let [{:keys [status body]} (http/get (str realm-url "/protocol/openid-connect/userinfo")
                                        {:oauth-token token
                                         :throw-exceptions false
                                         :as :json})]
    (when (= 200 status)
      body)))

(defn oauth-device-info [{:keys [realm-url client-id client-secret]}]
  (let [{:keys [status body]} (http/post (str realm-url "/protocol/openid-connect/auth/device")
                                         {:form-params {:grant_type "device_auth"
                                                        :client_id client-id
                                                        :client_secret client-secret
                                                        :scope "openid"}
                                          :throw-exceptions false
                                          :as :json})]
    (when (= 200 status)
      body)))

(defrecord DeviceAuthResponse [authn url device-code ^Duration interval]
  Authenticator$DeviceAuthResponse
  (getUrl [_] url)

  (await [_]
    (loop []
      (let [{:keys [status body]} (oauth-token authn
                                               {:grant_type "urn:ietf:params:oauth:grant-type:device_code"
                                                :device_code device-code})]
        (case (long status)
          200 (:sub (oauth-userinfo authn (:access_token body)))
          400 (when (= "authorization_pending" (:error body))
                (Thread/sleep interval)
                (recur)))))))

(defrecord OpenIdConnect [realm-url client-id client-secret rules]
  Authenticator
  (methodFor [_ user remote-addr]
    (method-for rules {:user user, :remote-addr remote-addr}))

  (verifyPassword [this _node user password]
    (let [{:keys [status body]} (oauth-token this {:grant_type "password", :scope "openid"
                                                   :username user, :password password})]
      (when (= status 200)
        (:sub (oauth-userinfo this (:access_token body))))))

  (startDeviceAuth [this _user]
    (let [{:keys [device_code verification_uri_complete interval]} (oauth-device-info this)]
      (->DeviceAuthResponse this (.toURL (URI. verification_uri_complete))
                            device_code (Duration/ofSeconds interval)))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn ->oidc-authn [^Authenticator$Factory$OpenIdConnect cfg]
  (->OpenIdConnect (.getRealmUrl cfg) (.getClientId cfg) (.getClientSecret cfg) (<-rules-cfg (.getRules cfg))))

(defmethod xtn/apply-config! ::openid-connect-authn [^Xtdb$Config config, _, {:keys [realm-url client-id client-secret rules]}]
  (.authn config (Authenticator$Factory$OpenIdConnect. (.toURL (URI. realm-url)) client-id client-secret (->rules-cfg rules))))

(defmethod ig/init-key :xtdb/authn [_ ^Authenticator$Factory authn-factory]
  (.open authn-factory))

(def default-authn
  (ig/init-key :xtdb/authn (Authenticator$Factory$UserTable.)))
