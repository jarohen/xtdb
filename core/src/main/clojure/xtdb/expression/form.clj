(ns xtdb.expression.form
  (:require [xtdb.error :as err]
            [xtdb.time :as time])
  (:import clojure.lang.MapEntry))

#_{:clj-kondo/ignore [:unused-binding]}
(defmulti parse-list-form
  (fn [[f & args] env]
    f)
  :default ::default)

(defn form->expr [form {:keys [col-types param-types locals] :as env}]
  (cond
    (symbol? form) (cond
                     (= 'xtdb/end-of-time form) {:op :literal, :literal time/end-of-time}
                     (contains? locals form) {:op :local, :local form}
                     (contains? param-types form) {:op :param, :param form, :param-type (get param-types form)}
                     (contains? col-types form) {:op :variable, :variable form, :var-type (get col-types form)}
                     :else (throw (err/illegal-arg :xtdb.expression/unknown-symbol
                                                   {::err/message (format "Unknown symbol: '%s'" form)
                                                    :symbol form})))

    (map? form) (do
                  (when-not (every? keyword? (keys form))
                    (throw (err/illegal-arg :xtdb.expression/parse-error
                                            {::err/message (str "keys to struct must be keywords: " (pr-str form))
                                             :form form})))
                  {:op :struct,
                   :entries (->> (for [[k v-form] form]
                                   (MapEntry/create k (form->expr v-form env)))
                                 (into {}))})

    (vector? form) {:op :list, :elements (mapv #(form->expr % env) form)}
    (set? form) {:op :set, :elements (mapv #(form->expr % env) form)}

    (seq? form) (parse-list-form form env)

    :else {:op :literal, :literal form}))

(defmethod parse-list-form 'if [[_ & args :as form] env]
  (when-not (= 3 (count args))
    (throw (err/illegal-arg :xtdb.expression/arity-error
                            {::err/message (str "'if' expects 3 args: " (pr-str form))
                             :form form})))

  (let [[pred then else] args]
    {:op :if,
     :pred (form->expr pred env),
     :then (form->expr then env),
     :else (form->expr else env)}))

(defmethod parse-list-form 'let [[_ & args :as form] env]
  (when-not (= 2 (count args))
    (throw (err/illegal-arg :xtdb.expression/arity-error
                            {::err/message (str "'let' expects 2 args - bindings + body"
                                                 (pr-str form))
                             :form form})))

  (let [[bindings body] args]
    (when-not (or (nil? bindings) (sequential? bindings))
      (throw (err/illegal-arg :xtdb.expression/parse-error
                              {::err/message (str "'let' expects a sequence of bindings: "
                                                   (pr-str form))
                               :form form})))

    (if-let [[local expr-form & more-bindings] (seq bindings)]
      (do
        (when-not (symbol? local)
          (throw (err/illegal-arg :xtdb.expression/parse-error
                                  {::err/message (str "bindings in `let` should be symbols: "
                                                       (pr-str local))
                                   :binding local})))
        {:op :let
         :local local
         :expr (form->expr expr-form env)
         :body (form->expr (list 'let more-bindings body)
                           (update env :locals (fnil conj #{}) local))})

      (form->expr body env))))

(defmethod parse-list-form '. [[_ & args :as form] env]
  (when-not (= 2 (count args))
    (throw (err/illegal-arg :xtdb.expression/arity-error
                            {::err/message (str "'.' expects 2 args: " (pr-str form))
                             :form form})))

  (let [[struct field] args]
    (when-not (or (symbol? field) (keyword? field))
      (throw (err/illegal-arg :xtdb.expression/arity-error
                              {::err/message (str "'.' expects symbol or keyword fields: " (pr-str form))
                               :form form})))

    {:op :call
     :f :get-field
     :args [(form->expr struct env)]
     :field (symbol field)}))

(defmethod parse-list-form '.. [[_ & args :as form] env]
  (let [[struct & fields] args]
    (when-not (seq fields)
      (throw (err/illegal-arg :xtdb.expression/arity-error
                              {::err/message (str "'..' expects at least 2 args: " (pr-str form))
                               :form form})))
    (when-not (every? #(or (symbol? %) (keyword? %)) fields)
      (throw (err/illegal-arg :xtdb.expression/parse-error
                              {::err/message (str "'..' expects symbol or keyword fields: " (pr-str form))
                               :form form})))
    (reduce (fn [struct-expr field]
              {:op :call
               :f :get-field
               :args [struct-expr]
               :field (symbol field)})
            (form->expr struct env)
            (rest args))))

(defmethod parse-list-form 'cast [[_ expr target-type cast-opts] env]
  {:op :call
   :f :cast
   :args [(form->expr expr env)]
   :target-type target-type
   :cast-opts cast-opts})

(defmethod parse-list-form ::default [[f & args] env]
  {:op :call
   :f (keyword (namespace f) (name f))
   :args (mapv #(form->expr % env) args)})
