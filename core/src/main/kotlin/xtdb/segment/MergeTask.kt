package xtdb.segment

import xtdb.util.closeAll

/**
 * @param sources each source's pages are iid-disjoint and in iid order, so a source is one merge input.
 */
class MergeTask(val sources: List<List<Segment.Page<*>>>, val path: ByteArray, private val lastUsedPages: List<Segment.Page<*>>, val wholePages: Boolean = false) : AutoCloseable {
    val pages: List<Segment.Page<*>> get() = sources.flatten()

    /**
     * Closes only the pages no later task uses — call once the relations loaded for this task are finished with.
     * Tasks MUST be consumed in plan order.
     */
    override fun close() = lastUsedPages.closeAll()
}
