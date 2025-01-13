package xtdb.raft

import com.google.protobuf.ByteString
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import xtdb.raft.RaftStore.InMemory
import java.nio.ByteBuffer
import java.util.UUID.randomUUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RaftTest {

    @Test
    @Disabled("just for manual testing")
    fun main() = runTest {
        Raft().use { raft1 ->
            Raft().use { raft2 ->
                Raft().use { raft3 ->
                    Raft().use { raft4 ->
                        Raft().use { raft5 ->
                            val nodes = mapOf(
                                raft1.nodeId to raft1,
                                raft2.nodeId to raft2,
                                raft3.nodeId to raft3,
                                raft4.nodeId to raft4,
                                raft5.nodeId to raft5
                            )

                            raft1.start(nodes)
                            raft2.start(nodes)
                            raft3.start(nodes)
                            raft4.start(nodes)
                            raft5.start(nodes)

                            while (raft1.leaderId == null) {
                                delay(100)
                            }

                            nodes[raft1.leaderId!!]!!.submitCommand(ByteString.copyFromUtf8("hello"))
                                .also { println("log idx: $it") }

                            Thread.sleep(500)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `single node awaits election timeout, then elects itself`() = runTest {
        val electionLatch = CompletableDeferred<Unit>()
        val leaderElectedLatch = CompletableDeferred<Unit>()

        val ticker = mockk<Ticker>(relaxUnitFun = true) {
            coEvery { electionTimeout() } coAnswers { electionLatch.await() } andThenJust awaits
            every { leaderElected() } answers { leaderElectedLatch.complete(Unit) }
        }

        Raft(ticker = ticker).use { raft ->
            raft.start(mapOf(raft.nodeId to raft))
            electionLatch.complete(Unit)
            leaderElectedLatch.await()

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
            val winner = randomUUID().asNodeId
            val loser = randomUUID().asNodeId

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

        val leaderId = randomNodeId

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

            val entry0 = LogEntry(1, Command.copyFromUtf8("entry 0"))

            val res0 = raft.appendEntries(1, leaderId, -1, -1, listOf(entry0), -1)
            assertEquals(appendEntriesResult(1, true), res0, "has applied log entry 0")
            assertEquals(listOf(entry0), raft.log)
            assertEquals(-1, raft.commitIdx)

            val entry1 = LogEntry(1, Command.copyFromUtf8("entry 1"))
            val res1 = raft.appendEntries(1, leaderId, 0, 1, listOf(entry1), 0)
            assertEquals(appendEntriesResult(1, true), res1, "has applied log entry 1")
            assertEquals(listOf(entry0, entry1), raft.log)
            assertEquals(0, raft.commitIdx)

            val heartbeatRes = raft.appendEntries(1, leaderId, 1, 1, emptyList(), 1)
            assertEquals(appendEntriesResult(1, true), heartbeatRes, "heartbeat")
            assertEquals(listOf(entry0, entry1), raft.log)
            assertEquals(1, raft.commitIdx)

            val entry2 = LogEntry(1, Command.copyFromUtf8("entry 2"))
            val res2 = raft.appendEntries(1, leaderId, 1, 1, listOf(entry2), 1)
            assertEquals(appendEntriesResult(1, true), res2, "has applied log entry 2")
            assertEquals(listOf(entry0, entry1, entry2), raft.log)
            assertEquals(1, raft.commitIdx)

            val leaderId2 = randomNodeId

            val resTruncate = raft.appendEntries(2, leaderId2, 2, 2, emptyList(), 1)
            assertEquals(appendEntriesResult(2, false), resTruncate, "truncates log when prevLogTerm doesn't match")
            assertEquals(listOf(entry0, entry1), raft.log)
            assertEquals(leaderId2, raft.leaderId)
            assertEquals(1, raft.commitIdx)

            val entry2Take2 = LogEntry(2, Command.copyFromUtf8("entry 2 take 2"))
            val resReplace = raft.appendEntries(2, leaderId2, 1, 1, listOf(entry2Take2), 1)
            assertEquals(appendEntriesResult(2, true), resReplace, "replaces log entry 1")
            assertEquals(listOf(entry0, entry1, entry2Take2), raft.log)
        }
    }

    @Test
    fun `test leader loop`() = runTest {
        val leaderElected = CompletableDeferred<Unit>()

        val ticker = mockk<Ticker>(relaxUnitFun = true) {
            coEvery { electionTimeout() } returns (Unit) andThenJust awaits
            coEvery { leaderElected() } answers { leaderElected.complete(Unit) } andThenJust awaits
        }

        val cmds = mutableListOf<List<LogEntry>>()

        Raft(ticker = ticker).use { raft ->
            val nodeId = raft.nodeId
            val id1 = randomNodeId
            val node1 = mockk<RaftNode> {
                every { this@mockk.nodeId } returns id1
                coEvery { requestVote(1, nodeId, any(), any()) }
                    .returns(requestVoteResult(1, true))
                coEvery { appendEntries(1, nodeId, any(), any(), emptyList(), any()) }
                    .returns(appendEntriesResult(1, true))
                coEvery { appendEntries(1, nodeId, any(), any(), capture(cmds), any()) }
                    .returns(appendEntriesResult(1, true))
            }

            raft.start(mapOf(raft.nodeId to raft, id1 to node1))
            leaderElected.await()
            assertEquals(raft.nodeId, raft.leaderId)

            val cmd = Command.copyFromUtf8("hello")
            assertEquals(0, raft.submitCommand(cmd))

            val cmd2 = Command.copyFromUtf8("world")
            assertEquals(1, raft.submitCommand(cmd2))

            assertEquals(listOf(LogEntry(1, cmd), LogEntry(1, cmd2)), cmds.flatten())
        }
    }

    @Test
    fun `leader cedes control`() = runTest {
        val leaderElected = CompletableDeferred<Unit>()

        val ticker = mockk<Ticker>(relaxUnitFun = true) {
            coEvery { electionTimeout() } returns (Unit) andThenJust awaits
            coEvery { leaderElected() } answers { leaderElected.complete(Unit) } andThenJust awaits
        }

        Raft(ticker = ticker).use { raft ->
            val nodeId = raft.nodeId
            val id1 = randomNodeId
            val node1 = mockk<RaftNode> {
                every { this@mockk.nodeId } returns id1
                coEvery { requestVote(1, nodeId, any(), any()) }
                    .returns(requestVoteResult(1, true))
                coEvery { appendEntries(1, nodeId, any(), any(), any(), any()) }
                    .returnsMany(
                        appendEntriesResult(1, true),
                        appendEntriesResult(2, false)
                    )
            }

            raft.start(mapOf(raft.nodeId to raft, id1 to node1))
            leaderElected.await()
            delay(500)

            coVerify(exactly = 2) { node1.appendEntries(1, nodeId, any(), any(), emptyList(), any()) }
        }
    }
}

