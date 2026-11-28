package xtdb.trie

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xtdb.arrow.Relation

internal class ArrowHashTrieTest {

    private lateinit var allocator: BufferAllocator

    @BeforeEach
    fun setUp() {
        allocator = RootAllocator()
    }

    @AfterEach
    fun tearDown() {
        allocator.close()
    }

    @Test
    fun `a trie with no nodes has no root`() {
        Relation(allocator, MetadataFileWriter.metaRelSchema(recencyBranches = false)).use { rel ->
            assertNull(ArrowHashTrie(rel["nodes"]).rootNode)
        }
    }

    private class NodesWriter(private val rel: Relation) {
        private val nodes = rel["nodes"]

        private fun endNode(): RowIndex = (nodes.valueCount - 1).also { rel.endRow() }

        fun leaf(pageIdx: Int): RowIndex {
            val leaf = nodes["leaf"]
            leaf["columns"].endList()
            leaf["data-page-idx"].writeInt(pageIdx)
            leaf.endStruct()
            return endNode()
        }

        fun iid(vararg children: RowIndex?): RowIndex {
            val branch = nodes["branch-iid"]
            for (child in children) if (child == null) branch.listElements.writeNull() else branch.listElements.writeInt(child)
            branch.endList()
            return endNode()
        }

        fun recency(vararg buckets: Pair<RecencyMicros, RowIndex>): RowIndex {
            val branch = nodes["branch-recency"]
            for ((maxRecency, idx) in buckets) {
                branch.listElements["max-recency"].writeLong(maxRecency)
                branch.listElements["idx"].writeInt(idx)
                branch.listElements.endStruct()
            }
            branch.endList()
            return endNode()
        }
    }

    @Test
    fun `each recency bucket roots an IID trie of its own, met depth-first after the root's`() {
        Relation(allocator, MetadataFileWriter.metaRelSchema(recencyBranches = true)).use { rel ->
            with(NodesWriter(rel)) {
                val entity = recency(10L to leaf(1), 20L to leaf(2))
                val cells = recency(100L to iid(leaf(0), entity, null, null), 200L to leaf(3))
                iid(null, cells, null, null)
            }

            val trie = ArrowHashTrie(rel["nodes"])

            assertEquals(
                listOf(
                    listOf<Byte>() to emptyList(),
                    listOf<Byte>(1) to listOf(0),
                    listOf<Byte>(1, 1) to listOf(1),
                    listOf<Byte>(1, 1) to listOf(2),
                    listOf<Byte>(1) to listOf(3),
                ),
                trie.iidRoots.map { root ->
                    val iidTrie = trie.iidTrie(root)
                    iidTrie.rootNode!!.path.toList() to iidTrie.leaves.map { it.dataPageIndex }
                }
            )

            assertEquals(listOf(0, 1, 2, 3), trie.leaves.map { it.dataPageIndex })
        }
    }

    @Test
    fun `a trie with no nodes has one empty IID trie`() {
        Relation(allocator, MetadataFileWriter.metaRelSchema(recencyBranches = false)).use { rel ->
            val trie = ArrowHashTrie(rel["nodes"])
            assertEquals(listOf(null), trie.iidRoots.map { trie.iidTrie(it).rootNode })
        }
    }
}
