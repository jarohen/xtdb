package xtdb.raft

import java.util.*
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

interface ITicker {
    fun lock()
    fun unlock()

    fun resetElectionTimeout()
    fun awaitElectionTimeout(): Boolean

    fun restartFollower()
    fun awaitRestartFollower()

    fun callElection()
    fun awaitCallElection()

    fun signalLeaderElected()
    fun awaitLeaderElected(): Boolean

    fun resetHeartbeatTimeout()
    fun awaitHeartbeatTimeout(): Boolean

    fun startHeartbeat()
    fun awaitStartHeartbeat()

    class Ticker : ITicker {
        private val lock: Lock = ReentrantLock()
        private val rand = Random()

        override fun lock() = lock.lockInterruptibly()
        override fun unlock() = lock.unlock()

        private val resetElectionTimeout = lock.newCondition()
        override fun resetElectionTimeout() = resetElectionTimeout.signalAll()
        override fun awaitElectionTimeout() = resetElectionTimeout.await(rand.nextLong(150, 300), MILLISECONDS)

        private val restartFollower = lock.newCondition()
        override fun restartFollower() = restartFollower.signalAll()
        override fun awaitRestartFollower() = restartFollower.await()

        private val callElection = lock.newCondition()
        override fun callElection() = callElection.signalAll()
        override fun awaitCallElection() = callElection.await()

        private val leaderElected = lock.newCondition()
        override fun signalLeaderElected() = leaderElected.signalAll()
        override fun awaitLeaderElected() = leaderElected.await(rand.nextLong(150, 300), MILLISECONDS)

        private val resetHeartbeatTimeout = lock.newCondition()
        override fun resetHeartbeatTimeout() = resetHeartbeatTimeout.signalAll()
        override fun awaitHeartbeatTimeout() = resetHeartbeatTimeout.await(50, MILLISECONDS)

        private val startHeartbeat = lock.newCondition()
        override fun startHeartbeat() = startHeartbeat.signalAll()
        override fun awaitStartHeartbeat() = startHeartbeat.await()
    }
}

internal inline fun <T> ITicker.withLock(action: () -> T): T {
    lock()
    try {
        return action()
    } finally {
        unlock()
    }
}
