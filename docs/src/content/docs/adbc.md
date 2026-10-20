---
title: 'ADBC: Arrow-native client access'
description: Connect to XTDB via Arrow Database Connectivity, in-process or over FlightSQL.
---

XTDB exposes [ADBC](https://arrow.apache.org/adbc/), the Arrow Database Connectivity standard.
If your pipeline is Arrow-shaped throughout (pandas, polars, DuckDB, DataFusion, `arrow-rs`, pyarrow), ADBC fits straight in: query results land in your client as Arrow batches with no row-by-row decode, and bulk-ingesting an Arrow table happens in a single round trip.

XTDB's storage layer, query engine, and wire format are all Arrow-native, and ADBC carries that through to the client.

## How a client reaches XTDB

ADBC is a client API; [Arrow Flight SQL](https://arrow.apache.org/docs/format/FlightSql.html) is the gRPC protocol it travels over when the database is in another process.
XTDB serves both: a JVM application running a node in-process uses the node's own ADBC connection, and everything else connects to the node's Flight SQL server.

```d2
direction: right

jvm: Your JVM process {
  app: Your application
  node: XTDB node {
    conn: "AdbcConnection\n(node.connect())"
    engine: Query engine + storage {
      style.bold: true
    }
    fsql: "Flight SQL server\n(port 3000)"
    conn -> engine
    fsql -> conn
  }
  app -> node.conn: in-process, no network hop
}

remote: Any other process {
  py: "Python, R, C/C++, Rust"
  go: Go
  java: "Java / Kotlin"
  cs: "C#"
  gofs: "Apache ADBC Flight SQL driver\n(Go, with a C ABI)"
  javafs: "Apache ADBC Flight SQL driver\n(Java)"
  csfs: "Apache ADBC Flight SQL driver\n(C#)"
  py -> gofs
  go -> gofs
  java -> javafs
  cs -> csfs
}

remote.gofs -> jvm.node.fsql: Flight SQL over gRPC {style.stroke-dash: 3}
remote.javafs -> jvm.node.fsql {style.stroke-dash: 3}
remote.csfs -> jvm.node.fsql {style.stroke-dash: 3}
```

- **There is no XTDB-specific driver to install for a remote client.**
  Each language uses the Apache ADBC Flight SQL driver, pointed at the node's Flight SQL port (`3000` in the Docker images).
  The Python, R, C/C++ and Rust drivers all load the same Go-built native library, so they behave alike.

- **The Flight SQL server is a thin shim over the in-process connection.**
  Both paths run the same `AdbcConnection` and `AdbcStatement` code, so the [reference](/adbc/reference) describes both, barring the per-client caveats it notes.

| You are… | Use | Start here |
|---|---|---|
| A JVM application running the node in-process | `node.connect()`, or `xt/open-adbc-conn` from Clojure | [Kotlin](/drivers/kotlin#arrow-native-access-via-adbc), [Java](/drivers/java#arrow-native-access-via-adbc), [Clojure](/drivers/clojure#arrow-native-access-via-adbc) |
| A JVM application talking to a remote node | the Apache ADBC Java Flight SQL driver, or `xtdb.flight-sql/open-conn` from Clojure | [Java](/drivers/java#over-the-wire), [Kotlin](/drivers/kotlin#over-the-wire), [Clojure](/drivers/clojure#over-the-wire) |
| Python | `adbc-driver-flightsql` | [tutorial](/adbc/tutorial), [Python](/drivers/python#arrow-native-access-via-adbc) |
| Go | `github.com/apache/arrow-adbc/go/adbc/driver/flightsql` | [Go](/drivers/go#arrow-native-access-via-adbc) |
| Rust | `adbc_core` + `adbc_driver_manager`, loading the native Flight SQL driver | [Rust pipeline guide](/adbc/guides/rust-arrow-pipeline) |
| R, C/C++, C# | the Flight SQL driver for that language | [Apache ADBC driver matrix](https://arrow.apache.org/adbc/current/driver/flight_sql.html) |

## Running more than one node

A Flight SQL client's state lives on the node it connected to, so behind a load balancer each client has to keep reaching that same node.

- **Sessions, transactions and prepared statements are held in memory by the node that created them.**
  The [session cookie](/adbc/reference#session-options), a transaction handle and a prepared-statement handle each mean something only to that node.
  Presented to another node, the call fails with `NOT_FOUND` (`unknown session`, `unknown transaction`, `unknown prepared statement`).

- **The ADBC drivers send the session cookie back on every call after their first**, so in practice every call from a client after its first has to reach the same node.
  Route by client connection: an L4 (TCP) balancer pins a gRPC channel to one backend for its lifetime, as does an L7 balancer with session affinity on the `arrow_flight_session_id` cookie.
  An L7 balancer that spreads a channel's individual calls across backends, gRPC's usual mode, breaks the session on the first call it moves.

- **Moving to another node starts a new session.**
  A client whose node goes away loses its session, its open transaction and its prepared statements with it, and has to reconnect: open a new ADBC connection, which starts a session on whichever node it reaches.

- **Committed data is not node-local.**
  Every node indexes the same databases, so a reconnecting client loses only its session state.
  Each node indexes asynchronously, though, so a write committed through one node can take a moment to become visible through another.

## Where to next

- **[Tutorial](/adbc/tutorial)**: an end-to-end walkthrough covering install, connect, ingest, query, transactions, and prepared statements.
- **[Reference](/adbc/reference)**: the supported ADBC surface in detail.
  Which calls work, which don't, and the per-client caveats (Python vs Rust vs Java).
- **[How-to guides](/adbc/guides)**: task-shaped recipes.
  "Bulk-load Parquet via ADBC", "round-trip a pandas DataFrame", "stream results into DuckDB".
- **[Examples](https://github.com/xtdb/xtdb/tree/main/docs/src/content/docs/adbc/examples)**: minimal runnable hello-world programs in each supported language.

## ADBC vs pgwire

XTDB also exposes a [PostgreSQL wire-compatible server](/drivers).
Use whichever fits your stack:

| | pgwire | ADBC (FlightSQL) |
|---|---|---|
| Tooling | psql, JDBC, psycopg, every PG client | ADBC clients (Python, Rust, Go, C, R, Java) |
| Wire format | Postgres text/binary | Arrow |
| Bulk ingest | row-at-a-time `INSERT` / `executemany` | `cur.adbc_ingest(arrow_table)`, one round trip |
| Result format | rows decoded one at a time | Arrow batches streaming straight into your client |
| Type fidelity | Postgres-mapped | Arrow-native (preserves Arrow types) |

Both backends talk to the same XTDB node, so you can pick per workload and mix freely.
