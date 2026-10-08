package xtdb.kdb.demo

import com.google.protobuf.ListValue
import com.google.protobuf.Struct
import com.google.protobuf.Value
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.subclass
import xtdb.api.error.Incorrect
import xtdb.api.tx.OpenTx
import xtdb.arrow.RelationReader
import xtdb.kdb.TickIndexer
import xtdb.kdb.tickInstants
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import com.google.protobuf.Any as ProtoAny

/**
 * An example [TickIndexer], and the model for writing your own: one-minute OHLC bars.
 *
 * For each update to one of [tables], it folds the ticks into a `<table>_bars` entity per sym -
 * `open`, `high`, `low`, `close` (by arrival order) and the summed `size` - with one version per minute,
 * valid from the minute's start to its end - or, while the minute is still open, to this tx's system time.
 * A minute that spans several updates is merged with that minute's version, read back through [OpenTx.openQuery].
 *
 * Registered via `META-INF/services` in the demo source set, and selected in ATTACH YAML as `indexer: !OhlcBars`.
 * Its proto carrier is a [Struct] rather than a generated message, as there's no protobuf generation in the demo source set.
 */
class OhlcBarsIndexer(private val tables: Set<String>, private val zone: ZoneId) : TickIndexer {

    @Serializable
    @SerialName("!OhlcBars")
    data class Factory(val tables: List<String>, val timezone: String? = null) : TickIndexer.Factory {

        override fun open(): TickIndexer =
            OhlcBarsIndexer(tables.toSet(), timezone?.let(ZoneId::of) ?: ZoneId.systemDefault())

        class Registration : TickIndexer.Registration<Factory> {
            override val protoTag: String get() = "proto.xtdb.com/OhlcBars/google.protobuf.Struct"
            override val factoryClass get() = Factory::class.java

            override fun toProto(factory: Factory): ProtoAny = ProtoAny.pack(
                Struct.newBuilder()
                    .putFields("tables", Value.newBuilder().setListValue(
                        ListValue.newBuilder().addAllValues(factory.tables.map { Value.newBuilder().setStringValue(it).build() })
                    ).build())
                    .putFields("timezone", Value.newBuilder().setStringValue(factory.timezone.orEmpty()).build())
                    .build(),
                "proto.xtdb.com/OhlcBars",
            )

            override fun fromProto(msg: ProtoAny): Factory {
                val struct = msg.unpack(Struct::class.java)
                return Factory(
                    tables = struct.getFieldsOrThrow("tables").listValue.valuesList.map { it.stringValue },
                    timezone = struct.getFieldsOrThrow("timezone").stringValue.ifEmpty { null },
                )
            }

            override fun registerSerde(builder: PolymorphicModuleBuilder<TickIndexer.Factory>) {
                builder.subclass(Factory::class)
            }
        }
    }

    private class Bar(val sym: String, val minute: Instant, var open: Double, var high: Double, var low: Double, var close: Double, var size: Long)

    override fun indexTicks(table: String, rel: RelationReader, openTx: OpenTx) {
        if (table !in tables) return

        val times = tickInstants(rel.vectorForOrNull("time"), rel.rowCount, openTx.systemTime, zone)
        val syms = rel["sym"]
        val prices = rel["price"]
        val sizes = rel["size"]

        val bars = LinkedHashMap<Pair<String, Instant>, Bar>()

        for (idx in 0 until rel.rowCount) {
            val sym = syms.getObject(idx) as String
            val minute = times[idx].truncatedTo(ChronoUnit.MINUTES)
            val price = (prices.getObject(idx) as Number).toDouble()
            val size = (sizes.getObject(idx) as Number).toLong()

            val bar = bars[sym to minute]
            if (bar == null) {
                bars[sym to minute] = Bar(sym, minute, price, price, price, price, size)
            } else {
                bar.high = maxOf(bar.high, price)
                bar.low = minOf(bar.low, price)
                bar.close = price
                bar.size += size
            }
        }

        // read before writing: until this tx stages a row, a first-ever bars table has no columns to query
        for (bar in bars.values) mergeExisting(openTx, "${table}_bars", bar)

        val barsTable = openTx.table("public", "${table}_bars")

        for (bar in bars.values) {
            barsTable.writePut(
                mapOf(
                    "_id" to bar.sym,
                    "open" to bar.open, "high" to bar.high, "low" to bar.low, "close" to bar.close, "size" to bar.size,
                ),
                validFrom = bar.minute, validTo = minOf(openTx.systemTime, bar.minute.plus(1, ChronoUnit.MINUTES)),
            )
        }
    }

    private fun mergeExisting(openTx: OpenTx, barsTable: String, bar: Bar) {
        val cursor = try {
            openTx.openQuery(
                "SELECT open, high, low, size FROM public.$barsTable FOR VALID_TIME AS OF TIMESTAMP '${bar.minute}' " +
                    "WHERE _id = '${bar.sym.replace("'", "''")}'"
            )
        } catch (_: Incorrect) {
            return // no bars table yet
        }

        cursor.use {
            cursor.forEachRemaining { r ->
                if (r.rowCount == 0) return@forEachRemaining
                bar.open = (r["open"].getObject(0) as Number).toDouble()
                bar.high = maxOf(bar.high, (r["high"].getObject(0) as Number).toDouble())
                bar.low = minOf(bar.low, (r["low"].getObject(0) as Number).toDouble())
                bar.size += (r["size"].getObject(0) as Number).toLong()
            }
        }
    }
}
