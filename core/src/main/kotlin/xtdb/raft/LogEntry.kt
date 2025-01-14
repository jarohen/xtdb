package xtdb.raft

import java.util.*

class LogEntry(val term: Long, val command: Command, internal val onCommit: (LogIdx) -> Unit = {}) {
    override fun equals(other: Any?) =
        this === other || (other is LogEntry && term == other.term && command == other.command)

    override fun hashCode() = Objects.hash(term, command)

    @OptIn(ExperimentalStdlibApi::class)
    override fun toString() = "<LogEntry term=$term command=${command.toByteArray().toHexString()}>"
}