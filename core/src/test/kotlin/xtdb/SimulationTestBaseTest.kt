package xtdb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds

class SimulationTestBaseTest : SimulationTestBase() {

    @Test
    @Timeout(10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a simulation whose cancellation never completes fails at runTest's timeout`() {
        assertThrows<AssertionError> {
            runTest(timeout = 500.milliseconds) {
                simulate {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { CompletableDeferred<Unit>().await() }
                    }
                }
            }
        }
    }

    @Test
    fun `a simulation's failure propagates out of simulate`() {
        val e = assertThrows<IllegalStateException> {
            runTest {
                simulate {
                    launch { error("boom") }
                }
            }
        }
        assertEquals("boom", e.message)
    }

    @Test
    fun `a simulation that cancels itself completes normally`() = runTest {
        simulate {
            launch { }.invokeOnCompletion { cancel() }
            awaitCancellation()
        }
    }
}
