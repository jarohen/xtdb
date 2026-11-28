package xtdb.operator.scan

/** One entity's events, newest system-time first. */
internal interface EntityEvents {
    fun nextEvent(): EntityEvent?
}

/**
 * Merges the scan's runs: an outer pass over entities in iid order, and within each entity
 * an inner pass over its events in reverse system-time order.
 */
internal class EntityMerge(runs: List<ScanRun>) : EntityEvents {
    private val heap = RunHeap(runs)

    private var onEntity = false
    private var iidHigh = 0L
    private var iidLow = 0L

    // advanced on the next `nextEvent` rather than eagerly, so the caller can still read the row it's on.
    private var current: ScanRun? = null

    // A pending run's key is a lower bound, so once the top is loaded, no pending run can hold an earlier event.
    private fun top(): ScanRun? {
        while (true) {
            val top = heap.top() ?: return null
            if (top.isLoaded) return top
            top.load()
            heap.reseatTop()
        }
    }

    private fun topOnEntity() = top()?.takeIf { it.sameIidAs(iidHigh, iidLow) }

    /** Advances to the next entity, returning false once every run is exhausted. */
    fun nextEntity(): Boolean {
        if (onEntity) {
            current = null

            while (true) {
                val run = topOnEntity() ?: break
                run.skipToNextIid()
                heap.reseatTop()
            }
        }

        val top = top()
        onEntity = top != null
        if (top == null) return false

        iidHigh = top.iidHigh
        iidLow = top.iidLow
        return true
    }

    override fun nextEvent(): EntityEvent? {
        if (!onEntity) return null

        current?.let { run ->
            current = null
            run.nextIndex()
            heap.reseatTop()
        }

        return topOnEntity()?.also { current = it }
    }
}
