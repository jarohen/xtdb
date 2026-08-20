package xtdb.indexer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import xtdb.api.log.Log
import xtdb.api.log.PartitionLog
import xtdb.api.log.ReplicaMessage
import xtdb.types.MessageId

/**
 * Reads the partition's replica log from [afterMsgId], one record at a time, for whoever is applying.
 *
 * A read failure closes [records] with its cause rather than being reported here, so it surfaces where
 * the records are applied — which is where it surfaced while each role read the log for itself. Records
 * already buffered are delivered first, so a failure no longer discards the batch in flight.
 *
 * One record per element, not one batch: the leader takes these as an arm of a `select` against
 * transaction resolution, and a batch would hold that arm for its whole length.
 */
internal class ReplicaFeed(
    replicaLog: PartitionLog<ReplicaMessage>,
    afterMsgId: MessageId,
    scope: CoroutineScope,
) {
    private val ch = Channel<Log.Record<ReplicaMessage>>(capacity = 128)

    val records: ReceiveChannel<Log.Record<ReplicaMessage>> get() = ch

    private val job = scope.launch {
        try {
            replicaLog.tailAll(afterMsgId) { records -> records.forEach { ch.send(it) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ch.close(e)
        }
    }

    suspend fun cancelAndJoin() = job.cancelAndJoin()
}
