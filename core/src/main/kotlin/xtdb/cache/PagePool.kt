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

    // DART: 'slab' = size-class slabs carved lazily from per-slab shared Arenas (see the slab section below)
    private val slabMode = System.getProperty("xtdb.pagePool.mode", "region") == "slab"

    private val regionBytes =
        if (arrowMode || slabMode) capacityBytes else (capacityBytes / MAX_BLOCK).coerceAtLeast(1) * MAX_BLOCK
    // DART: 'trim' = alloc_pages_exact-style - keep only the 4KiB-rounded used length of a buddy block, return the tail
    val trim = System.getProperty("xtdb.pagePool.trim", "false").toBoolean()

    // DART: the region from an FFM arena counts against MaxDirectMemorySize (JDK 21); Unsafe doesn't
    val useArena = System.getProperty("xtdb.pagePool.arena", "false").toBoolean()

    private val baseAddr =
        when {
            arrowMode || slabMode -> 0L
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
        while (!arrowMode && !slabMode && off < regionBytes) {
            freeLists[MAX_SHIFT].add(off); off += MAX_BLOCK
        }
    }

    private var freeBytes = if (arrowMode || slabMode) 0L else regionBytes

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

        // slab mode
        @Volatile
        var touched = 0L
        var slab: Slab? = null
        var slot = 0
        var cls: SizeClass? = null
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

    fun statsLine() = if (slabMode) slabStatsLine() else
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

    // =====================================================================================
    // DART (pool-slabs): size classes + 64MiB slabs, each slab its own shared Arena (closable), guarded by lock
    // =====================================================================================
    private val SLAB_BYTES = 64L shl 20
    private val maxClass = System.getProperty("xtdb.pagePool.slab.maxClass", "8m").let { parseSize(it) }
    private val rebalanceRatio = System.getProperty("xtdb.pagePool.slab.ratio", "2.0").toDouble()

    private val classSizes: LongArray = run {
        val l = ArrayList<Long>()
        var sz = MIN_BLOCK
        while (sz <= 64 * 1024 && sz <= maxClass) { l.add(sz); sz += MIN_BLOCK }
        var d = 64L * 1024
        while (d < maxClass) {
            for (k in 5..8) { val c = d * k / 4; if (c <= maxClass) l.add(c) }
            d *= 2
        }
        l.distinct().sorted().toLongArray()
    }

    inner class Queues {
        val small = ArrayDeque<Frame>()
        val main = ArrayDeque<Frame>()
        var smallBytes = 0L
        var mainBytes = 0L
        fun isEmpty() = small.isEmpty() && main.isEmpty()
        fun headTouched(): Long = minOf(small.firstOrNull()?.touched ?: Long.MAX_VALUE, main.firstOrNull()?.touched ?: Long.MAX_VALUE)
    }

    inner class Slab(val arena: Arena, val base: Long) {
        lateinit var occupant: Array<Frame?>
        lateinit var freeSlots: IntArray
        var nFree = 0
        val used get() = occupant.size - nFree

        fun assign(c: SizeClass) {
            val n = (SLAB_BYTES / c.size).toInt()
            occupant = arrayOfNulls(n)
            freeSlots = IntArray(n) { n - 1 - it }
            nFree = n
        }
    }

    inner class SizeClass(val size: Long) {
        val q = Queues()
        val slabs = ArrayList<Slab>()
        val partial = ArrayDeque<Slab>()
        val framesPerSlab = (SLAB_BYTES / size).toInt()
    }

    private val classes = classSizes.map { SizeClass(it) }
    private val dedQ = Queues()
    private var slabCount = 0
    private var committed = 0L
    private var dedicatedBytes = 0L

    @Volatile
    private var clock = 0L

    val rebalances = AtomicLong()
    val rebalanceEvictions = AtomicLong()
    val rebalanceNanos = AtomicLong()
    val rebalanceAborts = AtomicLong()
    val slabReleases = AtomicLong()
    val slabAllocs = AtomicLong()
    val dedicatedAllocs = AtomicLong()
    val dedicatedCloseNanos = AtomicLong()
    val classEvictions = AtomicLong()

    private fun classFor(len: Long): SizeClass? {
        var lo = 0; var hi = classSizes.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (classSizes[m] >= len) hi = m else lo = m + 1 }
        return classes.getOrNull(lo)
    }

    private fun slabTake(c: SizeClass, f: Frame): Long {
        val sl = c.partial.firstOrNull() ?: return -1
        val slot = sl.freeSlots[--sl.nFree]
        sl.occupant[slot] = f
        if (sl.nFree == 0) c.partial.removeFirst()
        f.slab = sl; f.slot = slot; f.cls = c
        f.frameBytes = c.size
        f.free = {
            sl.occupant[slot] = null
            if (sl.nFree == 0) c.partial.addLast(sl)
            sl.freeSlots[sl.nFree++] = slot
        }
        return sl.base + slot * c.size
    }

    private fun newSlab(c: SizeClass): Boolean {
        // an empty slab of another class is memory we already hold
        for (c2 in classes) if (c2 !== c) {
            val e = c2.slabs.firstOrNull { it.used == 0 } ?: continue
            detach(c2, e); attach(c, e)
            return true
        }
        if (committed + SLAB_BYTES > capacityBytes) return false
        val arena = Arena.ofShared()
        val seg = arena.allocate(SLAB_BYTES, MIN_BLOCK)
        committed += SLAB_BYTES; slabCount++; slabAllocs.incrementAndGet()
        attach(c, Slab(arena, seg.address()))
        return true
    }

    private fun detach(c: SizeClass, sl: Slab) { c.slabs.remove(sl); c.partial.remove(sl) }
    private fun attach(c: SizeClass, sl: Slab) { sl.assign(c); c.slabs.add(sl); c.partial.addLast(sl) }

    private fun releaseSlab(sl: Slab) {
        sl.arena.close(); committed -= SLAB_BYTES; slabCount--; slabReleases.incrementAndGet()
    }

    private fun qFor(f: Frame) = f.cls?.q ?: dedQ

    private fun removeFromQueues(c: Queues, f: Frame) {
        if (f.inMain) { c.main.remove(f); c.mainBytes -= f.frameBytes } else { c.small.remove(f); c.smallBytes -= f.frameBytes }
    }

    // S3-FIFO over one queue pair; `held` is the bytes the pair's owner holds, for the small queue's 10% share
    private fun evictFrom(c: Queues, held: Long): Boolean {
        var budget = 16 * (c.small.size + c.main.size) + 64
        var smallSkips = 0
        while (budget-- > 0) {
            if (c.small.isNotEmpty() && smallSkips < c.small.size && (c.smallBytes >= held / 10 || c.main.isEmpty())) {
                val f = c.small.removeFirst()
                c.smallBytes -= f.frameBytes
                if (f.freq > 0) {
                    smallSkips = 0
                    f.freq = 0; f.inMain = true
                    c.main.addLast(f); c.mainBytes += f.frameBytes
                } else if (f.pins.compareAndSet(0, -1)) {
                    dropFrame(f); ghost[f.key] = true
                    return true
                } else { smallSkips++; c.small.addLast(f); c.smallBytes += f.frameBytes }
            } else if (c.main.isNotEmpty()) {
                val f = c.main.removeFirst()
                c.mainBytes -= f.frameBytes
                if (f.freq > 0) {
                    f.freq--; c.main.addLast(f); c.mainBytes += f.frameBytes
                } else if (f.pins.compareAndSet(0, -1)) {
                    dropFrame(f)
                    return true
                } else { c.main.addLast(f); c.mainBytes += f.frameBytes }
            } else return false
        }
        return false
    }

    // f is out of its queue and pins == -1
    private fun dropFrame(f: Frame) {
        frames.remove(f.key, f)
        f.free()
        evictions.incrementAndGet()
    }

    // frames that lose the CAS race to a pin stay, so the slab may not empty
    private fun evictSlab(c: SizeClass, sl: Slab): Boolean {
        for (slot in sl.occupant.indices) {
            val f = sl.occupant[slot] ?: continue
            if (f.pins.compareAndSet(0, -1)) {
                removeFromQueues(c.q, f)
                dropFrame(f); rebalanceEvictions.incrementAndGet()
            }
        }
        return sl.used == 0
    }

    private fun slabScore(sl: Slab): Long {
        var sc = 0L
        for (f in sl.occupant) {
            if (f == null) continue
            if (f.pins.get() != 0) return Long.MAX_VALUE
            sc += 1 + f.freq
        }
        return sc
    }

    private fun ageOf(c: SizeClass): Long {
        val t = c.q.headTouched()
        return if (t == Long.MAX_VALUE) Long.MAX_VALUE else clock - t
    }

    private fun victimSlab(c: SizeClass): Slab? =
        c.slabs.map { it to slabScore(it) }.filter { it.second != Long.MAX_VALUE }.minByOrNull { it.second }?.first

    private fun donors(except: SizeClass?) = classes.filter { it !== except && it.slabs.isNotEmpty() }.sortedByDescending { ageOf(it) }

    /** move one slab to `to` from the coldest other class; unless `force`, only when that class's head is much older than ours */
    private fun rebalance(to: SizeClass, force: Boolean): Boolean {
        val ds = donors(to)
        if (ds.isEmpty()) return false
        if (!force && ageOf(ds.first()).toDouble() < rebalanceRatio * ageOf(to)) return false
        for (c in ds) {
            val sl = victimSlab(c) ?: continue
            val t0 = System.nanoTime()
            val emptied = evictSlab(c, sl)
            rebalanceNanos.addAndGet(System.nanoTime() - t0)
            if (!emptied) { rebalanceAborts.incrementAndGet(); continue }
            rebalances.incrementAndGet()
            detach(c, sl); attach(to, sl)
            return true
        }
        return false
    }

    /** frees budget for a dedicated frame or a new slab: empty slabs, then dedicated frames, then whole slabs, closing the arenas */
    private fun freeBudget(need: Long, onEvict: () -> Unit = {}): Boolean {
        while (committed + need > capacityBytes) {
            val empty = classes.firstNotNullOfOrNull { c -> c.slabs.firstOrNull { it.used == 0 }?.let { c to it } }
            if (empty != null) { detach(empty.first, empty.second); releaseSlab(empty.second); continue }
            if (evictFrom(dedQ, dedicatedBytes)) { onEvict(); continue }
            var moved = false
            for (c in donors(null)) {
                val sl = victimSlab(c) ?: continue
                if (evictSlab(c, sl)) { rebalances.incrementAndGet(); detach(c, sl); releaseSlab(sl); onEvict(); moved = true; break }
                rebalanceAborts.incrementAndGet()
            }
            if (!moved) return false
        }
        return true
    }

    /** @return frame base address, or -1 */
    private fun slabAlloc(f: Frame, readLen: Long): Long {
        var evicted = 0
        val c = classFor(readLen)
        try {
            if (c == null) {
                // dedicated: its own exact-size shared arena, closed on eviction
                val size = (readLen + MIN_BLOCK - 1) and (MIN_BLOCK - 1).inv()
                if (!freeBudget(size) { evicted++ }) { allocFailure("dedicated size=$size", evicted); return -1 }
                val arena = Arena.ofShared()
                val seg = arena.allocate(size, MIN_BLOCK)
                committed += size; dedicatedBytes += size; dedicatedAllocs.incrementAndGet()
                f.frameBytes = size
                f.free = {
                    val t0 = System.nanoTime()
                    arena.close(); committed -= size; dedicatedBytes -= size
                    dedicatedCloseNanos.addAndGet(System.nanoTime() - t0)
                }
                return seg.address()
            }
            while (true) {
                val a = slabTake(c, f)
                if (a >= 0) return a
                if (newSlab(c)) continue
                if (rebalance(c, force = c.q.isEmpty())) { evicted++; continue }
                if (evictFrom(c.q, c.slabs.size * SLAB_BYTES)) { evicted++; classEvictions.incrementAndGet(); continue }
                if (rebalance(c, true)) { evicted++; continue }
                if (freeBudget(SLAB_BYTES) && newSlab(c)) continue
                allocFailure("slab class=${c.size}", evicted); return -1
            }
        } finally {
            if (evicted > 0) {
                evictingAllocs.incrementAndGet()
                if (evicted > maxEvictionsPerAlloc.get()) maxEvictionsPerAlloc.set(evicted.toLong())
            }
        }
    }

    private fun slabInsert(f: Frame, ghostHit: Boolean) {
        clock += f.frameBytes
        f.touched = clock
        val q = qFor(f)
        if (ghostHit) { f.inMain = true; q.main.addLast(f); q.mainBytes += f.frameBytes }
        else { q.small.addLast(f); q.smallBytes += f.frameBytes }
    }

    private fun slabStatsLine(): String = synchronized(lock) {
        val all = classes.map { it.q } + dedQ
        val frameB = all.sumOf { it.smallBytes + it.mainBytes }
        val pageB = all.sumOf { q -> (q.small + q.main).sumOf { it.length } }
        val nFrames = all.sumOf { it.small.size + it.main.size }
        val slabFree = classes.sumOf { c -> c.slabs.sumOf { it.nFree } * c.size }
        val tail = classes.sumOf { c -> c.slabs.size * (SLAB_BYTES - c.framesPerSlab * c.size) }
        val perClass = classes.filter { it.slabs.isNotEmpty() }.joinToString(",") { "${it.size / 1024}K:${it.slabs.size}" }
        "PagePool slab stats: hits=${hits.get()} misses=${misses.get()} evictions=${evictions.get()} bypasses=${bypasses.get()} " +
            "resident=${frameB} frames=$nFrames pinnedFrames=$pinnedFrames readBytes=${readBytes.get()} readMs=${readNanos.get() / 1_000_000} " +
            "allocFailures=${allocFailures.get()} frameBytes=$frameB pageBytes=$pageB committed=$committed slabs=$slabCount dedicatedBytes=$dedicatedBytes " +
            "slabFreeSlotBytes=$slabFree slabTailBytes=$tail evictingAllocs=${evictingAllocs.get()} maxEvictionsPerAlloc=${maxEvictionsPerAlloc.get()} " +
            "classEvictions=${classEvictions.get()} rebalances=${rebalances.get()} rebalanceEvictions=${rebalanceEvictions.get()} rebalanceMs=${rebalanceNanos.get() / 1_000_000} " +
            "rebalanceAborts=${rebalanceAborts.get()} slabAllocs=${slabAllocs.get()} slabReleases=${slabReleases.get()} dedicatedAllocs=${dedicatedAllocs.get()} " +
            "dedicatedCloseMs=${dedicatedCloseNanos.get() / 1_000_000} classes=[$perClass]"
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
                    if (slabMode) existing.touched = clock
                    return existing.toArrowBuf()
                }
                continue
            }

            val alignedStart = if (direct) offset and (MIN_BLOCK - 1).inv() else offset
            val dataOff = offset - alignedStart
            val readLen = if (direct) ((offset + length + MIN_BLOCK - 1) and (MIN_BLOCK - 1).inv()) - alignedStart else length
            val order = orderFor(readLen)
            if (!arrowMode && !slabMode && order > MAX_SHIFT) return loadBypass(path, offset, length, fallbackAl)

            val f = Frame(key, length)
            if (frames.putIfAbsent(key, f) != null) continue
            misses.incrementAndGet()

            try {
                val frameBase: Long
                if (slabMode) {
                    val a = synchronized(lock) { slabAlloc(f, readLen) }
                    if (a < 0) {
                        frames.remove(key, f)
                        f.loaded.complete(Unit)
                        return loadBypass(path, offset, length, fallbackAl)
                    }
                    frameBase = a
                } else if (arrowMode) {
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
                    if (slabMode) slabInsert(f, ghost.remove(key) != null)
                    else if (ghost.remove(key) != null && admitToMain()) {
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
