package xtdb.kdb

import com.kx.c
import io.kotest.assertions.nondeterministic.eventually
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import xtdb.XtdbInternal
import xtdb.api.Xtdb
import xtdb.api.log.Log.Companion.localLog
import xtdb.api.storage.Storage
import xtdb.kdb.demo.FakeTickerplant
import xtdb.kdb.proto.KdbSourceToken
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KdbTickerplantSourceTest {

    @TempDir
    lateinit var tmp: Path

    private fun table(vararg cols: Pair<String, Any>) = c.Flip(cols.map { it.first }.toTypedArray(), cols.map { it.second }.toTypedArray())

    // kdb+tick's sym.q: the tickerplant prepends `time` as a timespan since midnight
    private val schemas = mapOf(
        "trade" to table(
            "time" to arrayOf<c.Timespan>(), "sym" to arrayOf<String>(),
            "price" to doubleArrayOf(), "size" to intArrayOf(),
        ),
        "quote" to table(
            "time" to arrayOf<Instant>(), "sym" to arrayOf<String>(),
            "bid" to doubleArrayOf(), "ask" to doubleArrayOf(),
        ),
    )

    private fun secs(s: Long) = c.Timespan(s * 1_000_000_000L)

    private fun query(node: Xtdb, sql: String): List<Map<String, Any?>> =
        node.createConnectionBuilder().database("tp").build().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
                    buildList { while (rs.next()) add(cols.associateWith { rs.getObject(it) }) }
                }
            }
        }

    private fun withTickerplantAndNode(
        sourceYaml: String = "", schemas: Map<String, c.Flip> = this.schemas, f: suspend (FakeTickerplant, Xtdb) -> Unit,
    ) = runTest(timeout = 120.seconds) {
        FakeTickerplant(schemas).use { tp ->
            Xtdb.openNode {
                server { port = 0 }
                log(localLog(tmp.resolve("log")))
                storage(Storage.local(tmp.resolve("storage")))
            }.use { node ->
                node.createConnectionBuilder().build().use { conn ->
                    conn.createStatement().use {
                        it.execute(
                            """
                            ATTACH DATABASE tp WITH $$
                                storage: !Local
                                  path: ${tmp.resolve("tp-storage")}
                                log: !Local
                                  path: ${tmp.resolve("tp-log")}
                                externalSource: !Kdb
                                  port: ${tp.port}
${sourceYaml.prependIndent("                                  ")}
                            $$""".trimIndent()
                        )
                    }
                }
                tp.awaitSubscriber()
                f(tp, node)
            }
        }
    }

    @Test
    fun `table updates become versions of the sym, valid from the tick's time`() = withTickerplantAndNode { tp, node ->
        assertTrue(tp.requests.single().contains(".u.sub"), "subscribed through .u.sub: ${tp.requests}")

        tp.publish(
            "trade", table(
                "time" to arrayOf(secs(1), secs(2)),
                "sym" to arrayOf("AAPL", "MSFT"),
                "price" to doubleArrayOf(100.0, 300.0),
                "size" to intArrayOf(10, 20),
            )
        )
        tp.publish(
            "trade", table(
                "time" to arrayOf(secs(3)),
                "sym" to arrayOf("AAPL"),
                "price" to doubleArrayOf(101.5),
                "size" to intArrayOf(5),
            )
        )

        eventually(30.seconds) {
            assertEquals(
                listOf("AAPL" to 101.5, "MSFT" to 300.0),
                query(node, "SELECT _id, price FROM public.trade ORDER BY _id").map { it["_id"] to it["price"] },
            )
        }

        assertEquals(
            listOf(100.0, 101.5),
            query(node, "SELECT price FROM public.trade FOR ALL VALID_TIME WHERE _id = 'AAPL' ORDER BY _valid_from")
                .map { it["price"] },
            "each tick is a version of its sym",
        )

        val midnight = Instant.now().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()

        assertEquals(
            100.0,
            query(
                node,
                "SELECT price FROM public.trade FOR VALID_TIME AS OF TIMESTAMP '${midnight.plusSeconds(2)}' WHERE _id = 'AAPL'"
            ).single()["price"],
            "as-of reads the book as it stood",
        )

        // the token says where in the tickerplant's log we've got to
        eventually(30.seconds) {
            val token = (node as XtdbInternal).dbCatalog["tp"]!!.watchers.externalSourceToken
            assertEquals(2L, KdbSourceToken.parseFrom(token).received)
            assertEquals(41L, KdbSourceToken.parseFrom(token).baseMsgCount)
        }
    }

    @Test
    fun `a bare list of columns is named from the subscribe-time schema, and timestamps are used as-is`() =
        withTickerplantAndNode { tp, node ->
            val t0 = Instant.parse("2026-10-08T09:30:00.123456Z")

            // what the feed sends to the tickerplant's .u.upd, and some tickerplants forward as-is
            tp.publish(
                "quote", arrayOf<Any>(
                    arrayOf(t0, t0.plusSeconds(1)),
                    arrayOf("AAPL", "AAPL"),
                    doubleArrayOf(99.0, 99.5),
                    doubleArrayOf(100.0, 100.5),
                )
            )

            eventually(30.seconds) {
                assertEquals(
                    listOf(t0, t0.plusSeconds(1)),
                    query(node, "SELECT _valid_from FROM public.quote FOR ALL VALID_TIME ORDER BY _valid_from")
                        .map { (it["_valid_from"] as java.time.ZonedDateTime).toInstant() },
                )
            }

            assertEquals(99.5, query(node, "SELECT bid FROM public.quote WHERE _id = 'AAPL'").single()["bid"])
        }

    @Test
    fun `subscribing to one table gets that table's pair, not a list of them`() =
        withTickerplantAndNode("tables: [quote]\nsyms: [AAPL]") { tp, node ->
            assertTrue(tp.requests.single().contains("each `quote"), tp.requests.toString())
            assertTrue(tp.requests.single().contains("[;`AAPL]"), tp.requests.toString())

            tp.publish(
                "quote", c.Flip(
                    arrayOf("time", "sym", "bid", "ask"),
                    arrayOf<Any>(arrayOf(Instant.parse("2026-10-08T09:30:00Z")), arrayOf("AAPL"), doubleArrayOf(99.0), doubleArrayOf(100.0)),
                )
            )

            eventually(30.seconds) {
                assertEquals(99.0, query(node, "SELECT bid FROM public.quote").single()["bid"])
            }
        }

    @Test
    fun `a custom indexer is found by its tag, survives the persisted config, and runs`() = withTickerplantAndNode(
        "indexer: !OhlcBars\n  tables: [trade]",
        mapOf(
            "trade" to table(
                "time" to arrayOf<Instant>(), "sym" to arrayOf<String>(),
                "price" to doubleArrayOf(), "size" to intArrayOf(),
            )
        ),
    ) { tp, node ->
        fun t(hms: String) = Instant.parse("2026-10-08T${hms}Z")

        fun ticks(vararg ticks: Triple<String, String, Pair<Double, Int>>) = c.Flip(
            arrayOf("time", "sym", "price", "size"),
            arrayOf<Any>(
                Array(ticks.size) { t(ticks[it].first) },
                Array(ticks.size) { ticks[it].second },
                DoubleArray(ticks.size) { ticks[it].third.first },
                IntArray(ticks.size) { ticks[it].third.second },
            ),
        )

        tp.publish(
            "trade", ticks(
                Triple("09:30:10", "AAPL", 100.0 to 10),
                Triple("09:30:20", "MSFT", 300.0 to 1),
                Triple("09:30:40", "AAPL", 102.0 to 5),
            )
        )
        // the same minute, in a later update
        tp.publish("trade", ticks(Triple("09:30:50", "AAPL", 99.0 to 20)))
        tp.publish("trade", ticks(Triple("09:31:05", "AAPL", 101.0 to 7)))

        eventually(30.seconds) {
            assertEquals(3, query(node, "SELECT _id FROM public.trade_bars FOR ALL VALID_TIME").size)
        }

        fun bars() = query(node, "SELECT _id, open, high, low, close, size, _valid_from, _valid_to FROM public.trade_bars FOR ALL VALID_TIME ORDER BY _id")
            .associateBy { it["_id"] }

        eventually(30.seconds) { assertEquals(35L, bars().getValue("AAPL@2026-10-08T09:30:00Z")["size"]) }

        val bars = bars()
        val aapl0930 = bars.getValue("AAPL@2026-10-08T09:30:00Z")
        assertEquals(listOf(100.0, 102.0, 99.0, 99.0), listOf("open", "high", "low", "close").map { aapl0930[it] })
        assertEquals(t("09:30:00"), (aapl0930["_valid_from"] as java.time.ZonedDateTime).toInstant())
        assertEquals(t("09:31:00"), (aapl0930["_valid_to"] as java.time.ZonedDateTime).toInstant())

        val aapl0931 = bars.getValue("AAPL@2026-10-08T09:31:00Z")
        assertEquals(listOf(101.0, 101.0, 101.0, 101.0, 7L), listOf("open", "high", "low", "close", "size").map { aapl0931[it] })

        assertEquals(300.0, bars.getValue("MSFT@2026-10-08T09:30:00Z")["close"])
    }

    @Test
    fun `block trades are filtered to their own table`() = withTickerplantAndNode(
        "indexer: !BlockTrades\n  minSize: 1000",
        mapOf(
            "trade" to table(
                "time" to arrayOf<Instant>(), "sym" to arrayOf<String>(),
                "price" to doubleArrayOf(), "size" to intArrayOf(),
            )
        ),
    ) { tp, node ->
        tp.publish(
            "trade", c.Flip(
                arrayOf("time", "sym", "price", "size"),
                arrayOf<Any>(
                    arrayOf(Instant.parse("2026-10-08T09:30:00Z"), Instant.parse("2026-10-08T09:30:01Z")),
                    arrayOf("AAPL", "AAPL"),
                    doubleArrayOf(100.0, 101.0),
                    intArrayOf(50, 2000),
                ),
            )
        )

        eventually(30.seconds) {
            val rows = query(node, "SELECT _id, price, size, notional FROM public.block_trades")
            assertEquals(listOf("AAPL@2026-10-08T09:30:01Z"), rows.map { it["_id"] })
            assertEquals(202_000.0, rows.single()["notional"])
        }
    }
}
