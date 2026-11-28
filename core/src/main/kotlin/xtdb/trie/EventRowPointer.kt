package xtdb.trie

import org.apache.arrow.memory.util.ArrowBufPointer
import xtdb.api.error.Fault
import xtdb.arrow.LongLongVectorReader
import xtdb.arrow.RelationReader
import java.lang.Long.compareUnsigned

class EventRowPointer(private val relReader: RelationReader, path: ByteArray?) {
    private val iidReader = relReader["_iid"] as LongLongVectorReader

    private val sysFromReader = relReader["_system_from"]
    private val validFromReader = relReader["_valid_from"]
    private val validToReader = relReader["_valid_to"]

    private val opReader = relReader["op"]

    // type ids differ between pages: `put` is 0 in one and 2 in another
    private val opsByTypeId = opReader.legNames!!.map(EventOp::ofLegOrNull).toTypedArray()

    var iidHigh: Long = -1
        private set

    var iidLow: Long = -1
        private set

    var systemFrom: Long = -1
        private set

    private fun indexOf(path: ByteArray): Int {
        var left = 0
        var right = relReader.rowCount
        var mid: Int
        while (left < right) {
            mid = (left + right) / 2
            if (Bucketer.DEFAULT.compareToPath(iidReader.getPointer(mid), path) < 0) left = mid + 1
            else right = mid
        }

        return left
    }

    private var iidRunEnd = 0

    var index: Int = -1
        private set(value) {
            field = value
            if (value < relReader.rowCount) {
                if (value >= iidRunEnd) {
                    iidHigh = iidReader.getLongLongHigh(value)
                    iidLow = iidReader.getLongLongLow(value)
                    iidRunEnd = iidReader.storedRunEnd(value)
                }
                systemFrom = sysFromReader.getLong(value)
            }
        }

    val minIndex: Int = if (path == null) 0 else indexOf(path)

    init {
        // done separately s.t. the setter is called
        index = minIndex
    }

    val maxIndex: Int = path?.let { Bucketer.DEFAULT.incrementPath(it)?.let { p -> indexOf(p) } } ?: relReader.rowCount

    fun nextIndex() = ++index

    fun skipToNextIid() {
        index = iidReader.equalRunEnd(index, maxIndex)
    }

    private fun iidCompare(row: Int, high: Long, low: Long): Int {
        val h = iidReader.getLongLongHigh(row)
        return if (h != high) compareUnsigned(h, high) else compareUnsigned(iidReader.getLongLongLow(row), low)
    }

    /**
     * Moves forward to the first row whose iid is >= the given one, galloping from the current index.
     * Returns whether that row holds exactly this iid.
     * Forward-only: the target MUST NOT precede the iid this pointer is already on.
     */
    fun seekToIid(high: Long, low: Long): Boolean {
        if (index >= maxIndex) return false

        if (iidCompare(index, high, low) < 0) {
            var before = index // known to be < target
            var step = 1
            while (before + step < maxIndex && iidCompare(before + step, high, low) < 0) {
                before += step
                step *= 2
            }
            var after = minOf(before + step, maxIndex) // >= target, or maxIndex
            while (after - before > 1) {
                val mid = (before + after) ushr 1
                if (iidCompare(mid, high, low) < 0) before = mid else after = mid
            }
            index = after
        }

        return index < maxIndex && sameIidAs(high, low)
    }

    fun getIidPointer(reuse: ArrowBufPointer) = iidReader.getPointer(index, reuse)

    val validFrom get() = validFromReader.getLong(index)
    val validTo get() = validToReader.getLong(index)
    val op: EventOp
        get() = opsByTypeId[opReader.getTypeId(index).toInt()]
            ?: opReader.getLeg(index).let { leg ->
                throw Fault("unknown op leg: $leg", "xtdb/unknown-op-leg", mapOf("leg" to leg))
            }

    fun isValid(): Boolean = index < maxIndex

    fun sameIidAs(iidHigh: Long, iidLow: Long) = this.iidHigh == iidHigh && this.iidLow == iidLow

    companion object {
        /** Orders events by iid ascending (unsigned), then newest system-time first. */
        @JvmStatic
        fun compare(l: EventRowPointer, r: EventRowPointer): Int {
            if (l.iidHigh != r.iidHigh) return compareUnsigned(l.iidHigh, r.iidHigh)
            if (l.iidLow != r.iidLow) return compareUnsigned(l.iidLow, r.iidLow)
            return r.systemFrom.compareTo(l.systemFrom)
        }
    }
}
