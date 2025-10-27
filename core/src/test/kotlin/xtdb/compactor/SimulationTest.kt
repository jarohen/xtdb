package xtdb.compactor

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
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
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class MockDb(override val trieCatalog: TrieCatalog) : IDatabase {
    override val name: DatabaseName get() = unsupported("name")
    override val allocator: BufferAllocator get() = unsupported("allocator")
    override val blockCatalog: BlockCatalog get() = unsupported("blockCatalog")
    override val tableCatalog: TableCatalog get() = unsupported("tableCatalog")
    override val log: Log get() = unsupported("log")
    override val bufferPool: BufferPool get() = unsupported("bufferPool")
    override val metadataManager: PageMetadata.Factory get() = unsupported("metadataManager")

    override val logProcessor: LogProcessor get() = unsupported("logProcessor")
    override val compactor: Compactor.ForDatabase get() = unsupported("compactor")
}

class MockDriver(seed: Int = 0) : Factory {

    sealed interface AsyncMessage

    class AppendMessage(val msg: TriesAdded, val msgTimestamp: Instant) : AsyncMessage
    class AwaitSignalMessage(val cont: Continuation<JobKey?>) : AsyncMessage
    class Launch(val f: suspend () -> Unit) : AsyncMessage

    val outerRand = Random(seed)

    override fun create(scope: CoroutineScope, db: IDatabase) = ForDatabase(scope, db)

    // GlobalScope.
    @OptIn(DelicateCoroutinesApi::class)
    inner class ForDatabase(scope: CoroutineScope, private val db: IDatabase) : Driver {
        val rand = Random(outerRand.nextInt())

        val channel = Channel<AsyncMessage>(UNLIMITED)

        var started = false
        var awaitSignalMessage: AwaitSignalMessage? = null

        var wokenUp: Boolean = false

        val launchedJobs = mutableSetOf<suspend () -> Unit>()
        val doneJobs = LinkedBlockingQueue<JobKey>()

        val logMessages = LinkedBlockingQueue<AppendMessage>()
        val trieCat = db.trieCatalog

        private fun consumeAll() {
            while (true) {
                channel.tryReceive()
                    .onSuccess { msg ->
                        started = true

                        when (msg) {
                            is AppendMessage -> logMessages.add(msg)

                            is AwaitSignalMessage -> {
                                check(awaitSignalMessage == null)
                                awaitSignalMessage = msg
                            }

                            is Launch -> launchedJobs.add(msg.f)
                        }

                    }
                    .onFailure { return } // channel is empty
                    .onClosed { throw (it ?: CancellationException()) }
            }
        }

        private fun handleAwaitSignal(message: AwaitSignalMessage) {
            when {
                doneJobs.isNotEmpty<JobKey>() && rand.nextDouble() < 0.8 -> {
                    val doneJob = doneJobs.poll()!!

                    message.cont.resume(doneJob)
                    awaitSignalMessage = null
                }

                wokenUp && rand.nextDouble() < 0.8 -> {
                    message.cont.resume(null)
                    awaitSignalMessage = null
                    wokenUp = false
                }
            }
        }

        init {
            scope.launch {
                try {
                    while (true) {
                        yield()
                        consumeAll()

                        val logs = mutableListOf<AppendMessage>()
                        logMessages.drainTo(logs)

                        logs.forEach { logMsg ->
                            logMsg.msg.tries
                                .groupBy { it.tableName }
                                .forEach { (tableName, tries) ->
                                    trieCat.addTries(TableRef.parse(db.name, tableName), tries, logMsg.msgTimestamp)
                                }
                        }

                        awaitSignalMessage?.let { handleAwaitSignal(it); continue }

                        launchedJobs.randomOrNull(rand)?.let { launchedJob ->
                            launchedJobs.remove(launchedJob)
                            launchedJob()
                            continue
                        }

                        if (started) break else delay(10.milliseconds)
                    }
                } catch (e: Throwable) {
                    channel.close(e)
                    consumeAll()
                    awaitSignalMessage?.let { msg -> msg.cont.resumeWithException(e); awaitSignalMessage = null }
                    throw e
                }
            }
        }

        override suspend fun launchIn(jobsScope: CoroutineScope, f: suspend () -> Unit) =
            channel.send(Launch(f))

        override fun executeJob(job: Job) =
            TriesAdded(
                Storage.VERSION, 0,
                listOf(
                    TrieDetails.newBuilder()
                        .setTableName(job.table.tableName)
                        .setTrieKey(job.outputTrieKey.toString())
                        .setDataFileSize(100 * 1024L * 1024L)
                        .build()
                )
            )

        var logOffset = 0L

        override suspend fun appendMessage(triesAdded: TriesAdded): Log.MessageMetadata {
            val logTimestamp = Instant.now()
            channel.send(AppendMessage(triesAdded, logTimestamp))
            return Log.MessageMetadata(logOffset++, logTimestamp)
        }

        override suspend fun awaitSignal(): JobKey? = suspendCoroutine { cont ->
            channel.trySendBlocking(AwaitSignalMessage(cont))
        }

        override suspend fun jobDone(jobKey: JobKey) {
            doneJobs.add(jobKey)
        }

        override fun wakeup() {
            wokenUp = true
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
        val trieCatalog = requiringResolve("xtdb.trie-catalog/->TrieCatalog").invoke(
            mutableMapOf<Any, Any>(),
            (100 * 1024 * 1024)
        ) as TrieCatalog

        val db = MockDb(trieCatalog)

        compactor.openForDatabase(db).use {
            val docsTableRef = TableRef("xtdb", "public", "docs")

            val l0Trie = TrieDetails.newBuilder()
                .setTableName(docsTableRef.tableName)
                .setTrieKey("l00-rc-b01")
                .setDataFileSize(1024L)
                .build()

            trieCatalog.addTries(docsTableRef, listOf(l0Trie), Instant.now())
        }

        // Add L0 to TrieCatalog
        // Do we need to wakeup compactor thread?
        // Compactor thread adds new job
        // runNext on driver
        // jobDone
    }
}
