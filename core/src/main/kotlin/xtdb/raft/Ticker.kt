package xtdb.raft

import kotlinx.coroutines.delay
import java.util.*

interface Ticker {
    suspend fun electionTimeout() = delay(150)
    suspend fun leaderHeartbeat(nodeId: NodeId) = delay(20)

    fun leaderChange(leaderId: NodeId?) = Unit
    fun indexCommitted(commitIdx: Long) = Unit

    companion object {
        operator fun invoke() = object : Ticker {
            private val rand = Random()

            override suspend fun electionTimeout() = delay(rand.nextLong(150, 300))
        }
    }
}