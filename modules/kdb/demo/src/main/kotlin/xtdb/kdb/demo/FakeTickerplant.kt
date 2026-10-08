package xtdb.kdb.demo

import com.kx.c
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Just enough of a kdb+tick tickerplant to subscribe to: speaks kdb+ IPC (via javakdb in server mode),
 * answers each subscriber's one sync `.u.sub`/`.u.i`/`.u.L` request with the tables it was asked for and a log
 * position - it can't evaluate q, so it only picks the tables out of the request with a regex (syms are ignored),
 * and records the request - and then [publish]es `upd`s to every subscriber.
 *
 * Any number of subscribers; one that joins late only sees what's published after it.
 */
class FakeTickerplant(
    private val schemas: Map<String, c.Flip>,
    private val logFile: String = ":log/sym2026.10.08",
    private val msgCount: Long = 41,
    port0: Int = 0,
) : AutoCloseable {

    private val server = ServerSocket(port0)
    val port get() = server.localPort

    val requests = CopyOnWriteArrayList<String>()
    val subscribed = CountDownLatch(1)

    private val conns = CopyOnWriteArrayList<c>()

    private fun serve(c: c) {
        try {
            while (true) {
                val (type, msg) = c.readMsg().let { it[0] as Byte to it[1] }
                if (type == 1.toByte()) {
                    val expr = String(msg as CharArray)
                    requests += expr
                    // q's `each` over an atom gives the bare (`tbl;schema) pair, over a list gives a list of them
                    val asked = Regex(" each ((?:`[\\w.]+)+)").find(expr)?.groupValues?.get(1)?.split('`')?.filter { it.isNotEmpty() }
                    val subbed: Array<*> = when {
                        asked == null -> schemas.map { (t, f) -> arrayOf(t, f) }.toTypedArray()
                        asked.size == 1 -> arrayOf(asked[0], schemas.getValue(asked[0]))
                        else -> asked.map { arrayOf(it, schemas.getValue(it)) }.toTypedArray()
                    }
                    synchronized(this) {
                        c.kr(arrayOf<Any>(subbed, arrayOf<Any>(msgCount, logFile)))
                        conns += c
                    }
                    subscribed.countDown()
                }
            }
        } catch (_: Exception) {
            // subscriber went away, or we were closed
        } finally {
            conns -= c
            runCatching { c.close() }
        }
    }

    private val acceptor = Thread {
        try {
            while (true) {
                val c = c(server)
                Thread { serve(c) }.apply { isDaemon = true; start() }
            }
        } catch (_: Exception) {
            // closed
        }
    }.apply { isDaemon = true; start() }

    fun awaitSubscriber() = check(subscribed.await(30, TimeUnit.SECONDS)) { "nobody subscribed" }

    /** What the tickerplant's `.u.pub` sends: `(`upd; table; data)`, where data is a table ([c.Flip]) or a list of columns. */
    fun publish(table: String, data: Any) = synchronized(this) {
        for (c in conns) runCatching { c.ks(arrayOf("upd", table, data)) }.onFailure { conns -= c }
    }

    override fun close() {
        conns.forEach { runCatching { it.close() } }
        runCatching { server.close() }
    }
}
