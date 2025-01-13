package xtdb.raft

import xtdb.raft.proto.Raft

internal class LeaderState(
    private val leaderTerm: Term,
    private val log: MutableList<LogEntry>,
    val nextIndices: LongArray,
    val matchIndices: LongArray,
    private val nodeIdx: Int,
) {
    data class AppendEntriesReq(val nextLogIdx: LogIdx, val entries: List<LogEntry>)

    fun nextReq(): AppendEntriesReq {
        val nextLogIdx = nextIndices[nodeIdx]

        return AppendEntriesReq(
            nextLogIdx, log.subList(nextLogIdx.toInt(), log.size.coerceAtMost((nextLogIdx + 10).toInt())).toList()
        )
    }

    sealed interface RespAction
    data object CedeControl : RespAction

    data class Commit(
        val oldCommitIdx: LogIdx,
        val newCommitIdx: LogIdx,
        val committedLogEntries: List<Pair<LogEntry, LogIdx>>
    ) : RespAction

    fun handleResp(req: AppendEntriesReq, resp: Raft.AppendEntriesResult, oldCommitIdx: LogIdx): RespAction {
        if (resp.term > leaderTerm) return CedeControl

        val nextLogIdx = req.nextLogIdx

        if (resp.success) {
            val entries = req.entries
            nextIndices[nodeIdx] = nextLogIdx + entries.size
            matchIndices[nodeIdx] = nextLogIdx + entries.size - 1
        } else {
            nextIndices[nodeIdx] = (nextLogIdx - 10).coerceAtLeast(-1)
        }

        val newCommitIdx = matchIndices.sorted().let { it[it.size / 2] }

        return Commit(
            oldCommitIdx, newCommitIdx,
            log.subList(oldCommitIdx.toInt() + 1, newCommitIdx.toInt() + 1)
                .mapIndexed { idx, it ->
                    Pair(it, oldCommitIdx + idx + 1)
                }
        )
    }
}