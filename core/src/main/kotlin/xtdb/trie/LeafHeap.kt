package xtdb.trie

/**
 * A cursor over a chain of pages whose rows are already, taken together, in [EventRowPointer.compare] order.
 * [evPtr] is the pointer of the page it is on; [relIdx] is that page's index, consecutive along the chain.
 */
internal class LeafPointer(private val chain: List<EventRowPointer>, private val firstRelIdx: Int) {
    constructor(evPtr: EventRowPointer, relIdx: Int) : this(listOf(evPtr), relIdx)

    private var pos = 0
    var evPtr: EventRowPointer = chain[0]
        private set
    val relIdx get() = firstRelIdx + pos

    init { settle() }

    /** Moves past exhausted pages; returns whether the chain still has a row. */
    fun settle(): Boolean {
        while (!evPtr.isValid() && pos + 1 < chain.size) evPtr = chain[++pos]
        return evPtr.isValid()
    }

    fun isValid() = evPtr.isValid()
}

/**
 * A loser (tournament) tree of page pointers in [EventRowPointer.compare] order: [top] is the next event to serve.
 * A caller advances the top pointer in place, then calls [reseatTop], which replays only the winner's leaf-to-root path:
 * one compare per level.
 *
 * `losers[1 until k]` hold the loser of the match at each internal node, `losers[0]` the overall winner;
 * source `s` plays at leaf `k + s`, and node `i`'s parent is `i / 2`.
 * An exhausted source loses every match, so it sinks without being removed.
 */
internal class LeafHeap(pointers: List<LeafPointer>) {
    private val ptrs = pointers.filter { it.isValid() }.toTypedArray()
    private val k = ptrs.size
    private val losers = IntArray(maxOf(k, 1))

    init {
        if (k > 0) {
            val winners = IntArray(2 * k)
            for (s in 0 until k) winners[k + s] = s
            for (i in k - 1 downTo 1) {
                val a = winners[2 * i]
                val b = winners[2 * i + 1]
                if (beats(a, b)) { winners[i] = a; losers[i] = b } else { winners[i] = b; losers[i] = a }
            }
            losers[0] = winners[1]
        }
    }

    fun top(): LeafPointer? = if (k == 0) null else ptrs[losers[0]].takeIf { it.isValid() }

    fun reseatTop() {
        var cand = losers[0]
        ptrs[cand].settle()
        var p = (cand + k) shr 1
        while (p >= 1) {
            val other = losers[p]
            if (beats(other, cand)) { losers[p] = cand; cand = other }
            p = p shr 1
        }
        losers[0] = cand
    }

    private fun beats(a: Int, b: Int): Boolean {
        val l = ptrs[a].evPtr
        val r = ptrs[b].evPtr
        if (!l.isValid()) return false
        if (!r.isValid()) return true
        return EventRowPointer.compare(l, r) < 0
    }
}
