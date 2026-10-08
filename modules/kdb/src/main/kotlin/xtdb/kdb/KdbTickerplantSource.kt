package xtdb.kdb

import com.kx.c
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.subclass
import org.apache.arrow.memory.RootAllocator
import xtdb.api.Remote
import xtdb.api.RemoteAlias
import xtdb.api.error.Incorrect
import xtdb.api.tx.ExternalSource
import xtdb.api.tx.ExternalSourceToken
import xtdb.api.tx.TxIndexer
import xtdb.api.tx.TxIndexer.TxResult
import xtdb.kdb.proto.KdbSourceConfig
import xtdb.kdb.proto.KdbSourceToken
import xtdb.kdb.proto.kdbSourceConfig
import xtdb.kdb.proto.kdbSourceToken
import xtdb.util.error
import xtdb.util.info
import xtdb.util.logger
import java.io.IOException
import com.google.protobuf.Any as ProtoAny

private val LOG = KdbTickerplantSource::class.logger

private const val PROTO_TAG_PREFIX = "proto.xtdb.com"

private val Q_NAME = Regex("[A-Za-z][A-Za-z0-9_.]*")

/**
 * XTDB as a kdb+tick subscriber: connects to a tickerplant, calls `.u.sub`, and turns each `upd` it's sent into a tx.
 *
 * Live subscription only — whatever the tickerplant publishes while we're away is missed, like an RDB that
 * starts without replaying the log. The [KdbSourceToken] records where in the tickerplant's log each update
 * sits, so replay could be added later.
 */
class KdbTickerplantSource internal constructor(
    private val dbName: String,
    private val config: Factory,
    private val indexer: TickIndexer,
) : ExternalSource {

    @Serializable
    @SerialName("!Kdb")
    data class Factory(
        val host: String = "localhost",
        val port: Int,
        val username: String? = null,
        val password: String? = null,
        /** kdb+ tables to subscribe to; empty for all of them. */
        val tables: List<String> = emptyList(),
        /** syms to subscribe to; empty for all of them. */
        val syms: List<String> = emptyList(),
        val indexer: TickIndexer.Factory = DefaultTickIndexer.Factory(),
    ) : ExternalSource.Factory {

        override fun open(dbName: String, remotes: Map<RemoteAlias, Remote>, meterRegistry: MeterRegistry?): ExternalSource {
            // spliced into q source below
            (tables + syms).forEach {
                if (!Q_NAME.matches(it)) throw Incorrect("'$it' isn't a plain kdb+ name", "xtdb.kdb/bad-name", mapOf("name" to it))
            }
            return KdbTickerplantSource(dbName, this, indexer.open())
        }

        class Registration : ExternalSource.Registration<Factory> {
            override val protoTag: String get() = "$PROTO_TAG_PREFIX/xtdb.kdb.proto.KdbSourceConfig"
            override val factoryClass get() = Factory::class.java

            override fun toProto(factory: Factory): ProtoAny =
                ProtoAny.pack(kdbSourceConfig {
                    host = factory.host
                    port = factory.port
                    username = factory.username.orEmpty()
                    password = factory.password.orEmpty()
                    tables.addAll(factory.tables)
                    syms.addAll(factory.syms)
                    indexer = TickIndexer.Factory.toProto(factory.indexer)
                }, PROTO_TAG_PREFIX)

            override fun fromProto(msg: ProtoAny): Factory {
                val c = msg.unpack(KdbSourceConfig::class.java)
                return Factory(
                    c.host, c.port, c.username.ifEmpty { null }, c.password.ifEmpty { null },
                    c.tablesList, c.symsList, TickIndexer.Factory.fromProto(c.indexer),
                )
            }

            override fun registerSerde(builder: PolymorphicModuleBuilder<ExternalSource.Factory>) {
                builder.subclass(Factory::class)
            }

            override val serializersModule: SerializersModule = TickIndexer.Factory.serializersModule
        }
    }

    /** What `.u.sub` told us: each subscribed table's column names, and where the tickerplant's log had got to. */
    private class Subscription(val columns: Map<String, List<String>>, val logFile: String?, val msgCount: Long)

    private val allocator = RootAllocator()

    private fun connect(): c =
        if (config.username != null) c(config.host, config.port, "${config.username}:${config.password.orEmpty()}")
        else c(config.host, config.port)

    // The same compound request as kdb+tick's own rdb.q: subscribing and reading the log position are one sync call,
    // so no `upd` can land between them. Nothing is installed on the tickerplant.
    private fun subscribe(conn: c): Subscription {
        val syms = if (config.syms.isEmpty()) "`" else config.syms.joinToString("") { "`$it" }
        val sub =
            if (config.tables.isEmpty()) ".u.sub[`;$syms]"
            else ".u.sub[;$syms] each ${config.tables.joinToString("") { "`$it" }}"

        val res = conn.k("($sub;(.u.i;.u.L))") as Array<*>

        val subbed = res[0] as Array<*>
        // one table subscribed gives (`tbl;schema), many give a list of those
        val pairs = if (subbed.firstOrNull() is String) listOf(subbed) else subbed.map { it as Array<*> }
        val columns = pairs.associate { (it[0] as String) to (it[1] as c.Flip).x.toList() }

        val pos = res[1] as Array<*>
        return Subscription(columns, pos[1]?.toString(), pos[0] as Long)
    }

    override suspend fun onPartitionAssigned(partition: Int, afterToken: ExternalSourceToken?, txIndexer: TxIndexer) {
        LOG.info("[$dbName] Partition $partition assigned (kdb+ ${config.host}:${config.port})")
        afterToken?.let { LOG.info("[$dbName] last indexed: ${KdbSourceToken.parseFrom(it)} - live subscription only, not replaying") }

        val conn = withContext(Dispatchers.IO) { connect() }

        try {
            coroutineScope {
                // a blocked socket read ignores cancellation, so close the socket when we're cancelled
                launch { try { awaitCancellation() } finally { runCatching { conn.close() } } }

                val sub = withContext(Dispatchers.IO) { subscribe(conn) }
                LOG.info("[$dbName] Subscribed to ${sub.columns.keys}, tickerplant at ${sub.logFile} #${sub.msgCount}")

                val messages = produce(Dispatchers.IO, capacity = 256) {
                    while (true) {
                        val msg = try {
                            conn.readMsg()[1]
                        } catch (e: IOException) {
                            if (!isActive) throw CancellationException("kdb+ connection closed").apply { initCause(e) }
                            throw e
                        }
                        send(msg)
                    }
                }

                var received = 0L

                for (msg in messages) {
                    val fn = (msg as? Array<*>)?.firstOrNull()
                    if (fn != "upd") {
                        LOG.info("[$dbName] ignoring tickerplant message: ${(fn as? CharArray)?.let(::String) ?: fn}")
                        continue
                    }
                    msg as Array<*>

                    val table = msg[1] as String
                    val data = msg[2]
                    val token = kdbSourceToken {
                        logFile = sub.logFile.orEmpty()
                        baseMsgCount = sub.msgCount
                        this.received = ++received
                    }.toByteArray()

                    // fire-and-forget, like the Kafka source: sequenced in order, and nothing to resume from yet
                    txIndexer.submitTx(token) { openTx ->
                        try {
                            val (names, cols) = tickColumns(data, sub.columns[table])
                            allocator.openTickRelation(names, cols).use { rel ->
                                indexer.indexTicks(table, rel, openTx)
                            }
                            TxResult.Committed()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LOG.error(e, "[$dbName] couldn't index an update to '$table' - recording the tx as aborted")
                            TxResult.Aborted(e)
                        }
                    }
                }
            }
        } finally {
            runCatching { conn.close() }
        }
    }

    override fun close() {
        runCatching { indexer.close() }.onFailure { LOG.error(it, "[$dbName] Indexer close failed") }
        runCatching { allocator.close() }.onFailure { LOG.error(it, "[$dbName] Allocator close failed") }
    }
}
