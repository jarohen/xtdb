package xtdb.raft

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xtdb.raft.RaftStore.InMemory
import xtdb.raft.proto.requestVoteResult
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RaftTest {

    @Test
    fun `single node awaits election timeout, then elects itself`() = runTest {
        val electionLatch = CountDownLatch(1)
        val leaderElectedLatch = CountDownLatch(1)

        val ticker = mockk<Ticker>(relaxUnitFun = true) {
            coEvery { electionTimeout() } answers { electionLatch.await() } andThenJust awaits
            every { leaderElected() } answers { leaderElectedLatch.countDown() }
        }

        Raft(ticker = ticker).use { raft ->
            raft.start(mapOf(raft.nodeId to raft))
            electionLatch.countDown()
            assertTrue(leaderElectedLatch.await(100, TimeUnit.MILLISECONDS))

            assertEquals(raft.nodeId, raft.leaderId)
        }
    }

    @Test
    fun `handles voting`() = runTest {
        val ticker = mockk<Ticker>(relaxUnitFun = true) {
            coEvery { electionTimeout() } just awaits
        }

        Raft(ticker = ticker, store = InMemory(currentTerm = 1)).use { raft ->
            raft.start(mapOf(raft.nodeId to raft))
            val winner = UUID.randomUUID()
            val loser = UUID.randomUUID()

            assertEquals(
                requestVoteResult(1, false),
                raft.requestVote(0, loser, 0, 0),
                "wrong term"
            )

            assertEquals(
                requestVoteResult(2, true),
                raft.requestVote(2, winner, 0, 0),
                "not voted yet, so grants"
            )

            assertEquals(
                requestVoteResult(2, true),
                raft.requestVote(2, winner, 0, 0),
                "same candidate requests again"
            )

            assertEquals(
                requestVoteResult(2, false),
                raft.requestVote(2, loser, 0, 0),
                "we've already voted"
            )
        }
    }
}

