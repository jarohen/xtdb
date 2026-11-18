package xtdb.arrow

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.types.pojo.ArrowType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import xtdb.api.query.IKeyFn.KeyFn.SNAKE_CASE_STRING
import xtdb.arrow.VectorType.Companion.I64
import xtdb.arrow.VectorType.Companion.UTF8
import xtdb.arrow.VectorType.Companion.listy
import xtdb.arrow.VectorType.Companion.mapTypeOf
import xtdb.test.AllocatorResolver
import xtdb.time.Interval
import xtdb.types.ZonedDateTimeRange
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@ExtendWith(AllocatorResolver::class)
class RowIteratorTest {

    private fun zdt(sec: Long) = ZonedDateTime.ofInstant(Instant.ofEpochSecond(sec), ZoneId.of("UTC"))

    private val vectors: Map<String, (BufferAllocator) -> VectorReader> = mapOf(
        "i64" to { al -> Vector.fromList(al, "col", listOf(1L, 2L, 3L, 4L, 5L)) },
        "nullable utf8" to { al -> Vector.fromList(al, "col", listOf("a", null, "c", "d", null)) },
        "f64" to { al -> Vector.fromList(al, "col", listOf(1.5, 2.5, 3.5, 4.5, 5.5)) },
        "bool" to { al -> Vector.fromList(al, "col", listOf(true, false, true, true, false)) },
        "timestamp" to { al -> Vector.fromList(al, "col", List(5) { Instant.ofEpochSecond(it.toLong()) }) },
        "duration" to { al -> Vector.fromList(al, "col", List(5) { Duration.ofSeconds(it.toLong()) }) },
        "uuid" to { al -> Vector.fromList(al, "col", List(5) { UUID(0, it.toLong()) }) },
        "uri" to { al -> Vector.fromList(al, "col", List(5) { URI("https://example.com/$it") }) },
        "interval" to { al -> Vector.fromList(al, "col", List(5) { Interval(it, it + 1, it * 1_000L) }) },
        "decimal" to { al -> Vector.fromList(al, "col", List(5) { BigDecimal("$it.25") }) },
        "list" to { al -> Vector.fromList(al, "col", List(5) { listOf(it, it + 1) }) },
        "set" to { al -> Vector.fromList(al, "col", List(5) { setOf(it, it + 10) }) },
        "fixed-size list" to { al ->
            Vector.fromList(al, "col", listy(ArrowType.FixedSizeList(2), I64), List(5) { listOf(it.toLong(), it + 1L) })
        },
        "map" to { al ->
            Vector.fromList(al, "col", mapTypeOf(I64, UTF8), List(5) { mapOf(it.toLong() to "v$it") })
        },
        "struct" to { al -> Vector.fromList(al, "col", List(5) { mapOf("fooBar" to it, "baz" to "s$it") }) },
        "union" to { al -> Vector.fromList(al, "col", listOf(1L, "two", 3.0, null, listOf(5L))) },
        "tstz-range" to { al ->
            Vector.fromList(al, "col", List(5) { ZonedDateTimeRange(zdt(it.toLong()), if (it % 2 == 0) null else zdt(it + 10L)) })
        },
        "run-end encoded" to { al ->
            RunEndEncodedVector(al, "col", Utf8Vector(al, REE_VALUES_NAME, true))
                .also { it.writeRun("a", 2); it.writeRun(null, 1); it.writeRun("b", 2) }
        },
        "relation as struct" to { al ->
            RelationAsStructReader("col", Relation.openFromRows(al, List(5) { mapOf("fooBar" to it.toLong()) }))
        },
        "renamed" to { al -> Vector.fromList(al, "col", listOf(1L, null, 3L, 4L, 5L)).withName("renamed") },
    )

    private fun checkAgainstGetObject(rdr: VectorReader, what: String) {
        assertEquals(List(rdr.valueCount) { rdr.getObject(it, SNAKE_CASE_STRING) }, rdr.toList(SNAKE_CASE_STRING), "$what, toList")
        assertEquals(List(rdr.valueCount) { rdr.getObject(it) }, rdr.asList, "$what, asList")
    }

    private fun checkSelections(vec: VectorReader, label: String) {
        checkAgainstGetObject(vec.select(intArrayOf(4, 0, 2, 2)), "$label, select(IntArray)")
        checkAgainstGetObject(vec.select(1, 3), "$label, select(start, len)")
        checkAgainstGetObject(vec.select(intArrayOf(4, 0, 2)).select(intArrayOf(2, 1)), "$label, nested select")
        checkAgainstGetObject(vec.select(1, 3).select(1, 2), "$label, nested slice")
    }

    @Test
    fun `toList and asList agree with getObject, directly and through selections`(al: BufferAllocator) {
        vectors.forEach { (label, open) ->
            open(al).use { vec ->
                checkAgainstGetObject(vec, "$label, direct")
                checkSelections(vec, label)
            }
        }
    }

    @Test
    fun `a dense union's leg reads through rowIterator, directly and through selections`(al: BufferAllocator) {
        Vector.fromList(al, "col", listOf(1L, "a", 2L, "b", 3L)).use { union ->
            val leg = union.vectorFor("i64")
            assertEquals(List(leg.valueCount) { leg.getObject(it, SNAKE_CASE_STRING) }, leg.toList(SNAKE_CASE_STRING))
            assertEquals(listOf(1L, 2L, 3L), leg.asList, "a leg's asList is its own values")
            checkSelections(leg, "i64 leg")
        }
    }

    @Test
    fun `an empty vector has no rows`(al: BufferAllocator) {
        Vector.fromList(al, "col", listOf<Long>()).use { vec ->
            assertEquals(emptyList<Any?>(), vec.toList(SNAKE_CASE_STRING))
            assertEquals(emptyList<Any?>(), vec.asList)

            val rows = vec.rowIterator()
            assertFalse(rows.hasNext())
            assertThrows<NoSuchElementException> { rows.next() }
        }
    }

    @Test
    fun `a relation-as-struct's row reader refuses positioned ValueReader reads`(al: BufferAllocator) {
        RelationAsStructReader("col", Relation.openFromRows(al, List(2) { mapOf("a" to it.toLong()) })).use { vec ->
            val row = vec.rowIterator().next()
            assertThrows<UnsupportedOperationException> { row.readObject() }
            assertThrows<UnsupportedOperationException> { row.isNull }
        }
    }

    @Test
    fun `a singleton list reads through rowIterator, directly and through selections`(al: BufferAllocator) {
        val docs = Relation.openFromRows(al, List(3) { mapOf("fooBar" to it.toLong()) })
        SingletonListReader("col", RelationAsStructReader("docs", docs)).use { vec ->
            checkAgainstGetObject(vec, "direct")
            checkAgainstGetObject(vec.select(intArrayOf(0, 0)), "select(IntArray)")
            checkAgainstGetObject(vec.select(0, 1), "select(start, len)")
        }
    }

    private fun outcome(read: () -> Any?): Any? = runCatching(read).fold({ it }, { it::class })

    private fun ValueReader.snapshot(): Any? = when {
        isNull -> null
        else -> when (val obj = readObject()) {
            is ListValueReader -> List(obj.size()) { obj.nth(it).snapshot() }
            is Map<*, *> -> obj.mapValues { (_, v) -> if (v is ValueReader) v.snapshot() else v }
            else -> obj
        }
    }

    @Test
    fun `row reader reads as the vector's own valueReader, directly and through a selection`(al: BufferAllocator) {
        val shapes = mapOf(
            "list" to listOf(listOf(1L, 2L), null, listOf(3L), listOf(), listOf(4L, 5L)),
            "struct" to listOf(mapOf("a" to 1L, "b" to "x"), null, mapOf("a" to 2L, "b" to "y"), mapOf("a" to 3L, "b" to null), mapOf("a" to 4L, "b" to "z")),
            "union" to listOf(1L, "two", null, listOf(4L), 5L),
            "interval" to List(5) { Interval(it, it + 1, it * 1_000L) },
            "integer union" to listOf((-12).toShort(), Int.MIN_VALUE, null, Long.MAX_VALUE, 7.toShort()),
            "floating-point union" to listOf(1.5f, -2.25, null, Double.MAX_VALUE, Float.MIN_VALUE),
        )

        shapes.forEach { (label, values) ->
            Vector.fromList(al, "col", values).use { vec ->
                fun check(rdr: VectorReader, how: String) {
                    val expected = rdr.valueReader()
                    rdr.rowIterator().forEach { row ->
                        expected.pos = row.pos
                        val what = "$label, $how, row ${row.pos}"
                        assertEquals(expected.isNull, row.isNull, what)
                        assertEquals(expected.snapshot(), row.snapshot(), what)
                        if (label == "union") assertEquals(expected.leg, row.leg, what)
                        if (!row.isNull) {
                            assertEquals(outcome { expected.readAsLong() }, outcome { row.readAsLong() }, what)
                            assertEquals(outcome { expected.readAsDouble() }, outcome { row.readAsDouble() }, what)
                        }
                    }
                }

                check(vec, "direct")
                check(vec.select(intArrayOf(4, 0, 2, 1)), "select(IntArray)")
            }
        }

        Vector.fromList(al, "col", listOf((-12).toShort(), Int.MIN_VALUE, null, Long.MAX_VALUE)).use { vec ->
            assertEquals(
                listOf(-12L, Int.MIN_VALUE.toLong(), null, Long.MAX_VALUE),
                vec.rowIterator().asSequence().map { if (it.isNull) null else it.readAsLong() }.toList()
            )
        }

        Vector.fromList(al, "col", listOf(1.5f, null, -2.25)).use { vec ->
            assertEquals(
                listOf(1.5, null, -2.25),
                vec.select(intArrayOf(0, 1, 2)).rowIterator().asSequence().map { if (it.isNull) null else it.readAsDouble() }.toList()
            )
        }

        Vector.fromList(al, "col", listOf(10L, null, 30L)).use { vec ->
            val rows = vec.select(intArrayOf(2, 1, 0)).rowIterator()
            assertEquals(30L, rows.next().readLong())
            assertEquals(true, rows.next().isNull)
            assertEquals(10L, rows.next().readLong())
        }
    }

    @Test
    fun `rowIterator through a selection hands out one reader, on the logical row`(al: BufferAllocator) {
        Vector.fromList(al, "col", listOf(10L, 20L, 30L, 40L)).use { vec ->
            val rows = vec.select(intArrayOf(3, 1)).rowIterator()

            val first = rows.next()
            assertEquals(0 to 40L, first.pos to first.readLong())

            val second = rows.next()
            assertSame(first, second)
            assertEquals(1 to 20L, second.pos to second.readLong())

            assertFalse(rows.hasNext())
            assertThrows<NoSuchElementException> { rows.next() }
        }
    }
}
