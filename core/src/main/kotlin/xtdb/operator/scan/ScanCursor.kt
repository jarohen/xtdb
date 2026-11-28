package xtdb.operator.scan

import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.BufferAllocator
import xtdb.api.ICursor
import xtdb.arrow.RelationReader
import xtdb.arrow.VectorIndirection.Mask
import xtdb.operator.SelectionSpec
import xtdb.segment.RunPage
import xtdb.segment.Segment
import xtdb.segment.SegmentRun
import xtdb.trie.ColumnName
import xtdb.util.TemporalBounds
import xtdb.util.closeAll
import java.util.*
import java.util.function.Consumer

class ScanCursor(
    private val al: BufferAllocator,

    private val colNames: List<ColumnName>, private val colPreds: Map<ColumnName, SelectionSpec>,
    temporalBounds: TemporalBounds, clampValidTime: Boolean,

    private val ownedSegments: List<Segment<*>>,
    segmentRuns: List<SegmentRun>,

    private val metrics: ScanMetrics
) : ICursor {

    override val cursorType get() = "scan"
    override val childCursors get() = emptyList<ICursor>()
    override val cursorAttributes get() = metrics.snapshot()

    private val iidPred = colPreds["_iid"]
    private val contentPreds = colPreds.filterKeys { it != "_iid" }.values.toList()

    private val bufferedRels: Queue<RelationReader> = LinkedList()

    private val ctx = object : RunContext {
        override fun load(page: RunPage): RelationReader {
            // we're not in coroutine land here, so it's a good boundary for runBlocking
            val rel = runBlocking { page.page.loadDataPage(al) }

            // rows physically read off the pages we loaded — the scan's throughput denominator,
            // before iid-selection and bitemporal resolution whittle them down to the emitted rows
            metrics.addRowsRead(rel.rowCount.toLong())

            return when (iidPred) {
                null -> rel
                is MultiIidSelector -> rel.select(iidPred.select(al, rel, page.path).toSelection())
                else -> rel.select(iidPred.select(rel).toSelection())
            }
        }

        override fun openOutput(rel: RelationReader) = BitemporalConsumer.open(al, rel, colNames)

        override fun emit(page: RunPage, rel: RelationReader) {
            val mask = contentPreds.fold(Mask.all(rel.rowCount)) { acc, colPred ->
                if (acc.cardinality == 0) acc else colPred.select(rel, acc)
            }

            when (mask.cardinality) {
                0 -> Unit
                rel.rowCount -> bufferedRels.add(rel.openSlice(al))
                else -> bufferedRels.add(rel.select(mask).openSlice(al))
            }
        }
    }

    private val runs = segmentRuns.map { ScanRun(it.pages, ctx) }
    private val merge = EntityMerge(runs)

    private val resolver: EntityResolver =
        if (temporalBounds.validTime.isPoint && temporalBounds.systemTime.isPoint)
            AsOfResolver(temporalBounds, clampValidTime)
        else PolygonResolver(temporalBounds, clampValidTime)

    override fun tryAdvance(c: Consumer<in RelationReader>): Boolean {
        while (true) {
            bufferedRels.poll()?.use {
                c.accept(it)
                return true
            }

            if (merge.nextEntity()) resolver.resolveEntity(merge)
            else return bufferedRels.poll()?.use { c.accept(it); true } ?: false
        }
    }

    override fun close() {
        (bufferedRels + runs + ownedSegments + colPreds.values).closeAll()
        bufferedRels.clear()
    }
}
