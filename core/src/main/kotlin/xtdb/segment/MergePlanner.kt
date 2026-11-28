package xtdb.segment

import com.carrotsearch.hppc.ObjectStack
import kotlinx.coroutines.runBlocking
import xtdb.segment.Segment.PageMeta
import xtdb.trie.DEFAULT_LEVEL_WIDTH
import xtdb.trie.HashTrie
import xtdb.trie.conjPath
import xtdb.util.safeMap
import xtdb.util.useAll
import java.util.Collections
import java.util.IdentityHashMap
import java.util.function.Predicate

object MergePlanner {
    // a coarse task holding more pages than this descends further instead, slicing shallower leaves in by path
    private val PAGE_BUDGET = (System.getenv("XTDB_SCAN_TASK_PAGES") ?: "64").toInt()

    private sealed interface SegmentNode<L> {
        val segMeta: Segment.Metadata<L>

        class Branch<L>(override val segMeta: Segment.Metadata<L>, val node: HashTrie.IidBranch<L>) : SegmentNode<L> {
            val children: List<List<SegmentNode<L>>>
                get() = node.hashChildren.map { child -> child?.let { segNodes(segMeta, it) }.orEmpty() }
        }

        class Leaf<L>(override val segMeta: Segment.Metadata<L>, val node: HashTrie.Leaf<L>) : SegmentNode<L> {
            @Suppress("UNCHECKED_CAST")
            val page get() = segMeta.page(node as L)
        }

        fun leavesUnder(): List<Leaf<*>> = when (this) {
            is Leaf -> listOf(this)
            is Branch -> children.flatMap { c -> c.flatMap { it.leavesUnder() } }
        }
    }

    private fun <L> segNodes(segMeta: Segment.Metadata<L>, node: HashTrie.Node<L>): List<SegmentNode<L>> =
        when (node) {
            is HashTrie.IidBranch -> listOf(SegmentNode.Branch(segMeta, node))
            is HashTrie.RecencyBranch -> node.buckets.flatMap { segNodes(segMeta, it.node) }
            is HashTrie.Leaf -> listOf(SegmentNode.Leaf(segMeta, node))
        }

    private fun <L> Segment.Metadata<L>.toSegmentNodes() = trie.rootNode?.let { segNodes(this, it) }.orEmpty()

    private class WorkTask(val segNodes: List<SegmentNode<*>>, val path: ByteArray)

    @FunctionalInterface
    fun interface PagesFilter {
        fun filterPages(pages: List<PageMeta<*>>): List<PageMeta<*>>?
    }

    @JvmStatic
    fun planSync(
        segments: List<Segment<*>>,
        pathPred: Predicate<ByteArray>?,
        filterPages: PagesFilter = PagesFilter { it },
        coarse: Boolean = false
    ) = runBlocking { plan(segments, pathPred, filterPages, coarse) }

    suspend fun plan(
        segments: List<Segment<*>>,
        pathPred: Predicate<ByteArray>?,
        filterPages: PagesFilter = PagesFilter { it },
        coarse: Boolean = false
    ): List<MergeTask> {
        segments
            .filter {
                val part = it.part
                if (part == null || pathPred == null) true
                else pathPred.test(part)
            }
            .safeMap { it.openMetadata() }.useAll { segMetas ->
                class Planned(val pages: List<Segment.Page<*>>, val path: ByteArray, val sliced: List<Boolean>)
                val result = mutableListOf<Planned>()

                val initialSegNodes = segMetas.flatMap { it.toSegmentNodes() }
                if (System.getenv("PLAN_STATS") != null)
                    System.err.println("PLAN_SEGS " + initialSegNodes.groupingBy { n -> n::class.simpleName + (if (n is SegmentNode.Leaf<*>) "" else "") }.eachCount() + " leavesPerSeg=" + initialSegNodes.map { it.leavesUnder().size }.sorted().take(8) + " shallow=" + initialSegNodes.count { it is SegmentNode.Leaf<*> })
                if (initialSegNodes.isEmpty()) return emptyList()

                val stack = ObjectStack<WorkTask>()

                stack.push(WorkTask(initialSegNodes, ByteArray(0)))

                while (!stack.isEmpty) {
                    val workTask = stack.pop()
                    val segNodes = workTask.segNodes
                    if (pathPred != null && !pathPred.test(workTask.path)) continue

                    val leaves = segNodes.filterIsInstance<SegmentNode.Leaf<*>>()
                    val depth = workTask.path.size

                    // coarse: every leaf under this path, whole - except that a leaf above the path (carried down by a
                    // descent) holds rows outside it, so it's sliced by path as the old planner did
                    val coarseLeaves =
                        if (coarse && leaves.isNotEmpty())
                            segNodes.flatMap { it.leavesUnder() }
                                .filter { it.node.path.size < depth || pathPred == null || pathPred.test(it.node.path) }
                        else null

                    val emit = coarseLeaves != null && (coarseLeaves.size <= PAGE_BUDGET || leaves.size == segNodes.size)

                    if (emit || (!coarse && leaves.size == segNodes.size)) {
                        val emitLeaves = if (emit) coarseLeaves!! else leaves
                        val pages = emitLeaves.map { it.page }
                        val slicedByPage = IdentityHashMap<Segment.PageMeta<*>, Boolean>()
                        emitLeaves.zip(pages).forEach { (l, p) -> slicedByPage[p] = !coarse || l.node.path.size < depth }
                        filterPages.filterPages(pages)
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { filtered -> result += Planned(filtered.map { it.page }, workTask.path, filtered.map { slicedByPage[it] ?: true }) }
                    } else if (leaves.size < segNodes.size || coarseLeaves != null) {
                        val nodeChildren = segNodes.map { segNode ->
                            when (segNode) {
                                is SegmentNode.Branch -> segNode.children
                                is SegmentNode.Leaf -> null
                            }
                        }

                        // do these in reverse order so that they're on the stack in path-prefix order
                        for (bucketIdx in (0..<DEFAULT_LEVEL_WIDTH).reversed()) {
                            val newSegNodes = segNodes.zip(nodeChildren).flatMap { (segNode, children) ->
                                children?.get(bucketIdx) ?: listOf(segNode)
                            }

                            if (newSegNodes.isNotEmpty())
                                stack.push(WorkTask(newSegNodes, conjPath(workTask.path, bucketIdx.toByte())))
                        }
                    }
                }

                val laterPages = Collections.newSetFromMap(IdentityHashMap<Segment.Page<*>, Boolean>())

                if (System.getenv("PLAN_STATS") != null)
                    System.err.println("PLAN_STATS coarse=$coarse tasks=${result.size} pageRefs=${result.sumOf { it.pages.size }} slicedRefs=${result.sumOf { t -> t.sliced.count { it } }} maxPages=${result.maxOfOrNull { it.pages.size }} distinctPages=${result.flatMap { it.pages }.toSet().size} paths=${result.map { it.path.size }.groupingBy { it }.eachCount().toSortedMap()}")

                return result.asReversed()
                    .map { MergeTask(it.pages, it.path, lastUsedPages = it.pages.filter { p -> laterPages.add(p) }, sliced = it.sliced) }
                    .asReversed()
            }
    }
}
