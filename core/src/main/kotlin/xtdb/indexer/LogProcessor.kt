package xtdb.indexer

import xtdb.api.TransactionResult
import xtdb.api.log.MessageId
import java.util.concurrent.CompletableFuture

interface LogProcessor {
    val ingestionError: Throwable?
    val latestProcessedMsgId: MessageId
    val latestProcessedOffset: Long
    val latestSubmittedMsgId: MessageId

    fun awaitAsync(msgId: MessageId = latestSubmittedMsgId): CompletableFuture<TransactionResult?>
}
