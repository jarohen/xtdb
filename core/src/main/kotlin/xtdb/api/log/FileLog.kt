package xtdb.api.log

import kotlinx.coroutines.*
import kotlinx.coroutines.future.future
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

interface FileLog : AutoCloseable {
    companion object {
        @JvmStatic
        fun openLocal() = Local()
    }

    fun appendFileNotification(addedPaths: FilesAddedNotification): CompletableFuture<Unit>
    fun subscribeToFileNotifications(processor: Processor): AutoCloseable

    @FunctionalInterface
    fun interface Processor {
        fun processNotification(addedPaths: FilesAddedNotification)
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    class Local : FileLog {
        @Volatile
        private var processor: Processor? = null

        private val scope: CoroutineScope = CoroutineScope(newSingleThreadContext("log"))

        override fun appendFileNotification(addedPaths: Collection<Path>): CompletableFuture<Unit> =
            scope.future {
                processor?.processNotification(addedPaths)
            }

        @Synchronized
        override fun subscribeToFileNotifications(processor: Processor): AutoCloseable {
            check(this.processor == null) { "Only one file notification processor can be subscribed" }

            this.processor = processor

            return AutoCloseable {
                synchronized(this@Local) {
                    this.processor = null
                }
            }
        }

        override fun close() {
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        }
    }
}