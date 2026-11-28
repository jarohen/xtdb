package xtdb.segment

import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.ipc.message.ArrowFooter
import org.apache.arrow.vector.types.pojo.Schema
import xtdb.arrow.Relation
import xtdb.arrow.RelationReader
import xtdb.metadata.MetadataPredicate
import xtdb.metadata.PageMetadata
import xtdb.segment.Segment.PageMeta
import xtdb.segment.Segment.PageMeta.Companion.pageMeta
import xtdb.storage.BufferPool
import xtdb.table.TableSlug
import xtdb.trie.ArrowHashTrie
import xtdb.trie.Trie
import xtdb.trie.RecencyMicros
import xtdb.trie.TrieKey
import xtdb.time.LocalDateTimeUtil.asMicros
import xtdb.util.closeOnCatch

/**
 * One IID trie of a data file - see [ArrowHashTrie.iidRoots].
 */
class BufferPoolSegment private constructor(
    private val al: BufferAllocator, private val bp: BufferPool, private val mm: PageMetadata.Factory,
    val slug: TableSlug, val trieKey: TrieKey, val metadataPredicate: MetadataPredicate?,
    private val footer: ArrowFooter, private val iidRoot: ArrowHashTrie.IidRoot,
) : Segment<ArrowHashTrie.Leaf> {
    val dataFilePath = slug.dataFilePath(trieKey)
    private val parsedTrieKey = Trie.parseKey(trieKey)
    private val recency: RecencyMicros = parsedTrieKey.recency?.atStartOfDay()?.asMicros ?: Long.MAX_VALUE

    override val part: ByteArray?
        get() = parsedTrieKey.part?.toArray()

    override val sameSystemTimeResolved = parsedTrieKey.level != 0L

    override val schema: Schema = footer.schema

    private val rel = Relation(al, schema)

    // an IID trie contributes at most one page to a merge task, and its pages' tasks run in path order,
    // so each page is closed at its last use before the next one loads.
    private var loadedPageIndex: Int? = null

    private fun unloadPage() {
        rel.close()
        loadedPageIndex = null
    }

    private inner class DataPage(private val dataPageIndex: Int) : Segment.Page<ArrowHashTrie.Leaf> {
        override val sameSystemTimeResolved get() = this@BufferPoolSegment.sameSystemTimeResolved

        override suspend fun loadDataPage(al: BufferAllocator): RelationReader {
            if (loadedPageIndex != dataPageIndex) {
                unloadPage()
                bp.getRecordBatch(dataFilePath, dataPageIndex).use { rb -> rel.closeOnCatch { it.load(rb) } }
                loadedPageIndex = dataPageIndex
            }

            return rel
        }

        override fun close() {
            if (loadedPageIndex == dataPageIndex) unloadPage()
        }
    }

    inner class Metadata(private val pageMetadata: PageMetadata) : Segment.Metadata<ArrowHashTrie.Leaf> {

        override val trie = pageMetadata.trie.iidTrie(iidRoot)

        private val pageIdxPredicate = metadataPredicate?.build(pageMetadata)

        private val pages = HashMap<Int, PageMeta<ArrowHashTrie.Leaf>>()

        override fun page(leaf: ArrowHashTrie.Leaf): PageMeta<ArrowHashTrie.Leaf> {
            val dataPageIndex = leaf.dataPageIndex

            return pages.getOrPut(dataPageIndex) {
                pageMeta(
                    DataPage(dataPageIndex), this, leaf,
                    pageMetadata.temporalMetadata(dataPageIndex),
                    minOf(recency, pageMetadata.maxRecency(dataPageIndex) ?: Long.MAX_VALUE)
                )
            }
        }

        override fun testPage(leaf: ArrowHashTrie.Leaf) = pageIdxPredicate?.test(leaf.dataPageIndex) ?: true

        override fun close() = pageMetadata.close()
    }

    override suspend fun openMetadata(): Metadata = Metadata(mm.openPageMetadata(slug.metaFilePath(trieKey)))

    override fun close() = unloadPage()

    companion object {
        /**
         * Opens a segment for each of the data file's IID tries.
         */
        suspend fun open(
            al: BufferAllocator, bp: BufferPool, mm: PageMetadata.Factory,
            slug: TableSlug, trieKey: TrieKey, metadataPredicate: MetadataPredicate? = null,
        ): List<BufferPoolSegment> {
            val footer = bp.getFooter(slug.dataFilePath(trieKey))
            val iidRoots = mm.openPageMetadata(slug.metaFilePath(trieKey)).use { it.trie.iidRoots }

            return iidRoots.map { BufferPoolSegment(al, bp, mm, slug, trieKey, metadataPredicate, footer, it) }
        }

        @JvmStatic
        fun openSync(
            al: BufferAllocator, bp: BufferPool, mm: PageMetadata.Factory,
            slug: TableSlug, trieKey: TrieKey, metadataPredicate: MetadataPredicate?,
        ) = runBlocking { open(al, bp, mm, slug, trieKey, metadataPredicate) }
    }
}
