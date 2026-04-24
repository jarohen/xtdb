package xtdb.indexer

import io.micrometer.tracing.Tracer
import org.apache.arrow.memory.BufferAllocator
import xtdb.NodeBase
import xtdb.api.TransactionKey
import xtdb.api.TransactionResult
import xtdb.api.log.*
import xtdb.api.log.Log.AtomicProducer.Companion.withTx
import xtdb.api.log.ReplicaMessage.BlockBoundary
import xtdb.api.storage.Storage
import xtdb.arrow.Relation
import xtdb.arrow.asChannel
import xtdb.database.Database
import xtdb.database.DatabaseState
import xtdb.database.DatabaseStorage
import xtdb.error.Anomaly
import xtdb.error.Fault
import xtdb.error.Incorrect
import xtdb.error.Interrupted
import xtdb.indexer.TxIndexer.TxResult
import xtdb.table.TableRef
import xtdb.tx.deserializeUserMetadata
import xtdb.util.*
import xtdb.util.StringUtil.asLexDec
import xtdb.util.StringUtil.asLexHex
import java.nio.ByteBuffer
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

private val LOG = LeaderLogProcessor::class.logger

class LeaderLogProcessor(
    allocator: BufferAllocator,
    nodeBase: NodeBase,
    dbStorage: DatabaseStorage,
    private val replicaProducer: Log.AtomicProducer<ReplicaMessage>,
    private val dbState: DatabaseState,
    crashLogger: CrashLogger,
    private val watchers: Watchers,
    private val skipTxs: Set<MessageId>,
    private val dbCatalog: Database.Catalog?,
    private val blockUploader: BlockUploader,
    afterSourceMsgId: MessageId,
    afterReplicaMsgId: MessageId,
    flushTimeout: Duration = Duration.ofMinutes(5),
) : LogProcessor.LeaderProcessor, TxCommitter {

    init {
        require((dbCatalog != null) == (dbState.name == "xtdb")) {
            "dbCatalog must be provided iff database is 'xtdb'"
        }
    }

    private val dbName = dbState.name
    private val sourceLog = dbStorage.sourceLog
    private val bufferPool = dbStorage.bufferPool
    private val liveIndex = dbState.liveIndex

    private val blockCatalog = dbState.blockCatalog
    private val trieCatalog = dbState.trieCatalog

    private val allocator = allocator.newChildAllocator("leader-log-processor", 0, Long.MAX_VALUE)

    private val tracer = nodeBase.tracer?.takeIf { nodeBase.config.tracer.transactionTracing }

    private val txIndexer = TxIndexer(this.allocator, nodeBase, dbStorage, dbState, watchers, committer = this, tracer = tracer)

    private val skippedException = Fault("Transaction was skipped", "xtdb/skipped-tx")

    override var pendingBlock: PendingBlock? = null
        private set

    override var latestSourceMsgId: MessageId = afterSourceMsgId
        private set

    override var latestReplicaMsgId: MessageId = afterReplicaMsgId
        private set

    private val blockFlusher = BlockFlusher(flushTimeout, blockCatalog)

    private suspend fun maybeFlushBlock() {
        if (blockFlusher.checkBlockTimeout(blockCatalog, liveIndex)) {
            val flushMessage = SourceMessage.FlushBlock(blockCatalog.currentBlockIndex ?: -1)
            blockFlusher.flushedTxId = sourceLog.appendMessage(flushMessage).msgId
        }
    }

    private suspend fun appendToReplica(message: ReplicaMessage): Log.MessageMetadata =
        replicaProducer.withTx { tx -> tx.appendMessage(message) }.await()
            .also { latestReplicaMsgId = it.msgId }

    override suspend fun commit(openTx: OpenTx, result: TxResult) = commit(openTx, result, dbOp = null)

    private suspend fun commit(openTx: OpenTx, result: TxResult, dbOp: DbOp?) {
        val txKey = openTx.txKey
        val tableData = openTx.serializeTableData()
        liveIndex.commitTx(openTx)

        val resolvedTx = ReplicaMessage.ResolvedTx(
            txKey.txId, txKey.systemTime,
            committed = result is TxResult.Committed,
            error = (result as? TxResult.Aborted)?.error,
            tableData, dbOp = dbOp,
            externalSourceToken = openTx.externalSourceToken,
        )

        appendToReplica(resolvedTx)
        latestSourceMsgId = txKey.txId

        val txResult = when (result) {
            is TxResult.Committed -> TransactionResult.Committed(txKey)
            is TxResult.Aborted -> TransactionResult.Aborted(txKey, result.error)
        }
        watchers.notifyTx(txResult, txKey.txId, resolvedTx.externalSourceToken)

        if (liveIndex.isFull())
            finishBlock(txKey.txId, resolvedTx.externalSourceToken)
    }

    private suspend fun finishBlock(latestProcessedMsgId: MessageId, externalSourceToken: xtdb.database.ExternalSourceToken?) {
        val boundaryMsg =
            BlockBoundary((blockCatalog.currentBlockIndex ?: -1) + 1, latestProcessedMsgId, externalSourceToken)
        val boundaryMsgId = appendToReplica(boundaryMsg).msgId
        LOG.debug("[$dbName] block boundary b${boundaryMsg.blockIndex.asLexHex}: source=$latestProcessedMsgId, replica=$boundaryMsgId")
        pendingBlock = PendingBlock(boundaryMsgId, boundaryMsg)

        latestReplicaMsgId = blockUploader.uploadBlock(replicaProducer, boundaryMsgId, boundaryMsg)
        pendingBlock = null
    }

    private suspend fun runSkippedTx(msgId: MessageId, systemTime: java.time.Instant) {
        txIndexer.indexTx(
            externalSourceToken = null, txId = msgId, systemTime = systemTime,
        ) { TxResult.Aborted(skippedException) }
    }

    /**
     * Runs the given [txOps] through a fresh OpenTx driven by [txIndexer]. Caller errors
     * surface as [TxResult.Aborted]; everything else becomes [TxResult.Committed].
     *
     * [specifiedSystemTime] is the caller-specified system-time (may be null — falling back to
     * [msgTimestamp]). Only a caller-specified time that's before `latestCompletedTx.systemTime`
     * triggers the `invalid-system-time` abort; the fallback never does.
     */
    private suspend fun ingestTxOps(
        msgId: MessageId, msgTimestamp: java.time.Instant,
        specifiedSystemTime: java.time.Instant?, defaultTz: ZoneId?,
        externalSourceToken: xtdb.database.ExternalSourceToken?,
        userMetadata: Map<*, *>?,
        txOps: xtdb.arrow.VectorReader,
    ) {
        val lct = liveIndex.latestCompletedTx
        val invalidSystemTime = specifiedSystemTime != null && lct != null
                && specifiedSystemTime.isBefore(lct.systemTime)

        txIndexer.indexTx(
            externalSourceToken = externalSourceToken,
            txId = msgId,
            systemTime = if (invalidSystemTime) msgTimestamp else (specifiedSystemTime ?: msgTimestamp),
        ) { openTx ->
            if (invalidSystemTime) {
                LOG.warn("[$dbName] specified system-time '$specifiedSystemTime' older than current tx '$lct'")
                return@indexTx TxResult.Aborted(
                    Incorrect(
                        "specified system-time older than current tx",
                        "xtdb/invalid-system-time",
                        mapOf(
                            "tx-key" to TransactionKey(msgId, specifiedSystemTime!!),
                            "latest-completed-tx" to lct,
                        ),
                    ),
                    userMetadata,
                )
            }

            val indexer = TxOpIndexer(allocator, openTx, txOps, openTx.txKey.systemTime, defaultTz, dbName, tracer)

            try {
                repeat(txOps.valueCount) { idx -> indexer.indexOp(idx) }
                TxResult.Committed(userMetadata)
            } catch (e: Anomaly.Caller) {
                LOG.debug(e) { "[$dbName] aborted tx $msgId" }
                TxResult.Aborted(e, userMetadata)
            }
        }
    }

    private suspend fun resolveTx(msgId: MessageId, record: Log.Record<SourceMessage>, msg: SourceMessage) {
        if (skipTxs.isNotEmpty() && skipTxs.contains(msgId)) {
            LOG.warn("[$dbName] Skipping transaction id $msgId - within XTDB_SKIP_TXS")

            val payload = when (msg) {
                is SourceMessage.Tx -> msg.encode()
                is SourceMessage.LegacyTx -> msg.payload
                else -> error("unexpected message type: ${msg::class}")
            }
            bufferPool.putObject("skipped-txs/${msgId.asLexDec}".asPath, ByteBuffer.wrap(payload))

            runSkippedTx(msgId, record.logTimestamp)
            return
        }

        when (msg) {
            is SourceMessage.Tx -> {
                msg.txOps.asChannel.use { ch ->
                    Relation.StreamLoader(allocator, ch).use { loader ->
                        Relation(allocator, loader.schema).use { rel ->
                            loader.loadNextPage(rel)
                            val userMetadata = msg.userMetadata?.let { deserializeUserMetadata(allocator, it) } as? Map<*, *>

                            ingestTxOps(
                                msgId = msgId,
                                msgTimestamp = record.logTimestamp,
                                specifiedSystemTime = msg.systemTime,
                                defaultTz = msg.defaultTz,
                                externalSourceToken = msg.externalSourceToken,
                                userMetadata = userMetadata,
                                txOps = rel["tx-ops"],
                            )
                        }
                    }
                }
            }

            is SourceMessage.LegacyTx -> {
                msg.payload.asChannel.use { txOpsCh ->
                    Relation.StreamLoader(allocator, txOpsCh).use { loader ->
                        Relation(allocator, loader.schema).use { rel ->
                            loader.loadNextPage(rel)

                            val systemTime = (rel["system-time"].getObject(0) as ZonedDateTime?)?.toInstant()
                            val defaultTz = (rel["default-tz"].getObject(0) as String?).let { ZoneId.of(it) }
                            val userMetadata = rel.vectorForOrNull("user-metadata")?.getObject(0) as? Map<*, *>

                            ingestTxOps(
                                msgId = msgId,
                                msgTimestamp = record.logTimestamp,
                                specifiedSystemTime = systemTime,
                                defaultTz = defaultTz,
                                externalSourceToken = null,
                                userMetadata = userMetadata,
                                txOps = rel["tx-ops"].listElements,
                            )
                        }
                    }
                }
            }

            else -> error("unexpected message type: ${msg::class}")
        }
    }

    private suspend fun handleDbOp(msgId: MessageId, logTimestamp: java.time.Instant, error: Throwable?, dbOp: DbOp?) {
        val txKey = TransactionKey(msgId, logTimestamp)
        val result = if (error == null) TxResult.Committed() else TxResult.Aborted(error)
        txIndexer.startTx(txKey).use { openTx ->
            openTx.addTxRow(dbName, error, null)
            commit(openTx, result, dbOp = dbOp)
        }
    }

    override suspend fun processRecords(records: List<Log.Record<SourceMessage>>) {
        maybeFlushBlock()

        for (record in records) {
            val msgId = record.msgId
            LOG.trace { "[$dbName] leader: message $msgId (${record.message::class.simpleName})" }

            try {
                when (val msg = record.message) {
                    is SourceMessage.Tx, is SourceMessage.LegacyTx -> resolveTx(msgId, record, msg)

                    is SourceMessage.FlushBlock -> {
                        val expectedBlockIdx = msg.expectedBlockIdx
                        if (expectedBlockIdx != null && expectedBlockIdx == (blockCatalog.currentBlockIndex ?: -1L)) {
                            finishBlock(msgId, watchers.externalSourceToken)
                        }
                        latestSourceMsgId = msgId
                        watchers.notifyMsg(msgId)
                    }

                    is SourceMessage.AttachDatabase -> {
                        val error = if (dbCatalog != null) {
                            try {
                                dbCatalog.attach(msg.dbName, msg.config)
                                null
                            } catch (e: Anomaly.Caller) {
                                LOG.debug(e) { "[$dbName] leader: attach database '${msg.dbName}' failed at $msgId" }
                                e
                            }
                        } else null

                        handleDbOp(
                            msgId, record.logTimestamp, error,
                            dbOp = if (error == null) DbOp.Attach(msg.dbName, msg.config) else null,
                        )
                    }

                    is SourceMessage.DetachDatabase -> {
                        val error = if (dbCatalog != null) {
                            try {
                                dbCatalog.detach(msg.dbName)
                                null
                            } catch (e: Anomaly.Caller) {
                                LOG.debug(e) { "[$dbName] leader: detach database '${msg.dbName}' failed at $msgId" }
                                e
                            }
                        } else null

                        handleDbOp(
                            msgId, record.logTimestamp, error,
                            dbOp = if (error == null) DbOp.Detach(msg.dbName) else null,
                        )
                    }

                    is SourceMessage.TriesAdded -> {
                        if (msg.storageVersion == Storage.VERSION && msg.storageEpoch == bufferPool.epoch) {
                            msg.tries.groupBy { it.tableName }.forEach { (tableName, tries) ->
                                trieCatalog.addTries(
                                    TableRef.parse(dbState.name, tableName),
                                    tries,
                                    record.logTimestamp,
                                )
                            }
                        }

                        appendToReplica(
                            ReplicaMessage.TriesAdded(
                                msg.storageVersion, msg.storageEpoch, msg.tries, sourceMsgId = msgId,
                            ),
                        )

                        latestSourceMsgId = msgId
                        watchers.notifyMsg(msgId)
                    }

                    // TODO this one's going before release
                    is SourceMessage.BlockUploaded -> {
                        latestSourceMsgId = msgId
                        watchers.notifyMsg(msgId)
                    }
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Interrupted) {
                throw e
            } catch (e: Throwable) {
                LOG.error(
                    e,
                    "[$dbName] leader: failed to process log record with msgId $msgId (${record.message::class.simpleName})",
                )
                watchers.notifyError(e)
                throw e
            }
        }
    }

    override fun close() {
        allocator.close()
    }
}
