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
    private val CHAIN = System.getenv("DART_NOCHAIN") == null

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

        fun hasRecencyUnder(): Boolean = hasRecency(when (this) { is Leaf -> node; is Branch -> node })

        fun leavesUnder(): List<Leaf<*>> = when (this) {
            is Leaf -> listOf(this)
            is Branch -> children.flatMap { c -> c.flatMap { it.leavesUnder() } }
        }
    }

    private fun <L> hasRecency(node: HashTrie.Node<L>): Boolean = when (node) {
        is HashTrie.RecencyBranch -> true
        is HashTrie.Leaf -> false
        is HashTrie.IidBranch -> node.hashChildren.any { it != null && hasRecency(it) }
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
                val result = mutableListOf<Pair<List<List<Segment.Page<*>>>, ByteArray>>()

                val initialSegNodes = segMetas.flatMap { it.toSegmentNodes() }
                if (initialSegNodes.isEmpty()) return emptyList()

                val stack = ObjectStack<WorkTask>()

                stack.push(WorkTask(initialSegNodes, ByteArray(0)))

                while (!stack.isEmpty) {
                    val workTask = stack.pop()
                    val segNodes = workTask.segNodes
                    if (pathPred != null && !pathPred.test(workTask.path)) continue

                    val leaves = segNodes.filterIsInstance<SegmentNode.Leaf<*>>()
                    if (coarse && leaves.isNotEmpty()) {
                        val chains = segNodes.groupBy { it.segMeta }.values.flatMap { nodes ->
                            val leavesPerChain =
                                if (CHAIN && nodes.size == 1 && !nodes.single().hasRecencyUnder()) listOf(nodes.single().leavesUnder())
                                else nodes.flatMap { n -> n.leavesUnder().map { listOf(it) } }
                            leavesPerChain.map { ls -> ls.filter { pathPred == null || pathPred.test(it.node.path) } }
                        }
                        val survivors = Collections.newSetFromMap(IdentityHashMap<PageMeta<*>, Boolean>())
                        val metaChains = chains.map { c -> c.map { it.page } }
                        filterPages.filterPages(metaChains.flatten())?.let { survivors.addAll(it) }
                        val kept = metaChains.map { c -> c.filter { it in survivors }.map { it.page } }.filter { it.isNotEmpty() }
                        if (kept.isNotEmpty()) result += kept to workTask.path
                    } else if (leaves.size < segNodes.size) {
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
                    } else {
                        filterPages.filterPages(leaves.map { it.page })
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { filteredPages -> result += filteredPages.map { listOf(it.page) } to workTask.path }
                    }
                }

                val laterPages = Collections.newSetFromMap(IdentityHashMap<Segment.Page<*>, Boolean>())

                if (System.getenv("PLAN_STATS") != null)
                    System.err.println("PLAN_STATS coarse=$coarse tasks=${result.size} pageRefs=${result.sumOf { it.first.sumOf { c -> c.size } }} distinctPages=${result.flatMap { it.first.flatten() }.toSet().size} sources=${result.sumOf { it.first.size }} paths=${result.map { it.second.size }.groupingBy { it }.eachCount().toSortedMap()}")

                return result.asReversed()
                    .map { (sources, path) -> MergeTask(sources, path, lastUsedPages = sources.flatten().filter { laterPages.add(it) }, wholePages = coarse) }
                    .asReversed()
            }
    }
}
