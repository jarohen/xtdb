package xtdb.raft

import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RaftTest {

    @Test
    fun `single node awaits election timeout, then elects itself`() {
        val electionLatch = CountDownLatch(1)
        val callElectionLatch = CountDownLatch(1)
        val leaderElectedLatch = CountDownLatch(1)

        val ticker = mockk<ITicker>(relaxUnitFun = true) {
            every { awaitStartHeartbeat() } just awaits
            every { awaitLeaderElected() } just awaits

            every { awaitElectionTimeout() } answers { electionLatch.await(); false } andThenJust awaits
            every { callElection() } answers { callElectionLatch.countDown() } andThenThrows UnsupportedOperationException()
            every { awaitCallElection() } answers { callElectionLatch.await() } andThenJust awaits
            every { signalLeaderElected() } answers { leaderElectedLatch.countDown() }
        }

        Raft(ticker = ticker).use { raft ->
            raft.start(mapOf(raft.nodeId to raft))
            electionLatch.countDown()
            assertTrue(leaderElectedLatch.await(100, TimeUnit.MILLISECONDS))

            assertEquals(raft.nodeId, raft.leaderId)
        }
    }

    @Test
    fun `handles voting`() {
        val ticker = mockk<ITicker>(relaxUnitFun = true) {
            every { awaitStartHeartbeat() } just awaits
            every { awaitElectionTimeout() } just awaits
            every { awaitCallElection() } just awaits
        }

        Raft(ticker = ticker, currentTerm = 1).use { raft ->
            raft.start(mapOf(raft.nodeId to raft))
            val winner = UUID.randomUUID()
            val loser = UUID.randomUUID()

            assertEquals(
                RequestVoteResult(1, false),
                raft.requestVote(0, loser, 0, 0),
                "wrong term"
            )

            assertEquals(
                RequestVoteResult(2, true),
                raft.requestVote(2, winner, 0, 0),
                "not voted yet, so grants"
            )

            assertEquals(
                RequestVoteResult(2, true),
                raft.requestVote(2, winner, 0, 0),
                "same candidate requests again"
            )

            assertEquals(
                RequestVoteResult(2, false),
                raft.requestVote(2, loser, 0, 0),
                "we've already voted"
            )
        }
    }
}

