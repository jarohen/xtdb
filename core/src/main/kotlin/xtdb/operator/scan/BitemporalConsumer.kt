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

    /**
     * The task's rows as one relation owned by this consumer; null if none survive.
     * [select] narrows each page's resolved rows (the column predicates) before they are copied together.
     */
    fun build(select: (RelationReader) -> RelationReader): RelationReader? {
        val parts = ArrayList<RelationReader>()

        for (p in rels.indices) {
            val rows = pageRows[p]
            val n = rows.sel.size()
            if (n == 0) continue
            val sel = rows.sel.toArray()
            val rel = rels[p]

            // an erase-only leaf writes `put` as an empty struct (see #4577) — treat that as
            // "no puts here" rather than merging its absent columns in.
            val putReader = rel["op"].vectorForOrNull("put")
                ?.takeIf { putVec -> !putVec.keyNames.isNullOrEmpty() }
                ?.select(sel)

            val cols = linkedMapOf<String, VectorReader>()
            for (colName in contentColNames)
                cols[colName] =
                    if (colName == "_iid") rel["_iid"].select(sel)
                    else putReader?.vectorForOrNull(colName) ?: NullVector(true, n)

            fun temporal(name: String, nullable: Boolean, vals: LongArrayList) {
                val out = openOut(maybe(VectorType.INSTANT, nullable))
                for (j in 0 until n) {
                    val v = vals[j]
                    if (nullable && v == MAX_LONG) out.writeNull() else out.writeLong(v)
                }
                cols[name] = out
            }

            if (hasValidFrom) temporal("_valid_from", false, rows.validFrom)
            if (hasValidTo) temporal("_valid_to", true, rows.validTo)
            if (hasSystemFrom) temporal("_system_from", false, rows.systemFrom)
            if (hasSystemTo) temporal("_system_to", true, rows.systemTo)

            select(RelationReader.from(cols, n)).takeIf { it.rowCount > 0 }?.let { parts.add(it) }
        }

        if (parts.isEmpty()) return null

        val cols = linkedMapOf<String, VectorReader>()
        for (colName in colNames) {
            val out = openOut(mergeTypes(parts.map { it[colName].type }))
            for (part in parts) out.append(part[colName])
            cols[colName] = out
        }

        return RelationReader.from(cols, parts.sumOf { it.rowCount })
    }

    override fun close() {
        outVecs.closeAll(); outVecs.clear()
    }
}
