package xtdb.raft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xtdb.raft.RaftStore.InMemory

class RaftStoreTest {

    @Test
    fun `handles voting`() {
        val winner = randomNodeId
        val loser = randomNodeId

        assertEquals(
            requestVoteResult(1, false),
            InMemory(1).requestVote(term = 0, candidateId = loser, lastLogIdx = -1, lastLogTerm = -1),
            "wrong term"
        )

        assertEquals(
            requestVoteResult(2, true),

            InMemory(1).requestVote(term = 2, candidateId = winner, lastLogIdx = -1, lastLogTerm = -1),
            "not voted yet, so grants"
        )

        assertEquals(
            requestVoteResult(2, true),
            InMemory(2, winner)
                .requestVote(term = 2, candidateId = winner, lastLogIdx = -1, lastLogTerm = -1),
            "same candidate requests again"
        )

        assertEquals(
            requestVoteResult(2, false),
            InMemory(2, winner)
                .requestVote(term = 2, candidateId = loser, lastLogIdx = -1, lastLogTerm = -1),
            "we've already voted"
        )

        fun entry(term: Term, text: String) = LogEntry(term, Command.copyFromUtf8(text))

        assertEquals(
            requestVoteResult(2, false),
            InMemory(2, log = listOf(entry(0, "entry 0")))
                .requestVote(term = 2, candidateId = loser, lastLogIdx = -1, lastLogTerm = -1),
            "candidate's log is empty"
        )

        assertEquals(
            requestVoteResult(2, true),
            InMemory(2).requestVote(term = 2, candidateId = winner, lastLogIdx = 0, lastLogTerm = 1),
            "our log is empty"
        )

        assertEquals(
            requestVoteResult(2, false),
            InMemory(term = 2, votedFor = null, log = listOf(entry(1, "entry 0")))
                .requestVote(term = 2, candidateId = loser, lastLogIdx = 0, lastLogTerm = 0),
            "our log has a later term"
        )

        assertEquals(
            requestVoteResult(2, true),
            InMemory(2, log = listOf(entry(1, "entry 0")))
                .requestVote(term = 2, candidateId = winner, lastLogIdx = 0, lastLogTerm = 2),
            "candidate's log has a later term"
        )

        assertEquals(
            requestVoteResult(2, false),
            InMemory(2, log = listOf(entry(1, "entry 0"), entry(1, "entry 1")))
                .requestVote(term = 2, candidateId = loser, lastLogIdx = 0, lastLogTerm = 1),
            "our log is longer"
        )

        assertEquals(
            requestVoteResult(2, true),
            InMemory(2, log = listOf(entry(1, "entry 0")))
                .requestVote(term = 2, candidateId = winner, lastLogIdx = 1, lastLogTerm = 1),
            "their log is longer"
        )

        assertEquals(
            requestVoteResult(2, true),
            InMemory(2, log = listOf(entry(1, "entry 0")))
                .requestVote(term = 2, candidateId = winner, lastLogIdx = 0, lastLogTerm = 1),
            "candidate's log is up to date"
        )
    }
}