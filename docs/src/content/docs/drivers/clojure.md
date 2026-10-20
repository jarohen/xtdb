---
title: Using XTDB from Clojure
---

Clojure users can execute SQL queries using standard JDBC tooling, via XTDB's PostgreSQL wire-compatible server.
Additionally, there is an [XTDB Clojure API](#clojure-api) for both SQL and XTQL queries.

## JDBC

SQL queries can be executed using the XTDB JDBC driver:

```clojure
{:deps {org.clojure/clojure {:mvn/version "1.12.0"} ; minimum reqmt

        ;; https://mvnrepository.com/artifact/com.xtdb/xtdb-api
        com.xtdb/xtdb-api {:mvn/version "XTDB_VERSION"}

        ;; https://mvnrepository.com/artifact/com/github/seancorfield/next.jdbc
        ;; other JDBC libraries are available
        com.github.seancorfield/next.jdbc {:mvn/version "1.3.955"}}}
```

Then, once you've [started the XTDB node](/intro/installation-via-docker), follow the usual Clojure JDBC process for connecting to a PostgreSQL database:

``` clojure
(require '[next.jdbc :as jdbc]
         '[next.jdbc.result-set :as jdbc-rs]
         '[xtdb.next.jdbc :as xt-jdbc])

;; this is relatively low-level code - the usual connection pooling
;; and SQL abstraction libraries can be used too.

(with-open [conn (jdbc/get-connection "jdbc:xtdb://localhost/xtdb")]
  (jdbc/execute! conn ["INSERT INTO users RECORDS {_id: 'jms', first_name: 'James'}"])
  (jdbc/execute! conn ["INSERT INTO users RECORDS ?" {:xt/id "joe", :first-name "Joe"}])

  (prn (jdbc/execute! conn ["SELECT * FROM users"]))
  ;; => [{:_id "joe", :first_name "Joe"}
  ;;     {:_id "jms", :first_name "James"}]

  ;; optional: use the XT col-reader to transform nested values too
  (prn (jdbc/execute! conn ["SELECT * FROM users"]
         {:builder-fn xt-jdbc/builder-fn}))

  ;; => [{:xt/id "joe", :first-name "Joe"}
  ;;     {:xt/id "jms", :first-name "James"}]
  )
```

## Clojure API

[API documentation](/drivers/clojure/codox/xtdb.api.html)

The XTDB Clojure API supports both SQL and XTQL queries.
You can run XTDB nodes either in-process, or connect to a remote XTDB server via the Postgres wire-compatible server.

``` clojure
{:deps {org.clojure/clojure {:mvn/version "1.12.0"} ; minimum reqmt

        ;; minimum JDK: 21

        ;; xtdb-api for the main public API, for both remote and in-process nodes
        com.xtdb/xtdb-api {:mvn/version "XTDB_VERSION"}

        ;; xtdb-core for running an in-process (test) node
        com.xtdb/xtdb-core {:mvn/version "XTDB_VERSION"}}

 ;; JVM options required for in-process node
 :aliases {:xtdb {:jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
                             "--enable-native-access=ALL-UNNAMED"
                             "-Dio.netty.tryReflectionSetAccessible=true"
                             "-Dio.netty.noUnsafe=false"]}}}
```

For Maven (pom.xml) or Gradle (build.gradle.kts), see the [Java getting-started guide](/drivers/java).

From here, check out the [`xtdb.api` API docs](/drivers/clojure/codox/xtdb.api.html) to submit data and run queries.

### In process

If you're running a JVM, you can also use XTDB directly, in-process.
In-process XTDB is particularly useful for testing and interactive development - you can start an in-memory node quickly and with little hassle, which makes it a great tool for unit tests and REPL experimentation.

1. First, ensure you are running JDK 21+ and then add the `xtdb-core` library to your dependency manager.
2. You'll also need to add the following JVM arguments to run Apache Arrow (included in the `:xtdb` deps.edn alias above):
   - `--add-opens=java.base/java.nio=ALL-UNNAMED`
   - `--enable-native-access=ALL-UNNAMED`
   - `-Dio.netty.tryReflectionSetAccessible=true`
   - `-Dio.netty.noUnsafe=false` — required on JDK 25+, where Netty otherwise turns off the `sun.misc.Unsafe` access Arrow's allocator depends on.
3. Once you have a REPL (started with `clj -A:xtdb` this time), you can create an in-memory XTDB node with:

``` clojure
(require '[xtdb.node :as xtn]
         '[xtdb.api :as xt])

(with-open [node (xtn/start-node)]
  (xt/status node)

  ;; ...
  )
```

This node uses exactly the same API as the remote client - so, again, from here, check out the [`xtdb.api` API docs](/drivers/clojure/codox/xtdb.api.html) to submit data and run queries.

## Arrow-native access via ADBC

An in-process node is already an [ADBC](/adbc) database: `xt/q` and `xt/execute-tx` against a node run through its ADBC connection, decoding the Arrow results into Clojure data, with no pgwire in between.

`xt/open-adbc-conn` opens a connection of your own on the node, for when you want one session across several calls - a transaction, or reads that see your own writes.
It returns an `org.apache.arrow.adbc.core.AdbcConnection`, which the `xtdb.api` functions accept, and which you close:

```clojure
(require '[xtdb.api :as xt]
         '[xtdb.node :as xtn])

(with-open [node (xtn/start-node)
            conn (xt/open-adbc-conn node)]
  (xt/execute-tx conn [[:put-docs :users {:xt/id "jms", :name "James"}]])
  (xt/q conn "SELECT * FROM users"))

;; => [{:xt/id "jms", :name "James"}]
```

For the results as Arrow rather than Clojure data, use the connection through the ADBC Java API directly:

```clojure
(import '(org.apache.arrow.adbc.core AdbcConnection)
        '(org.apache.arrow.vector.ipc ArrowReader))

(with-open [stmt (.createStatement ^AdbcConnection conn)]
  (.setSqlQuery stmt "SELECT * FROM users")
  (with-open [res (.executeQuery stmt)]
    (let [rdr ^ArrowReader (.getReader res)]
      (while (.loadNextBatch rdr)
        (println (.contentToTSVString (.getVectorSchemaRoot rdr)))))))
```

### Over the wire

`xtdb.flight-sql/open-conn` connects to a remote node's [Flight SQL server](/adbc/reference#over-the-wire-flightsql), and returns a connection the `xtdb.api` functions accept, as `xt/open-adbc-conn`'s does.
It lives in `xtdb-core`, so add that alongside `xtdb-api`, and close the connection when you're done:

```clojure
(require '[xtdb.api :as xt]
         '[xtdb.flight-sql :as fsql])

(with-open [conn (fsql/open-conn {:host "localhost", :port 3000})]
  (xt/q conn "SELECT * FROM users"))
```

- **`:host`** defaults to `127.0.0.1`, **`:port`** is the node's Flight SQL port (`3000` in the Docker images), and **`:dbname`** selects the database (default `xtdb`).
- **The connection holds a Flight SQL session**, so its reads see its own writes and its transactions aren't shared with other clients.
  Behind a load balancer, it has to keep reaching the same node - see [Running more than one node](/adbc#running-more-than-one-node).

## Babashka

[Babashka](https://babashka.org/) is a fast-starting Clojure scripting environment that can also connect to XTDB. See the [driver-examples repository](https://github.com/xtdb/driver-examples) for Babashka-specific examples.

## Examples

For more examples and tests, see the [XTDB driver-examples repository](https://github.com/xtdb/driver-examples), which contains comprehensive test suites demonstrating various features and use cases.
