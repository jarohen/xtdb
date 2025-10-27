package xtdb.compactor

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.apache.arrow.memory.BufferAllocator
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import xtdb.api.log.Log
import xtdb.api.log.Log.Message.TriesAdded
import xtdb.api.storage.Storage
import xtdb.arrow.unsupported
import xtdb.catalog.BlockCatalog
import xtdb.catalog.TableCatalog
import xtdb.compactor.Compactor.Driver
import xtdb.compactor.Compactor.Driver.Factory
import xtdb.compactor.Compactor.Job
import xtdb.database.DatabaseName
import xtdb.database.IDatabase
import xtdb.indexer.LogProcessor
import xtdb.log.proto.TrieDetails
import xtdb.metadata.PageMetadata
import xtdb.storage.BufferPool
import xtdb.table.TableRef
import xtdb.trie.TrieCatalog
import xtdb.util.requiringResolve
import xtdb.util.trace
import java.time.Instant
import java.time.InstantSource
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MockDb(override val trieCatalog: TrieCatalog): IDatabase {
    override val name: DatabaseName get() = unsupported("name")
    override val allocator: BufferAllocator get() = unsupported("name")
    override val blockCatalog: BlockCatalog get() = unsupported("name")
    override val tableCatalog: TableCatalog get() = unsupported("name")
    override val log: Log get() = unsupported("name")
    override val bufferPool: BufferPool get() = unsupported("name")
    override val metadataManager: PageMetadata.Factory get() = unsupported("name")
    override val logProcessor: LogProcessor get() = unsupported("name")
    override val compactor: Compactor.ForDatabase get() = unsupported("name")
}



@OptIn(DelicateCoroutinesApi::class)
class MockDriver(private val instantSource: InstantSource = InstantSource.system()) : Factory {

    sealed interface AsyncMessage {}
    class AppendMessage(val added: TriesAdded, val continuation: Continuation<Log.MessageMetadata>) : AsyncMessage
    class AwaitSignalMessage(val continuation: Continuation<JobKey?>) : AsyncMessage

    override fun create(db: IDatabase) = object : Driver {
        val appendedTries = mutableListOf<TriesAdded>()
        val launchedBlocks = mutableListOf<suspend CoroutineScope.() -> Unit>()
        private val doneCh = Channel<JobKey>()
        private val wakeupCh = Channel<Unit>(1, onBufferOverflow = DROP_OLDEST)
        val channel = Channel<AsyncMessage>()

        val thingsTodo: MutableSet<AsyncMessage> = mutableSetOf()

        init {
            GlobalScope.launch {
                for (msg in channel) {
                    when (msg) {
                        is AppendMessage -> {

                        }
                        is AwaitSignalMessage -> {

                        }
                        else -> thingsTodo.add(msg)
                    }
                }
            }
        }

        override suspend fun launchIn(scope: CoroutineScope, f: suspend CoroutineScope.() -> Unit) =
            launchedBlocks.add(f).let { }

        suspend fun runNext(scope: CoroutineScope) {
            launchedBlocks.removeFirstOrNull()?.invoke(scope)
        }

        suspend fun runAll(scope: CoroutineScope) {
            while (launchedBlocks.isNotEmpty()) runNext(scope)
        }

        override fun executeJob(job: Job): TriesAdded {
            val trie = TrieDetails.newBuilder()
                .setTableName(job.table.tableName)
                .setTrieKey(job.outputTrieKey.toString())
                .setDataFileSize(100 * 1024L * 1024L)
                .build()
            return TriesAdded(Storage.VERSION, 0, listOf(trie))
        }

        override suspend fun appendMessage(triesAdded: TriesAdded): Log.MessageMetadata = suspendCoroutine { continuation ->
            channel.trySend(AppendMessage(triesAdded, continuation))
        }

        override suspend fun awaitSignal(): JobKey? = suspendCoroutine { cont ->
            channel.trySend(AwaitSignalMessage(cont))
        }

        override suspend fun jobDone(jobKey: JobKey) {
            doneCh.send(jobKey)
        }

        override fun wakeup() {
            wakeupCh.trySend(Unit)
        }

        override fun close() = Unit
    }
}


@Tag("simulation")
class SimulationTest {
    @Test
    fun deterministicCompactorRun() {
        val mockDriver = MockDriver()
        val jobCalculator = requiringResolve("xtdb.compactor/->JobCalculator").invoke() as Compactor.JobCalculator
        val compactor = Compactor.Impl(mockDriver, null, jobCalculator, false, 2)
        val trieCatalog = requiringResolve("xtdb.trie-catalog/->TrieCatalog").invoke(mutableMapOf<Any,Any>(), (100 * 1024 * 1024)) as TrieCatalog
        val db = MockDb(trieCatalog)
        val compactorForDb = compactor.openForDatabase(db)
        val docsTableRef = TableRef("xtdb", "public", "docs")
        val driverForDb = compactorForDb.getDriver()

        val l0Trie = TrieDetails.newBuilder()
            .setTableName(docsTableRef.tableName)
            .setTrieKey("l00-rc-b01")
            .setDataFileSize(1024L)
            .build()

        trieCatalog.addTries(docsTableRef, listOf(l0Trie),Instant.now())


        // Add L0 to TrieCatalog
        // Do we need to wakeup compactor thread?
        // Compactor thread adds new job
        // runNext on driver
        // jobDone
    }
}
