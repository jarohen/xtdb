package xtdb.compactor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.channels.Channel
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

class MockDriver(private val instantSource: InstantSource = InstantSource.system()) : Factory {
    override fun create(db: IDatabase) = object : Driver {
        val appendedTries = mutableListOf<TriesAdded>()
        val launchedBlocks = mutableListOf<suspend CoroutineScope.() -> Unit>()
        private val doneCh = Channel<JobKey>()
        private val wakeupCh = Channel<Unit>(1, onBufferOverflow = DROP_OLDEST)

        override suspend fun launchIn(scope: CoroutineScope, f: suspend CoroutineScope.() -> Unit) =
            launchedBlocks.add(f).let { }

        suspend fun runNext(scope: CoroutineScope) {
            launchedBlocks.removeFirstOrNull()?.invoke(scope)
        }

        suspend fun runAll(scope: CoroutineScope) {
            while (launchedBlocks.isNotEmpty()) runNext(scope)
        }

        override suspend fun executeJob(job: Job): TriesAdded = TriesAdded(Storage.VERSION, 0, emptyList())

        override suspend fun appendMessage(triesAdded: TriesAdded): Log.MessageMetadata =
            Log.MessageMetadata(appendedTries.size.toLong(), instantSource.instant())
                .also { appendedTries += triesAdded }

        override suspend fun awaitSignal(): JobKey? =
            select {
                doneCh.onReceive { it }

                wakeupCh.onReceive {
                    null
                }
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

        trieCatalog.addTries(docsTableRef, listOf(TrieDetails.newBuilder().build()),Instant.now())


    }
}
