# Demo: XTDB on a kdb+ tickerplant

XTDB attaches to a running kdb+tick tickerplant as a subscriber, the way an RDB does, and indexes what it publishes.
This walkthrough needs the XTDB repo, a JDK, and `psql` - no kdb+ licence: a stand-in tickerplant speaks the same IPC protocol.
Everything is run from the **repo root**.

The tickerplant publishes a `trade` tick (`time`, `sym`, `price`, `size`) per sym every 50ms (60 ticks/s), and about one in ten is a block trade (10,000+ shares).

## Start

Two terminals.
Each `./gradlew` command compiles on first run, then keeps running until you press Ctrl-C.

```sh
# terminal 1: XTDB, on pgwire :5432. Its state lives in modules/kdb/build/demo-state and is wiped on each start.
./gradlew :modules:xtdb-kdb:demoNode

# terminal 2: the stand-in tickerplant, on :5010. It starts publishing once something subscribes.
./gradlew :modules:xtdb-kdb:demoTickerplant
```

Wait for `Node started` in terminal 1 and `listening on 5010` in terminal 2.

## Phase 1: one subscriber, the default indexer

Attach a database called `ticks` to the tickerplant (`attach-ticks.sql`).
It uses the built-in `!Ticks` indexer: one table per kdb+ table, `_id` is the `sym`, and valid-time is the tick's `time` - so each tick is a *version* of its sym.

```sh
psql -h localhost -p 5432 xtdb -f modules/kdb/demo/attach-ticks.sql
```

Give it a few seconds, then query (`queries-ticks.sql`): the book now, a sym's tick history (`FOR ALL VALID_TIME`), and the book as it stood five seconds ago (`FOR VALID_TIME AS OF`).
Run it again a little later and watch it move.

```sh
psql -h localhost -p 5432 ticks -f modules/kdb/demo/queries-ticks.sql
```

## Phase 2: more subscribers, your own indexers

The feed is live and `ticks` is still taking it.
Attach two more databases to the *same* tickerplant, each with its own indexer - a tickerplant happily has any number of subscribers:

```sh
psql -h localhost -p 5432 xtdb -f modules/kdb/demo/attach-bars.sql
psql -h localhost -p 5432 xtdb -f modules/kdb/demo/attach-blocks.sql
```

- `bars` uses `!OhlcBars` ([OhlcBarsIndexer.kt](src/main/kotlin/xtdb/kdb/demo/OhlcBarsIndexer.kt)): one-minute open/high/low/close/size bars per sym, merged across updates.
- `blocks` uses `!BlockTrades` ([BlockTradeIndexer.kt](src/main/kotlin/xtdb/kdb/demo/BlockTradeIndexer.kt)): only trades of `minSize` (10,000) or more, with their notional value.

Both are about 100 lines each, and they're what "Writing your own indexer" in [../README.md](../README.md) points at.
A database only has what was published after it attached, so the new ones start empty and fill from now.

```sh
psql -h localhost -p 5432 bars -f modules/kdb/demo/queries-bars.sql
psql -h localhost -p 5432 blocks -f modules/kdb/demo/queries-blocks.sql
```

## Stop

Ctrl-C in each terminal.
To start over, just start `demoNode` again: it deletes `modules/kdb/build/demo-state` first.
(If you stop only the node and keep the tickerplant, restart the tickerplant too: it takes new subscribers fine, but the databases are gone.)

## With real q (optional, untested)

[q/sym.q](q/sym.q) and [q/feed.q](q/feed.q) are the same schema and feed for a real kdb+tick tickerplant on :5010.
They're written from memory and haven't been run.
Replace `demoTickerplant` with a `q tick.q sym tplog -p 5010` from a [kdb-tick](https://github.com/KxSystems/kdb-tick) checkout (with `sym.q` as its `tick/sym.q`), then `q feed.q`.

## What's here

| | |
|---|---|
| `config.yaml` | the node config for `demoNode` |
| `attach-*.sql`, `queries-*.sql` | the walkthrough |
| `src/main/kotlin/xtdb/kdb/demo/` | the stand-in tickerplant, and the two example indexers |
| `src/main/resources/META-INF/services/` | registers the example indexers, so `!OhlcBars` and `!BlockTrades` resolve |
| `q/` | optional real-q files |
