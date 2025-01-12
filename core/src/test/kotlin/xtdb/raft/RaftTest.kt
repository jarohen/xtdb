package xtdb.raft

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xtdb.raft.RaftStore.InMemory
import java.nio.ByteBuffer
import java.util.UUID.randomUUID
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
            val winner = randomUUID()
            val loser = randomUUID()

            assertEquals(
                requestVoteResult(1, false),
                raft.requestVote(0, loser, -1, -1),
                "wrong term"
            )

            assertEquals(
                requestVoteResult(2, true),
                raft.requestVote(2, winner, -1, -1),
                "not voted yet, so grants"
            )

            assertEquals(
                requestVoteResult(2, true),
                raft.requestVote(2, winner, -1, -1),
                "same candidate requests again"
            )

            assertEquals(
                requestVoteResult(2, false),
                raft.requestVote(2, loser, -1, -1),
                "we've already voted"
            )
        }
    }

    @Test
    fun `handles appendEntries`() = runTest {
        val ticker = mockk<Ticker>(relaxUnitFun = true) {
            coEvery { electionTimeout() } just awaits
        }

        val leaderId = randomUUID()

        Raft(ticker = ticker, store = InMemory(currentTerm = 1)).use { raft ->
            assertEquals(
                appendEntriesResult(1, false),
                raft.appendEntries(0, leaderId, -1, -1, emptyList(), -1),
                "term < currentTerm"
            )

            assertEquals(
                appendEntriesResult(1, false),
                raft.appendEntries(1, leaderId, 0, 0, emptyList(), 0),
                "doesn't have log entry 0 yet"
            )

            val entry0 = LogEntry(1, ByteBuffer.allocate(0))

            val res0 = raft.appendEntries(1, leaderId, -1, -1, listOf(entry0), -1)
            assertEquals(appendEntriesResult(1, true), res0, "has applied log entry 0")
            assertEquals(listOf(entry0), raft.log)
            assertEquals(-1, raft.commitIdx)

            val entry1 = LogEntry(1, ByteBuffer.allocate(0))
            val res1 = raft.appendEntries(1, leaderId, 0, 1, listOf(entry1), 0)
            assertEquals(appendEntriesResult(1, true), res1, "has applied log entry 1")
            assertEquals(listOf(entry0, entry1), raft.log)
            assertEquals(0, raft.commitIdx)

            val heartbeatRes = raft.appendEntries(1, leaderId, 1, 1, emptyList(), 1)
            assertEquals(appendEntriesResult(1, true), heartbeatRes, "heartbeat")
            assertEquals(listOf(entry0, entry1), raft.log)
            assertEquals(1, raft.commitIdx)

            val entry2 = LogEntry(1, ByteBuffer.allocate(0))
            val res2 = raft.appendEntries(1, leaderId, 1, 1, listOf(entry2), 1)
            assertEquals(appendEntriesResult(1, true), res2, "has applied log entry 2")
            assertEquals(listOf(entry0, entry1, entry2), raft.log)
            assertEquals(1, raft.commitIdx)

            val leaderId2 = randomUUID()

            val resTruncate = raft.appendEntries(2, leaderId2, 2, 2, emptyList(), 1)
            assertEquals(appendEntriesResult(2, false), resTruncate, "truncates log when prevLogTerm doesn't match")
            assertEquals(listOf(entry0, entry1), raft.log)
            assertEquals(leaderId2, raft.leaderId)
            assertEquals(1, raft.commitIdx)

            val entry2Take2 = LogEntry(2, ByteBuffer.allocate(0))
            val resReplace = raft.appendEntries(2, leaderId2, 1, 1, listOf(entry2Take2), 1)
            assertEquals(appendEntriesResult(2, true), resReplace, "replaces log entry 1")
            assertEquals(listOf(entry0, entry1, entry2Take2), raft.log)
        }
    }
}

