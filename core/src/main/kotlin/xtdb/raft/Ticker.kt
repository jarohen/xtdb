package xtdb.raft

import kotlinx.coroutines.delay
import java.util.*

interface Ticker {
    suspend fun electionTimeout()
    suspend fun leaderHeartbeat() = Unit

    fun leaderElected() = Unit

    companion object {
        operator fun invoke() = object : Ticker {
            private val rand = Random()

            override suspend fun electionTimeout() = delay(rand.nextLong(150, 300))
            override suspend fun leaderHeartbeat() = delay(20)
        }
    }
}