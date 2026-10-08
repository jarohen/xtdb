package xtdb.kdb.demo

import com.google.protobuf.Struct
import com.google.protobuf.Value
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.subclass
import xtdb.api.tx.OpenTx
import xtdb.arrow.RelationReader
import xtdb.kdb.TickIndexer
import xtdb.kdb.tickInstants
import java.time.ZoneId
import com.google.protobuf.Any as ProtoAny

/**
 * An example [TickIndexer]: keeps only the block trades - `trade` rows with `size >= minSize` - in a `block_trades` table,
 * one document per trade, with its notional value.
 *
 * Selected in ATTACH YAML as `indexer: !BlockTrades`, with an optional `minSize`.
 */
class BlockTradeIndexer(private val minSize: Long, private val zone: ZoneId) : TickIndexer {

    @Serializable
    @SerialName("!BlockTrades")
    data class Factory(val minSize: Long = 10_000, val timezone: String? = null) : TickIndexer.Factory {

        override fun open(): TickIndexer = BlockTradeIndexer(minSize, timezone?.let(ZoneId::of) ?: ZoneId.systemDefault())

        class Registration : TickIndexer.Registration<Factory> {
            override val protoTag: String get() = "proto.xtdb.com/BlockTrades/google.protobuf.Struct"
            override val factoryClass get() = Factory::class.java

            override fun toProto(factory: Factory): ProtoAny = ProtoAny.pack(
                Struct.newBuilder()
                    .putFields("minSize", Value.newBuilder().setNumberValue(factory.minSize.toDouble()).build())
                    .putFields("timezone", Value.newBuilder().setStringValue(factory.timezone.orEmpty()).build())
                    .build(),
                "proto.xtdb.com/BlockTrades",
            )

            override fun fromProto(msg: ProtoAny): Factory {
                val struct = msg.unpack(Struct::class.java)
                return Factory(
                    minSize = struct.getFieldsOrThrow("minSize").numberValue.toLong(),
                    timezone = struct.getFieldsOrThrow("timezone").stringValue.ifEmpty { null },
                )
            }

            override fun registerSerde(builder: PolymorphicModuleBuilder<TickIndexer.Factory>) {
                builder.subclass(Factory::class)
            }
        }
    }

    override fun indexTicks(table: String, rel: RelationReader, openTx: OpenTx) {
        if (table != "trade") return

        val times = tickInstants(rel.vectorForOrNull("time"), rel.rowCount, openTx.systemTime, zone)
        val syms = rel["sym"]
        val prices = rel["price"]
        val sizes = rel["size"]

        val blockTrades = openTx.table("public", "block_trades")

        for (idx in 0 until rel.rowCount) {
            // kdb+ columns can be any width (real or float, int or long), so read through Number rather than getDouble/getInt
            val size = (sizes.getObject(idx) as Number).toLong()
            if (size < minSize) continue

            val sym = syms.getObject(idx) as String
            val price = (prices.getObject(idx) as Number).toDouble()
            val time = times[idx]

            blockTrades.writePut(
                mapOf("_id" to "$sym@$time", "sym" to sym, "price" to price, "size" to size, "notional" to price * size),
                validFrom = time,
            )
        }
    }
}
