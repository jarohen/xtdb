package xtdb.spike

import java.util.concurrent.atomic.LongAdder

// SPIKE (#6164) — throwaway wall-clock counters, never lands.
enum class SpikeTimer { LOAD_PAGES, CACHE_GET, MMAP, FIRST_TOUCH, RB_DECODE, REL_LOAD, ARENA_CLOSE, MERGE, BUILD_AND_PREDS }

object SpikeTimers {
    private val ns = Array(SpikeTimer.entries.size) { LongAdder() }
    private val counts = Array(SpikeTimer.entries.size) { LongAdder() }

    @JvmStatic
    fun add(t: SpikeTimer, startNs: Long) {
        ns[t.ordinal].add(System.nanoTime() - startNs)
        counts[t.ordinal].increment()
    }

    inline fun <R> time(t: SpikeTimer, f: () -> R): R {
        val start = System.nanoTime()
        try {
            return f()
        } finally {
            add(t, start)
        }
    }

    private val fileReqs = java.util.concurrent.ConcurrentHashMap<String, LongAdder>()

    @JvmStatic
    fun fileReq(key: String) = fileReqs.computeIfAbsent(key) { LongAdder() }.increment()

    @JvmStatic
    fun fileReqs(): Map<String, Long> = fileReqs.mapValues { it.value.sum() }.toSortedMap()

    @JvmStatic
    fun reset() {
        fileReqs.clear()
        ns.forEach { it.reset() }
        counts.forEach { it.reset() }
    }

    @JvmStatic
    fun snapshot(): Map<String, List<Long>> =
        SpikeTimer.entries.associate { it.name to listOf(ns[it.ordinal].sum() / 1_000_000, counts[it.ordinal].sum()) }
}
