package xtdb.raft

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xtdb.raft.RaftStore.AppendEntriesAction
import xtdb.raft.RaftStore.InMemory
import xtdb.raft.proto.Raft.AppendEntriesResult
import xtdb.raft.proto.Raft.RequestVoteResult
import xtdb.raft.proto.RaftServiceGrpcKt.RaftServiceCoroutineImplBase
import java.lang.System.Logger.Level.*
import java.lang.System.LoggerFinder.getLoggerFinder

private val LOGGER = getLoggerFinder().getLogger("xtdb.raft", Raft::class.java.module)

internal class Raft(
    private val store: RaftStore = InMemory(),
    private val ticker: Ticker = Ticker()
) : RaftServiceCoroutineImplBase(), RaftNode, AutoCloseable {

    override val nodeId: NodeId = randomNodeId
    private lateinit var otherNodes: Map<NodeId, RaftNode>
    private var quorum: Int = -1

    private val term get() = store.term

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
            val currentTerm = this.term

            if (term < currentTerm) return appendEntriesResult(currentTerm, false)

            if (term > currentTerm) {
                store.setTerm(term, null)
                currentRole?.run { cancel("new term"); join() }
            }

            timeoutJob?.cancel("received append entries")
            resetElectionTimeout()

            if (this.leaderId != leaderId) {
                this.leaderId = leaderId
                ticker.leaderElected()
                LOGGER.log(DEBUG, "${nodeId.prefix} acknowledged new leader: ${leaderId.prefix}")
            }

            return when (val action = store.appendEntries(prevLogIdx, prevLogTerm, entries, leaderCommit)) {
                is AppendEntriesAction.Success -> {
                    commitIdx = action.newCommitIdx
                    appendEntriesResult(term, true)
                }

                AppendEntriesAction.Failure -> appendEntriesResult(term, false)
            }
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
            if (term > this.term) {
                store.setTerm(term, null)
                currentRole?.cancel("new term")
            }

            val voteRes = store.requestVote(term, candidateId, lastLogIdx, lastLogTerm)

            if (voteRes.voteGranted) {
                timeoutJob?.cancel("granted vote")
                store.setTerm(term, candidateId)
                resetElectionTimeout()
            }

            return voteRes
        }
    }

    suspend fun submitCommand(command: Command): LogIdx =
        mutex.withLock {
            if (leaderId != nodeId) TODO("not leader")

            // TODO durable log
            CompletableDeferred<LogIdx>().also { fut -> store += LogEntry(term, command, fut::complete) }
        }.await()

    private fun startLeader() {
        val leaderTerm = term
        currentRole = nodeScope.launch {
            val sup = this

            val nextIndex = LongArray(otherNodes.size) { store.lastLogIdx + 1 }
            val matchIndex = LongArray(otherNodes.size) { -1L }

            supervisorScope {
                otherNodes.entries.forEachIndexed { nodeIdx, (_, node) ->
                    launch {
                        val leaderState = LeaderState(store, leaderTerm, nextIndex, matchIndex, nodeIdx)

                        while (true) {
                            val heartbeatJob = launch { ticker.leaderHeartbeat() }

                            try {
                                val req = mutex.withLock { leaderState.nextReq() }

                                val prevLogIdx = req.nextLogIdx - 1
                                val res = node.appendEntries(
                                    leaderTerm, nodeId, prevLogIdx,
                                    store[prevLogIdx]?.term ?: -1,
                                    req.entries, commitIdx
                                )

                                val action = mutex.withLock {
                                    when (val action = leaderState.handleResp(req, res, commitIdx)) {
                                        LeaderState.CedeControl -> {
                                            sup.cancel("new term")
                                            currentRole = null
                                            resetElectionTimeout()
                                            throw CancellationException("new term")
                                        }

                                        is LeaderState.Commit -> {
                                            commitIdx = action.newCommitIdx
                                            action
                                        }
                                    }
                                }

                                action.committedLogEntries.forEach { (entry, logIdx) -> entry.onCommit(logIdx) }

                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                LOGGER.log(WARNING, "${nodeId.prefix} failed to replicate to ${node.nodeId.prefix}", e)
                            }

                            heartbeatJob.join()
                        }
                    }
                }
            }
        }
    }

    private suspend fun runElection() {
        data class VoteReq(val term: Long, val lastLogIdx: Long, val lastLogTerm: Long)

        val req = mutex.withLock {
            store.setTerm(term + 1, nodeId)

            resetElectionTimeout()
            VoteReq(term, store.lastLogIdx, store[store.lastLogIdx]?.term ?: -1)
        }

        LOGGER.log(INFO, "${nodeId.prefix} calling election, term: ${req.term}")

        val results = Channel<RequestVoteResult>(otherNodes.size)

        supervisorScope {
            otherNodes.forEach { (otherId, node) ->
                launch {
                    val result = node.requestVote(req.term, nodeId, req.lastLogIdx, req.lastLogTerm)

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
                LOGGER.log(INFO, "${nodeId.prefix} elected leader, term: ${req.term}")

                mutex.withLock {
                    leaderId = nodeId
                    ticker.leaderElected()

                    timeoutJob?.run { cancel("elected leader"); join() }
                    timeoutJob = null
                    startLeader()
                }

                return
            }

            val res = results.receiveCatching()

            when {
                res.isClosed -> break

                res.isSuccess -> {
                    val resp = res.getOrThrow()

                    when {
                        resp.term > req.term -> throw CancellationException("new term")
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