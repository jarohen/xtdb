package xtdb.trie

import xtdb.arrow.Vector
import xtdb.arrow.VectorReader
import java.util.stream.Stream

private const val NIL = "nil"
private const val BRANCH_IID = "branch-iid"
private const val BRANCH_RECENCY = "branch-recency"
private const val LEAF = "leaf"
private const val DATA_PAGE_IDX = "data-page-idx"
private const val MAX_RECENCY = "max-recency"
private const val IDX = "idx"

/**
 * A data file's trie, as its metadata file stores it: it branches on IID and may also branch on recency,
 * so it splits into [HashTrie]s at its recency branches - see [iidRoots].
 */
class ArrowHashTrie @JvmOverloads constructor(
    private val nodesVec: Vector, private val closeNodesVec: Boolean = false
) : AutoCloseable {

    private val iidBranchVec = nodesVec[BRANCH_IID]
    private val iidBranchElVec = iidBranchVec.listElements

    private val recencyBranchVec = nodesVec.vectorForOrNull(BRANCH_RECENCY)

    private val dataPageIdxVec = nodesVec[LEAF][DATA_PAGE_IDX]

    sealed interface Node {
        val path: ByteArray
    }

    inner class IidBranch(override val path: ByteArray, branchVecIdx: Int) : Node, HashTrie.IidBranch<Leaf> {
        private val startIdx = iidBranchVec.getListStartIndex(branchVecIdx)
        private val count = iidBranchVec.getListCount(branchVecIdx)

        val children: List<Node?>
            get() = List(count) { childBucket ->
                val childIdx = childBucket + startIdx
                if (iidBranchElVec.isNull(childIdx))
                    null
                else
                    forIndex(conjPath(path, childBucket.toByte()), iidBranchElVec.getInt(childIdx))
            }

        /**
         * [children], with each recency branch left out - its buckets root IID tries of their own.
         */
        override val hashChildren: List<HashTrie.Node<Leaf>?>
            get() = children.map { it.asIidNode() }
    }

    /**
     * Every bucket's node sits at this branch's own IID [path].
     */
    inner class RecencyBranch(
        override val path: ByteArray, recencyBranchVec: VectorReader, branchVecIdx: Int
    ) : Node {
        private val startIdx = recencyBranchVec.getListStartIndex(branchVecIdx)
        private val count = recencyBranchVec.getListCount(branchVecIdx)
        private val maxRecencyVec = recencyBranchVec.listElements[MAX_RECENCY]
        private val childIdxVec = recencyBranchVec.listElements[IDX]

        /**
         * @property maxRecency the inclusive upper bound of the recency of every row under [node]
         */
        inner class Bucket(val maxRecency: RecencyMicros, internal val nodeIdx: Int) {
            val node: Node get() = forIndex(path, nodeIdx)!!
        }

        /**
         * in ascending order of [Bucket.maxRecency]
         */
        val buckets: List<Bucket>
            get() = List(count) { bucket ->
                val elIdx = startIdx + bucket
                Bucket(maxRecencyVec.getLong(elIdx), childIdxVec.getInt(elIdx))
            }
    }

    class Leaf(override val path: ByteArray, val dataPageIndex: Int) : Node, HashTrie.Leaf<Leaf>

    private fun forIndex(path: ByteArray, idx: Int): Node? =
        when (nodesVec.getLeg(idx)) {
            NIL -> null
            BRANCH_IID -> IidBranch(path, idx)
            BRANCH_RECENCY -> RecencyBranch(path, recencyBranchVec!!, idx)
            LEAF -> Leaf(path, dataPageIdxVec.getInt(idx))
            else -> error("unknown leg: ${nodesVec.getLeg(idx)}")
        }

    private fun Node?.asIidNode(): HashTrie.Node<Leaf>? =
        when (this) {
            is IidBranch -> this
            is Leaf -> this
            is RecencyBranch, null -> null
        }

    private val rootIdx get() = nodesVec.valueCount - 1

    val rootNode get() = if (rootIdx < 0) null else forIndex(ByteArray(0), rootIdx)

    private fun Node.leafStream(): Stream<Leaf> =
        when (this) {
            is IidBranch -> children.stream().flatMap { child -> child?.leafStream() }
            is RecencyBranch -> buckets.stream().flatMap { bucket -> bucket.node.leafStream() }
            is Leaf -> Stream.of(this)
        }

    val leaves: List<Leaf> get() = rootNode?.leafStream()?.toList().orEmpty()

    /**
     * Where one of this trie's IID tries is rooted, stable across opens of the same metadata file - see [iidTrie].
     */
    class IidRoot internal constructor(internal val path: ByteArray, internal val nodeIdx: Int)

    /**
     * The roots of the IID tries that hold this trie's leaves between them, no two of which share a leaf:
     * the root's own, then each recency bucket's, in the order a depth-first walk meets them.
     *
     * A root whose node is a recency branch roots an empty IID trie.
     */
    val iidRoots: List<IidRoot>
        get() = buildList {
            fun Node?.addBucketRoots() {
                when (this) {
                    is IidBranch -> children.forEach { it.addBucketRoots() }

                    is RecencyBranch -> buckets.forEach { bucket ->
                        add(IidRoot(path, bucket.nodeIdx))
                        bucket.node.addBucketRoots()
                    }

                    is Leaf, null -> Unit
                }
            }

            add(IidRoot(ByteArray(0), rootIdx))
            rootNode.addBucketRoots()
        }

    fun iidTrie(root: IidRoot): HashTrie<Leaf> =
        object : HashTrie<Leaf> {
            override val rootNode = if (root.nodeIdx < 0) null else forIndex(root.path, root.nodeIdx).asIidNode()
        }

    override fun close() {
        if (closeNodesVec) nodesVec.close()
    }
}
