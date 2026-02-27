package xtdb.indexer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.arrow.memory.RootAllocator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xtdb.api.TransactionKey
import xtdb.api.log.InMemoryLog
import xtdb.api.log.Log
import xtdb.api.log.ReplicaMessage
import xtdb.api.log.Watchers
import xtdb.api.storage.ObjectStore
import xtdb.block.proto.block
import xtdb.catalog.BlockCatalog
import xtdb.catalog.TableCatalog
import xtdb.compactor.Compactor
import xtdb.database.DatabaseState
import xtdb.storage.BufferPool
import xtdb.trie.TrieCatalog
import java.nio.file.Path
import java.time.Instant
import java.time.InstantSource

class FollowerLogProcessorTest {

    private fun resolvedTx(txId: Long = 0) = ReplicaMessage.ResolvedTx(
        txId = txId,
        systemTimeMicros = Instant.now().toEpochMilli() * 1000,
        committed = true,
        error = ByteArray(0),
        tableData = emptyMap()
    )

    private fun replicaProc(
        replicaLog: InMemoryLog<ReplicaMessage> = InMemoryLog(InstantSource.system(), 0),
        bufferPool: BufferPool = BufferPool.UNUSED,
        liveIndex: LiveIndex = mockk(relaxed = true),
        maxBufferedRecords: Int = 1024,
        watchers: Watchers = Watchers(-1),
    ): Triple<FollowerLogProcessor, LiveIndex, Watchers> {
        val blockCatalog = BlockCatalog("test", null)
        val dbState = DatabaseState(
            "test", blockCatalog,
            mockk<TableCatalog>(relaxed = true), mockk<TrieCatalog>(relaxed = true), null,
        )

        val lp = FollowerLogProcessor(
            RootAllocator(), SimpleMeterRegistry(),
            replicaLog, bufferPool,
            dbState,
            mockk<Indexer.ForDatabase>(relaxed = true),
            liveIndex,
            mockk<Compactor.ForDatabase>(relaxed = true),
            emptySet(),
            watchers = watchers,
            maxBufferedRecords = maxBufferedRecords
        )

        return Triple(lp, liveIndex, watchers)
    }

    @Test
    fun `buffer overflow stops ingestion`() {
        val watchers = Watchers(-1)
        val (lp, _, _) = replicaProc(maxBufferedRecords = 2, watchers = watchers)

        val now = Instant.now()
        val records = listOf(
            Log.Record(0, now, ReplicaMessage.BlockBoundary(0)),
            Log.Record(1, now, ReplicaMessage.TriesAdded(0, 0, emptyList())),
            Log.Record(2, now, ReplicaMessage.TriesAdded(0, 0, emptyList())),
            Log.Record(3, now, ReplicaMessage.TriesAdded(0, 0, emptyList())),
        )

        assertThrows<Exception> { lp.processRecords(records) }
        assertNotNull(watchers.exception, "ingestion error should be set after buffer overflow")
    }

    @Test
    fun `replay handles block transitions during replay`() {
        val liveIndex = mockk<LiveIndex>(relaxed = true)

        val blocks = mapOf(
            0L to block { blockIndex = 0 }.toByteArray(),
            1L to block { blockIndex = 1 }.toByteArray(),
        )
        val blockFiles = blocks.keys.map { ObjectStore.StoredObject(BlockCatalog.blockFilePath(it), 0) }
        val bufferPool = mockk<BufferPool> {
            every { epoch } returns 0
            every { listAllObjects(any<Path>()) } returns blockFiles
            every { getByteArray(any()) } answers {
                val path = firstArg<Path>()
                blocks.entries.first { BlockCatalog.blockFilePath(it.key) == path }.value
            }
        }

        val watchers = Watchers(-1)
        val (lp, _, _) = replicaProc(bufferPool = bufferPool, liveIndex = liveIndex, watchers = watchers)

        val now = Instant.now()
        val records = listOf(
            Log.Record(0, now, ReplicaMessage.BlockBoundary(0)),
            Log.Record(1, now, resolvedTx(1)),
            Log.Record(2, now, ReplicaMessage.BlockBoundary(1)),
            Log.Record(3, now, ReplicaMessage.BlockUploaded(1, 0, 0)),
            Log.Record(4, now, ReplicaMessage.BlockUploaded(0, 0, 0)),
        )

        lp.processRecords(records)

        assertNull(watchers.exception)

        // nextBlock should be called for each block transition
        verify(exactly = 2) { liveIndex.nextBlock() }
    }

    @Test
    fun `ResolvedTx calls importTx`() {
        val liveIndex = mockk<LiveIndex>(relaxed = true)
        val (lp, _, _) = replicaProc(liveIndex = liveIndex)

        val tx = resolvedTx(42)
        val now = Instant.now()
        lp.processRecords(listOf(Log.Record(0, now, tx)))

        verify(exactly = 1) { liveIndex.importTx(tx) }
    }

    @Test
    fun `ResolvedTx skips already-applied transactions`() {
        val liveIndex = mockk<LiveIndex>(relaxed = true) {
            every { latestCompletedTx } returns TransactionKey(42, Instant.now())
        }
        val (lp, _, _) = replicaProc(liveIndex = liveIndex)

        val now = Instant.now()
        // tx 40 and 42 should be skipped; tx 43 should be applied
        lp.processRecords(listOf(
            Log.Record(0, now, resolvedTx(40)),
            Log.Record(1, now, resolvedTx(42)),
            Log.Record(2, now, resolvedTx(43)),
        ))

        verify(exactly = 0) { liveIndex.importTx(match { it.txId <= 42 }) }
        verify(exactly = 1) { liveIndex.importTx(match { it.txId == 43L }) }
    }
}
