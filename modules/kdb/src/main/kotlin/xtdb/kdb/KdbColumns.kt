package xtdb.kdb

import com.kx.c
import org.apache.arrow.memory.BufferAllocator
import xtdb.arrow.Relation
import xtdb.arrow.Vector
import xtdb.arrow.Vector.Companion.openVector
import xtdb.arrow.VectorType
import xtdb.arrow.VectorType.Companion.BOOL
import xtdb.arrow.VectorType.Companion.DATE_DAY
import xtdb.arrow.VectorType.Companion.DURATION_MICRO
import xtdb.arrow.VectorType.Companion.F32
import xtdb.arrow.VectorType.Companion.F64
import xtdb.arrow.VectorType.Companion.I16
import xtdb.arrow.VectorType.Companion.I32
import xtdb.arrow.VectorType.Companion.I64
import xtdb.arrow.VectorType.Companion.I8
import xtdb.arrow.VectorType.Companion.INSTANT
import xtdb.arrow.VectorType.Companion.TIME_MICRO
import xtdb.arrow.VectorType.Companion.UTF8
import xtdb.arrow.VectorType.Companion.UUID
import xtdb.util.closeAllOnCatch
import xtdb.util.closeOnCatch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * One kdb+ column, as javakdb deserialises it: a primitive array, `String[]` (symbols), `Instant[]` (timestamps),
 * `c.Timespan[]`, `LocalDate[]`, `LocalTime[]`, `UUID[]`, or an `Object[]` of `char[]` (a list of strings).
 *
 * kdb+ nulls (`0N`, `0n`, ...) become Arrow nulls for the types where it's cheap to tell.
 */
private fun kdbColumn(col: Any): Pair<VectorType, List<Any?>> = when (col) {
    is BooleanArray -> BOOL to col.toList()
    is ByteArray -> I8 to col.toList()
    is ShortArray -> I16 to col.map { it.takeUnless { v -> v == Short.MIN_VALUE } }
    is IntArray -> I32 to col.map { it.takeUnless { v -> v == Int.MIN_VALUE } }
    is LongArray -> I64 to col.map { it.takeUnless { v -> v == Long.MIN_VALUE } }
    is FloatArray -> F32 to col.map { it.takeUnless { v -> v.isNaN() } }
    is DoubleArray -> F64 to col.map { it.takeUnless { v -> v.isNaN() } }
    is CharArray -> UTF8 to col.map { it.toString() }
    is Array<*> -> when {
        col.isArrayOf<String>() -> UTF8 to col.toList()
        col.isArrayOf<Instant>() ->
            INSTANT to col.map { (it as Instant).takeUnless { i -> i == Instant.MIN }?.truncatedTo(ChronoUnit.MICROS) }
        col.isArrayOf<LocalDate>() -> DATE_DAY to col.map { (it as LocalDate).takeUnless { d -> d == LocalDate.MIN } }
        col.isArrayOf<LocalTime>() -> TIME_MICRO to col.toList()
        col.isArrayOf<java.util.UUID>() -> UUID to col.toList()
        col.isArrayOf<c.Timespan>() ->
            DURATION_MICRO to col.map { (it as c.Timespan).j.takeUnless { j -> j == Long.MIN_VALUE }?.let { j -> Duration.ofNanos(j / 1000 * 1000) } }
        // a list of strings: each element a char[]; an empty list is indistinguishable from any other empty list
        col.all { it is CharArray } -> UTF8 to col.map { String(it as CharArray) }
        else -> throw IllegalArgumentException("unsupported kdb+ column type: ${col.javaClass.simpleName}")
    }

    else -> throw IllegalArgumentException("unsupported kdb+ column type: ${col.javaClass.simpleName}")
}

/** Converts a kdb+ table (column names + column vectors, all the same length) to an Arrow relation, which the caller owns. */
internal fun BufferAllocator.openTickRelation(names: List<String>, cols: List<Any>): Relation {
    require(names.size == cols.size) { "kdb+ update has ${cols.size} columns, expected ${names.size}: $names" }

    val vecs = mutableListOf<Vector>().closeAllOnCatch { acc ->
        names.indices.forEach { idx ->
            val (type, values) = kdbColumn(cols[idx])
            val nullable = values.any { it == null }

            acc += openVector(names[idx], VectorType.maybe(type, nullable)).closeOnCatch { vec ->
                values.forEach { vec.writeObject(it) }
                vec
            }
        }
        acc
    }

    return Relation(this, vecs, vecs.firstOrNull()?.valueCount ?: 0)
}

/** The column names and vectors of a kdb+ table sent as a flip (a table proper) or as a bare list of column vectors. */
internal fun tickColumns(data: Any?, schemaNames: List<String>?): Pair<List<String>, List<Any>> = when (data) {
    is c.Flip -> data.x.toList() to data.y.map { it!! }
    is Array<*> -> {
        requireNotNull(schemaNames) { "got a bare list of columns, but no schema for the table" }
        schemaNames to data.map { it!! }
    }

    else -> throw IllegalArgumentException("unsupported kdb+ update payload: ${data?.javaClass?.simpleName}")
}
