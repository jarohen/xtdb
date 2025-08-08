package xtdb.api.log

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xtdb.api.TransactionAborted
import xtdb.api.TransactionCommitted
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

class Dispatcher(private val outerScope: CoroutineScope) : CoroutineDispatcher() {

    private class Event(val context: CoroutineContext, val block: Runnable)

    private val lock = Any()
    private val evs = mutableListOf<Event>()

    @Volatile
    private var simulationDone = false

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(lock) {
            if (simulationDone) {
                outerScope.launch { block.run() }
            } else {
                evs.add(Event(context, block))
            }
        }
    }

    fun runSimulation() {
        while (synchronized(lock) { evs.isNotEmpty() }) {
            val ev = synchronized(lock) { evs.removeFirst() }
            ev.block.run()
        }
    }

    fun done() {
        synchronized(lock) {
            evs.forEach { outerScope.launch { it.block.run() } }
            evs.clear()
            simulationDone = true
        }
    }
}

@OptIn(InternalCoroutinesApi::class)
class Simulation(private val dispatcher: Dispatcher, parentScope: CoroutineScope) :
    AbstractCoroutine<Unit>(parentScope.coroutineContext + dispatcher, true, true) {

    fun runSimulation() = dispatcher.runSimulation()
    fun done() = dispatcher.done()
}

fun CoroutineScope.simulation(block: suspend Simulation.() -> Unit) {
    val dispatcher = Dispatcher(this)

    val sim = Simulation(dispatcher, this)

    sim.start(CoroutineStart.UNDISPATCHED, sim) {
        try {
            block()
        } catch (e: Exception) {
            println("bang ${e.javaClass}")
            throw e
        }
    }
}

class WatchersTest {

    @Test
    fun `test ready already`() = runTest(timeout = 1.seconds) {
        simulation {
            Watchers(3).use {
                val job = launch { it.await0(2) }

                runSimulation()
                assertTrue(job.isCompleted)
                done()
            }
        }
    }

    @Test
    fun `test awaits`() = runTest(timeout = 3.seconds) {
        simulation {
            Watchers(3, this).use {
                val awaitJob = launch { it.await0(4) }

                try {
                    runSimulation()
                    assertFalse(awaitJob.isCompleted)
                    done()
                } finally {
                    awaitJob.cancelAndJoin()
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `notifies watchers of completion`() = runTest(timeout = 1.seconds) {
        simulation {
            Watchers(3, this).use {
                val await5 = async { it.await0(5) }
                val await4 = async { it.await0(4) }

                runSimulation()

                assertFalse(await5.isCompleted)
                assertFalse(await4.isCompleted)

                val res4 = TransactionCommitted(4, Instant.parse("2021-01-01T00:00:00Z"))
                it.notify(4, res4)

                runSimulation()

                assertFalse(await5.isCompleted)
                assertTrue(await4.isCompleted)

                done()

                assertEquals(res4, await4.await())

                val res5 = TransactionAborted(5, Instant.parse("2021-01-02T00:00:00Z"), Exception("test"))
                it.notify(5, res5)

                assertEquals(res5, await5.await())
            }
        }
    }

    @Test
    fun `handles ingestion stopped`() = runTest(timeout = 1.seconds) {
        Watchers(3).use { watchers ->
            val await4 = async(SupervisorJob()) { watchers.await0(4) }

            assertThrows<TimeoutCancellationException> { withTimeout(50) { await4.await() } }

            val ex = Exception("test")
            watchers.notify(4, ex)

            assertThrows<IngestionStoppedException> { await4.await() }
                .also { assertEquals(ex, it.cause) }

            assertThrows<IngestionStoppedException> { watchers.await0(5) }
                .also { assertEquals(ex, it.cause) }

        }
    }
}