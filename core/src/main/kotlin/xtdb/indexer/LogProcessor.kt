package xtdb.indexer

class LogProcessor(private val factory: SystemFactory) : AutoCloseable {

    interface System : AutoCloseable

    interface SystemFactory {
        fun openFollower(): System
        fun openLeader(): System
    }

    private val system: System = factory.openFollower()

    override fun close() = system.close()
}
