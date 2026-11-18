package xtdb.arrow

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.ipc.message.ArrowFieldNode
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import xtdb.api.query.IKeyFn.KeyFn.SNAKE_CASE_STRING
import xtdb.test.AllocatorResolver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneId

@ExtendWith(AllocatorResolver::class)
class FixedWidthRowIteratorTest {
    private val rowCount = 200

    private fun isNullRow(idx: Int) = idx % 7 == 3 || idx == 63 || idx == 64

    private fun rawValue(width: Int, idx: Int): Long = when (width) {
        1 -> idx.toLong()
        2 -> (idx + 1) * 7L
        4 -> (idx + 1) * 301L
        else -> (idx + 1) * 86_400_123L
    }

    private fun FixedWidthVector.fill(withNulls: Boolean) = apply {
        repeat(rowCount) { idx ->
            if (withNulls && isNullRow(idx)) writeNull()
            else {
                val le = ByteBuffer.allocate(maxOf(byteWidth, Long.SIZE_BYTES)).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(0, rawValue(byteWidth, idx))
                writeBytes(ByteBuffer.wrap(le.array(), 0, byteWidth))
            }
        }
    }

    private val vectors: Map<String, (BufferAllocator, Boolean) -> FixedWidthVector> = mapOf(
        "long" to { al, n -> LongVector(al, "v", n) },
        "int" to { al, n -> IntVector.open(al, "v", n) },
        "short" to { al, n -> ShortVector(al, "v", n) },
        "byte" to { al, n -> ByteVector(al, "v", n) },
        "double" to { al, n -> DoubleVector(al, "v", n) },
        "float" to { al, n -> FloatVector(al, "v", n) },
        "decimal" to { al, n -> DecimalVector(al, "v", n, ArrowType.Decimal(38, 2, 128)) },
        "date-day" to { al, n -> DateDayVector(al, "v", n) },
        "date-milli" to { al, n -> DateMilliVector(al, "v", n) },
        "time32" to { al, n -> Time32Vector(al, "v", n, TimeUnit.SECOND) },
        "time64" to { al, n -> Time64Vector(al, "v", n, TimeUnit.NANOSECOND) },
        "timestamp" to { al, n -> TimestampLocalVector(al, "v", n, TimeUnit.MICROSECOND) },
        "timestamp-tz" to { al, n -> TimestampTzVector(al, "v", n, TimeUnit.MICROSECOND, ZoneId.of("Europe/London")) },
        "duration" to { al, n -> DurationVector(al, "v", n, TimeUnit.MICROSECOND) },
        "fixed-size-binary" to { al, n -> FixedSizeBinaryVector(al, "v", n, 16) },
        "interval-year-month" to { al, n -> IntervalYearMonthVector(al, "v", n) },
        "interval-day-time" to { al, n -> IntervalDayTimeVector(al, "v", n) },
        "interval-month-day-nano" to { al, n -> IntervalMonthDayNanoVector(al, "v", n) },
    )

    private fun outcome(read: () -> Any?): Any? =
        runCatching(read).fold(
            { if (it is ByteArray) it.toList() else it },
            { it::class }
        )

    private fun reads(rdr: ValueReader): List<Any?> = listOf(
        outcome { rdr.isNull },
        outcome { rdr.leg },
        outcome { rdr.readBoolean() },
        outcome { rdr.readByte() },
        outcome { rdr.readShort() },
        outcome { rdr.readInt() },
        outcome { rdr.readLong() },
        outcome { rdr.readFloat() },
        outcome { rdr.readDouble() },
        outcome { rdr.readAsLong() },
        outcome { rdr.readAsDouble() },
        outcome { rdr.readBytes() },
        outcome { rdr.readObject() },
    )

    private fun checkRow(rdr: VectorReader, expected: ValueReader, row: RowValueReader, what: String) {
        expected.pos = row.pos
        assertEquals(reads(expected), reads(row), "$what, row ${row.pos}")
        assertEquals(outcome { rdr.getObject(row.pos, SNAKE_CASE_STRING) }, outcome { row.readValue(SNAKE_CASE_STRING) }, "$what, row ${row.pos}")
    }

    private fun shapes(vec: VectorReader) = mapOf(
        "direct" to vec,
        "select(IntArray)" to vec.select(IntArray(150) { (it * 37) % rowCount }),
        "select(start, len)" to vec.select(60, 90),
        "slice of a selection" to vec.select(IntArray(150) { (it * 11) % rowCount }).select(30, 100),
        "selection of a slice" to vec.select(10, 150).select(IntArray(80) { (it * 13) % 150 }),
    )

    @Test
    fun `rows read as the vector's valueReader and getObject, for every fixed-width vector`(al: BufferAllocator) {
        vectors.forEach { (label, open) ->
            listOf(true, false).forEach { withNulls ->
                open(al, withNulls).fill(withNulls).use { vec ->
                    shapes(vec).forEach { (shape, rdr) ->
                        val what = "$label, nulls=$withNulls, $shape"
                        val expected = rdr.valueReader()
                        var count = 0
                        rdr.rowIterator().forEach { row ->
                            assertEquals(count++, row.pos, what)
                            checkRow(rdr, expected, row, what)
                        }
                        assertEquals(rdr.valueCount, count, what)
                    }
                }
            }
        }
    }

    @Test
    fun `a row reads only its vector's own type, as getX does`(al: BufferAllocator) {
        IntVector.open(al, "i", false).fill(false).use { vec ->
            val row = vec.rowIterator().next()
            assertEquals(vec.getInt(0), row.readInt())
            assertThrows<UnsupportedOperationException> { row.readLong() }
            assertThrows<UnsupportedOperationException> { row.readShort() }
        }

        ShortVector(al, "s", false).fill(false).use { vec ->
            val row = vec.rowIterator().next()
            assertEquals(vec.getShort(0), row.readShort())
            assertThrows<UnsupportedOperationException> { row.readInt() }
            assertThrows<UnsupportedOperationException> { row.readLong() }
        }

        FloatVector(al, "f", false).fill(false).use { vec ->
            val row = vec.rowIterator().next()
            assertEquals(vec.getFloat(0), row.readFloat())
            assertThrows<UnsupportedOperationException> { row.readDouble() }
        }

        DateMilliVector(al, "d", false).fill(false).use { vec ->
            val row = vec.rowIterator().next()
            assertEquals(86_400_123L, row.readLong())
            assertThrows<UnsupportedOperationException> { row.readInt() }
        }
    }

    @Test
    fun `seeking across a validity word reads the row sought`(al: BufferAllocator) {
        val seeks = listOf(0, 63, 64, 130, 65, 62, 5, 199, 128, 127, 64, 63)

        vectors.forEach { (label, open) ->
            open(al, true).fill(true).use { vec ->
                mapOf(
                    "direct" to vec,
                    "reversed selection" to vec.select(IntArray(rowCount) { rowCount - 1 - it }),
                    "slice" to vec.select(0, rowCount),
                ).forEach { (shape, rdr) ->
                    val what = "$label, $shape"
                    val expected = rdr.valueReader()
                    val row = rdr.rowIterator()
                    seeks.forEach { idx ->
                        row.pos = idx
                        checkRow(rdr, expected, row, "$what, seek to $idx")
                    }

                    row.pos = 62
                    checkRow(rdr, expected, row.next(), "$what, next after seek")
                    checkRow(rdr, expected, row.next(), "$what, next after seek")
                    assertEquals(64, row.pos, what)
                }
            }
        }
    }

    @Test
    fun `a validity buffer shorter than a word reads its nulls`(al: BufferAllocator) {
        val rows = 10
        LongVector(al, "v", true).use { vec ->
            al.buffer(2).use { validity ->
                al.buffer(rows * 8L).use { data ->
                    validity.setZero(0, validity.capacity())
                    repeat(rows) { idx ->
                        if (idx != 3 && idx != 9) validity.setByte(idx / 8L, validity.getByte(idx / 8L).toInt() or (1 shl (idx % 8)))
                        data.setLong(idx * 8L, idx * 10L)
                    }
                    vec.loadPage(mutableListOf(ArrowFieldNode(rows.toLong(), 2)), mutableListOf(validity, data))
                }
            }

            assertEquals(2L, vec.validityBuffer!!.buf.capacity())
            assertEquals(
                List(rows) { if (it == 3 || it == 9) null else it * 10L },
                vec.rowIterator().asSequence().map { if (it.isNull) null else it.readLong() }.toList()
            )
        }
    }

    @Test
    fun `a row iterator reads its rows while the vector grows under it`(al: BufferAllocator) {
        LongVector(al, "v", true).use { vec ->
            repeat(10) { if (it == 4) vec.writeNull() else vec.writeLong(it.toLong()) }

            val rows = vec.rowIterator()
            val seen = mutableListOf<Long?>()
            repeat(3) { rows.next().let { seen += if (it.isNull) null else it.readLong() } }

            repeat(100_000) { vec.writeLong(-1L) }

            rows.forEach { seen += if (it.isNull) null else it.readLong() }
            assertEquals(List(10) { if (it == 4) null else it.toLong() }, seen)
            assertFalse(rows.hasNext())
        }
    }
}
