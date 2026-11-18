package xtdb.arrow

import xtdb.api.error.Fault

enum class CmpOp { LT, LE, GT, GE, EQ, NE }

/**
 * Throws unless every row [evalWords] reads for ([mask], [sel]) lies inside this vector's data buffer.
 * [evalWords] reads every row of each non-empty mask word, not only the set bits, so this covers all of `[0, mask.size)`.
 */
internal fun FixedWidthVector.requireRowsInBounds(mask: RowBits, sel: IntArray?) {
    val n = mask.size

    if (dataBuffer.capacity < valueCount.toLong() * byteWidth)
        throw Fault("data buffer smaller than $valueCount rows of $byteWidth bytes", "xtdb/vector-buffer-short")

    if (sel == null) {
        if (n > valueCount) throw Fault("mask of $n rows over $valueCount rows", "xtdb/mask-out-of-bounds")
    } else {
        if (n > sel.size) throw Fault("mask of $n rows over a selection of ${sel.size}", "xtdb/mask-out-of-bounds")
        for (i in 0 until n) {
            val idx = sel[i]
            if (idx < 0 || idx >= valueCount)
                throw Fault("selected row $idx outside $valueCount rows", "xtdb/mask-out-of-bounds")
        }
    }
}

/**
 * Evaluates [test] over the rows [mask] selects, a word at a time: bit `i` is row `sel[i]` of this vector, or row `i` if [sel] is null.
 * The result has the bits of [mask] that are non-null and pass [test].
 */
internal inline fun FixedWidthVector.evalWords(mask: RowBits, sel: IntArray?, test: (Int) -> Boolean): RowBits {
    val inWords = mask.words
    val n = mask.size
    val out = LongArray(inWords.size)
    val validity = validityBuffer

    for (w in inWords.indices) {
        val m = inWords[w]
        if (m == 0L) continue

        val base = w shl 6
        val len = minOf(64, n - base)
        var bits = 0L
        var valid = -1L

        if (sel == null) {
            for (b in 0 until len) bits = bits or ((if (test(base + b)) 1L else 0L) shl b)
            if (validity != null) valid = validity.wordAt(w)
        } else {
            for (b in 0 until len) bits = bits or ((if (test(sel[base + b])) 1L else 0L) shl b)
            if (validity != null) {
                valid = 0L
                for (b in 0 until len) if (validity.getBoolean(sel[base + b])) valid = valid or (1L shl b)
            }
        }

        out[w] = bits and m and valid
    }

    return RowBits(out, n)
}

internal inline fun FixedWidthVector.cmpLongs(
    op: CmpOp, rhs: Long, mask: RowBits, sel: IntArray?, crossinline read: (Int) -> Long
): RowBits = when (op) {
    CmpOp.LT -> evalWords(mask, sel) { read(it) < rhs }
    CmpOp.LE -> evalWords(mask, sel) { read(it) <= rhs }
    CmpOp.GT -> evalWords(mask, sel) { read(it) > rhs }
    CmpOp.GE -> evalWords(mask, sel) { read(it) >= rhs }
    CmpOp.EQ -> evalWords(mask, sel) { read(it) == rhs }
    CmpOp.NE -> evalWords(mask, sel) { read(it) != rhs }
}

internal inline fun FixedWidthVector.cmpDoubles(
    op: CmpOp, rhs: Double, mask: RowBits, sel: IntArray?, crossinline read: (Int) -> Double
): RowBits = when (op) {
    CmpOp.LT -> evalWords(mask, sel) { read(it) < rhs }
    CmpOp.LE -> evalWords(mask, sel) { read(it) <= rhs }
    CmpOp.GT -> evalWords(mask, sel) { read(it) > rhs }
    CmpOp.GE -> evalWords(mask, sel) { read(it) >= rhs }
    CmpOp.EQ -> evalWords(mask, sel) { read(it) == rhs }
    CmpOp.NE -> evalWords(mask, sel) { read(it) != rhs }
}

internal fun FixedWidthVector.notNullBits(mask: RowBits, sel: IntArray?): RowBits = evalWords(mask, sel) { true }
