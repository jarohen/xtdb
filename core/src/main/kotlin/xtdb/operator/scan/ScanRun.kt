package xtdb.operator.scan

import xtdb.arrow.RelationReader
import xtdb.segment.RunPage
import xtdb.trie.Bucketer
import xtdb.trie.EventRowPointer
import java.nio.ByteBuffer

/** One of an entity's events, as served to an [EntityResolver]. */
internal interface EntityEvent {
    val evPtr: EventRowPointer

    /** Emits the row the event is on, with the given bitemporal coordinates. */
    fun accept(validFrom: Long, validTo: Long, systemFrom: Long, systemTo: Long)
}

/** What a [ScanRun] needs from the scan it belongs to. */
internal interface RunContext {
    /** Loads the page's rows that the scan can read, in iid order; empty if there are none. */
    fun load(page: RunPage): RelationReader

    fun openOutput(rel: RelationReader): BitemporalConsumer

    /** Takes the rows [page] emitted as it retired, which are only valid until this returns. */
    fun emit(page: RunPage, rel: RelationReader)
}

/**
 * The merge's cursor over one [xtdb.segment.SegmentRun], for the life of the scan.
 *
 * It holds at most one page: it loads the next only when the merge reaches it, and retires the current
 * one — emitting the rows the resolver accepted from it — as soon as it moves past the page's last row.
 *
 * [iidHigh], [iidLow] and [systemFrom] are its key in the merge's order: the event it's on once loaded,
 * and before that, a lower bound for its page — the first iid under the page's path, ahead of every event at that iid.
 */
internal class ScanRun(pages: List<RunPage>, private val ctx: RunContext) : EntityEvent, AutoCloseable {

    private sealed interface State {
        class Pending(val page: RunPage) : State
        class Loaded(val page: RunPage, val evPtr: EventRowPointer, val out: BitemporalConsumer) : State
        data object Done : State
    }

    private val pages = pages.iterator()
    private var state: State = State.Done

    var iidHigh = 0L; private set
    var iidLow = 0L; private set
    var systemFrom = 0L; private set

    init {
        nextPage()
    }

    val isLoaded get() = state is State.Loaded
    val isDone get() = state === State.Done

    override val evPtr get() = (state as State.Loaded).evPtr

    private fun nextPage() {
        if (!pages.hasNext()) {
            state = State.Done
            return
        }

        val page = pages.next()
        state = State.Pending(page)

        val startIid = ByteBuffer.wrap(Bucketer.DEFAULT.startIid(page.path))
        iidHigh = startIid.getLong(0)
        iidLow = startIid.getLong(8)
        systemFrom = Long.MAX_VALUE
    }

    private fun onEvent(evPtr: EventRowPointer) {
        iidHigh = evPtr.iidHigh
        iidLow = evPtr.iidLow
        systemFrom = evPtr.systemFrom
    }

    /** Loads the pending page; if the scan can read nothing on it, moves on to the next. Only moves the key later. */
    fun load() {
        val pending = state as State.Pending
        val rel = ctx.load(pending.page)

        if (rel.rowCount == 0) {
            pending.page.page.close()
            nextPage()
            return
        }

        val evPtr = EventRowPointer(rel, ByteArray(0))
        state = State.Loaded(pending.page, evPtr, ctx.openOutput(rel))
        onEvent(evPtr)
    }

    private fun retire(loaded: State.Loaded) {
        try {
            ctx.emit(loaded.page, loaded.out.build())
        } finally {
            loaded.out.close()
            loaded.page.page.close()
        }

        nextPage()
    }

    private fun afterMove(loaded: State.Loaded) {
        val evPtr = loaded.evPtr
        if (evPtr.isValid()) onEvent(evPtr) else retire(loaded)
    }

    fun nextIndex() {
        val loaded = state as State.Loaded
        loaded.evPtr.nextIndex()
        afterMove(loaded)
    }

    fun skipToNextIid() {
        val loaded = state as State.Loaded
        loaded.evPtr.skipToNextIid()
        afterMove(loaded)
    }

    fun sameIidAs(iidHigh: Long, iidLow: Long) = this.iidHigh == iidHigh && this.iidLow == iidLow

    override fun accept(validFrom: Long, validTo: Long, systemFrom: Long, systemTo: Long) {
        val loaded = state as State.Loaded
        loaded.out.accept(loaded.evPtr.index, validFrom, validTo, systemFrom, systemTo)
    }

    override fun close() {
        when (val state = state) {
            is State.Loaded -> {
                state.out.close()
                state.page.page.close()
            }

            is State.Pending, State.Done -> Unit
        }

        this.state = State.Done
    }
}
