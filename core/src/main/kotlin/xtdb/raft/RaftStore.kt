package xtdb.raft

import xtdb.raft.proto.Raft.RequestVoteResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.moveTo

internal sealed class RaftStore {
    abstract val term: Term
    abstract val votedFor: NodeId?

    abstract fun setTerm(term: Term, votedFor: NodeId?)

    abstract val lastLogIdx: LogIdx
    abstract operator fun get(idx: LogIdx): LogEntry?
    abstract fun subList(from: LogIdx, to: LogIdx): List<LogEntry>
    abstract operator fun plusAssign(entry: LogEntry)
    abstract operator fun plusAssign(entries: Iterable<LogEntry>)
    abstract fun truncateFrom(idx: LogIdx)

    fun requestVote(term: Long, candidateId: NodeId, lastLogIdx: Long, lastLogTerm: Long): RequestVoteResult =
        if (term < this.term)
            requestVoteResult(this.term, false)
        else {
            val ourLastLogIdx = this.lastLogIdx
            val ourLastLogTerm = this[ourLastLogIdx]?.term ?: -1

            val voteGranted = when {
                votedFor != null && votedFor != candidateId -> false
                lastLogTerm != ourLastLogTerm -> lastLogTerm > ourLastLogTerm
                else -> lastLogIdx >= ourLastLogIdx
            }

            requestVoteResult(term, voteGranted)
        }

    sealed interface AppendEntriesAction {
        val success: Boolean

        data class Success(val newCommitIdx: LogIdx) : AppendEntriesAction {
            override val success: Boolean = true
        }

        data object Failure : AppendEntriesAction {
            override val success: Boolean = false
        }
    }

    fun appendEntries(
        prevLogIdx: LogIdx, prevLogTerm: Term, entries: List<LogEntry>, leaderCommit: LogIdx
    ): AppendEntriesAction {
        if (prevLogIdx in 0..lastLogIdx && this[prevLogIdx]!!.term != prevLogTerm)
            truncateFrom(prevLogIdx)

        if (prevLogIdx > lastLogIdx) return AppendEntriesAction.Failure

        this += entries.subList((lastLogIdx - prevLogIdx).toInt(), entries.size)
        return AppendEntriesAction.Success(leaderCommit.coerceAtMost(lastLogIdx))
    }

    class InMemory(term: Term = 0, votedFor: NodeId? = null, log: Iterable<LogEntry> = emptyList()) : RaftStore() {

        override var term: Term = term; private set
        override var votedFor: NodeId? = votedFor; private set
        val log: MutableList<LogEntry> = log.toMutableList()

        override fun setTerm(term: Term, votedFor: NodeId?) {
            this.term = term
            this.votedFor = votedFor
        }

        override val lastLogIdx get() = log.size.toLong() - 1
        override operator fun get(idx: LogIdx): LogEntry? = log.getOrNull(idx.toInt())
        override fun subList(from: LogIdx, to: LogIdx): List<LogEntry> = log.subList(from.toInt(), to.toInt())

        override operator fun plusAssign(entry: LogEntry) {
            log += entry
        }

        override operator fun plusAssign(entries: Iterable<LogEntry>) {
            log += entries
        }

        override fun truncateFrom(idx: LogIdx) {
            log.subList(idx.toInt(), log.size).clear()
        }
    }

    class DiskStore(dir: Path) : RaftStore() {
        private val metaFile: Path = dir.resolve("meta")
        private val tempMetaFile = dir.resolve("meta.tmp")

        override var term: Term
        override var votedFor: NodeId?

        // TODO durable log
        private val log: MutableList<LogEntry> = mutableListOf()
        override val lastLogIdx get() = log.size.toLong() - 1
        override operator fun get(idx: LogIdx): LogEntry? = log.getOrNull(idx.toInt())
        override fun subList(from: LogIdx, to: LogIdx): List<LogEntry> = log.subList(from.toInt(), to.toInt())

        override operator fun plusAssign(entry: LogEntry) {
            log += entry
        }

        override operator fun plusAssign(entries: Iterable<LogEntry>) {
            log += entries
        }

        override fun truncateFrom(idx: LogIdx) {
            log.subList(idx.toInt(), log.size).clear()
        }

        private fun writeMetaFile(path: Path, currentTerm: Term, votedFor: NodeId?) {
            DataOutputStream(Files.newOutputStream(path, CREATE, WRITE, TRUNCATE_EXISTING)).use { out ->
                out.writeLong(currentTerm)
                if (votedFor != null) {
                    out.write(votedFor.toByteArray())
                } else {
                    out.writeLong(0)
                    out.writeLong(0)
                }
            }
        }

        init {
            dir.createDirectories()

            if (metaFile.exists()) {
                DataInputStream(Files.newInputStream(metaFile)).use {
                    term = it.readLong()
                    val msb = it.readLong()
                    val lsb = it.readLong()
                    votedFor = if (msb == 0L && lsb == 0L) null else UUID(msb, lsb).asNodeId
                }
            } else {
                term = 0
                votedFor = null
                writeMetaFile(metaFile, term, votedFor)
            }
        }

        override fun setTerm(term: Term, votedFor: NodeId?) {
            writeMetaFile(tempMetaFile, term, votedFor)
            tempMetaFile.moveTo(metaFile, REPLACE_EXISTING, ATOMIC_MOVE)
            this.term = term
            this.votedFor = votedFor
        }
    }
}