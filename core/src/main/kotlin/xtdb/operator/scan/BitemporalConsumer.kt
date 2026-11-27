package xtdb.operator.scan

import com.carrotsearch.hppc.IntArrayList
import com.carrotsearch.hppc.LongArrayList
import org.apache.arrow.memory.BufferAllocator
import xtdb.arrow.*
import xtdb.arrow.MergeTypes.Companion.mergeTypes
import xtdb.arrow.Vector.Companion.openVector
import xtdb.arrow.VectorType.Companion.maybe
import xtdb.time.TEMPORAL_COL_NAMES
import xtdb.trie.ColumnName
import xtdb.util.closeAll
import kotlin.Long.Companion.MAX_VALUE as MAX_LONG

/**
 * Collects the rows a merge task resolves, from any of its pages, and builds them as one relation.
 * Reused task to task: [reset] begins a task and releases the previous task's output.
 */
class BitemporalConsumer(private val al: BufferAllocator, private val colNames: List<ColumnName>) : AutoCloseable {
    private val hasValidFrom = "_valid_from" in colNames
    private val hasValidTo = "_valid_to" in colNames
    private val hasSystemFrom = "_system_from" in colNames
    private val hasSystemTo = "_system_to" in colNames
    private val contentColNames = colNames.filterNot { it in TEMPORAL_COL_NAMES }

    private class PageRows {
        val sel = IntArrayList()
        val validFrom = LongArrayList()
        val validTo = LongArrayList()
        val systemFrom = LongArrayList()
        val systemTo = LongArrayList()

        fun clear() {
            sel.clear(); validFrom.clear(); validTo.clear(); systemFrom.clear(); systemTo.clear()
        }
    }

    private var rels: List<RelationReader> = emptyList()
    private val pageRows = ArrayList<PageRows>()
    private val outVecs = ArrayList<Vector>()

    fun reset(rels: List<RelationReader>) {
        outVecs.closeAll(); outVecs.clear()
        this.rels = rels
        while (pageRows.size < rels.size) pageRows.add(PageRows())
        for (p in pageRows) p.clear()
    }

    fun accept(
        relIdx: Int, rowIdx: Int,
        validFrom: Long, validTo: Long,
        systemFrom: Long, systemTo: Long
    ) {
        val p = pageRows[relIdx]
        p.sel.add(rowIdx)
        if (hasValidFrom) p.validFrom.add(validFrom)
        if (hasValidTo) p.validTo.add(validTo)
        if (hasSystemFrom) p.systemFrom.add(systemFrom)
        if (hasSystemTo) p.systemTo.add(systemTo)
    }

    private fun openOut(type: VectorType) = al.openVector(type).also { outVecs.add(it) }

    /** The task's rows, page by page, as one relation owned by this consumer; null if there are none. */
    fun build(): RelationReader? {
        val pages = rels.indices.filter { pageRows[it].sel.size() > 0 }
        val rowCount = pages.sumOf { pageRows[it].sel.size() }
        if (rowCount == 0) return null

        val selArrays = pages.map { pageRows[it].sel.toArray() }

        // an erase-only leaf writes `put` as an empty struct (see #4577) — treat that as
        // "no puts here" rather than merging its absent columns in.
        val putReaders = pages.mapIndexed { i, p ->
            rels[p]["op"].vectorForOrNull("put")
                ?.takeIf { putVec -> !putVec.keyNames.isNullOrEmpty() }
                ?.select(selArrays[i])
        }

        val cols = linkedMapOf<String, VectorReader>()

        for (colName in contentColNames) {
            val srcs = pages.mapIndexed { i, p ->
                if (colName == "_iid") rels[p]["_iid"].select(selArrays[i])
                else putReaders[i]?.vectorForOrNull(colName) ?: NullVector(true, selArrays[i].size)
            }
            val out = openOut(mergeTypes(srcs.map { it.type }))
            for (src in srcs) out.append(src)
            cols[colName] = out
        }

        fun temporal(name: String, nullable: Boolean, get: (PageRows) -> LongArrayList) {
            val out = openOut(maybe(VectorType.INSTANT, nullable))
            for (p in pages) {
                val vals = get(pageRows[p])
                for (j in 0 until vals.size()) {
                    val v = vals[j]
                    if (nullable && v == MAX_LONG) out.writeNull() else out.writeLong(v)
                }
            }
            cols[name] = out
        }

        if (hasValidFrom) temporal("_valid_from", false) { it.validFrom }
        if (hasValidTo) temporal("_valid_to", true) { it.validTo }
        if (hasSystemFrom) temporal("_system_from", false) { it.systemFrom }
        if (hasSystemTo) temporal("_system_to", true) { it.systemTo }

        return RelationReader.from(cols, rowCount)
    }

    override fun close() {
        outVecs.closeAll(); outVecs.clear()
    }
}
