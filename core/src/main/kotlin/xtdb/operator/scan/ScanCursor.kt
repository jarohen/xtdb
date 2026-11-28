package xtdb.operator.scan

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.BufferAllocator
import xtdb.api.ICursor
import xtdb.arrow.RelationReader
import xtdb.arrow.VectorIndirection.Mask
import xtdb.operator.SelectionSpec
import xtdb.operator.scan.BitemporalConsumer.RelBuilder
import xtdb.segment.MergeTask
import xtdb.segment.Segment
import xtdb.trie.ColumnName
import xtdb.trie.EventRowPointer
import xtdb.trie.LeafPointer
import xtdb.util.TemporalBounds
import xtdb.util.closeAll
import java.util.*
import java.util.function.Consumer

class ScanCursor(
    private val al: BufferAllocator,

    private val colNames: List<ColumnName>, private val colPreds: Map<ColumnName, SelectionSpec>,
    private val temporalBounds: TemporalBounds, private val clampValidTime: Boolean,

    private val ownedSegments: List<Segment<*>>,
    private val mergeTasks: Iterator<MergeTask>,

    private val metrics: ScanMetrics
) : ICursor {

    override val cursorType get() = "scan"
    override val childCursors get() = emptyList<ICursor>()
    override val cursorAttributes get() = metrics.snapshot()

    private fun openResolver(): EntityResolver =
        if (temporalBounds.validTime.isPoint && temporalBounds.systemTime.isPoint)
            AsOfResolver(temporalBounds, clampValidTime)
        else PolygonResolver(temporalBounds, clampValidTime)

    private fun RelationReader.maybeSelect(iidPred: SelectionSpec?) =
        when (iidPred) {
            null -> this
            is MultiIidSelector -> select(iidPred.select(al, this, ByteArray(0)).toSelection())
            else -> select(iidPred.select(this).toSelection())
        }

    private fun RelationReader.selectMatching(colPreds: List<SelectionSpec>): RelationReader {
        val mask = colPreds.fold(Mask.all(rowCount)) { acc, colPred ->
            if (acc.cardinality == 0) acc else colPred.select(this, acc)
        }

        return if (mask.cardinality == rowCount) this else select(mask)
    }

    private val bufferedRels: Queue<RelationReader> = LinkedList()

    private val contentColPreds = colPreds.entries.filterNot { it.key == "_iid" }.map { it.value }

    private class OpenPage(val loadedRowCount: Int, val rel: RelationReader, val out: RelBuilder) : AutoCloseable {
        override fun close() = out.close()
    }

    private val openPages = IdentityHashMap<Segment.Page<*>, OpenPage>()

    private fun open(pages: List<Segment.Page<*>>): List<OpenPage> {
        val unopened = pages.filterNot { it in openPages }

        // we're not in coroutine land here, so it's a good boundary for runBlocking
        val loaded = runBlocking { unopened.map { async { it.loadDataPage(al) } }.awaitAll() }

        for ((page, rel) in unopened.zip(loaded)) {
            val selected = rel.maybeSelect(colPreds["_iid"])
            openPages[page] = OpenPage(rel.rowCount, selected, RelBuilder.open(al, selected, colNames))
        }

        return pages.map { openPages.getValue(it) }
    }

    private fun emitPage(page: OpenPage) {
        page.out.build()
            .selectMatching(contentColPreds)
            .takeIf { it.rowCount > 0 }
            ?.let { bufferedRels.add(it.openSlice(al)) }
    }

    private fun bufferTask(task: MergeTask) {
        task.use {
            val resolver = openResolver()
            val pages = open(task.pages)

            // rows physically read off the pages we loaded — the scan's throughput denominator,
            // before iid-selection and bitemporal resolution whittle them down to the emitted rows
            metrics.addRowsRead(pages.sumOf { it.loadedRowCount.toLong() })

            val pointers = pages.mapIndexedNotNull { idx, page ->
                EventRowPointer(page.rel, task.path).takeIf { it.isValid() }?.let { LeafPointer(it, idx) }
            }

            val merge = EntityMerge(pointers)
            val out = BitemporalConsumer(pages.map { it.out })

            while (merge.nextEntity())
                resolver.resolveEntity(merge, out)

            for (page in task.lastUsedPages)
                openPages.remove(page)!!.use { emitPage(it) }
        }
    }

    override fun tryAdvance(c: Consumer<in RelationReader>): Boolean {
        bufferedRels.poll()?.use {
            c.accept(it)
            return true
        }

        while (mergeTasks.hasNext()) {
            bufferTask(mergeTasks.next())

            bufferedRels.poll()?.use {
                c.accept(it)
                return true
            }
        }

        return false
    }

    override fun close() {
        (bufferedRels + openPages.values + ownedSegments + colPreds.values).closeAll()
        bufferedRels.clear()
        openPages.clear()
    }
}
