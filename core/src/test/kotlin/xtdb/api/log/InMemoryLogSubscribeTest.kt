package xtdb.api.log

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xtdb.api.log.Log.*
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

class InMemoryLogSubscribeTest {

    private fun txMessage(id: Byte) = SourceMessage.Tx(byteArrayOf(-1, id))

    @Test
    fun `assignment callback fires immediately`() = runTest(timeout = 10.seconds) {
        val assignedPartitions = AtomicReference<Collection<Int>>(null)

        val subscriber = object : RecordProcessor<SourceMessage> {
            override fun processRecords(records: List<Record<SourceMessage>>) = Unit
        }

        val listener = object : SubscriptionListener {
            override fun onPartitionsAssigned(partitions: Collection<Int>) {
                assignedPartitions.set(partitions)
            }
            override fun onPartitionsRevoked(partitions: Collection<Int>) = Unit
        }

        InMemoryLog.Factory().openSourceLog(emptyMap()).use { log ->
            log.openGroupConsumer(listener).use { consumer ->
                consumer.tailAll(-1, subscriber).use {
                    // Assignment should be immediate (synchronous)
                    assertEquals(listOf(0), assignedPartitions.get()?.toList())
                }
            }
        }
    }

    @Test
    fun `subscribe receives new messages`() = runTest(timeout = 10.seconds) {
        val receivedRecords = Collections.synchronizedList(mutableListOf<Record<SourceMessage>>())

        val subscriber = object : RecordProcessor<SourceMessage> {
            override fun processRecords(records: List<Record<SourceMessage>>) {
                receivedRecords.addAll(records)
            }
        }

        val listener = object : SubscriptionListener {
            override fun onPartitionsAssigned(partitions: Collection<Int>) = Unit
            override fun onPartitionsRevoked(partitions: Collection<Int>) = Unit
        }

        InMemoryLog.Factory().openSourceLog(emptyMap()).use { log ->
            log.openGroupConsumer(listener).use { consumer ->
                consumer.tailAll(-1, subscriber).use {
                    log.appendMessage(txMessage(0)).get()
                    log.appendMessage(txMessage(1)).get()
                    log.appendMessage(txMessage(2)).get()

                    while (synchronized(receivedRecords) { receivedRecords.size } < 3) delay(50)
                }
            }
        }

        synchronized(receivedRecords) {
            assertEquals(3, receivedRecords.size)
            assertEquals(0L, receivedRecords.first().logOffset)
        }
    }

    @Test
    fun `revocation callback fires on close`() {
        val revokedPartitions = AtomicReference<Collection<Int>>(null)

        val subscriber = object : RecordProcessor<SourceMessage> {
            override fun processRecords(records: List<Record<SourceMessage>>) = Unit
        }

        val listener = object : SubscriptionListener {
            override fun onPartitionsAssigned(partitions: Collection<Int>) = Unit
            override fun onPartitionsRevoked(partitions: Collection<Int>) {
                revokedPartitions.set(partitions)
            }
        }

        InMemoryLog.Factory().openSourceLog(emptyMap()).use { log ->
            log.openGroupConsumer(listener).use { consumer ->
                consumer.tailAll(-1, subscriber).close()
            }
        }

        assertEquals(listOf(0), revokedPartitions.get()?.toList())
    }
}
