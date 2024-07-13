package xtdb.raft

import xtdb.raft.ITicker.Ticker
import java.lang.System.Logger.Level.*
import java.lang.System.LoggerFinder.getLoggerFinder
import java.nio.ByteBuffer
import java.util.*

private val LOGGER = getLoggerFinder().getLogger("xtdb.raft", Raft::class.java.module)

data class LogEntry(val term: Long, val command: ByteBuffer)

data class AppendEntriesResult(val term: Long, val success: Boolean)
data class RequestVoteResult(val term: Long, val voteGranted: Boolean)

private typealias NodeId = UUID
private val NodeId.prefix get() = toString().substring(0, 8)

private typealias Term = Long

interface RaftNode {
    fun appendEntries(
        term: Term,
        leaderId: NodeId,
        prevLogIdx: Int,
        prevLogTerm: Term,
        entries: List<LogEntry>,
        leaderCommit: Long
    ): AppendEntriesResult

    fun requestVote(term: Term, candidateId: NodeId, lastLogIdx: Long, lastLogTerm: Long): RequestVoteResult
}

class Raft(private val ticker: ITicker = Ticker(), private var currentTerm: Long = 0) : RaftNode, AutoCloseable {

    val nodeId: NodeId = UUID.randomUUID()
    private lateinit var otherNodes: Map<NodeId, RaftNode>
    private var quorum: Int = -1

    private var votedFor: NodeId? = null
    private val log: MutableList<LogEntry> = mutableListOf()

    private var commitIdx = 0L
    private var lastApplied = 0L

    internal var leaderId: NodeId? = null

    private lateinit var followerThread: Thread

    private lateinit var candidateThread: Thread

    private lateinit var leaderThread: Thread

    private var votesReceived: Int = 0

    private fun checkTerm(term: Term): Boolean {
        val sameTerm = term == currentTerm
        if (!sameTerm) {
            currentTerm = term
            votedFor = null
            ticker.resetHeartbeatTimeout()
            ticker.restartFollower()
        }
        return sameTerm
    }

    override fun appendEntries(
        term: Long,
        leaderId: NodeId,
        prevLogIdx: Int,
        prevLogTerm: Long,
        entries: List<LogEntry>,
        leaderCommit: Long
    ): AppendEntriesResult {
        ticker.withLock {
            ticker.resetElectionTimeout()

            if (term < currentTerm) return AppendEntriesResult(currentTerm, false)

            checkTerm(term)

            if (this.leaderId != leaderId) {
                this.leaderId = leaderId
                LOGGER.log(DEBUG, "${nodeId.prefix} acknowledged new leader: ${leaderId.prefix}")
                ticker.signalLeaderElected()
            }
        }

        return AppendEntriesResult(currentTerm, true)
    }

    private fun appendEntriesResponse(result: AppendEntriesResult) {
        ticker.withLock {
            checkTerm(result.term) || return

            if (result.success) {
                // TODO update commitIdx
            } else TODO()
        }
    }

    override fun requestVote(term: Long, candidateId: NodeId, lastLogIdx: Long, lastLogTerm: Long): RequestVoteResult {
        LOGGER.log(TRACE, "${nodeId.prefix} received vote request from ${candidateId.prefix}")

        ticker.withLock {
            if (term < currentTerm) return RequestVoteResult(currentTerm, false)

            checkTerm(term)

            if (votedFor == null || votedFor == candidateId) {
                if (log.isEmpty() || (lastLogIdx >= log.size && lastLogTerm >= log.last().term)) {
                    votedFor = candidateId
                    ticker.resetElectionTimeout()
                    return RequestVoteResult(currentTerm, true)
                }
            }

            return RequestVoteResult(currentTerm, false)
        }
    }

    fun submitCommand(command: ByteBuffer) {
        TODO()
    }

    private fun checkQuorum() {
        if (votesReceived == quorum) {
            leaderId = nodeId
            ticker.startHeartbeat()
            ticker.signalLeaderElected()
            LOGGER.log(INFO, "${nodeId.prefix} won the election, term: $currentTerm")
        }
    }

    private fun voteResponseReceived(electionTerm: Long, otherId: NodeId, result: RequestVoteResult) {
        LOGGER.log(TRACE, "${nodeId.prefix} received vote response from ${otherId.prefix}: ${result.voteGranted}")
        ticker.withLock {
            checkTerm(result.term) || return

            if (result.voteGranted && electionTerm == currentTerm) {
                votesReceived++
                LOGGER.log(TRACE, "${nodeId.prefix} received vote from ${otherId.prefix}")

                checkQuorum()
            }
        }
    }

    private fun runElection() {
        ticker.withLock {
            currentTerm++
            votedFor = nodeId
            votesReceived = 1

            LOGGER.log(INFO, "${nodeId.prefix} calling election, term: $currentTerm")

            otherNodes.forEach { (otherId, node) ->
                Thread.startVirtualThread {
                    val result = node.requestVote(currentTerm, nodeId, log.size.toLong(), log.lastOrNull()?.term ?: 0)
                    voteResponseReceived(currentTerm, otherId, result)
                }
            }

            checkQuorum()
        }
    }

    internal fun start(nodes: Map<NodeId, RaftNode>) {
        ticker.withLock {
            this.otherNodes = nodes.minus(nodeId)
            this.quorum = (nodes.size / 2) + 1
            followerThread = Thread.ofVirtual().name("raft-follower-${nodeId.prefix}").start(::followerLoop)
            candidateThread = Thread.ofVirtual().name("raft-candidate-${nodeId.prefix}").start(::candidateLoop)
            leaderThread = Thread.ofVirtual().name("raft-leader-${nodeId.prefix}").start(::leaderLoop)
        }
    }

    private fun followerLoop() =
        try {
            while (true)
                ticker.withLock {
                    while (ticker.awaitElectionTimeout()) {
                        // restart timeout
                    }

                    ticker.callElection()
                    ticker.awaitRestartFollower()
                }
        } catch (_: InterruptedException) {
            LOGGER.log(DEBUG, "${nodeId.prefix} follower thread interrupted")
        }

    private fun candidateLoop() =
        try {
            ticker.withLock {
                while (true) {
                    ticker.awaitCallElection()

                    do {
                        runElection()
                    } while (!ticker.awaitLeaderElected())
                }
            }

        } catch (_: InterruptedException) {
            LOGGER.log(DEBUG, "${nodeId.prefix} candidate thread interrupted")
        }

    private fun sendHeartbeat() =
        otherNodes.forEach { (_, node) ->
            Thread.startVirtualThread {
                val res = node.appendEntries(
                    currentTerm, nodeId,
                    log.size - 1, log.lastOrNull()?.term ?: 0,
                    emptyList(), commitIdx
                )

                appendEntriesResponse(res)
            }
        }

    private fun leaderLoop() =
        try {
            while (true)
                ticker.withLock {
                    ticker.awaitStartHeartbeat()
                    val term = this.currentTerm

                    sendHeartbeat()

                    while (this.currentTerm == term) {
                        while (ticker.awaitHeartbeatTimeout()) {
                            // restart timeout
                        }

                        sendHeartbeat()
                    }
                }
        } catch (_: InterruptedException) {
            LOGGER.log(DEBUG, "${nodeId.prefix} leader thread interrupted")
        }

    override fun close() {
        followerThread.interrupt()
        candidateThread.interrupt()
        leaderThread.interrupt()

        followerThread.join(100)
        candidateThread.join(100)
        leaderThread.join(100)
        LOGGER.log(INFO, "${nodeId.prefix} stopped")
    }
}

fun main() {
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
                        Thread.sleep(1000)
                    }
                }
            }
        }
    }
}