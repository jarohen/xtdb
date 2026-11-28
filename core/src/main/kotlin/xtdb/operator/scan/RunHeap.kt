package xtdb.operator.scan

import java.lang.Long.compareUnsigned

/**
 * A binary min-heap of [ScanRun]s by their keys — iid ascending (unsigned), then newest system-time first —
 * so its top is the run to serve next.
 * A caller moves the top run in place, then calls [reseatTop].
 */
internal class RunHeap(runs: List<ScanRun>) {
    // The array-backed binary heap (CLRS, ch. 6): `heap[0 until size]` is the tree, `heap[i]`'s children sit at `2i + 1` and `2i + 2`, and no run precedes its parent.
    // A run that's done is dropped: the last run takes its slot, and `size` shrinks by one.
    private val heap = runs.filterNot { it.isDone }.toTypedArray()
    private var size = heap.size

    // Floyd's bottom-up heap construction: sift down every parent, deepest first.
    init {
        for (i in size / 2 - 1 downTo 0) siftDown(i)
    }

    fun top(): ScanRun? = if (size == 0) null else heap[0]

    // A run's key only ever moves later, so sifting the root down restores the heap without a sift up.
    fun reseatTop() {
        if (heap[0].isDone) heap[0] = heap[--size]
        if (size > 0) siftDown(0)
    }

    // Carries `node` down as a hole, moving each preceding child up a level, and writes it once where neither child precedes it.
    private fun siftDown(from: Int) {
        val node = heap[from]
        var i = from

        while (true) {
            var child = 2 * i + 1
            if (child >= size) break
            if (child + 1 < size && precedes(heap[child + 1], heap[child])) child++
            if (!precedes(heap[child], node)) break

            heap[i] = heap[child]
            i = child
        }

        heap[i] = node
    }

    private fun precedes(l: ScanRun, r: ScanRun): Boolean {
        if (l.iidHigh != r.iidHigh) return compareUnsigned(l.iidHigh, r.iidHigh) < 0
        if (l.iidLow != r.iidLow) return compareUnsigned(l.iidLow, r.iidLow) < 0
        return l.systemFrom > r.systemFrom
    }
}
