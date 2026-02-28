package xtdb.indexer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.arrow.memory.RootAllocator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xtdb.api.log.InMemoryLog
import xtdb.api.log.Log
import xtdb.api.log.ReplicaMessage
import xtdb.api.log.SourceMessage
import xtdb.api.log.Watchers
import xtdb.api.TransactionKey
import xtdb.api.storage.Storage
import xtdb.block.proto.Partition
import xtdb.block.proto.TableBlock
import xtdb.catalog.BlockCatalog
import xtdb.catalog.TableCatalog
import xtdb.compactor.Compactor
import xtdb.database.DatabaseState
import xtdb.log.proto.TrieDetails
import xtdb.log.proto.trieMetadata
import xtdb.storage.BufferPool
import xtdb.table.TableRef
import xtdb.trie.TrieCatalog
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.time.InstantSource

class LeaderLogProcessorTest {

    private fun leaderProc(
        sourceLog: InMemoryLog<SourceMessage> = InMemoryLog(InstantSource.system(), 0),
        replicaLog: InMemoryLog<ReplicaMessage> = InMemoryLog(InstantSource.system(), 0),
        bufferPool: BufferPool = mockk(relaxed = true) { every { epoch } returns 0 },
        liveIndex: LiveIndex = mockk(relaxed = true),
        indexer: Indexer.ForDatabase = mockk(relaxed = true),
        blockCatalog: BlockCatalog = BlockCatalog("test", null),
        trieCatalog: TrieCatalog = mockk(relaxed = true),
        compactor: Compactor.ForDatabase = mockk(relaxed = true),
        watchers: Watchers = Watchers(-1),
    ): LeaderLogProcessor {
        val tableCatalog = mockk<TableCatalog>(relaxed = true)
        val dbState = DatabaseState("test", blockCatalog, tableCatalog, trieCatalog, liveIndex)

        return LeaderLogProcessor(
            RootAllocator(), SimpleMeterRegistry(),
            sourceLog, replicaLog,
            bufferPool, dbState, indexer, liveIndex, watchers, compactor,
            skipTxs = emptySet()
        )
    }

    @Test
    fun `TriesAdded forwarded to replica log`() {
        val replicaLog = InMemoryLog<ReplicaMessage>(InstantSource.system(), 0)
        val trieCatalog = mockk<TrieCatalog>(relaxed = true)
        val lp = leaderProc(replicaLog = replicaLog, trieCatalog = trieCatalog)

        val tries = listOf(
            TrieDetails.newBuilder()
                .setTableName("public/foo")
                .setTrieKey("trie-key-1")
                .setDataFileSize(100)
                .setTrieMetadata(trieMetadata {})
                .build()
        )

        val now = Instant.now()
        lp.processRecords(listOf(
            Log.Record(0, now, SourceMessage.TriesAdded(Storage.VERSION, 0, tries))
        ))

        // Verify trie catalog was updated
        verify { trieCatalog.addTries(any(), any(), any()) }

        // Verify replica log received a TriesAdded message
        assertTrue(replicaLog.latestSubmittedOffset >= 0, "replica log should have received a message")
    }

    @Test
    fun `FlushBlock triggers block finish when CAS matches`() {
        val replicaLog = InMemoryLog<ReplicaMessage>(InstantSource.system(), 0)
        val liveIndex = mockk<LiveIndex>(relaxed = true) {
            every { finishBlock(any()) } returns emptyMap()
            every { latestCompletedTx } returns null
        }
        val trieCatalog = mockk<TrieCatalog>(relaxed = true) {
            every { getPartitions(any()) } returns emptyList()
        }
        val tableCatalog = mockk<TableCatalog>(relaxed = true) {
            every { finishBlock(any(), any()) } returns emptyMap()
        }
        val compactor = mockk<Compactor.ForDatabase>(relaxed = true)
        val bufferPool = mockk<BufferPool>(relaxed = true) { every { epoch } returns 0 }
        val blockCatalog = BlockCatalog("test", null)
        val dbState = DatabaseState("test", blockCatalog, tableCatalog, trieCatalog, liveIndex)

        val lp = LeaderLogProcessor(
            RootAllocator(), SimpleMeterRegistry(),
            InMemoryLog(InstantSource.system(), 0), replicaLog,
            bufferPool, dbState, mockk(relaxed = true), liveIndex, Watchers(-1), compactor,
            skipTxs = emptySet()
        )

        val now = Instant.now()
        // currentBlockIndex is null, so expectedBlockIdx should be -1 to match
        lp.processRecords(listOf(
            Log.Record(0, now, SourceMessage.FlushBlock(-1))
        ))

        // Verify block finishing occurred
        verify { liveIndex.finishBlock(0) }
        verify { liveIndex.nextBlock() }
        verify { compactor.signalBlock() }

        // Verify replica log received block messages (TriesAdded + BlockBoundary + BlockUploaded)
        assertTrue(replicaLog.latestSubmittedOffset >= 0, "replica log should have block messages")
    }

    @Test
    fun `FlushBlock ignored when CAS does not match`() {
        val liveIndex = mockk<LiveIndex>(relaxed = true)
        val lp = leaderProc(liveIndex = liveIndex)

        val now = Instant.now()
        // currentBlockIndex is null (-1), but expectedBlockIdx is 5 — no match
        lp.processRecords(listOf(
            Log.Record(0, now, SourceMessage.FlushBlock(5))
        ))

        verify(exactly = 0) { liveIndex.finishBlock(any()) }
    }

    @Test
    fun `block finishing writes TriesAdded + BlockBoundary + BlockUploaded to replica log`() {
        val replicaLog = InMemoryLog<ReplicaMessage>(InstantSource.system(), 0)
        val finishedBlock = LiveTable.FinishedBlock(
            vecTypes = emptyMap(),
            trieKey = "test-trie",
            dataFileSize = 42,
            rowCount = 10,
            trieMetadata = trieMetadata {},
            hllDeltas = emptyMap()
        )
        val tableRef = TableRef.parse("test", "public/foo")

        val liveIndex = mockk<LiveIndex>(relaxed = true) {
            every { finishBlock(any()) } returns mapOf(tableRef to finishedBlock)
            every { latestCompletedTx } returns null
        }
        val trieCatalog = mockk<TrieCatalog>(relaxed = true) {
            every { getPartitions(any()) } returns emptyList()
        }
        val tableCatalog = mockk<TableCatalog>(relaxed = true) {
            every { finishBlock(any(), any()) } returns mapOf(
                tableRef to TableBlock.getDefaultInstance()
            )
        }
        val compactor = mockk<Compactor.ForDatabase>(relaxed = true)
        val bufferPool = mockk<BufferPool>(relaxed = true) { every { epoch } returns 0 }
        val blockCatalog = BlockCatalog("test", null)
        val dbState = DatabaseState("test", blockCatalog, tableCatalog, trieCatalog, liveIndex)

        val lp = LeaderLogProcessor(
            RootAllocator(), SimpleMeterRegistry(),
            InMemoryLog(InstantSource.system(), 0), replicaLog,
            bufferPool, dbState, mockk(relaxed = true), liveIndex, Watchers(-1), compactor,
            skipTxs = emptySet()
        )

        val now = Instant.now()
        lp.processRecords(listOf(
            Log.Record(0, now, SourceMessage.FlushBlock(-1))
        ))

        // Collect all messages from replica log by subscribing
        val replicaMessages = mutableListOf<ReplicaMessage>()
        val consumer = replicaLog.openConsumer()
        val sub = consumer.tailAll(-1, Log.RecordProcessor<ReplicaMessage> { records ->
            replicaMessages.addAll(records.map { it.message })
        })

        // Give the subscription time to process
        Thread.sleep(200)
        sub.close()
        consumer.close()

        // Should have exactly: TriesAdded, BlockBoundary, BlockUploaded
        assertEquals(3, replicaMessages.size, "expected 3 replica messages, got: $replicaMessages")
        assertTrue(replicaMessages[0] is ReplicaMessage.TriesAdded)
        assertTrue(replicaMessages[1] is ReplicaMessage.BlockBoundary)
        assertTrue(replicaMessages[2] is ReplicaMessage.BlockUploaded)

        val boundary = replicaMessages[1] as ReplicaMessage.BlockBoundary
        assertEquals(0, boundary.blockIndex)

        val uploaded = replicaMessages[2] as ReplicaMessage.BlockUploaded
        assertEquals(0, uploaded.blockIndex)
    }

    @Test
    fun `idempotent - skips already-processed messages`() {
        val replicaLog = InMemoryLog<ReplicaMessage>(InstantSource.system(), 0)
        val trieCatalog = mockk<TrieCatalog>(relaxed = true)
        val lp = leaderProc(replicaLog = replicaLog, trieCatalog = trieCatalog)

        val tries = listOf(
            TrieDetails.newBuilder()
                .setTableName("public/foo")
                .setTrieKey("trie-key-1")
                .setDataFileSize(100)
                .setTrieMetadata(trieMetadata {})
                .build()
        )

        val now = Instant.now()
        // Process once
        lp.processRecords(listOf(
            Log.Record(0, now, SourceMessage.TriesAdded(Storage.VERSION, 0, tries))
        ))

        val offsetAfterFirst = replicaLog.latestSubmittedOffset

        // Process same offset again — should be skipped
        lp.processRecords(listOf(
            Log.Record(0, now, SourceMessage.TriesAdded(Storage.VERSION, 0, tries))
        ))

        assertEquals(offsetAfterFirst, replicaLog.latestSubmittedOffset,
            "replica log should not have received a duplicate message")
    }

    @Test
    fun `leader resumes from latestProcessedMsgId after restart`() {
        val replicaLog = InMemoryLog<ReplicaMessage>(InstantSource.system(), 0)
        val trieCatalog = mockk<TrieCatalog>(relaxed = true)

        // Simulate a block catalog that already has a processed msg id
        val blockCatalog = BlockCatalog("test", null)

        val lp = leaderProc(
            replicaLog = replicaLog, trieCatalog = trieCatalog, blockCatalog = blockCatalog
        )

        val tries = listOf(
            TrieDetails.newBuilder()
                .setTableName("public/foo")
                .setTrieKey("trie-key-1")
                .setDataFileSize(100)
                .setTrieMetadata(trieMetadata {})
                .build()
        )

        val now = Instant.now()
        // Process messages at offsets 0 and 1
        lp.processRecords(listOf(
            Log.Record(0, now, SourceMessage.TriesAdded(Storage.VERSION, 0, tries)),
            Log.Record(1, now, SourceMessage.TriesAdded(Storage.VERSION, 0, tries)),
        ))
        lp.close()

        val offsetAfterFirst = replicaLog.latestSubmittedOffset

        // Create a new leader with the same block catalog (simulates restart)
        // blockCatalog.latestProcessedMsgId is still null since no block was finished
        // so the new leader starts from -1 and should process offset 0 again
        val lp2 = leaderProc(
            replicaLog = replicaLog, trieCatalog = trieCatalog, blockCatalog = blockCatalog
        )

        // Process offset 2 — should succeed since it's new
        lp2.processRecords(listOf(
            Log.Record(2, now, SourceMessage.TriesAdded(Storage.VERSION, 0, tries)),
        ))

        assertTrue(replicaLog.latestSubmittedOffset > offsetAfterFirst,
            "new leader should process new messages")
        lp2.close()
    }

    @Test
    fun `block finishing writes correct latestReplicaMsgId to block`() {
        val replicaLog = InMemoryLog<ReplicaMessage>(InstantSource.system(), 0)
        val tableRef = TableRef.parse("test", "public/foo")
        val finishedBlock = LiveTable.FinishedBlock(
            vecTypes = emptyMap(),
            trieKey = "test-trie",
            dataFileSize = 42,
            rowCount = 10,
            trieMetadata = trieMetadata {},
            hllDeltas = emptyMap()
        )

        val liveIndex = mockk<LiveIndex>(relaxed = true) {
            every { finishBlock(any()) } returns mapOf(tableRef to finishedBlock)
            every { latestCompletedTx } returns null
        }
        val trieCatalog = mockk<TrieCatalog>(relaxed = true) {
            every { getPartitions(any()) } returns emptyList()
        }
        val tableCatalog = mockk<TableCatalog>(relaxed = true) {
            every { finishBlock(any(), any()) } returns mapOf(
                tableRef to TableBlock.getDefaultInstance()
            )
        }
        val bufferPool = mockk<BufferPool>(relaxed = true) { every { epoch } returns 0 }
        val blockCatalog = BlockCatalog("test", null)
        val dbState = DatabaseState("test", blockCatalog, tableCatalog, trieCatalog, liveIndex)

        val lp = LeaderLogProcessor(
            RootAllocator(), SimpleMeterRegistry(),
            InMemoryLog(InstantSource.system(), 0), replicaLog,
            bufferPool, dbState, mockk(relaxed = true), liveIndex, Watchers(-1),
            mockk(relaxed = true),
            skipTxs = emptySet()
        )

        val now = Instant.now()
        lp.processRecords(listOf(
            Log.Record(0, now, SourceMessage.FlushBlock(-1))
        ))

        // After block finishing, blockCatalog should have a block with latestReplicaMsgId set
        assertNotNull(blockCatalog.latestReplicaMsgId, "block should have latestReplicaMsgId set")
        // The BlockBoundary is the second message (after TriesAdded), so its offset is 1
        assertEquals(1L, blockCatalog.latestReplicaMsgId,
            "latestReplicaMsgId should be the BlockBoundary's replica log offset")
    }

    // --- Leader startup / replay tests ---

    private fun resolvedTx(txId: Long = 0) = ReplicaMessage.ResolvedTx(
        txId = txId,
        systemTimeMicros = Instant.now().toEpochMilli() * 1000,
        committed = true,
        error = ByteArray(0),
        tableData = emptyMap()
    )

    @Test
    fun `start - cold start with empty replica log`() {
        val sourceLog = InMemoryLog<SourceMessage>(InstantSource.system(), 0)
        val replicaLog = InMemoryLog<ReplicaMessage>(InstantSource.system(), 0)
        val liveIndex = mockk<LiveIndex>(relaxed = true) {
            every { latestCompletedTx } returns null
        }
        val blockCatalog = BlockCatalog("test", null)
        val trieCatalog = mockk<TrieCatalog>(relaxed = true)
        val tableCatalog = mockk<TableCatalog>(relaxed = true)
        val dbState = DatabaseState("test", blockCatalog, tableCatalog, trieCatalog, liveIndex)
        val bufferPool = mockk<BufferPool>(relaxed = true) { every { epoch } returns 0 }

        val result = LeaderLogProcessor.start(
            RootAllocator(), SimpleMeterRegistry(),
            sourceLog, replicaLog, bufferPool, dbState,
            mockk(relaxed = true), liveIndex, Watchers(-1), mockk(relaxed = true),
            skipTxs = emptySet()
        )

        assertEquals(-1L, result.sourceResumeOffset, "cold start should resume from -1")

        // No txs should have been imported
        verify(exactly = 0) { liveIndex.importTx(any()) }

        result.processor.close()
    }

    @Test
    fun `start - replays resolved txs from replica log`() {
        val sourceLog = InMemoryLog<SourceMessage>(InstantSource.system(), 0)
        val replicaLog = InMemoryLog<ReplicaMessage>(InstantSource.system(), 0)

        // Pre-populate replica log with resolved txs (simulating a previous leader session)
        val tx0 = resolvedTx(txId = 100)
        val tx1 = resolvedTx(txId = 200)
        replicaLog.appendMessage(tx0).get()
        replicaLog.appendMessage(tx1).get()

        val liveIndex = mockk<LiveIndex>(relaxed = true) {
            every { latestCompletedTx } returns null
        }
        val blockCatalog = BlockCatalog("test", null)
        val trieCatalog = mockk<TrieCatalog>(relaxed = true)
        val tableCatalog = mockk<TableCatalog>(relaxed = true)
        val dbState = DatabaseState("test", blockCatalog, tableCatalog, trieCatalog, liveIndex)
        val bufferPool = mockk<BufferPool>(relaxed = true) { every { epoch } returns 0 }

        val result = LeaderLogProcessor.start(
            RootAllocator(), SimpleMeterRegistry(),
            sourceLog, replicaLog, bufferPool, dbState,
            mockk(relaxed = true), liveIndex, Watchers(-1), mockk(relaxed = true),
            skipTxs = emptySet()
        )

        // Both txs should have been imported
        verify(exactly = 2) { liveIndex.importTx(any()) }

        // Source resume offset should be derived from the last replayed tx's txId (200).
        // txId 200 is a source msg id — with epoch 0, offset = 200.
        assertEquals(200L, result.sourceResumeOffset)

        result.processor.close()
    }

    @Test
    fun `start - skips already-applied txs during replay`() {
        val sourceLog = InMemoryLog<SourceMessage>(InstantSource.system(), 0)
        val replicaLog = InMemoryLog<ReplicaMessage>(InstantSource.system(), 0)

        replicaLog.appendMessage(resolvedTx(txId = 100)).get()
        replicaLog.appendMessage(resolvedTx(txId = 200)).get()

        val liveIndex = mockk<LiveIndex>(relaxed = true) {
            // Live index already has tx 100 applied
            every { latestCompletedTx } returns TransactionKey(100, Instant.now())
        }
        val blockCatalog = BlockCatalog("test", null)
        val trieCatalog = mockk<TrieCatalog>(relaxed = true)
        val tableCatalog = mockk<TableCatalog>(relaxed = true)
        val dbState = DatabaseState("test", blockCatalog, tableCatalog, trieCatalog, liveIndex)
        val bufferPool = mockk<BufferPool>(relaxed = true) { every { epoch } returns 0 }

        val result = LeaderLogProcessor.start(
            RootAllocator(), SimpleMeterRegistry(),
            sourceLog, replicaLog, bufferPool, dbState,
            mockk(relaxed = true), liveIndex, Watchers(-1), mockk(relaxed = true),
            skipTxs = emptySet()
        )

        // Only tx 200 should be imported (tx 100 already applied)
        verify(exactly = 1) { liveIndex.importTx(any()) }

        result.processor.close()
    }

    @Test
    fun `start - replays TriesAdded from replica log`() {
        val sourceLog = InMemoryLog<SourceMessage>(InstantSource.system(), 0)
        val replicaLog = InMemoryLog<ReplicaMessage>(InstantSource.system(), 0)
        val trieCatalog = mockk<TrieCatalog>(relaxed = true)

        val tries = listOf(
            TrieDetails.newBuilder()
                .setTableName("public/bar")
                .setTrieKey("trie-key-replay")
                .setDataFileSize(50)
                .setTrieMetadata(trieMetadata {})
                .build()
        )
        replicaLog.appendMessage(ReplicaMessage.TriesAdded(Storage.VERSION, 0, tries)).get()

        val liveIndex = mockk<LiveIndex>(relaxed = true) {
            every { latestCompletedTx } returns null
        }
        val blockCatalog = BlockCatalog("test", null)
        val tableCatalog = mockk<TableCatalog>(relaxed = true)
        val dbState = DatabaseState("test", blockCatalog, tableCatalog, trieCatalog, liveIndex)
        val bufferPool = mockk<BufferPool>(relaxed = true) { every { epoch } returns 0 }

        val result = LeaderLogProcessor.start(
            RootAllocator(), SimpleMeterRegistry(),
            sourceLog, replicaLog, bufferPool, dbState,
            mockk(relaxed = true), liveIndex, Watchers(-1), mockk(relaxed = true),
            skipTxs = emptySet()
        )

        verify { trieCatalog.addTries(any(), any(), any()) }

        result.processor.close()
    }
}
