package xtdb.raft

import com.google.protobuf.ByteString
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xtdb.raft.RaftStore.InMemory
import kotlin.time.Duration.Companion.seconds

class RaftTest {

    @Test
    fun `e2e of 5 nodes`() = runTest(timeout = 2.seconds) {
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

                            raft1.setNodes(nodes).start()
                            raft2.setNodes(nodes).start()
                            raft3.setNodes(nodes).start()
                            raft4.setNodes(nodes).start()
                            raft5.setNodes(nodes).start()

                            while (raft1.leaderId == null) delay(100)

                            assertEquals(
                                0,
                                nodes[raft1.leaderId!!]!!.submitCommand(ByteString.copyFromUtf8("hello")))

                            while (raft1.commitIdx < 0) delay(100)

                            assertEquals(raft1.store[0], LogEntry(1, ByteString.copyFromUtf8("hello")))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `single node awaits election timeout, then elects itself`() = runTest(timeout = 2.seconds) {
        val electionLatch = CompletableDeferred<Unit>()
        val leaderElectedLatch = CompletableDeferred<NodeId>()

        val ticker = mockk<Ticker>(relaxUnitFun = true) {
            coEvery { electionTimeout() } coAnswers { electionLatch.await() } andThenJust awaits
            every { leaderChange(any()) } answers { leaderElectedLatch.complete(firstArg()) }
        }

        Raft(ticker = ticker).use { raft ->
            raft.setNodes(emptyMap()).start()
            electionLatch.complete(Unit)
            assertEquals(raft.nodeId, leaderElectedLatch.await())
            assertEquals(raft.nodeId, raft.leaderId)
        }
    }

    @Test
    fun `handles appendEntries`() = runTest {
        val ticker = mockk<Ticker>(relaxUnitFun = true) {
            coEvery { electionTimeout() } just awaits
        }

        val leaderId = randomNodeId
        val store = InMemory(term = 1)

        Raft(ticker = ticker, store = store).use { raft ->
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
            assertEquals(listOf(entry0), store.log)
            assertEquals(-1, raft.commitIdx)

            val entry1 = LogEntry(1, Command.copyFromUtf8("entry 1"))
            val res1 = raft.appendEntries(1, leaderId, 0, 1, listOf(entry1), 0)
            assertEquals(appendEntriesResult(1, true), res1, "has applied log entry 1")
            assertEquals(listOf(entry0, entry1), store.log)
            assertEquals(0, raft.commitIdx)

            val heartbeatRes = raft.appendEntries(1, leaderId, 1, 1, emptyList(), 1)
            assertEquals(appendEntriesResult(1, true), heartbeatRes, "heartbeat")
            assertEquals(listOf(entry0, entry1), store.log)
            assertEquals(1, raft.commitIdx)

            val entry2 = LogEntry(1, Command.copyFromUtf8("entry 2"))
            val res2 = raft.appendEntries(1, leaderId, 1, 1, listOf(entry2), 1)
            assertEquals(appendEntriesResult(1, true), res2, "has applied log entry 2")
            assertEquals(listOf(entry0, entry1, entry2), store.log)
            assertEquals(1, raft.commitIdx)

            val leaderId2 = randomNodeId

            val resTruncate = raft.appendEntries(2, leaderId2, 2, 2, emptyList(), 1)
            assertEquals(appendEntriesResult(2, false), resTruncate, "truncates log when prevLogTerm doesn't match")
            assertEquals(listOf(entry0, entry1), store.log)
            assertEquals(leaderId2, raft.leaderId)
            assertEquals(1, raft.commitIdx)

            val entry2Take2 = LogEntry(2, Command.copyFromUtf8("entry 2 take 2"))
            val resReplace = raft.appendEntries(2, leaderId2, 1, 1, listOf(entry2Take2), 1)
            assertEquals(appendEntriesResult(2, true), resReplace, "replaces log entry 1")
            assertEquals(listOf(entry0, entry1, entry2Take2), store.log)
        }
    }

    @Test
    fun `test leader loop`() = runTest(timeout = 2.seconds) {
        val leaderElected = CompletableDeferred<Unit>()

        val ticker = mockk<Ticker>(relaxUnitFun = true) {
            coEvery { electionTimeout() } returns (Unit) andThenJust awaits
            coEvery { leaderChange(any()) } answers { leaderElected.complete(Unit) } andThenJust awaits
        }

        val cmds = mutableListOf<List<LogEntry>>()

        Raft(store = InMemory(term = 1), ticker = ticker).use { raft ->
            val nodeId = raft.nodeId
            val id1 = randomNodeId
            val node1 = mockk<RaftNode> {
                every { this@mockk.nodeId } returns id1
                coEvery { appendEntries(1, nodeId, any(), any(), emptyList(), any()) }
                    .returns(appendEntriesResult(1, true))
                coEvery { appendEntries(1, nodeId, any(), any(), capture(cmds), any()) }
                    .returns(appendEntriesResult(1, true))
            }

            raft.setNodes(mapOf(node1.nodeId to node1))

            raft.startLeader(1)

            val cmd = Command.copyFromUtf8("hello")
            assertEquals(0, raft.submitCommand(cmd))

            val cmd2 = Command.copyFromUtf8("world")
            assertEquals(1, raft.submitCommand(cmd2))

            assertEquals(listOf(LogEntry(1, cmd), LogEntry(1, cmd2)), cmds.flatten())
        }
    }

    @Test
    fun `leader cedes control`() = runTest(timeout = 2.seconds) {
        val leaderElected = Channel<NodeId?>()

        val ticker = mockk<Ticker>(relaxUnitFun = true) {
            coEvery { electionTimeout() } returns (Unit) andThenJust awaits
            coEvery { leaderChange(any()) } coAnswers { leaderElected.send(firstArg()) }
        }

        Raft(ticker = ticker).use { raft ->
            val nodeId = raft.nodeId
            val node1 = mockk<RaftNode> {
                val id1 = randomNodeId
                every { this@mockk.nodeId } returns id1
                coEvery { requestVote(1, nodeId, any(), any()) }
                    .returns(requestVoteResult(1, true))
                coEvery { appendEntries(1, nodeId, any(), any(), any(), any()) }
                    .returnsMany(
                        appendEntriesResult(1, true),
                        appendEntriesResult(2, false)
                    )
            }

            raft.setNodes(mapOf(node1.nodeId to node1)).start()
            assertEquals(nodeId, leaderElected.receive())
            assertEquals(null, leaderElected.receive())
            coVerify(exactly = 2) { node1.appendEntries(1, nodeId, any(), any(), emptyList(), any()) }
        }
    }
}

