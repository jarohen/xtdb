(defproject com.xtdb.labs/xtdb-events "<inherited>"
  :description "XTDB Events"

  :plugins [[lein-parent "0.3.8"]]

  :parent-project {:path "../../project.clj"
                   :inherit [:version :repositories :deploy-repositories
                             :managed-dependencies
                             :pedantic? :global-vars
                             :license :url :pom-addition]}

  :scm {:dir "../.."}

  :dependencies [[org.clojure/clojure "1.10.3"]
                 [com.xtdb/xtdb-core]
                 [org.apache.kafka/kafka-clients "3.1.0"]
                 [com.cognitect/transit-clj "1.0.329" :exclusions [org.msgpack/msgpack]]]

  :profiles {:dev {:jvm-opts ["-Dlogback.configurationFile=../../resources/logback-test.xml"]
                   :dependencies [[ch.qos.logback/logback-classic "1.2.3"]]}})
