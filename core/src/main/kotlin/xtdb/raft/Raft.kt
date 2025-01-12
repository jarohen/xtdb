package xtdb.raft

import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xtdb.raft.proto.Raft.AppendEntriesResult
import xtdb.raft.proto.Raft.RequestVoteResult
import xtdb.raft.proto.RaftServiceGrpcKt.RaftServiceCoroutineImplBase
import xtdb.raft.proto.appendEntriesResult
import xtdb.raft.proto.requestVoteResult
import java.io.DataOutputStream
import java.lang.System.Logger.Level.*
import java.lang.System.LoggerFinder.getLoggerFinder
import java.nio.ByteBuffer
import java.util.*
import java.util.UUID.randomUUID
import java.util.concurrent.CancellationException

private val LOGGER = getLoggerFinder().getLogger("xtdb.raft", Raft::class.java.module)

internal typealias Command = ByteString

data class LogEntry(val term: Long, val command: Command)

internal typealias NodeId = ByteString

internal val UUID.asNodeId
    get() = NodeId.newOutput(16).use { out ->
        DataOutputStream(out).also { it.writeLong(mostSignificantBits); it.writeLong(leastSignificantBits) }
        out.toByteString()
    }

internal val randomNodeId get() = randomUUID().asNodeId

internal val NodeId.asUuid get() = asReadOnlyByteBuffer().let { UUID(it.long, it.long) }
internal val NodeId.prefix get() = asUuid.toString().substring(0, 8)

internal typealias Term = Long
internal typealias LogIdx = Long

interface RaftNode : AutoCloseable {
    val nodeId: NodeId

    suspend fun appendEntries(
        term: Term,
        leaderId: NodeId,
        prevLogIdx: LogIdx,
        prevLogTerm: Term,
        entries: List<LogEntry>,
        leaderCommit: LogIdx
    ): AppendEntriesResult

    suspend fun requestVote(term: Term, candidateId: NodeId, lastLogIdx: Long, lastLogTerm: Long): RequestVoteResult
}

internal fun requestVoteResult(term: Long, voteGranted: Boolean) =
    requestVoteResult {
        this.term = term
        this.voteGranted = voteGranted
    }

internal fun appendEntriesResult(term: Long, success: Boolean) =
    appendEntriesResult {
        this.term = term
        this.success = success
    }

interface Ticker {
    suspend fun electionTimeout()
    fun leaderElected() = Unit

    companion object {
        operator fun invoke() = object : Ticker {
            private val rand = Random()

            override suspend fun electionTimeout() = delay(rand.nextLong(150, 300))
        }
    }
}

class Raft(
    private val store: RaftStore = RaftStore.InMemory(),
    private val ticker: Ticker = Ticker()
) : RaftServiceCoroutineImplBase(), RaftNode, AutoCloseable {

    override val nodeId: NodeId = randomNodeId
    private lateinit var otherNodes: Map<NodeId, RaftNode>
    private var quorum: Int = -1

    private val currentTerm get() = store.currentTerm
    private val votedFor get() = store.votedFor
    internal val log: MutableList<LogEntry> = mutableListOf()

    internal var commitIdx = -1L
    internal var lastApplied = -1L

    internal var leaderId: NodeId? = null

    private val mutex = Mutex()
    private val nodeScope = CoroutineScope(Dispatchers.Default)
    private var currentRole: Job? = null
    private var timeoutJob: Job? = null

    override fun toString() = "<RaftNode ${nodeId.prefix}>"

    private fun resetElectionTimeout() {
        timeoutJob = nodeScope.launch {
            ticker.electionTimeout()

            mutex.withLock {
                currentRole = nodeScope.launch { runElection() }
            }
        }
    }

    override suspend fun appendEntries(
        term: Term,
        leaderId: NodeId,
        prevLogIdx: LogIdx,
        prevLogTerm: Term,
        entries: List<LogEntry>,
        leaderCommit: LogIdx
    ): AppendEntriesResult {
        mutex.withLock {
            if (term < currentTerm) return appendEntriesResult(currentTerm, false)

            if (term >= currentTerm) {
                currentRole?.let { it.cancel("new term"); it.join() }

                if (term > currentTerm) store.setTerm(term, null)
            }

            timeoutJob?.cancel("received append entries")
            resetElectionTimeout()

            if (this.leaderId != leaderId) {
                this.leaderId = leaderId
                ticker.leaderElected()
                LOGGER.log(DEBUG, "${nodeId.prefix} acknowledged new leader: ${leaderId.prefix}")
            }

            if (prevLogIdx in log.indices && log[prevLogIdx.toInt()].term != prevLogTerm)
                while (log.size > prevLogIdx) log.removeLast()

            if (prevLogIdx >= log.size)
                return appendEntriesResult(currentTerm, false)

            // TODO durable log
            log.addAll(entries.subList((log.size - prevLogIdx - 1).toInt(), entries.size))
            commitIdx = leaderCommit.coerceAtMost(log.size.toLong() - 1)
            return appendEntriesResult(currentTerm, true)
        }
    }

    override suspend fun requestVote(
        term: Long,
        candidateId: NodeId,
        lastLogIdx: Long,
        lastLogTerm: Long
    ): RequestVoteResult {
        LOGGER.log(TRACE, "${nodeId.prefix} received vote request from ${candidateId.prefix}")

        mutex.withLock {
            if (term < currentTerm) return requestVoteResult(currentTerm, false)

            if (term > currentTerm) {
                store.setTerm(term, null)
                currentRole?.cancel("new term")
            }

            if (votedFor == null || votedFor == candidateId) {
                if (log.isEmpty() || (lastLogIdx > log.size && lastLogTerm == log.last().term)) {
                    timeoutJob?.cancel("granted vote")
                    store.setTerm(term, candidateId)
                    resetElectionTimeout()
                    return requestVoteResult(term, true)
                }
            }
            return requestVoteResult(currentTerm, false)
        }
    }

    fun submitCommand(command: ByteBuffer) {
        TODO()
    }

    private suspend fun startLeader() {
        val leaderTerm = currentTerm
        supervisorScope {
            val sup = this

            otherNodes.forEach { (_, node) ->
                launch {
                    var nextIdx = log.size
                    var matchIdx = -1L

                    while (true) {
                        val heartbeatJob = launch { delay(20) }

                        val res = node.appendEntries(
                            leaderTerm, nodeId,
                            log.size.toLong(), log.lastOrNull()?.term ?: 0,
                            emptyList(), commitIdx
                        )

                        mutex.withLock {
                            if (res.term > leaderTerm) {
                                sup.cancel("new term")
                                currentRole = null
                                resetElectionTimeout()
                                yield()
                            }

                            // TODO update commitIdx
                        }

                        heartbeatJob.join()
                    }
                }
            }
        }
    }

    private suspend fun runElection() {
        mutex.withLock {
            store.setTerm(currentTerm + 1, nodeId)

            resetElectionTimeout()
        }

        val term = currentTerm
        LOGGER.log(INFO, "${nodeId.prefix} calling election, term: $term")

        val results = Channel<RequestVoteResult>(otherNodes.size)

        supervisorScope {
            otherNodes.forEach { (otherId, node) ->
                launch {
                    val result = node.requestVote(term, nodeId, log.size.toLong(), log.lastOrNull()?.term ?: 0)

                    LOGGER.log(
                        TRACE,
                        "${nodeId.prefix} received vote response from ${otherId.prefix}: ${result.voteGranted}"
                    )

                    results.send(result)
                }
            }
        }

        var votes = 1

        while (true) {
            if (votes >= quorum) {
                leaderId = nodeId
                LOGGER.log(INFO, "${nodeId.prefix} elected leader, term: $term")
                ticker.leaderElected()

                mutex.withLock {
                    timeoutJob?.cancel("elected leader")
                    timeoutJob = null
                    currentRole = nodeScope.launch { startLeader() }
                }

                return
            }

            val res = results.receiveCatching()

            when {
                res.isClosed -> break

                res.isSuccess -> {
                    val resp = res.getOrThrow()

                    when {
                        resp.term > term -> throw CancellationException("new term")
                        resp.voteGranted -> votes++
                    }
                }
            }
        }
    }

    private suspend fun startFollower() {
        mutex.withLock {
            resetElectionTimeout()
        }
    }

    internal fun start(nodes: Map<NodeId, RaftNode>) {
        this.otherNodes = nodes - setOf(nodeId)
        this.quorum = (nodes.size / 2) + 1
        runBlocking { startFollower() }
    }

    override fun close() {
        nodeScope.cancel("closing")
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