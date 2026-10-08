# XTDB as a kdb+ tickerplant subscriber

XTDB connects to a running kdb+tick tickerplant like any other subscriber (an RDB, say): it calls `.u.sub`, and each `upd` the tickerplant publishes becomes an XTDB transaction.
Nothing is installed on the kdb+ side.

Live subscription only: ticks published while XTDB is down are missed.
Each tx's token records the tickerplant's log file and message count, so replay from the log could be added later.

## Attaching

```sql
ATTACH DATABASE ticks WITH $$
  log: !Local
    path: /var/lib/xtdb/ticks-log
  storage: !Local
    path: /var/lib/xtdb/ticks-storage
  externalSource: !Kdb
    host: localhost       # default
    port: 5010
    # username / password: only if the tickerplant needs them (sent in the clear)
    # tables: [trade]     # default: all of them
    # syms: [AAPL, MSFT]  # default: all of them
    # indexer: !Ticks     # the default - see below
    #   schema: public
    #   idColumn: sym
    #   timeColumn: time
    #   timezone: Europe/London   # default: the JVM's
$$
```

## Default mapping (`!Ticks`)

One XTDB table per kdb+ table, one document per tick.

- `_id` is `sym`, so the table has one entity per instrument and every tick is a new *version* of it.
- valid-from is `time`, so `FOR VALID_TIME AS OF` shows the book as it stood at any instant, and `FOR ALL VALID_TIME` is the tick history.
  kdb+tick's tickerplant prepends `time` as a *timespan* (time since midnight), so that's added to the date of the tx's system time (in `timezone`).
- The document holds every kdb+ column, plus `_id`.

Two ticks for one sym in the same microsecond collide: the later one wins.

Supported kdb+ types: boolean, byte, short, int, long, real, float, char, symbol, string, timestamp, timespan, date, time, guid.

To map differently, write your own indexer - see below.

## Writing your own indexer

Implement `xtdb.kdb.TickIndexer`:

```kotlin
fun indexTicks(table: String, rel: RelationReader, openTx: OpenTx)
```

It's called once per `upd`, inside the tx for that update.
`rel` is the update as an Arrow relation, one column per kdb+ column; write whatever you like into `openTx`, and read back through `openTx.openQuery`.
Register its `Factory` (a `@Serializable` data class with an `@SerialName("!YourTag")`) through a `TickIndexer.Registration` listed in `META-INF/services/xtdb.kdb.TickIndexer$Registration`, then select it with `indexer: !YourTag` under `externalSource: !Kdb`.

`modules/kdb/demo/src/main/kotlin/xtdb/kdb/demo/` has two worked examples - `OhlcBarsIndexer` (one-minute bars per sym, merged across updates) and `BlockTradeIndexer` (a filtered table) - with tests in `KdbTickerplantSourceTest.kt`.
`DefaultTickIndexer` is the other.

## Demo

[demo/](demo/README.md) is a self-contained walkthrough from the repo root: two gradle commands, a stand-in tickerplant (no q needed), the default indexer and two example ones.

## Tests

```sh
./gradlew :modules:xtdb-kdb:test
```

These run against `FakeTickerplant` (in `demo/`), an in-JVM server speaking kdb+ IPC through [javakdb](https://github.com/KxSystems/javakdb) (`com.kx:javakdb`, Apache 2.0).
No `q` needed.
