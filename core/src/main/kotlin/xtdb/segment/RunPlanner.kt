package xtdb.segment

import com.carrotsearch.hppc.ObjectStack
import kotlinx.coroutines.runBlocking
import xtdb.segment.MergePlanner.PagesFilter
import xtdb.segment.Segment.PageMeta
import xtdb.trie.DEFAULT_LEVEL_WIDTH
import xtdb.trie.HashTrie
import xtdb.trie.conjPath
import xtdb.util.safeMap
import xtdb.util.useAll
import java.util.IdentityHashMap
import java.util.function.Predicate

/**
 * A sorted source for the scan's merge: pages whose rows, read end to end, are in iid order.
 *
 * Each recency cell of a segment is its own run, as is each segment without them.
 */
class SegmentRun(val pages: List<RunPage>)

class RunPage(val page: Segment.Page<*>, val path: ByteArray)

/**
 * Plans a scan as [SegmentRun]s, reading only metadata.
 *
 * Pages are pruned across segments by [PagesFilter], over each group of pages whose paths overlap —
 * the same groups [MergePlanner] cuts tasks at.
 * Pruning only skips work the merge would otherwise discard, so a page any group keeps is kept.
 */
object RunPlanner {
    private class Run {
        val leaves = mutableListOf<LeafNode<*>>()
    }

    private class LeafNode<L>(val run: Run, val segMeta: Segment.Metadata<L>, val leaf: L, val path: ByteArray) {
        val pageMeta: PageMeta<L> by lazy(LazyThreadSafetyMode.NONE) { segMeta.page(leaf) }
        var seen = false
        var kept = false
    }

    private sealed interface SegmentNode {
        class Branch<L>(val segMeta: Segment.Metadata<L>, val node: HashTrie.IidBranch<L>, val run: Run) : SegmentNode
        class Leaf(val leaf: LeafNode<*>) : SegmentNode
    }

    private class Walk(val runs: MutableList<Run> = mutableListOf()) {
        fun newRun() = Run().also { runs += it }

        @Suppress("UNCHECKED_CAST")
        fun <L> segNodes(segMeta: Segment.Metadata<L>, node: HashTrie.Node<L>, run: Run): List<SegmentNode> =
            when (node) {
                is HashTrie.IidBranch -> listOf(SegmentNode.Branch(segMeta, node, run))
                is HashTrie.RecencyBranch -> node.buckets.flatMap { segNodes(segMeta, it.node, newRun()) }
                is HashTrie.Leaf -> listOf(SegmentNode.Leaf(LeafNode(run, segMeta, node as L, node.path)))
            }

        fun <L> rootNodes(segMeta: Segment.Metadata<L>): List<SegmentNode> =
            segMeta.trie.rootNode?.let { segNodes(segMeta, it, newRun()) }.orEmpty()

        fun <L> children(branch: SegmentNode.Branch<L>): List<List<SegmentNode>> =
            branch.node.hashChildren.map { child -> child?.let { segNodes(branch.segMeta, it, branch.run) }.orEmpty() }
    }

    private class WorkTask(val segNodes: List<SegmentNode>, val path: ByteArray)

    @JvmStatic
    fun planSync(segments: List<Segment<*>>, pathPred: Predicate<ByteArray>?, filterPages: PagesFilter) =
        runBlocking { plan(segments, pathPred, filterPages) }

    suspend fun plan(segments: List<Segment<*>>, pathPred: Predicate<ByteArray>?, filterPages: PagesFilter): List<SegmentRun> =
        segments
            .filter { seg -> seg.part.let { part -> part == null || pathPred == null || pathPred.test(part) } }
            .safeMap { it.openMetadata() }
            .useAll { segMetas ->
                val walk = Walk()

                val initialSegNodes = segMetas.flatMap { walk.rootNodes(it) }

                val stack = ObjectStack<WorkTask>()
                if (initialSegNodes.isNotEmpty()) stack.push(WorkTask(initialSegNodes, ByteArray(0)))

                while (!stack.isEmpty) {
                    val workTask = stack.pop()
                    val segNodes = workTask.segNodes
                    if (pathPred != null && !pathPred.test(workTask.path)) continue

                    if (segNodes.any { it is SegmentNode.Branch<*> }) {
                        val nodeChildren = segNodes.map { if (it is SegmentNode.Branch<*>) walk.children(it) else null }

                        // pushed in reverse, so that groups pop in path order
                        for (bucketIdx in (0..<DEFAULT_LEVEL_WIDTH).reversed()) {
                            val newSegNodes = segNodes.zip(nodeChildren).flatMap { (segNode, children) ->
                                children?.get(bucketIdx) ?: listOf(segNode)
                            }

                            if (newSegNodes.isNotEmpty())
                                stack.push(WorkTask(newSegNodes, conjPath(workTask.path, bucketIdx.toByte())))
                        }
                    } else {
                        val leaves = segNodes.map { (it as SegmentNode.Leaf).leaf }

                        // groups pop in path order, so a run's leaves arrive in path order the first time each is seen
                        for (leaf in leaves) {
                            if (!leaf.seen) {
                                leaf.seen = true
                                leaf.run.leaves += leaf
                            }
                        }

                        val byPage = IdentityHashMap<PageMeta<*>, LeafNode<*>>(leaves.size)
                        for (leaf in leaves) byPage[leaf.pageMeta] = leaf

                        filterPages.filterPages(leaves.map { it.pageMeta })
                            ?.forEach { byPage.getValue(it).kept = true }
                    }
                }

                walk.runs.mapNotNull { run ->
                    run.leaves
                        .filter { it.kept }
                        .map { RunPage(it.pageMeta.page, it.path) }
                        .takeIf { it.isNotEmpty() }
                        ?.let { SegmentRun(it) }
                }
            }
}
