package xtdb.indexer

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.apache.arrow.memory.BufferAllocator
import xtdb.api.TransactionAborted
import xtdb.api.TransactionCommitted
import xtdb.api.TransactionKey
import xtdb.api.log.Log
import xtdb.api.log.MessageId
import xtdb.api.log.ReplicaMessage
import xtdb.api.log.SourceMessage
import xtdb.api.log.Watchers
import xtdb.api.storage.Storage
import xtdb.arrow.Relation
import xtdb.arrow.asChannel
import xtdb.catalog.BlockCatalog
import xtdb.catalog.BlockCatalog.Companion.allBlockFiles
import xtdb.compactor.Compactor
import xtdb.database.Database
import xtdb.database.DatabaseState
import xtdb.database.proto.DatabaseConfig
import xtdb.error.Anomaly
import xtdb.error.Conflict
import xtdb.error.Incorrect
import xtdb.error.NotFound
import xtdb.log.proto.TrieDetails
import xtdb.storage.BufferPool
import xtdb.table.TableRef
import xtdb.time.InstantUtil
import xtdb.util.MsgIdUtil.offsetToMsgId
import xtdb.util.StringUtil.asLexDec
import xtdb.util.StringUtil.asLexHex
import xtdb.util.TransitFormat.MSGPACK
import xtdb.util.asPath
import xtdb.util.debug
import xtdb.util.logger
import xtdb.util.readTransit
import xtdb.util.MsgIdUtil.msgIdToEpoch
import xtdb.util.MsgIdUtil.msgIdToOffset
import xtdb.util.warn
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private val LOG = LeaderLogProcessor::class.logger

/**
 * Subscribes to the source log as leader, resolves transactions, finishes blocks,
 * and writes all output to the replica log via an AtomicProducer.
 *
 * This is the write side of the leader/follower split — followers subscribe to
 * the replica log and import the resolved output produced here.
 */
class LeaderLogProcessor(
    allocator: BufferAllocator,
    meterRegistry: MeterRegistry,
    private val sourceLog: Log<SourceMessage>,
    private val replicaLog: Log<ReplicaMessage>,
    private val bufferPool: BufferPool,
    private val dbState: DatabaseState,
    private val indexer: Indexer.ForDatabase,
    private val liveIndex: LiveIndex,
    private val watchers: Watchers,
    private val compactor: Compactor.ForDatabase,
    private val skipTxs: Set<MessageId>,
    private val txSource: Indexer.TxSource? = null,
    private val dbCatalog: Database.Catalog? = null,
    flushTimeout: Duration = Duration.ofMinutes(5),
) : Log.RecordProcessor<SourceMessage>, AutoCloseable {

    /**
     * Result of the leader startup replay sequence.
     * Contains the constructed [LeaderLogProcessor] and the source log offset to resume from.
     */
    data class StartResult(
        val processor: LeaderLogProcessor,
        val sourceResumeOffset: Long
    )

    companion object {
        /**
         * Performs the leader startup replay: replays the replica log to catch up the live index,
         * detects dangling block boundaries from a crashed previous leader, and determines
         * the source log resume point.
         *
         * The returned [StartResult] contains the ready-to-use processor and the offset
         * to pass to `sourceLog.tailAll`.
         */
        @JvmStatic
        fun start(
            allocator: BufferAllocator,
            meterRegistry: MeterRegistry,
            sourceLog: Log<SourceMessage>,
            replicaLog: Log<ReplicaMessage>,
            bufferPool: BufferPool,
            dbState: DatabaseState,
            indexer: Indexer.ForDatabase,
            liveIndex: LiveIndex,
            watchers: Watchers,
            compactor: Compactor.ForDatabase,
            skipTxs: Set<MessageId>,
            txSource: Indexer.TxSource? = null,
            dbCatalog: Database.Catalog? = null,
            flushTimeout: Duration = Duration.ofMinutes(5),
        ): StartResult {
            val blockCatalog = dbState.blockCatalog
            val trieCatalog = dbState.trieCatalog

            // Construct the processor first — this fences the old leader via the atomic producer.
            val processor = LeaderLogProcessor(
                allocator, meterRegistry, sourceLog, replicaLog,
                bufferPool, dbState, indexer, liveIndex, watchers, compactor,
                skipTxs, txSource, dbCatalog, flushTimeout
            )

            try {
                val replayTarget = replicaLog.latestSubmittedOffset

                // Determine replay start from the last block's replica msg id.
                val latestReplicaMsgId = blockCatalog.latestReplicaMsgId
                val replayStartOffset = if (latestReplicaMsgId != null && msgIdToEpoch(latestReplicaMsgId) == replicaLog.epoch)
                    msgIdToOffset(latestReplicaMsgId)
                else -1L

                var pendingBlockIdx: Long? = null
                var lastReplayedTxId: Long? = null

                if (replayTarget >= 0 && replayTarget > replayStartOffset) {
                    LOG.debug("replaying replica log from offset ${replayStartOffset + 1} to $replayTarget")

                    val replaySubscriber = object : Log.RecordProcessor<ReplicaMessage> {
                        override fun processRecords(records: List<Log.Record<ReplicaMessage>>) {
                            for (record in records) {
                                when (val msg = record.message) {
                                    is ReplicaMessage.ResolvedTx -> {
                                        val latestTxId = liveIndex.latestCompletedTx?.txId
                                        if (latestTxId == null || latestTxId < msg.txId) {
                                            liveIndex.importTx(msg)
                                        }
                                        lastReplayedTxId = msg.txId
                                    }

                                    is ReplicaMessage.TriesAdded -> {
                                        if (msg.storageVersion == Storage.VERSION && msg.storageEpoch == bufferPool.epoch) {
                                            msg.tries.groupBy { it.tableName }.forEach { (tableName, tries) ->
                                                trieCatalog.addTries(
                                                    TableRef.parse(dbState.name, tableName),
                                                    tries, record.logTimestamp
                                                )
                                            }
                                        }
                                    }

                                    is ReplicaMessage.BlockBoundary -> {
                                        pendingBlockIdx = msg.blockIndex
                                    }

                                    is ReplicaMessage.BlockUploaded -> {
                                        if (msg.blockIndex == pendingBlockIdx) {
                                            val blockFile = bufferPool.allBlockFiles
                                                .find { BlockCatalog.blockFilePath(msg.blockIndex) == it.key }
                                            val block = blockFile?.let {
                                                xtdb.block.proto.Block.parseFrom(bufferPool.getByteArray(it.key))
                                            }

                                            if (block != null) {
                                                blockCatalog.refresh(block)
                                                dbState.tableCatalog.refresh(msg.blockIndex)
                                                trieCatalog.refresh(msg.blockIndex)
                                                liveIndex.nextBlock()
                                                compactor.signalBlock()
                                            }
                                            pendingBlockIdx = null
                                        }
                                    }
                                }
                            }
                        }
                    }

                    replicaLog.openConsumer().use { consumer ->
                        val subscription = consumer.tailAll(replayStartOffset, replaySubscriber, untilOffset = replayTarget)
                        subscription.close()
                    }

                    LOG.debug("replica log replay complete")
                }

                // Dangling boundary: a previous leader wrote BlockBoundary but crashed before BlockUploaded.
                // The block file has been persisted to the object store (that happens before BlockUploaded),
                // so we complete the transition and write BlockUploaded.
                if (pendingBlockIdx != null) {
                    val blockIdx = pendingBlockIdx!!
                    LOG.debug("detected dangling block boundary for b${blockIdx.asLexHex}, completing block transition")

                    val blockFile = bufferPool.allBlockFiles
                        .find { BlockCatalog.blockFilePath(blockIdx) == it.key }

                    if (blockFile != null) {
                        val block = xtdb.block.proto.Block.parseFrom(bufferPool.getByteArray(blockFile.key))
                        blockCatalog.refresh(block)
                        dbState.tableCatalog.refresh(blockIdx)
                        trieCatalog.refresh(blockIdx)
                        liveIndex.nextBlock()
                        compactor.signalBlock()

                        processor.appendToReplica(ReplicaMessage.BlockUploaded(blockIdx, block.latestProcessedMsgId, bufferPool.epoch))
                    }
                }

                // Determine source resume offset from the last replayed tx,
                // falling back to blockCatalog.latestProcessedMsgId.
                val sourceResumeOffset = when {
                    lastReplayedTxId != null -> {
                        if (msgIdToEpoch(lastReplayedTxId!!) == sourceLog.epoch)
                            msgIdToOffset(lastReplayedTxId!!)
                        else -1L
                    }
                    blockCatalog.latestProcessedMsgId != null -> {
                        val msgId = blockCatalog.latestProcessedMsgId!!
                        if (msgIdToEpoch(msgId) == sourceLog.epoch) msgIdToOffset(msgId) else -1L
                    }
                    else -> -1L
                }

                return StartResult(processor, sourceResumeOffset)
            } catch (e: Throwable) {
                processor.close()
                throw e
            }
        }
    }

    init {
        require((dbCatalog != null) == (dbState.name == "xtdb")) {
            "dbCatalog must be provided iff database is 'xtdb'"
        }
    }

    private val epoch = sourceLog.epoch

    private val blockCatalog = dbState.blockCatalog
    private val trieCatalog = dbState.trieCatalog
    private val tableCatalog = dbState.tableCatalog

    private val secondaryDatabases: MutableMap<String, DatabaseConfig> =
        blockCatalog.secondaryDatabases.toMutableMap()

    private var latestProcessedMsgId: MessageId = blockCatalog.latestProcessedMsgId ?: -1

    private val replicaProducer = replicaLog.openAtomicProducer("leader-${dbState.name}")

    private val allocator =
        allocator.newChildAllocator("leader-log-processor", 0, Long.MAX_VALUE)
            .also { alloc ->
                Gauge.builder("watcher.allocator.allocated_memory", alloc) { it.allocatedMemory.toDouble() }
                    .baseUnit("bytes")
                    .register(meterRegistry)
            }

    private val flusher = SourceLogProcessor.Flusher(flushTimeout, blockCatalog)

    override fun close() {
        replicaProducer.close()
        allocator.close()
    }

    private fun appendToReplica(message: ReplicaMessage): Log.MessageMetadata {
        val tx = replicaProducer.openTx()
        try {
            val future = tx.appendMessage(message)
            tx.commit()
            return future.get()
        } catch (e: Throwable) {
            tx.abort()
            throw e
        }
    }

    private fun resolveTx(msgId: MessageId, record: Log.Record<SourceMessage>, msg: SourceMessage.Tx): ReplicaMessage.ResolvedTx {
        return if (skipTxs.isNotEmpty() && skipTxs.contains(msgId)) {
            LOG.warn("Skipping transaction id $msgId - within XTDB_SKIP_TXS")

            val skippedTxPath = "skipped-txs/${msgId.asLexDec}".asPath
            bufferPool.putObject(skippedTxPath, ByteBuffer.wrap(msg.payload))

            indexer.indexTx(msgId, record.logTimestamp, null, null, null, null, null)
        } else {
            msg.payload.asChannel.use { txOpsCh ->
                Relation.StreamLoader(allocator, txOpsCh).use { loader ->
                    Relation(allocator, loader.schema).use { rel ->
                        loader.loadNextPage(rel)

                        val systemTime =
                            (rel["system-time"].getObject(0) as ZonedDateTime?)?.toInstant()

                        val defaultTz =
                            (rel["default-tz"].getObject(0) as String?).let { ZoneId.of(it) }

                        val user = rel["user"].getObject(0) as String?

                        val userMetadata = rel.vectorForOrNull("user-metadata")?.getObject(0)

                        indexer.indexTx(
                            msgId, record.logTimestamp,
                            rel["tx-ops"].listElements,
                            systemTime, defaultTz, user, userMetadata
                        )
                    }
                }
            }
        }
    }

    private fun finishBlock(systemTime: Instant) {
        val blockIdx = (blockCatalog.currentBlockIndex ?: -1) + 1
        LOG.debug("finishing block: 'b${blockIdx.asLexHex}'...")

        val finishedBlocks = liveIndex.finishBlock(blockIdx)

        val addedTries = finishedBlocks.map { (table, fb) ->
            TrieDetails.newBuilder()
                .setTableName(table.schemaAndTable)
                .setTrieKey(fb.trieKey)
                .setDataFileSize(fb.dataFileSize)
                .also { fb.trieMetadata.let { tm -> it.setTrieMetadata(tm) } }
                .build()
        }

        finishedBlocks.forEach { (table, _) ->
            val trie = addedTries.find { it.tableName == table.schemaAndTable }!!
            trieCatalog.addTries(table, listOf(trie), systemTime)
        }

        val allTables = finishedBlocks.keys + blockCatalog.allTables
        val tablePartitions = allTables.associateWith { trieCatalog.getPartitions(it) }

        val tableBlocks = tableCatalog.finishBlock(finishedBlocks, tablePartitions)

        for ((table, tableBlock) in tableBlocks) {
            val path = BlockCatalog.tableBlockPath(table, blockIdx)
            bufferPool.putObject(path, ByteBuffer.wrap(tableBlock.toByteArray()))
        }

        val secondaryDatabasesForBlock = secondaryDatabases.takeIf { dbState.name == "xtdb" }

        // Write TriesAdded + BlockBoundary atomically — followers enter pending mode.
        val boundaryTx = replicaProducer.openTx()
        val blockBoundaryMeta = try {
            boundaryTx.appendMessage(ReplicaMessage.TriesAdded(Storage.VERSION, bufferPool.epoch, addedTries))
            val blockBoundaryFuture = boundaryTx.appendMessage(ReplicaMessage.BlockBoundary(
                blockIndex = blockIdx,
                latestProcessedMsgId = latestProcessedMsgId
            ))
            boundaryTx.commit()
            blockBoundaryFuture.get()
        } catch (e: Throwable) {
            boundaryTx.abort()
            throw e
        }

        // Persist block to object store — must complete before BlockUploaded,
        // because followers read the block file when they see BlockUploaded.
        val latestReplicaMsgId = offsetToMsgId(replicaLog.epoch, blockBoundaryMeta.logOffset)

        val block = blockCatalog.buildBlock(
            blockIdx, liveIndex.latestCompletedTx, latestProcessedMsgId,
            tableBlocks.keys, secondaryDatabasesForBlock,
            latestReplicaMsgId = latestReplicaMsgId
        )

        bufferPool.putObject(BlockCatalog.blockFilePath(blockIdx), ByteBuffer.wrap(block.toByteArray()))
        blockCatalog.refresh(block)

        // Now signal followers that the block is available.
        appendToReplica(ReplicaMessage.BlockUploaded(blockIdx, latestProcessedMsgId, bufferPool.epoch))

        liveIndex.nextBlock()
        compactor.signalBlock()
        LOG.debug("finished block: 'b${blockIdx.asLexHex}'.")
    }

    override fun processRecords(records: List<Log.Record<SourceMessage>>) {
        if (flusher.checkBlockTimeout(blockCatalog, liveIndex)) {
            val flushMessage = SourceMessage.FlushBlock(blockCatalog.currentBlockIndex ?: -1)
            val offset = sourceLog.appendMessage(flushMessage).get().logOffset
            flusher.flushedTxId = offsetToMsgId(epoch, offset)
        }

        var lastMsgId: MessageId = latestProcessedMsgId
        val queue = ArrayDeque(records)

        try {
            while (queue.isNotEmpty()) {
                val record = queue.removeFirst()
                val msgId = offsetToMsgId(epoch, record.logOffset)

                if (msgId <= latestProcessedMsgId) continue

                lastMsgId = msgId
                latestProcessedMsgId = msgId

                when (val msg = record.message) {
                    is SourceMessage.Tx -> {
                        val resolvedTx = resolveTx(msgId, record, msg)
                        appendToReplica(resolvedTx)
                        notifyTx(msgId, resolvedTx)

                        if (liveIndex.isFull()) {
                            finishBlock(record.logTimestamp)
                        }
                    }

                    is SourceMessage.FlushBlock -> {
                        val expectedBlockIdx = msg.expectedBlockIdx
                        if (expectedBlockIdx != null && expectedBlockIdx == (blockCatalog.currentBlockIndex ?: -1L)) {
                            finishBlock(record.logTimestamp)
                        }
                    }

                    is SourceMessage.AttachDatabase -> {
                        val error = try {
                            if (msg.dbName == "xtdb" || msg.dbName in secondaryDatabases)
                                throw Conflict("Database already exists", "xtdb/db-exists", mapOf("db-name" to msg.dbName))
                            secondaryDatabases[msg.dbName] = msg.config.serializedConfig
                            null
                        } catch (e: Anomaly.Caller) { e }

                        val txKey = TransactionKey(msgId, record.logTimestamp)
                        val resolvedTx = indexer.addTxRow(txKey, error)
                        appendToReplica(resolvedTx)
                        txSource?.onCommit(resolvedTx)

                        if (error == null) {
                            dbCatalog!!.attach(msg.dbName, msg.config)
                            watchers.notify(msgId, TransactionCommitted(txKey.txId, txKey.systemTime))
                        } else {
                            watchers.notify(msgId, TransactionAborted(txKey.txId, txKey.systemTime, error))
                        }
                    }

                    is SourceMessage.DetachDatabase -> {
                        val error = try {
                            when {
                                msg.dbName == "xtdb" ->
                                    throw Incorrect("Cannot detach the primary 'xtdb' database", "xtdb/cannot-detach-primary", mapOf("db-name" to msg.dbName))
                                msg.dbName !in secondaryDatabases ->
                                    throw NotFound("Database does not exist", "xtdb/no-such-db", mapOf("db-name" to msg.dbName))
                                else -> {
                                    secondaryDatabases.remove(msg.dbName)
                                    null
                                }
                            }
                        } catch (e: Anomaly.Caller) { e }

                        val txKey = TransactionKey(msgId, record.logTimestamp)
                        val resolvedTx = indexer.addTxRow(txKey, error)
                        appendToReplica(resolvedTx)
                        txSource?.onCommit(resolvedTx)

                        if (error == null) {
                            dbCatalog!!.detach(msg.dbName)
                            watchers.notify(msgId, TransactionCommitted(txKey.txId, txKey.systemTime))
                        } else {
                            watchers.notify(msgId, TransactionAborted(txKey.txId, txKey.systemTime, error))
                        }
                    }

                    is SourceMessage.TriesAdded -> {
                        if (msg.storageVersion == Storage.VERSION && msg.storageEpoch == bufferPool.epoch) {
                            msg.tries.groupBy { it.tableName }.forEach { (tableName, tries) ->
                                trieCatalog.addTries(TableRef.parse(dbState.name, tableName), tries, record.logTimestamp)
                            }
                        }
                        appendToReplica(ReplicaMessage.TriesAdded(msg.storageVersion, msg.storageEpoch, msg.tries))
                        watchers.notify(msgId, null)
                    }

                    is SourceMessage.BlockUploaded -> {
                        watchers.notify(msgId, null)
                    }
                }
            }
        } catch (e: Throwable) {
            watchers.notify(lastMsgId, e)
            throw e
        }
    }

    private fun notifyTx(msgId: MessageId, resolvedTx: ReplicaMessage.ResolvedTx) {
        txSource?.onCommit(resolvedTx)

        val systemTime = InstantUtil.fromMicros(resolvedTx.systemTimeMicros)

        val result = if (resolvedTx.committed) {
            TransactionCommitted(resolvedTx.txId, systemTime)
        } else {
            TransactionAborted(
                resolvedTx.txId, systemTime,
                readTransit(resolvedTx.error, MSGPACK) as Throwable
            )
        }

        watchers.notify(msgId, result)
    }
}
