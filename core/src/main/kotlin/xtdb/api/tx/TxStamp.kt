package xtdb.api.tx

import xtdb.api.TableRef
import xtdb.api.TransactionKey
import xtdb.types.MessageId
import java.time.Instant

/**
 * A transaction's id, and the times the leader decides, once, for it.
 *
 * [ingestTime] strictly increases across every transaction: DDL effects, and the transaction's own `xt.txs` row, land at it.
 * [systemTime] is the DML clock's — an explicit `SYSTEM_TIME` may put it behind [ingestTime] — and DML effects land at it.
 * It is null where the transaction staged no data rows, which moves nothing on that clock.
 */
data class TxStamp(val txId: MessageId, val ingestTime: Instant, val systemTime: Instant?) {
    /** The key a transaction is known by: its system time where it moved the DML clock, else its ingest time. */
    val txKey: TransactionKey get() = TransactionKey(txId, systemTime ?: ingestTime)

    companion object {
        @JvmStatic
        fun dml(txId: MessageId, at: Instant) = TxStamp(txId, at, at)
    }
}

/**
 * A partition's latest completed transaction, and where its two clocks stand after it.
 *
 * [ingestTime] is [latestCompletedTx]'s ingest time; [systemTime] is the latest DML transaction's system time,
 * null until there has been one.
 */
data class TxHead(val latestCompletedTx: TransactionKey, val ingestTime: Instant, val systemTime: Instant?) {

    fun after(stamp: TxStamp) =
        TxHead(stamp.txKey, stamp.ingestTime, stamp.systemTime ?: systemTime)

    companion object {
        /** The head of a partition that recorded one time per transaction: both clocks stood at it. */
        @JvmStatic
        fun ofKey(txKey: TransactionKey) = TxHead(txKey, txKey.systemTime, txKey.systemTime)

        @JvmStatic
        fun after(prev: TxHead?, stamp: TxStamp) =
            prev?.after(stamp) ?: TxHead(stamp.txKey, stamp.ingestTime, stamp.systemTime)
    }
}

/**
 * The tables written and read at ingest time rather than system time: `xt.txs`, which records every transaction,
 * and `xt.role_membership`, which only DDL writes.
 */
object IngestTables {
    private val tables = setOf(TableRef("xt", "txs"), TableRef("xt", "role_membership"))

    @JvmStatic
    fun contains(table: TableRef) = table in tables
}
