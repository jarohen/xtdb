package xtdb.indexer

import io.micrometer.tracing.Tracer
import org.apache.arrow.memory.BufferAllocator
import xtdb.arrow.INSTANT_TYPE
import xtdb.arrow.Relation
import xtdb.arrow.RelationAsStructReader
import xtdb.arrow.RelationReader
import xtdb.arrow.VectorReader
import xtdb.database.DatabaseName
import xtdb.error.Fault
import xtdb.error.Incorrect
import xtdb.error.Unsupported
import xtdb.indexer.OpenTx.Companion.checkNotForbidden
import xtdb.table.TableRef
import xtdb.time.InstantUtil.asMicros
import java.time.Instant
import java.time.ZoneId

private inline fun <R> Tracer?.withSpan(
    name: String,
    attributes: Map<String, Any?> = emptyMap(),
    block: () -> R,
): R {
    if (this == null) return block()
    val span = nextSpan().name(name).start()
    attributes.forEach { (k, v) -> span.tag(k, v.toString()) }
    return try {
        withSpan(span).use { block() }
    } finally {
        span.end()
    }
}

/**
 * Dispatches each row of a `tx-ops` rel to the right writer call on [openTx].
 *
 * Each leg (put-docs, patch-docs, delete-docs, erase-docs, sql) has its own inner indexer;
 * per-leg readers are constructed lazily the first time a tx-op of that shape is seen,
 * so an all-SQL tx pays nothing for the put/patch/delete/erase readers (and vice versa).
 */
internal class TxOpIndexer(
    private val allocator: BufferAllocator,
    private val openTx: OpenTx,
    private val txOpsRdr: VectorReader,
    private val systemTime: Instant,
    private val defaultTz: ZoneId?,
    private val dbName: DatabaseName,
    private val tracer: Tracer?,
) {
    private val systemTimeMicros = systemTime.asMicros

    private val sqlIndexer by lazy { SqlIndexer() }
    private val putDocsIndexer by lazy { PutDocsIndexer() }
    private val patchDocsIndexer by lazy { PatchDocsIndexer() }
    private val deleteDocsIndexer by lazy { DeleteDocsIndexer() }
    private val eraseDocsIndexer by lazy { EraseDocsIndexer() }

    fun indexOp(txOpIdx: Int): Unit = when (val leg = txOpsRdr.getLeg(txOpIdx)) {
        "sql" -> sqlIndexer.indexOp(txOpIdx)
        "put-docs" -> putDocsIndexer.indexOp(txOpIdx)
        "patch-docs" -> patchDocsIndexer.indexOp(txOpIdx)
        "delete-docs" -> deleteDocsIndexer.indexOp(txOpIdx)
        "erase-docs" -> eraseDocsIndexer.indexOp(txOpIdx)

        "xtql" -> throw Unsupported(
            "XTQL DML is no longer supported, as of 2.0.0-beta7. " +
                    "Please use SQL DML statements instead - see the release notes for more information.",
            "xtdb/xtql-dml-removed",
        )

        "call" -> throw Unsupported(
            "tx-fns are no longer supported, as of 2.0.0-beta7. " +
                    "Please use ASSERTs and SQL DML statements instead - see the release notes for more information.",
            "xtdb/tx-fns-removed",
        )

        else -> error("unknown tx-op leg: $leg")
    }

    private inner class SqlIndexer {
        private val sqlLeg = txOpsRdr.vectorFor("sql")
        private val queryRdr = sqlLeg.vectorFor("query")
        private val argsRdr = sqlLeg.vectorFor("args")
        private val qOpts = TxIndexer.QueryOpts(systemTime, defaultTz)

        fun indexOp(txOpIdx: Int) {
            val queryStr = queryRdr.getObject(txOpIdx) as String

            val argsBytes = if (argsRdr.isNull(txOpIdx)) null else argsRdr.getObject(txOpIdx) as ByteArray
            val loader = argsBytes?.let { Relation.streamLoader(allocator, it) }

            tracer.withSpan(
                "xtdb.transaction.sql",
                mapOf("query.text" to queryStr),
            ) {
                if (loader == null) {
                    openTx.executeDml(queryStr, args = null, opts = qOpts)
                } else {
                    loader.use { argsLoader ->
                        Relation(allocator, argsLoader.schema).use { paramRel ->
                            while (argsLoader.loadNextPage(paramRel)) {
                                repeat(paramRel.rowCount) { idx ->
                                    val selection = intArrayOf(idx)
                                    paramRel.select(selection).openSlice(allocator).use { argRow ->
                                        openTx.executeDml(queryStr, argRow, qOpts)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private inner class PutDocsIndexer {
        private val putLeg = txOpsRdr.vectorFor("put-docs")
        private val iidsRdr = putLeg.vectorFor("iids")
        private val iidRdr = iidsRdr.listElements
        private val docsRdr = putLeg.vectorFor("documents")
        private val validFromRdr = putLeg.vectorFor("_valid_from")
        private val validToRdr = putLeg.vectorFor("_valid_to")

        fun indexOp(txOpIdx: Int) {
            val legName = requireNotNull(docsRdr.getLeg(txOpIdx)) { "put-docs tx-op has no leg at idx $txOpIdx" }
            val tableRef = TableRef.parse(dbName, legName)
            checkNotForbidden(tableRef)

            val tableDocsRdr = docsRdr.vectorFor(legName)
            val docRdr = tableDocsRdr.listElements
            val ks = docRdr.keyNames ?: emptySet()

            val forbidden = ks.filter {
                it.startsWith("_") && it != "_id" && it != "_fn" && it != "_valid_from" && it != "_valid_to"
            }
            if (forbidden.isNotEmpty()) {
                throw Incorrect(
                    "Cannot put documents with columns: ${forbidden.toSet()}",
                    "xtdb/forbidden-columns",
                    mapOf("table" to tableRef, "forbidden-cols" to forbidden.toSet()),
                )
            }

            val docStartIdx = tableDocsRdr.getListStartIndex(txOpIdx)
            val docCount = tableDocsRdr.getListCount(txOpIdx)

            val docCols = ks
                .filter { it != "_valid_from" && it != "_valid_to" }
                .map { docRdr.vectorFor(it).select(docStartIdx, docCount) }

            val docStruct = RelationAsStructReader("doc", RelationReader.from(docCols, docCount))
                .withName("doc")

            val iidCol =
                if (!iidsRdr.isNull(txOpIdx))
                    iidRdr.select(iidsRdr.getListStartIndex(txOpIdx), docCount).withName("_iid")
                else
                    docRdr.vectorFor("_id").select(docStartIdx, docCount).withName("_id")

            val rowVfCol = docRdr.vectorForOrNull("_valid_from")?.let {
                assertTimestampColType(it)
                it.select(docStartIdx, docCount).withName("_valid_from")
            }
            val rowVtCol = docRdr.vectorForOrNull("_valid_to")?.let {
                assertTimestampColType(it)
                it.select(docStartIdx, docCount).withName("_valid_to")
            }

            val putRel = RelationReader.from(
                listOfNotNull(iidCol, docStruct, rowVfCol, rowVtCol),
                docCount,
            )

            val validFrom =
                if (validFromRdr.isNull(txOpIdx)) systemTimeMicros else validFromRdr.getLong(txOpIdx)
            val validTo =
                if (validToRdr.isNull(txOpIdx)) Long.MAX_VALUE else validToRdr.getLong(txOpIdx)

            openTx.table(tableRef).logPuts(validFrom, validTo, putRel)
        }
    }

    private inner class PatchDocsIndexer {
        private val patchLeg = txOpsRdr.vectorFor("patch-docs")
        private val iidsRdr = patchLeg.vectorFor("iids")
        private val iidRdr = iidsRdr.listElements
        private val docsRdr = patchLeg.vectorFor("documents")
        private val validFromRdr = patchLeg.vectorFor("_valid_from")
        private val validToRdr = patchLeg.vectorFor("_valid_to")

        fun indexOp(txOpIdx: Int) {
            val legName = requireNotNull(docsRdr.getLeg(txOpIdx)) { "patch-docs tx-op has no leg at idx $txOpIdx" }
            val tableRef = TableRef.parse(dbName, legName)

            val tableDocsRdr = docsRdr.vectorFor(legName)
            val docRdr = tableDocsRdr.listElements
            val ks = docRdr.keyNames ?: emptySet()

            val forbidden = ks.filter { it.startsWith("_") && it != "_id" && it != "_fn" }
            if (forbidden.isNotEmpty()) {
                throw Incorrect(
                    "Cannot patch documents with columns: ${forbidden.toSet()}",
                    "xtdb/forbidden-columns",
                    mapOf("table" to tableRef, "forbidden-cols" to forbidden.toSet()),
                )
            }

            // Arrow-null `_valid_from` on a patch-docs tx-op means "from current tx time" (matches put-docs /
            // delete-docs). SQL `PATCH ... FROM NULL` doesn't arrive as Arrow-null — the SQL planner writes the
            // start-of-time µs sentinel explicitly (see sql.clj #visitPatchStatementValidTimePortion).
            val validFromµs =
                if (validFromRdr.isNull(txOpIdx)) systemTimeMicros else validFromRdr.getLong(txOpIdx)
            val validToµs =
                if (validToRdr.isNull(txOpIdx)) Long.MAX_VALUE else validToRdr.getLong(txOpIdx)

            val docs = RelationReader.from(
                listOf(
                    iidRdr.select(iidsRdr.getListStartIndex(txOpIdx), iidsRdr.getListCount(txOpIdx))
                        .withName("_iid"),
                    docRdr.select(tableDocsRdr.getListStartIndex(txOpIdx), tableDocsRdr.getListCount(txOpIdx))
                        .withName("doc"),
                ),
            )

            tracer.withSpan(
                "xtdb.transaction.patch-docs",
                mapOf(
                    "db" to tableRef.dbName,
                    "schema" to tableRef.schemaName,
                    "table" to tableRef.tableName,
                ),
            ) {
                openTx.patchDocs(openTx.table(tableRef), validFromµs, validToµs, docs)
            }
        }
    }

    private inner class DeleteDocsIndexer {
        private val deleteLeg = txOpsRdr.vectorFor("delete-docs")
        private val tableRdr = deleteLeg.vectorFor("table")
        private val iidsRdr = deleteLeg.vectorFor("iids")
        private val iidRdr = iidsRdr.listElements
        private val validFromRdr = deleteLeg.vectorFor("_valid_from")
        private val validToRdr = deleteLeg.vectorFor("_valid_to")

        fun indexOp(txOpIdx: Int) {
            val tableRef = TableRef.parse(dbName, tableRdr.getObject(txOpIdx) as String)
            checkNotForbidden(tableRef)

            val validFrom =
                if (validFromRdr.isNull(txOpIdx)) systemTimeMicros else validFromRdr.getLong(txOpIdx)
            val validTo =
                if (validToRdr.isNull(txOpIdx)) Long.MAX_VALUE else validToRdr.getLong(txOpIdx)

            val iidsRel = RelationReader.from(
                listOf(
                    iidRdr.select(iidsRdr.getListStartIndex(txOpIdx), iidsRdr.getListCount(txOpIdx))
                        .withName("_iid"),
                ),
            )

            openTx.table(tableRef).logDeletes(validFrom, validTo, iidsRel)
        }
    }

    private inner class EraseDocsIndexer {
        private val eraseLeg = txOpsRdr.vectorFor("erase-docs")
        private val tableRdr = eraseLeg.vectorFor("table")
        private val iidsRdr = eraseLeg.vectorFor("iids")
        private val iidRdr = iidsRdr.listElements

        fun indexOp(txOpIdx: Int) {
            val tableRef = TableRef.parse(dbName, tableRdr.getObject(txOpIdx) as String)
            checkNotForbidden(tableRef)

            val iids = iidRdr.select(iidsRdr.getListStartIndex(txOpIdx), iidsRdr.getListCount(txOpIdx))
            openTx.table(tableRef).logErases(iids)
        }
    }

    private fun assertTimestampColType(rdr: VectorReader) {
        if (rdr.arrowType != INSTANT_TYPE) {
            throw Fault(
                "Invalid timestamp col type",
                "xtdb/invalid-timestamp-col-type",
                mapOf("field" to rdr.field),
            )
        }
    }
}
