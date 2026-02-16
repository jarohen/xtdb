package xtdb.database

import clojure.lang.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.apache.arrow.memory.BufferAllocator
import xtdb.api.YAML_SERDE
import xtdb.api.log.Log
import xtdb.api.log.Log.Message
import xtdb.api.log.MessageId
import xtdb.util.MsgIdUtil.offsetToMsgId
import xtdb.api.storage.Storage
import xtdb.api.storage.Storage.applyStorage
import xtdb.catalog.BlockCatalog
import xtdb.catalog.TableCatalog
import xtdb.compactor.Compactor
import xtdb.database.proto.DatabaseConfig
import xtdb.database.proto.DatabaseMode
import xtdb.indexer.ControlPlaneConsumer
import xtdb.indexer.LiveIndex
import xtdb.indexer.LogProcessor
import xtdb.indexer.Snapshot
import xtdb.indexer.Indexer.TxSource
import xtdb.metadata.PageMetadata
import xtdb.query.IQuerySource
import xtdb.storage.BufferPool
import xtdb.trie.TrieCatalog
import java.time.Duration
import java.util.*

data class SourceIndexer(
    val logProcessorOrNull: LogProcessor?,
    val compactor: Compactor.ForDatabase,
    val state: DatabaseState,
) {
    val logProcessor: LogProcessor get() = logProcessorOrNull ?: error("source log processor not initialised")
}

data class ReplicaIndexer(
    val logProcessorOrNull: LogProcessor?,
    val txSource: TxSource?,
    val state: DatabaseState,
) {
    val logProcessor: LogProcessor get() = logProcessorOrNull ?: error("replica log processor not initialised")
}

data class Database(
    val allocator: BufferAllocator,
    val config: Config,
    override val storage: DatabaseStorage,

    val sourceIndexer: SourceIndexer,
    val replicaIndexer: ReplicaIndexer,
    val controlPlaneConsumerOrNull: ControlPlaneConsumer?,
) : IQuerySource.QueryDatabase {
    override val queryState: DatabaseState get() = replicaIndexer.state
    val name: DatabaseName get() = queryState.name
    override fun openSnapshot(): Snapshot = queryState.liveIndex.openSnapshot()

    val sourceLog: Log get() = storage.sourceLog
    val replicaLog: Log get() = storage.replicaLog
    val bufferPool: BufferPool get() = storage.bufferPool
    val metadataManager: PageMetadata.Factory get() = storage.metadataManager

    val controlPlaneConsumer: ControlPlaneConsumer get() = controlPlaneConsumerOrNull ?: error("control-plane consumer not initialised")

    override fun equals(other: Any?): Boolean =
        this === other || (other is Database && name == other.name)

    override fun hashCode() = Objects.hash(name)

    fun sendFlushBlockMessage(): Log.MessageMetadata = runBlocking {
        sourceLog.appendMessage(Message.FlushBlock(queryState.blockCatalog.currentBlockIndex ?: -1)).await()
    }

    fun sendAttachDbMessage(dbName: DatabaseName, config: Database.Config): Log.MessageMetadata = runBlocking {
        sourceLog.appendMessage(Message.AttachDatabase(dbName, config)).await()
    }

    fun sendDetachDbMessage(dbName: DatabaseName): Log.MessageMetadata = runBlocking {
        sourceLog.appendMessage(Message.DetachDatabase(dbName)).await()
    }

    @Serializable
    enum class Mode {
        @SerialName("read-write") READ_WRITE,
        @SerialName("read-only") READ_ONLY;

        fun toProto(): DatabaseMode = when (this) {
            READ_WRITE -> DatabaseMode.READ_WRITE
            READ_ONLY -> DatabaseMode.READ_ONLY
        }

        companion object {
            @JvmStatic
            fun fromProto(mode: DatabaseMode): Mode = when (mode) {
                DatabaseMode.READ_WRITE, DatabaseMode.UNRECOGNIZED -> READ_WRITE
                DatabaseMode.READ_ONLY -> READ_ONLY
            }
        }
    }

    @Serializable
    data class Config(
        val log: Log.Factory = Log.inMemoryLog,
        val storage: Storage.Factory = Storage.inMemory(),
        val mode: Mode = Mode.READ_WRITE,
    ) {
        fun log(log: Log.Factory) = copy(log = log)
        fun storage(storage: Storage.Factory) = copy(storage = storage)
        fun mode(mode: Mode) = copy(mode = mode)

        val isReadOnly: Boolean get() = mode == Mode.READ_ONLY

        val serializedConfig: DatabaseConfig
            get() = DatabaseConfig.newBuilder()
                .also { dbConfig ->
                    log.writeTo(dbConfig)
                    dbConfig.applyStorage(storage)
                    dbConfig.mode = mode.toProto()
                }.build()

        companion object {
            @JvmStatic
            fun fromYaml(yaml: String): Config = YAML_SERDE.decodeFromString(yaml.trimIndent())

            @JvmStatic
            fun fromProto(dbConfig: DatabaseConfig) =
                Config()
                    .log(Log.Factory.fromProto(dbConfig))
                    .storage(Storage.Factory.fromProto(dbConfig))
                    .mode(Mode.fromProto(dbConfig.mode))
        }
    }

    interface Catalog : ILookup, Seqable, Iterable<Database>, IQuerySource.QueryCatalog {
        companion object {
            // Currently source and replica process concurrent logs, so we await both.
            // Eventually, replica will be downstream of source and we'll only need to await replica.
            private suspend fun Database.await(msgId: MessageId) {
                sourceIndexer.logProcessorOrNull?.awaitAsync(msgId)?.await()
                replicaIndexer.logProcessorOrNull?.awaitAsync(msgId)?.await()
                controlPlaneConsumerOrNull?.awaitAsync(msgId)?.await()
            }

            private suspend fun Database.sync() {
                sourceIndexer.logProcessorOrNull?.let { lp -> lp.awaitAsync(lp.latestSubmittedMsgId).await() }
                replicaIndexer.logProcessorOrNull?.let { lp -> lp.awaitAsync(lp.latestSubmittedMsgId).await() }
                controlPlaneConsumerOrNull?.awaitAsync(sourceLog.latestSubmittedMsgId)?.await()
            }

            private suspend fun Catalog.awaitAll0(token: String) = coroutineScope {
                val basis = token.decodeTxBasisToken()

                // Await the primary's control-plane consumer first so that all
                // attached secondary databases exist in the catalog.
                primary.controlPlaneConsumerOrNull
                    ?.let { cp -> basis[primary.name]?.first()?.let { cp.awaitAsync(it).await() } }

                databaseNames
                    .mapNotNull { databaseOrNull(it) }
                    .map { db -> launch { basis[db.name]?.first()?.let { db.await(it) } } }
                    .joinAll()
            }

            private suspend fun Catalog.syncAll0() = coroutineScope {
                // Await the primary's control-plane consumer first so that all
                // attached secondary databases exist in the catalog.
                primary.controlPlaneConsumer
                    .awaitAsync(primary.sourceLog.latestSubmittedMsgId)
                    .await()

                databaseNames
                    .mapNotNull { databaseOrNull(it) }
                    .map { db -> launch { db.sync() } }
                    .joinAll()
            }

            @JvmField
            val EMPTY = object : Catalog {
                override val databaseNames: Collection<DatabaseName> = emptySet()

                override fun databaseOrNull(dbName: DatabaseName) = null

                override fun attach(dbName: DatabaseName, config: Config?) =
                    error("can't attach database to empty db-cat")

                override fun detach(dbName: DatabaseName) =
                    error("can't detach database from empty db-cat")
            }
        }

        override val databaseNames: Collection<DatabaseName>
        override fun databaseOrNull(dbName: DatabaseName): Database?

        operator fun get(dbName: DatabaseName) = databaseOrNull(dbName)

        val primary: Database get() = databaseOrNull("xtdb")!!

        fun attach(dbName: DatabaseName, config: Config?): Database
        fun detach(dbName: DatabaseName)

        override fun valAt(key: Any?) = valAt(key, null)
        override fun valAt(key: Any?, notFound: Any?) = databaseOrNull(key as DatabaseName) ?: notFound

        override fun iterator() = databaseNames.mapNotNull { databaseOrNull(it) }.iterator()

        override fun seq(): ISeq? =
            databaseNames.takeIf { it.isNotEmpty() }
                ?.map { MapEntry(it, databaseOrNull(it)) }
                ?.let { RT.seq(it) }

        fun awaitAll(token: String?, timeout: Duration?) = runBlocking {
            if (token != null)
                if (timeout == null) awaitAll0(token) else withTimeout(timeout) { awaitAll0(token) }
        }

        fun syncAll(timeout: Duration?) = runBlocking {
            if (timeout == null) syncAll0() else withTimeout(timeout) { syncAll0() }
        }

        val serialisedSecondaryDatabases
            get(): Map<DatabaseName, DatabaseConfig> =
                this.filterNot { it.name == "xtdb" }
                    .associate { db -> db.name to db.config.serializedConfig }
    }
}
