package xtdb.indexer

import xtdb.api.log.Log.SubscriptionListener

class LogProcessor(private val factory: SystemFactory) : AutoCloseable, SubscriptionListener {

    interface System : AutoCloseable

    interface SystemFactory {
        fun openFollower(): System
        fun openLeader(): System
    }

    @Volatile private var system: System = factory.openFollower()

    override fun onPartitionsAssigned(partitions: Collection<Int>) {
        system.close()
        system = factory.openLeader()
    }

    override fun onPartitionsRevoked(partitions: Collection<Int>) {
        system.close()
        system = factory.openFollower()
    }

    override fun close() = system.close()
}
