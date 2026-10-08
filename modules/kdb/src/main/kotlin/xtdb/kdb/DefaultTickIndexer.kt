package xtdb.kdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.subclass
import xtdb.api.error.Incorrect
import xtdb.api.tx.OpenTx
import xtdb.arrow.RelationAsStructReader
import xtdb.arrow.RelationReader
import xtdb.arrow.Vector.Companion.openVector
import xtdb.arrow.VectorReader
import xtdb.arrow.VectorType.Companion.INSTANT
import org.apache.arrow.memory.RootAllocator
import xtdb.kdb.proto.DefaultTickIndexerConfig
import xtdb.kdb.proto.defaultTickIndexerConfig
import xtdb.time.InstantUtil.asMicros
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import com.google.protobuf.Any as ProtoAny

private const val PROTO_TAG_PREFIX = "proto.xtdb.com"

/**
 * One XTDB table per kdb+ table (in [schema], `public` by default), one document per tick:
 *
 * - `_id` is the [idColumn] (`sym`), so a table holds one entity per instrument and each tick is a new version of it;
 * - valid-from is the [timeColumn] (`time`), so `FOR VALID_TIME AS OF` reads the book as it stood at any instant.
 *   A timestamp column is taken as-is.
 *   A timespan column (what kdb+tick's tickerplant prepends: time since midnight) is added to today's date in
 *   [zone] - today being the tx's system time, less a day if that would put the tick in the future;
 * - the doc is every kdb+ column, plus `_id`.
 *
 * Two ticks for the same sym at the same instant collide; the later one wins.
 */
class DefaultTickIndexer(
    private val schema: String,
    private val idColumn: String,
    private val timeColumn: String,
    private val zone: ZoneId,
) : TickIndexer {

    @Serializable
    @SerialName("!Ticks")
    data class Factory(
        val schema: String = "public",
        val idColumn: String = "sym",
        val timeColumn: String = "time",
        val timezone: String? = null,
    ) : TickIndexer.Factory {

        override fun open(): TickIndexer =
            DefaultTickIndexer(schema, idColumn, timeColumn, timezone?.let(ZoneId::of) ?: ZoneId.systemDefault())

        class Registration : TickIndexer.Registration<Factory> {
            override val protoTag: String get() = "$PROTO_TAG_PREFIX/xtdb.kdb.proto.DefaultTickIndexerConfig"
            override val factoryClass get() = Factory::class.java

            override fun toProto(factory: Factory): ProtoAny =
                ProtoAny.pack(defaultTickIndexerConfig {
                    schema = factory.schema
                    idColumn = factory.idColumn
                    timeColumn = factory.timeColumn
                    timezone = factory.timezone.orEmpty()
                }, PROTO_TAG_PREFIX)

            override fun fromProto(msg: ProtoAny): Factory {
                val config = msg.unpack(DefaultTickIndexerConfig::class.java)
                return Factory(config.schema, config.idColumn, config.timeColumn, config.timezone.ifEmpty { null })
            }

            override fun registerSerde(builder: PolymorphicModuleBuilder<TickIndexer.Factory>) {
                builder.subclass(Factory::class)
            }
        }
    }

    private val allocator = RootAllocator()

    override fun indexTicks(table: String, rel: RelationReader, openTx: OpenTx) {
        val n = rel.rowCount
        if (n == 0) return

        val idVec = rel.vectorForOrNull(idColumn)
            ?: throw Incorrect(
                "kdb+ table '$table' has no '$idColumn' column to use as _id",
                "xtdb.kdb/missing-id-column",
                mapOf("table" to table, "columns" to rel.vectors.map { it.name }),
            )

        allocator.openVector("_valid_from", INSTANT).use { validFrom ->
            tickInstants(rel.vectorForOrNull(timeColumn), n, openTx.systemTime, zone, timeColumn).forEach { validFrom.writeLong(it.asMicros) }

            val id = idVec.withName("_id")

            openTx.table(schema, table).writePuts(
                RelationReader.from(
                    listOf(
                        id,
                        validFrom,
                        RelationAsStructReader("doc", RelationReader.from(rel.vectors + id, n)),
                    ),
                    n,
                )
            )
        }
    }

    override fun close() = allocator.close()
}

/**
 * The instant of each of the first [n] ticks, from a kdb+ `time` column: a timestamp as-is, a timespan (what kdb+tick's
 * tickerplant prepends) as time since midnight on [systemTime]'s date in [zone] - less a day if that lands more than
 * an hour after [systemTime] - and no column (or a null) as [systemTime].
 */
fun tickInstants(timeVec: VectorReader?, n: Int, systemTime: Instant, zone: ZoneId, column: String = "time"): List<Instant> =
    List(n) { idx ->
        when (val t = timeVec?.takeUnless { it.isNull(idx) }?.getObject(idx)) {
            null -> systemTime
            is Instant -> t
            is ZonedDateTime -> t.toInstant()
            is Duration -> {
                val today = systemTime.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().plus(t)
                if (today > systemTime.plus(Duration.ofHours(1))) today.minus(Duration.ofDays(1)) else today
            }

            else -> throw Incorrect(
                "can't use '$column' (${t.javaClass.simpleName}) as valid-time",
                "xtdb.kdb/bad-time-column", mapOf("column" to column),
            )
        }
    }
