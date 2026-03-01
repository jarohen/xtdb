package xtdb.api.log

import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.kafka.ConfluentKafkaContainer
import xtdb.api.log.Log.*
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
class KafkaSubscribeTest {
    companion object {
        private val container = ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0")

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            container.start()
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            container.stop()
        }
    }

    private fun txMessage(id: Byte) = SourceMessage.Tx(byteArrayOf(-1, id))

    @Test
    fun `assignment callback fires on subscribe`() = runTest(timeout = 30.seconds) {
        val topicName = "test-topic-${UUID.randomUUID()}"
        val groupId = "test-group-${UUID.randomUUID()}"

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

        KafkaCluster.ClusterFactory(container.bootstrapServers)
            .pollDuration(Duration.ofMillis(100))
            .open().use { cluster ->
                KafkaCluster.LogFactory("my-cluster", topicName)
                    .groupId(groupId)
                    .openSourceLog(mapOf("my-cluster" to cluster))
                    .use { log ->
                        log.openGroupConsumer(listener).use { consumer ->
                            consumer.tailAll(-1, subscriber).use {
                                while (assignedPartitions.get() == null) delay(50)
                            }
                        }
                    }
            }

        assertEquals(listOf(0), assignedPartitions.get()?.toList())
    }

    @Test
    fun `subscribe receives messages`() = runTest(timeout = 30.seconds) {
        val topicName = "test-topic-${UUID.randomUUID()}"
        val groupId = "test-group-${UUID.randomUUID()}"

        val receivedRecords = Collections.synchronizedList(mutableListOf<Record<SourceMessage>>())
        val assigned = AtomicBoolean(false)

        val subscriber = object : RecordProcessor<SourceMessage> {
            override fun processRecords(records: List<Record<SourceMessage>>) {
                receivedRecords.addAll(records)
            }
        }

        val listener = object : SubscriptionListener {
            override fun onPartitionsAssigned(partitions: Collection<Int>) { assigned.set(true) }
            override fun onPartitionsRevoked(partitions: Collection<Int>) = Unit
        }

        KafkaCluster.ClusterFactory(container.bootstrapServers)
            .pollDuration(Duration.ofMillis(100))
            .open().use { cluster ->
                KafkaCluster.LogFactory("my-cluster", topicName)
                    .groupId(groupId)
                    .openSourceLog(mapOf("my-cluster" to cluster))
                    .use { log ->
                        log.openGroupConsumer(listener).use { consumer ->
                            consumer.tailAll(-1, subscriber).use {
                                while (!assigned.get()) delay(50)

                                log.appendMessage(txMessage(0)).await()
                                log.appendMessage(txMessage(1)).await()
                                log.appendMessage(txMessage(2)).await()

                                while (synchronized(receivedRecords) { receivedRecords.size } < 3) delay(50)
                            }
                        }
                    }
            }

        synchronized(receivedRecords) {
            assertEquals(3, receivedRecords.size)
        }
    }

    @Test
    fun `revocation callback fires on close`() = runTest(timeout = 30.seconds) {
        val topicName = "test-topic-${UUID.randomUUID()}"
        val groupId = "test-group-${UUID.randomUUID()}"

        val revokedPartitions = AtomicReference<Collection<Int>>(null)
        val assigned = AtomicBoolean(false)

        val subscriber = object : RecordProcessor<SourceMessage> {
            override fun processRecords(records: List<Record<SourceMessage>>) = Unit
        }

        val listener = object : SubscriptionListener {
            override fun onPartitionsAssigned(partitions: Collection<Int>) {
                assigned.set(true)
            }
            override fun onPartitionsRevoked(partitions: Collection<Int>) {
                revokedPartitions.set(partitions)
            }
        }

        KafkaCluster.ClusterFactory(container.bootstrapServers)
            .pollDuration(Duration.ofMillis(100))
            .open().use { cluster ->
                KafkaCluster.LogFactory("my-cluster", topicName)
                    .groupId(groupId)
                    .openSourceLog(mapOf("my-cluster" to cluster))
                    .use { log ->
                        log.openGroupConsumer(listener).use { consumer ->
                            consumer.tailAll(-1, subscriber).use {
                                while (!assigned.get()) delay(50)
                            }
                        }
                    }
            }

        assertEquals(listOf(0), revokedPartitions.get()?.toList())
    }

    @Test
    fun `subscribe without groupId throws`() {
        val topicName = "test-topic-${UUID.randomUUID()}"

        val subscriber = object : RecordProcessor<SourceMessage> {
            override fun processRecords(records: List<Record<SourceMessage>>) = Unit
        }

        val listener = object : SubscriptionListener {
            override fun onPartitionsAssigned(partitions: Collection<Int>) = Unit
            override fun onPartitionsRevoked(partitions: Collection<Int>) = Unit
        }

        KafkaCluster.ClusterFactory(container.bootstrapServers)
            .pollDuration(Duration.ofMillis(100))
            .open().use { cluster ->
                KafkaCluster.LogFactory("my-cluster", topicName)
                    // no groupId set
                    .openSourceLog(mapOf("my-cluster" to cluster))
                    .use { log ->
                        val ex = assertThrows(IllegalArgumentException::class.java) {
                            log.openGroupConsumer(listener)
                        }
                        assertTrue(ex.message?.contains("groupId") == true)
                    }
            }
    }
}
