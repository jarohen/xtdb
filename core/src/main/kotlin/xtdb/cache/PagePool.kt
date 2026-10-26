package xtdb.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.arrow.memory.ArrowBuf
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.ForeignAllocation
import org.apache.arrow.memory.RootAllocator
import xtdb.util.logger
import java.lang.foreign.MemorySegment
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val LOGGER = PagePool::class.logger

/**
 * DART: an explicit buffer pool for immutable data pages.
 *
 * - one off-heap region (Unsafe.allocateMemory, so it isn't charged to MaxDirectMemorySize), carved by a buddy allocator
 *   into power-of-two frames (4KiB .. MAX_BLOCK)
 * - (path, offset) -> Frame in a ConcurrentHashMap; a hit is a lookup + CAS pin on the caller's thread
 * - a miss installs a loading placeholder (concurrent misses share it), allocates a frame (evicting under the pool lock),
 *   and suspends while a bounded IO dispatcher preads into the frame (O_DIRECT where possible)
 * - eviction is S3-FIFO (small FIFO, main FIFO, ghost keys), byte-weighted, unpinned frames only
 * - pages are handed out as ArrowBufs over the frame, owned by the pool's own root allocator; the ledger's release unpins
 */
class PagePool(
    val capacityBytes: Long,
    private val direct: Boolean,
    ioParallelism: Int,
) {
    companion object {
        private const val MIN_SHIFT = 12
        private const val MAX_SHIFT = 24
        private const val MIN_BLOCK = 1L shl MIN_SHIFT
        private const val MAX_BLOCK = 1L shl MAX_SHIFT

        private val unsafe: sun.misc.Unsafe =
            sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").let { it.isAccessible = true; it.get(null) as sun.misc.Unsafe }

        private fun parseSize(s: String): Long {
            val t = s.trim().lowercase()
            val mult = when (t.last()) {
                'k' -> 1L shl 10; 'm' -> 1L shl 20; 'g' -> 1L shl 30; else -> 1L
            }
            return (if (mult == 1L) t else t.dropLast(1)).toDouble().times(mult).toLong()
        }

        @JvmStatic
        val INSTANCE: PagePool? by lazy {
            System.getProperty("xtdb.pagePool.bytes")?.let { sz ->
                PagePool(
                    parseSize(sz),
                    direct = System.getProperty("xtdb.pagePool.direct", "true").toBoolean(),
                    ioParallelism = System.getProperty("xtdb.pagePool.io", "8").toInt()
                ).also { LOGGER.info("PagePool: capacity=${it.capacityBytes} direct=${it.direct}") }
            }
        }

        @JvmStatic
        fun owns(buf: ArrowBuf): Boolean {
            val pool = INSTANCE ?: return false
            return buf.referenceManager.allocator === pool.allocator
        }

        private fun orderFor(len: Long): Int {
            var o = MIN_SHIFT
            while ((1L shl o) < len) o++
            return o
        }
    }

    private val regionBytes = (capacityBytes / MAX_BLOCK).coerceAtLeast(1) * MAX_BLOCK
    private val rawAddr = unsafe.allocateMemory(regionBytes + MIN_BLOCK)
    private val baseAddr = (rawAddr + MIN_BLOCK - 1) and (MIN_BLOCK - 1).inv()
    private val region: MemorySegment = MemorySegment.ofAddress(baseAddr).reinterpret(regionBytes)

    val allocator: BufferAllocator = RootAllocator(Long.MAX_VALUE)

    @Suppress("OPT_IN_USAGE")
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(ioParallelism)

    // --- buddy allocator (guarded by lock) ---
    private val freeLists = Array(MAX_SHIFT + 1) { LinkedHashSet<Long>() }

    init {
        var off = 0L
        while (off < regionBytes) {
            freeLists[MAX_SHIFT].add(off); off += MAX_BLOCK
        }
    }

    private fun buddyAlloc(order: Int): Long {
        var k = order
        while (k <= MAX_SHIFT && freeLists[k].isEmpty()) k++
        if (k > MAX_SHIFT) return -1
        val it = freeLists[k].iterator()
        val off = it.next(); it.remove()
        while (k > order) {
            k--
            freeLists[k].add(off + (1L shl k))
        }
        return off
    }

    private fun buddyFree(off0: Long, order0: Int) {
        var off = off0
        var order = order0
        while (order < MAX_SHIFT) {
            val buddy = off xor (1L shl order)
            if (!freeLists[order].remove(buddy)) break
            off = minOf(off, buddy)
            order++
        }
        freeLists[order].add(off)
    }

    // --- frames ---
    data class Key(val path: Path, val offset: Long)

    inner class Frame(val key: Key, val length: Long) {
        // >= 0: pin count; -1: evicted
        val pins = AtomicInteger(1)
        val loaded = CompletableDeferred<Unit>()

        @Volatile
        var freq = 0
        var blockOff = -1L
        var order = 0
        var dataOff = 0L
        var inMain = false

        val frameBytes get() = 1L shl order

        fun tryPin(): Boolean {
            while (true) {
                val p = pins.get()
                if (p < 0) return false
                if (pins.compareAndSet(p, p + 1)) return true
            }
        }

        fun unpin() {
            pins.decrementAndGet()
        }

        fun toArrowBuf(): ArrowBuf =
            allocator.wrapForeignAllocation(object : ForeignAllocation(length, baseAddr + blockOff + dataOff) {
                override fun release0() = unpin()
            })
    }

    private val frames = ConcurrentHashMap<Key, Frame>()

    // --- S3-FIFO (guarded by lock) ---
    private val lock = Any()
    private val small = ArrayDeque<Frame>()
    private val main = ArrayDeque<Frame>()
    private var smallBytes = 0L
    private var mainBytes = 0L
    private val ghostCap = (4 * regionBytes / (64 * 1024)).toInt()
    private val ghost = object : LinkedHashMap<Key, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Boolean>?) = size > ghostCap
    }

    // --- stats ---
    val hits = AtomicLong()
    val misses = AtomicLong()
    val evictions = AtomicLong()
    val bypasses = AtomicLong()
    val readNanos = AtomicLong()
    val readBytes = AtomicLong()
    val residentBytes get() = synchronized(lock) { smallBytes + mainBytes }
    val pinnedFrames get() = frames.values.count { it.pins.get() > 0 }

    fun statsLine() =
        "PagePool stats: hits=${hits.get()} misses=${misses.get()} evictions=${evictions.get()} bypasses=${bypasses.get()} " +
                "resident=${residentBytes} small=$smallBytes main=$mainBytes frames=${frames.size} pinnedFrames=$pinnedFrames " +
                "readBytes=${readBytes.get()} readMs=${readNanos.get() / 1_000_000}"

    init {
        Thread({
            while (true) {
                Thread.sleep(10_000)
                System.err.println("${java.time.Instant.now()} ${statsLine()}")
            }
        }, "page-pool-stats").apply { isDaemon = true }.start()

        Runtime.getRuntime().addShutdownHook(Thread { System.err.println("${java.time.Instant.now()} FINAL ${statsLine()}") })
    }

    private fun evictFrame(f: Frame) {
        frames.remove(f.key, f)
        buddyFree(f.blockOff, f.order)
        evictions.incrementAndGet()
    }

    /**
     * @return true if a frame was evicted
     */
    private fun evictOne(): Boolean {
        var budget = 4 * (small.size + main.size) + 8
        while (budget-- > 0) {
            if (small.isNotEmpty() && (smallBytes >= regionBytes / 10 || main.isEmpty())) {
                val f = small.removeFirst()
                smallBytes -= f.frameBytes
                if (f.freq > 0) {
                    f.freq = 0; f.inMain = true
                    main.addLast(f); mainBytes += f.frameBytes
                } else if (f.pins.compareAndSet(0, -1)) {
                    evictFrame(f)
                    ghost[f.key] = true
                    return true
                } else {
                    small.addLast(f); smallBytes += f.frameBytes
                }
            } else if (main.isNotEmpty()) {
                val f = main.removeFirst()
                mainBytes -= f.frameBytes
                if (f.freq > 0) {
                    f.freq--
                    main.addLast(f); mainBytes += f.frameBytes
                } else if (f.pins.compareAndSet(0, -1)) {
                    evictFrame(f)
                    return true
                } else {
                    main.addLast(f); mainBytes += f.frameBytes
                }
            } else return false
        }
        return false
    }

    private fun allocBlock(order: Int): Long = synchronized(lock) {
        while (true) {
            val off = buddyAlloc(order)
            if (off >= 0) return off
            if (!evictOne()) return -1
        }
        @Suppress("UNREACHABLE_CODE") -1
    }

    private val channels = ConcurrentHashMap<Path, FileChannel>()

    private fun channel(path: Path): FileChannel =
        channels.computeIfAbsent(path) {
            if (direct) {
                try {
                    return@computeIfAbsent FileChannel.open(path, StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.DIRECT)
                } catch (e: Exception) {
                    LOGGER.warn("O_DIRECT open failed for $path, falling back: $e")
                }
            }
            FileChannel.open(path, StandardOpenOption.READ)
        }

    private fun readInto(ch: FileChannel, path: Path, seg: MemorySegment, pos: Long, minBytes: Long) {
        val start = System.nanoTime()
        val bb = seg.asByteBuffer()
        var p = pos
        while (bb.hasRemaining()) {
            val n = ch.read(bb, p)
            if (n < 0) break
            p += n
        }
        check(bb.position() >= minBytes) { "short read on $path at $pos: ${bb.position()} < $minBytes" }
        readNanos.addAndGet(System.nanoTime() - start)
        readBytes.addAndGet(bb.position().toLong())
    }

    private suspend fun loadBypass(path: Path, offset: Long, length: Long, al: BufferAllocator): ArrowBuf {
        bypasses.incrementAndGet()
        val buf = al.buffer(length)
        try {
            withContext(ioDispatcher) {
                val ch = FileChannel.open(path, StandardOpenOption.READ)
                ch.use { readInto(it, path, MemorySegment.ofAddress(buf.memoryAddress()).reinterpret(length), offset, length) }
            }
            return buf
        } catch (t: Throwable) {
            buf.close(); throw t
        }
    }

    suspend fun get(path: Path, offset: Long, length: Long, fallbackAl: BufferAllocator): ArrowBuf {
        val key = Key(path, offset)
        while (true) {
            val existing = frames[key]
            if (existing != null) {
                if (!existing.loaded.isCompleted) {
                    try {
                        existing.loaded.await()
                    } catch (_: Throwable) {
                        currentCoroutineContext().ensureActive()
                    }
                    continue
                }
                if (existing.tryPin()) {
                    hits.incrementAndGet()
                    if (existing.freq < 3) existing.freq++
                    return existing.toArrowBuf()
                }
                continue
            }

            val alignedStart = if (direct) offset and (MIN_BLOCK - 1).inv() else offset
            val dataOff = offset - alignedStart
            val readLen = if (direct) ((offset + length + MIN_BLOCK - 1) and (MIN_BLOCK - 1).inv()) - alignedStart else length
            val order = orderFor(readLen)
            if (order > MAX_SHIFT) return loadBypass(path, offset, length, fallbackAl)

            val f = Frame(key, length)
            if (frames.putIfAbsent(key, f) != null) continue
            misses.incrementAndGet()

            try {
                val blockOff = allocBlock(order)
                if (blockOff < 0) {
                    frames.remove(key, f)
                    f.loaded.complete(Unit)
                    return loadBypass(path, offset, length, fallbackAl)
                }
                f.blockOff = blockOff; f.order = order; f.dataOff = dataOff

                try {
                    withContext(ioDispatcher) {
                        readInto(channel(path), path, region.asSlice(blockOff, readLen), alignedStart, dataOff + length)
                    }
                } catch (t: Throwable) {
                    synchronized(lock) { buddyFree(blockOff, order) }
                    throw t
                }

                synchronized(lock) {
                    if (ghost.remove(key) != null) {
                        f.inMain = true; main.addLast(f); mainBytes += f.frameBytes
                    } else {
                        small.addLast(f); smallBytes += f.frameBytes
                    }
                }
                f.loaded.complete(Unit)
                return f.toArrowBuf()
            } catch (t: Throwable) {
                frames.remove(key, f)
                f.loaded.completeExceptionally(t)
                throw t
            }
        }
    }
}
