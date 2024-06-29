package xtdb

import java.lang.System.Logger.Level.*
import java.lang.System.LoggerFinder.getLoggerFinder
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val LOGGER = getLoggerFinder().getLogger("xtdb.raft", Raft::class.java.module)

data class LogEntry(val term: Long, val command: ByteBuffer)

data class AppendEntriesResult(val term: Long, val success: Boolean)
data class RequestVoteResult(val term: Long, val voteGranted: Boolean)

private val UUID.prefix get() = toString().substring(0, 8)
private typealias Term = Long

interface RaftNode {
    fun appendEntries(
        term: Term,
        leaderId: UUID,
        prevLogIdx: Int,
        prevLogTerm: Term,
        entries: List<LogEntry>,
        leaderCommit: Long
    ): AppendEntriesResult

    fun requestVote(term: Term, candidateId: UUID, lastLogIdx: Long, lastLogTerm: Long): RequestVoteResult
}

class Raft : RaftNode, AutoCloseable {

    val nodeId: UUID = UUID.randomUUID()
    private lateinit var otherNodes: Map<UUID, RaftNode>
    private var quorum: Int = -1

    private var currentTerm: Long = 0
    private var votedFor: UUID? = null
    private val log: MutableList<LogEntry> = mutableListOf()

    private var commitIdx = 0L
    private var lastApplied = 0L

    private var leaderId: UUID? = null

    private val random = Random()
    private val lock = ReentrantLock()

    private lateinit var followerThread: Thread
    private val resetElectionTimeout = lock.newCondition()
    private val restartFollower = lock.newCondition()

    private lateinit var candidateThread: Thread
    private val callElection = lock.newCondition()
    private val leaderElected = lock.newCondition()

    private lateinit var leaderThread: Thread
    private val resetHeartbeatTimeout = lock.newCondition()
    private val startHeartbeat = lock.newCondition()

    private var votesReceived: Int = 0

    private fun checkTerm(term: Term): Boolean {
        val sameTerm = term == currentTerm
        if (!sameTerm) {
            currentTerm = term
            votedFor = null
            resetHeartbeatTimeout.signalAll()
            restartFollower.signalAll()
        }
        return sameTerm
    }

    override fun appendEntries(
        term: Long,
        leaderId: UUID,
        prevLogIdx: Int,
        prevLogTerm: Long,
        entries: List<LogEntry>,
        leaderCommit: Long
    ): AppendEntriesResult {
        lock.withLock {
            resetElectionTimeout.signalAll()

            checkTerm(term)

            if (term < currentTerm) {
                return AppendEntriesResult(currentTerm, false)
            }

            if (this.leaderId != leaderId) {
                this.leaderId = leaderId
                LOGGER.log(DEBUG, "${nodeId.prefix} acknowledged new leader: ${leaderId.prefix}")
                leaderElected.signalAll()
            }
        }

        return AppendEntriesResult(currentTerm, true)
    }

    private fun appendEntriesResponse(result: AppendEntriesResult) {
        lock.withLock {
            checkTerm(result.term) || return

            if (result.success) {
                // TODO update commitIdx
            } else TODO()
        }
    }

    override fun requestVote(term: Long, candidateId: UUID, lastLogIdx: Long, lastLogTerm: Long): RequestVoteResult {
        LOGGER.log(TRACE, "${nodeId.prefix} received vote request from ${candidateId.prefix}")

        lock.withLock {
            if (term < currentTerm) {
                return RequestVoteResult(currentTerm, false)
            }

            checkTerm(term)

            if (votedFor == null || votedFor == candidateId) {
                if (log.isEmpty() || (lastLogIdx >= log.size && lastLogTerm >= log.last().term)) {
                    votedFor = candidateId
                    resetElectionTimeout.signalAll()
                    return RequestVoteResult(currentTerm, true)
                }
            }

            return RequestVoteResult(currentTerm, false)
        }
    }

    fun submitCommand(command: ByteBuffer) {
        TODO()
    }

    private fun voteResponseReceived(electionTerm: Long, otherId: UUID, result: RequestVoteResult) {
        LOGGER.log(TRACE, "${nodeId.prefix} received vote response from ${otherId.prefix}: ${result.voteGranted}")
        lock.withLock {
            checkTerm(result.term) || return

            if (result.voteGranted && electionTerm == currentTerm) {
                votesReceived++
                LOGGER.log(TRACE, "${nodeId.prefix} received vote from ${otherId.prefix}")
                if (votesReceived == quorum) {
                    startHeartbeat.signalAll()
                    leaderElected.signalAll()
                    leaderId = nodeId
                    LOGGER.log(INFO, "${nodeId.prefix} won the election, term: $currentTerm")
                }
            }
        }
    }

    private fun runElection() {
        lock.withLock {
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
        }
    }

    internal fun start(nodes: Map<UUID, RaftNode>) {
        lock.withLock {
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
                lock.withLock {
                    while (resetElectionTimeout.await(random.nextLong(150, 300), MILLISECONDS)) {
                        // restart timeout
                    }

                    callElection.signalAll()
                    restartFollower.await()
                }
        } catch (_: InterruptedException) {
        }

    private fun candidateLoop() =
        try {
            lock.withLock {
                while (true) {
                    callElection.await()

                    do {
                        runElection()
                    } while (!leaderElected.await(random.nextLong(150, 300), MILLISECONDS))
                }
            }

        } catch (_: InterruptedException) {
        }

    private fun sendHeartbeat() {
        otherNodes.forEach { (otherId, node) ->
            Thread.startVirtualThread {
                val res = node.appendEntries(currentTerm, nodeId, log.size - 1, log.lastOrNull()?.term ?: 0, emptyList(), commitIdx)
                appendEntriesResponse(res)
            }
        }
    }

    private fun leaderLoop() =
        try {
            while (true)
                lock.withLock {
                    startHeartbeat.await()
                    val term = this.currentTerm

                    sendHeartbeat()

                    while(this.currentTerm == term) {
                        while (resetHeartbeatTimeout.await(50, MILLISECONDS)) {
                            // restart timeout
                        }

                        sendHeartbeat()
                    }
                }
        } catch (_: InterruptedException) {
        }

    override fun close() {
        followerThread.interrupt()
        candidateThread.interrupt()
        leaderThread.interrupt()

        followerThread.join(100)
        candidateThread.join(100)
        leaderThread.join(100)
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