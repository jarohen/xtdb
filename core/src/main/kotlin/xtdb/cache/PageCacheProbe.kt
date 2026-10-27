package xtdb.cache

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.file.Path

/**
 * Asks the kernel (cachestat) whether a byte range of a file is entirely in the OS page cache.
 * Faulting nothing in: unlike a buffered NOWAIT read, a probe of a cold range doesn't trigger readahead.
 */
internal object PageCacheProbe {
    private const val PAGE = 4096L
    private val linker = Linker.nativeLinker()
    private val std = linker.defaultLookup()

    private fun handle(name: String, fd: FunctionDescriptor) = linker.downcallHandle(std.find(name).get(), fd)

    private val open = handle("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    private val close = handle("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    // syscall(SYS_cachestat = 451, fd, &range, &cstat, 0) - Linux 6.5+; variadic after the syscall number
    private val syscall = linker.downcallHandle(
        std.find("syscall").get(),
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT),
        Linker.Option.firstVariadicArg(1),
    )
    private const val SYS_CACHESTAT = 451L

    private fun cString(arena: Arena, s: String): MemorySegment {
        val bytes = s.toByteArray()
        val seg = arena.allocate(bytes.size + 1L)
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, seg, 0, bytes.size.toLong())
        return seg
    }

    fun isResident(path: Path, offset: Long, length: Long): Boolean {
        if (length <= 0) return true
        val firstPage = offset / PAGE
        val lastPage = (offset + length - 1) / PAGE
        val nPages = lastPage - firstPage + 1

        return Arena.ofConfined().use { arena ->
            val fd = open.invoke(cString(arena, path.toString()), 0) as Int
            if (fd < 0) return false
            try {
                val range = arena.allocate(16)
                range.set(JAVA_LONG, 0, offset)
                range.set(JAVA_LONG, 8, length)
                val cstat = arena.allocate(40)
                if (syscall.invoke(SYS_CACHESTAT, fd, range, cstat, 0) as Long != 0L) return false
                cstat.get(JAVA_LONG, 0) >= nPages
            } finally {
                close.invoke(fd) as Int
            }
        }
    }
}
