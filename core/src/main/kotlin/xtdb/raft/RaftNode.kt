package xtdb.raft

import com.google.protobuf.ByteString
import xtdb.raft.proto.Raft
import java.io.DataOutputStream
import java.util.*
import java.util.UUID.randomUUID

internal typealias Command = ByteString

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

internal fun requestVoteResult(term: Long, voteGranted: Boolean) =
    xtdb.raft.proto.requestVoteResult {
        this.term = term
        this.voteGranted = voteGranted
    }

internal fun appendEntriesResult(term: Long, success: Boolean) =
    xtdb.raft.proto.appendEntriesResult {
        this.term = term
        this.success = success
    }

interface RaftNode : AutoCloseable {
    val nodeId: NodeId

    suspend fun appendEntries(
        term: Term,
        leaderId: NodeId,
        prevLogIdx: LogIdx,
        prevLogTerm: Term,
        entries: List<LogEntry>,
        leaderCommit: LogIdx
    ): Raft.AppendEntriesResult

    suspend fun requestVote(term: Term, candidateId: NodeId, lastLogIdx: Long, lastLogTerm: Long): Raft.RequestVoteResult

    suspend fun submitCommand(command: Command): LogIdx
}