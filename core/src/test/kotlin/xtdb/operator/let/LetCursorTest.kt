package xtdb.operator.let

import org.apache.arrow.memory.BufferAllocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xtdb.ICursor.Companion.toMaps
import xtdb.test.AllocatorResolver
import xtdb.test.PagesCursor

@ExtendWith(AllocatorResolver::class)
class LetCursorTest {

    @Test
    fun testLetCursor(al: BufferAllocator) {
        PagesCursor(al, listOf(listOf(mapOf("a" to 10)))).use { bound ->
            LetCursor(al, bound) { PagesCursor(al, listOf(listOf(mapOf("b" to 42)))) }
                .use { letCursor ->
                    assertEquals(listOf(mapOf("b" to 42)), letCursor.toMaps())
                }
        }
    }
}