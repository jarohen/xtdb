package xtdb.raft

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.produce
import xtdb.raft.Raft.RespAction.CedeControl
import xtdb.raft.Raft.RespAction.Commit
import xtdb.raft.RaftStore.*
import xtdb.raft.proto.Raft.AppendEntriesResult
import xtdb.raft.proto.Raft.RequestVoteResult
import xtdb.raft.proto.RaftServiceGrpcKt.RaftServiceCoroutineImplBase
import java.lang.System.Logger.Level.*
import java.lang.System.LoggerFinder.getLoggerFinder

private val LOGGER = getLoggerFinder().getLogger("xtdb.raft", Raft::class.java.module)

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class Raft(
    val store: RaftStore = InMemory(),
    private val ticker: Ticker = Ticker()
) : RaftServiceCoroutineImplBase(), RaftNode, AutoCloseable {

    override val nodeId: NodeId = randomNodeId
    private lateinit var otherNodes: Map<NodeId, RaftNode>
    private var quorum: Int = -1

    private val currentTerm get() = store.term

    internal var commitIdx = -1L

    internal var leaderId: NodeId? = null
        set(leaderId) {
            field = leaderId
            LOGGER.log(DEBUG, "${nodeId.prefix} acknowledged new leader: ${leaderId?.prefix}")
            ticker.leaderChange(leaderId)
        }

    private val nodeScope = CoroutineScope(Dispatchers.Default)
    private val stateThread = newSingleThreadContext("raft-state")
    private var currentRole: Job? = null
    private var timeoutJob: Job? = null

    override fun toString() = "<RaftNode ${nodeId.prefix}>"

    data class VoteReq(val term: Long, val lastLogIdx: Long, val lastLogTerm: Long)

    private suspend fun runElection(req: VoteReq): Boolean = coroutineScope {
        LOGGER.log(INFO, "${nodeId.prefix} calling election, term: ${req.term}")

        val results = produce(capacity = otherNodes.size) {
            supervisorScope {
                otherNodes.forEach { (otherId, node) ->
                    launch {
                        val resp = node.requestVote(req.term, nodeId, req.lastLogIdx, req.lastLogTerm)

                        LOGGER.log(TRACE, "${nodeId.prefix} vote-resp from ${otherId.prefix}: ${resp.voteGranted}")

                        send(resp)
                    }
                }
            }
        }

        var votes = 1
        var elected = false

        while (true) {
            if (votes >= quorum) {
                LOGGER.log(INFO, "${nodeId.prefix} elected leader, term: ${req.term}")

                elected = true
                break
            }

            val res = results.receiveCatching()

            when {
                res.isClosed -> break

                res.isSuccess -> {
                    val resp = res.getOrThrow()

                    when {
                        resp.term > req.term -> break
                        resp.voteGranted -> votes++
                    }
                }
            }
        }

        results.cancel()
        elected
    }

    private suspend fun startCandidate() = withContext(stateThread) {
        store.setTerm(currentTerm + 1, nodeId)
        resetElectionTimeout()

        val req = VoteReq(currentTerm, store.lastLogIdx, store[store.lastLogIdx]?.term ?: -1)

        currentRole = nodeScope.launch {
            runElection(req).also { elected -> if (elected) withContext(stateThread) { startLeader(req.term) } }
        }
    }

    suspend fun startLeader(leaderTerm: Term) {
        timeoutJob?.cancelAndJoin()
        timeoutJob = null

        leaderId = nodeId

        currentRole = nodeScope.launch {
            RaftLeader(leaderTerm).runLeader()
            withContext(stateThread) { resetElectionTimeout() }
        }
    }

    private fun resetElectionTimeout() {
        timeoutJob?.cancel()

        timeoutJob = nodeScope.launch {
            ticker.electionTimeout()
            startCandidate()
        }
    }

    override suspend fun appendEntries(
        term: Term,
        leaderId: NodeId,
        prevLogIdx: LogIdx,
        prevLogTerm: Term,
        entries: List<LogEntry>,
        leaderCommit: LogIdx
    ): AppendEntriesResult = withContext(stateThread) {
        val currentTerm = this@Raft.currentTerm

        if (term > currentTerm) {
            store.setTerm(term, null)
            currentRole?.cancel()
        }

        if (term >= currentTerm) {
            resetElectionTimeout()

            if (this@Raft.leaderId != leaderId) {
                this@Raft.leaderId = leaderId
            }
        }

        val res = store.appendEntries(term, prevLogIdx, prevLogTerm, entries, leaderCommit)

        if (res is AppendEntriesAction.Success) commitIdx = res.newCommitIdx

        return@withContext appendEntriesResult(res.term, res.success)
    }

    override suspend fun requestVote(
        term: Long,
        candidateId: NodeId,
        lastLogIdx: Long,
        lastLogTerm: Long
    ): RequestVoteResult = withContext(stateThread) {
        LOGGER.log(TRACE, "${nodeId.prefix} received vote request from ${candidateId.prefix}")

        val currentTerm = this@Raft.currentTerm

        store.requestVote(term, candidateId, lastLogIdx, lastLogTerm)
            .also {
                if (it.term > currentTerm) {
                    currentRole?.cancel()
                    currentRole = null
                }

                if (it.voteGranted) resetElectionTimeout()
            }
    }

    override suspend fun submitCommand(command: Command): LogIdx = withContext(stateThread) {
        when (leaderId) {
            null -> TODO("no leader")

            nodeId -> {
                val res = CompletableDeferred<LogIdx>(this.coroutineContext.job)
                store += LogEntry(currentTerm, command, res::complete)
                res.await()
            }

            else -> {
                val leader = otherNodes[leaderId] ?: error("leader not found")
                leader.submitCommand(command)
            }
        }
    }

    data class AppendEntriesReq(val nodeIdx: Int, val nextLogIdx: LogIdx, val entries: List<LogEntry>)

    sealed interface RespAction {
        data object CedeControl : RespAction

        data class Commit(val committedLogEntries: List<Pair<Long, LogEntry>>) : RespAction
    }

    internal inner class RaftLeader(private val leaderTerm: Term) {
        private val nextIndices = LongArray(otherNodes.size) { store.lastLogIdx + 1 }
        private val matchIndices = LongArray(otherNodes.size)

        suspend fun nextReq(nodeIdx: Int): AppendEntriesReq = withContext(stateThread) {
            val nextLogIdx = nextIndices[nodeIdx]

            AppendEntriesReq(
                nodeIdx, nextLogIdx,
                store.subList(nextLogIdx, store.lastLogIdx.coerceAtMost((nextLogIdx + 10)) + 1).toList()
            )
        }

        suspend fun handleResp(req: AppendEntriesReq, resp: AppendEntriesResult): RespAction =
            withContext(stateThread) {
                if (resp.term > leaderTerm) {
                    currentRole = null
                    leaderId = null
                    return@withContext CedeControl
                }

                val nextLogIdx = req.nextLogIdx
                val nodeIdx = req.nodeIdx

                if (resp.success) {
                    val entries = req.entries
                    nextIndices[nodeIdx] = nextLogIdx + entries.size
                    matchIndices[nodeIdx] = nextLogIdx + entries.size - 1
                } else {
                    nextIndices[nodeIdx] = (nextLogIdx - 10).coerceAtLeast(-1)
                }

                val oldCommitIdx = commitIdx
                commitIdx = matchIndices.sorted().let { it[it.size / 2] }
                ticker.indexCommitted(commitIdx)

                Commit(
                    store.subList(oldCommitIdx + 1, commitIdx + 1)
                        .mapIndexed { idx, it -> Pair(oldCommitIdx + idx + 1, it) }
                )
            }

        suspend fun runLeader() =
            supervisorScope {
                otherNodes.entries.forEachIndexed { nodeIdx, (_, node) ->
                    launch {
                        while (true) {
                            val heartbeatJob = launch { ticker.leaderHeartbeat(node.nodeId) }

                            try {
                                val req = nextReq(nodeIdx)

                                val prevLogIdx = req.nextLogIdx - 1
                                val res = node.appendEntries(
                                    leaderTerm, nodeId, prevLogIdx,
                                    store[prevLogIdx]?.term ?: -1,
                                    req.entries, commitIdx
                                )

                                when (val action = handleResp(req, res)) {
                                    CedeControl -> {
                                        this@supervisorScope.cancel()
                                        yield()
                                        error("should not reach here")
                                    }

                                    is Commit ->
                                        action.committedLogEntries
                                            .forEach { (logIdx, entry) -> entry.onCommit(logIdx) }
                                }

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

    fun setNodes(nodes: Map<NodeId, RaftNode>) = apply {
        this.otherNodes = nodes - setOf(nodeId)
        this.quorum = (nodes.size / 2) + 1
    }

    fun start() = apply {
        runBlocking { withContext(stateThread) { resetElectionTimeout() } }
    }

    override fun close() {
        nodeScope.cancel("closing")
        stateThread.close()
        LOGGER.log(INFO, "${nodeId.prefix} stopped")
    }
}