package xtdb.operator.scan

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.BufferAllocator
import xtdb.api.ICursor
import xtdb.arrow.RelationReader
import xtdb.arrow.VectorIndirection.Mask
import xtdb.operator.SelectionSpec
import xtdb.segment.MergeTask
import xtdb.segment.Segment
import com.carrotsearch.hppc.IntArrayList
import xtdb.arrow.LongLongVectorReader
import xtdb.arrow.NullVector
import xtdb.arrow.VectorReader
import xtdb.time.TEMPORAL_COL_NAMES
import xtdb.trie.ColumnName
import xtdb.trie.EventOp
import xtdb.trie.EventRowPointer
import xtdb.trie.LeafPointer
import xtdb.util.TemporalBounds
import xtdb.util.closeAll
import xtdb.util.safeMap
import java.util.*
import java.util.function.Consumer
import java.util.concurrent.*

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

    private fun RelationReader.maybeSelect(iidPred: SelectionSpec?, path: ByteArray) =
        when (iidPred) {
            null -> this
            is MultiIidSelector -> select(iidPred.select(al, this, path).toSelection())
            else -> select(iidPred.select(this).toSelection())
        }

    private fun RelationReader.selectMatching(colPreds: List<SelectionSpec>): RelationReader {
        val mask = colPreds.fold(Mask.all(rowCount)) { acc, colPred ->
            if (acc.cardinality == 0) acc else colPred.select(this, acc)
        }

        return if (mask.cardinality == rowCount) this else select(mask)
    }

    private val loadCols: Set<ColumnName> = (colNames + colPreds.keys).toSet()

    private class Iid(val high: Long, val low: Long)

    // predicates over a put event's own content columns: a necessary condition on any row the entity can emit,
    // because a resolved row is always a put event, emitted with that event's content columns.
    private val pagePreds = colPreds.filterKeys { it != "_iid" && it !in TEMPORAL_COL_NAMES }.values.toList()
    private val predContentCols = (colPreds.keys.filterNot { it in TEMPORAL_COL_NAMES }).toList()

    /**
     * The sorted, distinct iids of entities with at least one put event in the task passing [pagePreds],
     * or null where the full merge is the cheaper way (no page predicates, or too many rows pass).
     */
    private fun candidateIids(leafReaders: List<RelationReader>, pointers: List<LeafPointer>): List<Iid>? {
        if (pagePreds.isEmpty()) return null

        val found = ArrayList<Iid>()
        var inRange = 0L
        var passing = 0L

        for (ptr in pointers) {
            val rel = leafReaders[ptr.relIdx]
            val evPtr = ptr.evPtr
            val lo = evPtr.minIndex
            val hi = evPtr.maxIndex
            inRange += hi - lo

            val opVec = rel["op"]
            val putTypeId = opVec.legNames!!.indexOfFirst { EventOp.ofLegOrNull(it) == EventOp.PUT }
            if (putTypeId < 0) continue

            val putRows = IntArrayList()
            for (row in lo until hi) if (opVec.getTypeId(row).toInt() == putTypeId) putRows.add(row)
            val sel = putRows.toArray()

            val putVec = opVec.vectorForOrNull("put")?.takeIf { !it.keyNames.isNullOrEmpty() }?.select(sel) ?: continue

            val cols = linkedMapOf<String, VectorReader>()
            for (c in predContentCols)
                cols[c] = if (c == "_iid") rel["_iid"].select(sel) else putVec.vectorForOrNull(c) ?: NullVector(true, sel.size)
            val predRel = RelationReader.from(cols, sel.size)

            val mask = pagePreds.fold(Mask.all(sel.size)) { acc, pred -> if (acc.cardinality == 0) acc else pred.select(predRel, acc) }
            passing += mask.cardinality
            // pages are hash-ordered samples of the task, so a clearly unselective first page decides it
            if (passing > 2 * MAX_CANDIDATE_FRACTION * inRange) return null
            val hits = mask.toSelection().map { sel[it] }

            val iidVec = rel["_iid"] as LongLongVectorReader
            for (row in hits) found.add(Iid(iidVec.getLongLongHigh(row), iidVec.getLongLongLow(row)))
        }

        if (passing > inRange * MAX_CANDIDATE_FRACTION) return null

        found.sortWith { l, r ->
            if (l.high != r.high) java.lang.Long.compareUnsigned(l.high, r.high)
            else java.lang.Long.compareUnsigned(l.low, r.low)
        }

        val distinct = ArrayList<Iid>(found.size)
        for (iid in found) {
            val last = distinct.lastOrNull()
            if (last == null || last.high != iid.high || last.low != iid.low) distinct.add(iid)
        }
        return distinct
    }

    private companion object {
        val WORKERS = (System.getenv("XTDB_SCAN_WORKERS") ?: "0").toInt()
        val WINDOW = (System.getenv("XTDB_SCAN_WINDOW") ?: (WORKERS * 2).toString()).toInt()
        val ORDERED = (System.getenv("XTDB_SCAN_ORDERED") ?: "true") == "true"
        val POOL: ExecutorService by lazy {
            Executors.newFixedThreadPool(WORKERS) { r -> Thread(r, "scan-worker").also { it.isDaemon = true } }
        }
        val TARGETED = (System.getenv("XTDB_SCAN_TARGETED") ?: "true") == "true"
        val MAX_CANDIDATE_FRACTION = (System.getenv("XTDB_SCAN_MAXFRAC") ?: "0.05").toDouble()
    }

    private val bufferedRels: Queue<RelationReader> = LinkedList()

    private class TaskResult(val rels: List<RelationReader>, val rowsRead: Long)

    private fun bufferTask(task: MergeTask): TaskResult {
        return task.use {
            val iidPred = colPreds["_iid"]
            val taskPath = task.path
            val resolver = openResolver()

            // we're not in coroutine land here, so it's a good boundary for runBlocking
            val loadedPages = runBlocking { task.pages.map { async { it.loadDataPage(al, loadCols) } }.awaitAll() }

            // rows physically read off the pages we loaded — the scan's throughput denominator,
            // before iid-selection and bitemporal resolution whittle them down to the emitted rows
            val rowsRead = loadedPages.sumOf { it.rowCount.toLong() }

            val leafReaders = loadedPages.map { it.maybeSelect(iidPred, taskPath) }

            val pointers = leafReaders.mapIndexedNotNull { idx, leafReader ->
                EventRowPointer(leafReader, if (task.wholePages) null else taskPath).takeIf { it.isValid() }?.let { LeafPointer(it, idx) }
            }

            BitemporalConsumer.open(al, leafReaders, colNames).use { bitemporalConsumer ->
                val candidates = if (TARGETED) candidateIids(leafReaders, pointers) else null

                if (candidates != null) {
                    val onEntity = ArrayList<LeafPointer>(pointers.size)
                    for (iid in candidates) {
                        onEntity.clear()
                        for (ptr in pointers) if (ptr.evPtr.seekToIid(iid.high, iid.low)) onEntity.add(ptr)
                        if (onEntity.isEmpty()) continue
                        val merge = EntityMerge(onEntity)
                        if (merge.nextEntity()) resolver.resolveEntity(merge, bitemporalConsumer)
                    }
                } else {
                    val merge = EntityMerge(pointers)

                    while (merge.nextEntity())
                        resolver.resolveEntity(merge, bitemporalConsumer)
                }

                val colPreds = colPreds.entries
                    .filterNot { it.key == "_iid" }
                    .map { it.value }

                bitemporalConsumer.build()
                    .map { childRel -> childRel.selectMatching(colPreds) }
                    .filter { it.rowCount > 0 }
                    .safeMap { it.openSlice(al) }
                    .let { TaskResult(it, rowsRead) }
            }
        }
    }

    // --- parallel read-ahead (spike)

    private class Job(val task: MergeTask) {
        @Volatile var cancelled = false
        val done = CompletableFuture<TaskResult?>()
    }

    private val taskList: List<MergeTask> = mergeTasks.asSequence().toList()

    // a page shared between tasks has a plain-var cache and is closed by its last task in plan order,
    // so concurrency is only sound where every page belongs to exactly one task
    private val parallel: Boolean = WORKERS > 0 && run {
        val seen = IdentityHashMap<Any, Boolean>()
        taskList.all { t -> t.pages.all { seen.put(it, true) == null } }
    }

    private val taskIter = taskList.iterator()
    private val inFlight = ArrayDeque<Job>()
    private val completed = LinkedBlockingQueue<Job>()
    private val executor get() = POOL

    private fun submitMore() {
        while (inFlight.size < WINDOW && taskIter.hasNext()) {
            val job = Job(taskIter.next())
            inFlight.add(job)
            executor.execute {
                try {
                    if (job.cancelled) job.done.complete(null)
                    else job.done.complete(bufferTask(job.task))
                } catch (t: Throwable) {
                    job.done.completeExceptionally(t)
                } finally {
                    completed.add(job)
                }
            }
        }
    }

    private fun nextJob(): Job? {
        submitMore()
        if (inFlight.isEmpty()) return null
        val job = if (ORDERED) inFlight.first() else completed.take()
        if (ORDERED) job.done.join()
        inFlight.remove(job)
        if (ORDERED) completed.remove(job)
        return job
    }

    private fun tryAdvanceParallel(c: Consumer<in RelationReader>): Boolean {
        while (true) {
            bufferedRels.poll()?.use {
                c.accept(it)
                return true
            }

            val job = nextJob() ?: return false
            val res = try { job.done.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
            if (res != null) {
                metrics.addRowsRead(res.rowsRead)
                bufferedRels.addAll(res.rels)
            }
        }
    }

    private fun closeParallel() {
        // quiesce every worker before anything the workers touch is closed
        inFlight.forEach { it.cancelled = true }
        inFlight.forEach { job ->
            val res = try { job.done.get() } catch (e: Exception) { null }
            res?.rels?.closeAll()
        }
        inFlight.clear()
    }

    override fun tryAdvance(c: Consumer<in RelationReader>): Boolean {
        if (parallel) return tryAdvanceParallel(c)

        bufferedRels.poll()?.use {
            c.accept(it)
            return true
        }

        while (taskIter.hasNext()) {
            bufferTask(taskIter.next()).let { metrics.addRowsRead(it.rowsRead); bufferedRels.addAll(it.rels) }

            bufferedRels.poll()?.use {
                c.accept(it)
                return true
            }
        }

        return false
    }

    override fun close() {
        if (parallel) closeParallel()
        (bufferedRels + ownedSegments + colPreds.values).closeAll()
        bufferedRels.clear()
    }
}
