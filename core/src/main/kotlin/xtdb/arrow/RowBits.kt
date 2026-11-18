package xtdb.arrow

/**
 * A set of row indices below [size], one bit per row.
 * Bits at or above [size] are always zero.
 */
class RowBits(internal val words: LongArray, val size: Int) {

    infix fun and(other: RowBits) = RowBits(LongArray(words.size) { words[it] and other.words[it] }, size)
    infix fun or(other: RowBits) = RowBits(LongArray(words.size) { words[it] or other.words[it] }, size)
    infix fun andNot(other: RowBits) = RowBits(LongArray(words.size) { words[it] and other.words[it].inv() }, size)

    fun isSet(idx: Int) = (words[idx ushr 6] ushr (idx and 63)) and 1L != 0L

    fun cardinality() = words.sumOf { java.lang.Long.bitCount(it) }

    /** the set rows, ascending */
    fun toSelection(): IntArray {
        val out = IntArray(cardinality())
        var n = 0
        for (w in words.indices) {
            var word = words[w]
            val base = w shl 6
            while (word != 0L) {
                out[n++] = base + java.lang.Long.numberOfTrailingZeros(word)
                word = word and (word - 1)
            }
        }
        return out
    }

    companion object {
        private fun wordCount(n: Int) = (n + 63) ushr 6

        @JvmStatic
        fun none(size: Int) = RowBits(LongArray(wordCount(size)), size)

        @JvmStatic
        fun all(size: Int): RowBits {
            val words = LongArray(wordCount(size)) { -1L }
            val tail = size and 63
            if (tail != 0) words[words.size - 1] = (1L shl tail) - 1
            return RowBits(words, size)
        }
    }
}
