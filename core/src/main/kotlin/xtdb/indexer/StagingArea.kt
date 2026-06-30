package xtdb.indexer

import xtdb.api.TransactionKey
import xtdb.api.log.ReplicaMessage

/**
 * The leader resolver's staging area: committed-but-not-yet-durable transactions, held between the
 * resolver applying a tx and the replica writer confirming it durable on the replica log. The
 * resolver is the sole accessor, so no lock is needed — single-threaded by construction.
 *
 * Standing state owned by [LeaderLogProcessor]: created when it becomes leader, freed in its `close()`
 * (phase two of the two-phase teardown), not by the resolver coroutine.
 *
 * Two slots: [accumulating] takes newly-resolved txs; [committing] is the batch handed to the replica
 * writer and awaiting its durable confirmation. Under the serial commit the resolver seals each tx and
 * awaits the durable write before resolving the next, so at most one tx is ever in either slot and
 * [committing] is always promoted before the next is applied. The slots and the durable/applied head
 * split are in place for the pipelining increment, where the resolver runs ahead and a batch
 * accumulates while the previous one commits.
 */
internal class StagingArea(latestCompletedTx: TransactionKey?) : AutoCloseable {
    private val accumulating = ArrayDeque<ReplicaMessage.ResolvedTx>()

    /** The batch handed to the replica writer, awaiting durable confirmation; null when idle. */
    var committing: ReplicaMessage.ResolvedTx? = null
        private set

    /**
     * The staging area's own latest-completed-tx — the applied / resolution head. Drives the next
     * external-source tx-id and system-time smoothing. Seeded from the durable head
     * ([LiveIndex.latestCompletedTx]) when the area is created, then advanced as txs are applied; it
     * leads the durable head by the in-flight txs, and is equal to it under the serial commit.
     */
    var latestCompletedTx: TransactionKey? = latestCompletedTx
        private set

    /** True while a batch is in flight to the replica writer. */
    val inFlight get() = committing != null

    fun apply(resolvedTx: ReplicaMessage.ResolvedTx) {
        accumulating.addLast(resolvedTx)
        latestCompletedTx = TransactionKey(resolvedTx.txId, resolvedTx.systemTime)
    }

    /** Seal the accumulated txs into the committing slot, to be written to the replica log. */
    fun seal(): ReplicaMessage.ResolvedTx {
        check(committing == null) { "a batch is already in flight" }
        return accumulating.removeFirst().also { committing = it }
    }

    /** Promote the committing batch into the durable live index, once it's durably on the replica log. */
    fun promote(liveIndex: LiveIndex): ReplicaMessage.ResolvedTx {
        val tx = checkNotNull(committing) { "nothing is committing" }
        liveIndex.importTx(tx)
        committing = null
        return tx
    }

    override fun close() {
        accumulating.clear()
        committing = null
    }
}
