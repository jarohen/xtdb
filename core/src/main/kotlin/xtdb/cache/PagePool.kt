package xtdb.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.arrow.memory.ArrowBuf
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.ForeignAllocation
import org.apache.arrow.memory.AllocationListener
import org.apache.arrow.memory.OutOfMemoryException
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.memory.rounding.RoundingPolicy
import xtdb.util.info
import xtdb.util.logger
import xtdb.util.maxDirectMemory
import xtdb.util.warn
import java.lang.foreign.Arena
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
        private const val MAX_SHIFT = 26
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
            (System.getProperty("xtdb.pagePool.bytes")?.let { parseSize(it) }
                ?: System.getProperty("xtdb.pagePool.ratio")?.let { (maxDirectMemory * it.toDouble()).toLong() })?.let { sz ->
                PagePool(
                    sz,
                    direct = System.getProperty("xtdb.pagePool.direct", "true").toBoolean(),
                    ioParallelism = System.getProperty("xtdb.pagePool.io", "8").toInt()
                ).also { LOGGER.info("PagePool: capacity=${it.capacityBytes} maxDirectMemory=$maxDirectMemory trim=${it.trim} arena=${it.useArena} direct=${it.direct} mode=${System.getProperty("xtdb.pagePool.mode", "region")} admit=${System.getProperty("xtdb.pagePool.admit", "s3")}") }
            }
        }

        @JvmStatic
        fun owns(buf: ArrowBuf): Boolean {
            val pool = INSTANCE ?: return false
            val al = buf.referenceManager.allocator
            return al === pool.allocator || (pool.frameAl != null && al === pool.frameAl)
        }

        private fun orderFor(len: Long): Int {
            var o = MIN_SHIFT
            while ((1L shl o) < len) o++
            return o
        }
    }

    // DART: 'region' = one Unsafe-allocated region + buddy allocator; 'arrow' = each frame its own allocation from a
    // root allocator whose limit is the pool size (exact sizes, no power-of-two rounding)
    private val arrowMode = System.getProperty("xtdb.pagePool.mode", "region") == "arrow"

    private val regionBytes =
        if (arrowMode) capacityBytes else (capacityBytes / MAX_BLOCK).coerceAtLeast(1) * MAX_BLOCK
    // DART: 'trim' = alloc_pages_exact-style - keep only the 4KiB-rounded used length of a buddy block, return the tail
    val trim = System.getProperty("xtdb.pagePool.trim", "false").toBoolean()

    // DART: the region from an FFM arena counts against MaxDirectMemorySize (JDK 21); Unsafe doesn't
    val useArena = System.getProperty("xtdb.pagePool.arena", "false").toBoolean()

    private val baseAddr =
        when {
            arrowMode -> 0L
            useArena -> Arena.ofShared().allocate(regionBytes, MIN_BLOCK).address()
            else -> {
                val rawAddr = unsafe.allocateMemory(regionBytes + MIN_BLOCK)
                (rawAddr + MIN_BLOCK - 1) and (MIN_BLOCK - 1).inv()
            }
        }

    val allocator: BufferAllocator = RootAllocator(Long.MAX_VALUE)

    val frameAl: BufferAllocator? =
        if (arrowMode) RootAllocator(AllocationListener.NOOP, capacityBytes, RoundingPolicy { it }) else null

    @Suppress("OPT_IN_USAGE")
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(ioParallelism)

    // --- buddy allocator (guarded by lock) ---
    private val freeLists = Array(MAX_SHIFT + 1) { LinkedHashSet<Long>() }

    init {
        var off = 0L
        while (!arrowMode && off < regionBytes) {
            freeLists[MAX_SHIFT].add(off); off += MAX_BLOCK
        }
    }

    private var freeBytes = if (arrowMode) 0L else regionBytes

    private fun largestFree(): Long {
        for (k in MAX_SHIFT downTo MIN_SHIFT) if (freeLists[k].isNotEmpty()) return 1L shl k
        return 0
    }

    private fun buddyAlloc(order: Int): Long {
        var k = order
        while (k <= MAX_SHIFT && freeLists[k].isEmpty()) k++
        if (k > MAX_SHIFT) return -1
        val it = freeLists[k].iterator()
        val off = it.next(); it.remove()
        freeBytes -= 1L shl order
        while (k > order) {
            k--
            freeLists[k].add(off + (1L shl k))
        }
        return off
    }

    // frees [off, off + len) as aligned power-of-two pieces, largest-first from the start
    private fun buddyFreeRange(off0: Long, len0: Long) {
        var off = off0
        var len = len0
        while (len > 0) {
            var o = 63 - java.lang.Long.numberOfLeadingZeros(len)
            while ((off and ((1L shl o) - 1)) != 0L) o--
            buddyFree(off, o)
            off += 1L shl o; len -= 1L shl o
        }
    }

    // each tail piece's buddy overlaps the used range, so nothing coalesces until the frame itself is freed
    private fun freeTail(off: Long, len: Long) = buddyFreeRange(off, len)

    private fun buddyFree(off0: Long, order0: Int) {
        freeBytes += 1L shl order0
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
        var addr = 0L
        var frameBytes = 0L
        var free: () -> Unit = {}
        var inMain = false

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
            allocator.wrapForeignAllocation(object : ForeignAllocation(length, addr) {
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
                "readBytes=${readBytes.get()} readMs=${readNanos.get() / 1_000_000} allocFailures=${allocFailures.get()} " +
                "frameBytes=${synchronized(lock) { (small + main).sumOf { it.frameBytes } }} pageBytes=${synchronized(lock) { (small + main).sumOf { it.length } }} " +
                "freeBytes=${synchronized(lock) { freeBytes }} largestFree=${synchronized(lock) { largestFree() }} " +
                "evictingAllocs=${evictingAllocs.get()} maxEvictionsPerAlloc=${maxEvictionsPerAlloc.get()} " +
                "freeBytesAtEvictSum=${freeBytesAtEvictSum.get()} largestFreeAtEvictSum=${largestFreeAtEvictSum.get()}" +
                (frameAl?.let { " frameAlBytes=${it.allocatedMemory}" } ?: "")

    init {
        Thread({
            while (true) {
                Thread.sleep(10_000)
                System.err.println("${java.time.Instant.now()} ${statsLine()}")
            }
        }, "page-pool-stats").apply { isDaemon = true }.start()

        Runtime.getRuntime().addShutdownHook(Thread { System.err.println("${java.time.Instant.now()} FINAL ${statsLine()}") })
    }

    private val incumbent = System.getProperty("xtdb.pagePool.admit", "s3") == "incumbent"
    private var insertsSinceAging = 0L

    // DART: loop resistance - once main is full, a ghost hit only displaces main's head if that head has gone cold;
    // main's frequencies halve every ~4 pool-fulls of inserts so stale incumbents eventually lose
    private fun admitToMain(): Boolean {
        if (!incumbent) return true
        if (++insertsSinceAging > 4L * (main.size + small.size)) {
            insertsSinceAging = 0
            main.forEach { it.freq = it.freq / 2 }
        }
        val head = main.firstOrNull() ?: return true
        if (mainBytes + smallBytes < regionBytes * 9 / 10) return true
        if (head.freq == 0) return true
        main.removeFirst(); main.addLast(head)
        return false
    }

    private fun evictFrame(f: Frame) {
        frames.remove(f.key, f)
        f.free()
        evictions.incrementAndGet()
    }

    /**
     * @return true if a frame was evicted
     */
    private fun evictOne(): Boolean {
        // freq is capped at 3, so ~4 passes over both queues (plus promotions) visit every unpinned frame at freq 0
        var budget = 16 * (small.size + main.size) + 64
        // pinned frames skipped in small since its last progress - once we've been round small, fall through to main
        var smallSkips = 0
        while (budget-- > 0) {
            if (small.isNotEmpty() && smallSkips < small.size && (smallBytes >= regionBytes / 10 || main.isEmpty())) {
                val f = small.removeFirst()
                smallBytes -= f.frameBytes
                if (f.freq > 0) {
                    smallSkips = 0
                    f.freq = 0; f.inMain = true
                    main.addLast(f); mainBytes += f.frameBytes
                } else if (f.pins.compareAndSet(0, -1)) {
                    evictFrame(f)
                    ghost[f.key] = true
                    return true
                } else {
                    smallSkips++
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

    val allocFailures = AtomicLong()

    private fun allocFailure(what: String, evictedThisCall: Int) {
        if (allocFailures.incrementAndGet() <= 20) {
            val pinned = small.count { it.pins.get() > 0 } + main.count { it.pins.get() > 0 }
            val dead = small.count { it.pins.get() < 0 } + main.count { it.pins.get() < 0 }
            val freqs = (small + main).groupingBy { it.freq }.eachCount()
            LOGGER.warn("PagePool alloc failure ($what): evictedThisCall=$evictedThisCall small=${small.size} main=${main.size} pinned=$pinned dead=$dead freqs=$freqs smallBytes=$smallBytes mainBytes=$mainBytes frameAl=${frameAl?.allocatedMemory}")
        }
    }

    val evictingAllocs = AtomicLong()
    val maxEvictionsPerAlloc = AtomicLong()
    val freeBytesAtEvictSum = AtomicLong()
    val largestFreeAtEvictSum = AtomicLong()

    private fun allocBlock(order: Int): Long = synchronized(lock) {
        var evicted = 0
        while (true) {
            val off = buddyAlloc(order)
            if (off >= 0) {
                if (evicted > 0) {
                    evictingAllocs.incrementAndGet()
                    if (evicted > maxEvictionsPerAlloc.get()) maxEvictionsPerAlloc.set(evicted.toLong())
                }
                return off
            }
            if (evicted == 0) {
                freeBytesAtEvictSum.addAndGet(freeBytes); largestFreeAtEvictSum.addAndGet(largestFree())
            }
            if (!evictOne()) {
                LOGGER.warn("PagePool region alloc failure: order=$order evicted=$evicted freeBytes=$freeBytes largestFree=${largestFree()}")
                allocFailure("region order=$order", evicted); return -1
            }
            evicted++
        }
        @Suppress("UNREACHABLE_CODE") -1
    }

    private fun allocArrowFrame(size: Long): ArrowBuf {
        val al = frameAl!!
        var evicted = 0
        while (true) {
            try {
                return al.buffer(size)
            } catch (e: OutOfMemoryException) {
                synchronized(lock) {
                    if (!evictOne()) {
                        allocFailure("arrow size=$size", evicted); throw e
                    }
                    evicted++
                }
            }
        }
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
            if (n <= 0) break
            p += n
            // O_DIRECT: a short read means EOF, and the next position wouldn't be aligned anyway
            if (direct && (p and (MIN_BLOCK - 1)) != 0L) break
        }
        check(bb.position() >= minBytes) { "short read on $path at $pos: ${bb.position()} < $minBytes" }
        readNanos.addAndGet(System.nanoTime() - start)
        readBytes.addAndGet(bb.position().toLong())
    }

    private suspend fun loadBypass(path: Path, offset: Long, length: Long, al: BufferAllocator): ArrowBuf {
        if (bypasses.incrementAndGet() < 20) LOGGER.info("PagePool bypass: $path @$offset len=$length")
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
            if (!arrowMode && order > MAX_SHIFT) return loadBypass(path, offset, length, fallbackAl)

            val f = Frame(key, length)
            if (frames.putIfAbsent(key, f) != null) continue
            misses.incrementAndGet()

            try {
                val frameBase: Long
                if (arrowMode) {
                    val buf = allocArrowFrame(if (direct) readLen + MIN_BLOCK else readLen)
                    frameBase = if (direct) (buf.memoryAddress() + MIN_BLOCK - 1) and (MIN_BLOCK - 1).inv() else buf.memoryAddress()
                    f.frameBytes = buf.capacity()
                    f.free = { buf.close() }
                } else {
                    val blockOff = allocBlock(order)
                    if (blockOff < 0) {
                        frames.remove(key, f)
                        f.loaded.complete(Unit)
                        return loadBypass(path, offset, length, fallbackAl)
                    }
                    frameBase = baseAddr + blockOff
                    if (trim) {
                        val used = (readLen + MIN_BLOCK - 1) and (MIN_BLOCK - 1).inv()
                        val block = 1L shl order
                        if (used < block) synchronized(lock) { freeTail(blockOff + used, block - used) }
                        f.frameBytes = used
                        f.free = { buddyFreeRange(blockOff, used) }
                    } else {
                        f.frameBytes = 1L shl order
                        f.free = { buddyFree(blockOff, order) }
                    }
                }
                f.addr = frameBase + dataOff

                try {
                    withContext(ioDispatcher) {
                        readInto(channel(path), path, MemorySegment.ofAddress(frameBase).reinterpret(readLen), alignedStart, dataOff + length)
                    }
                } catch (t: Throwable) {
                    synchronized(lock) { f.free() }
                    throw t
                }

                synchronized(lock) {
                    if (ghost.remove(key) != null && admitToMain()) {
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
