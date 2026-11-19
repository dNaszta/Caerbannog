package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.topology.QueueInspectionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Gate test for milestone M7 (@gate-M7) against a real RabbitMQ 4 broker in Testcontainers.
 *
 * The risky part of M7 is queue message inspection, the one place the app shows messages of a queue (management API `get`
 * with `ack_requeue_true`). The gate proves, on a real quorum queue:
 *  1. Inspection is non-destructive and repeatable: the same messages, in order, every time; the queue keeps its count; a
 *     real consumer afterwards still receives every message.
 *  2. Properties and headers set by the publisher are visible, and inspected messages are never marked acknowledged.
 *  3. Repeated inspection (more times than the quorum queue delivery limit, 20 by default on RabbitMQ 4) loses nothing.
 * The stream-side M7 inputs are covered by `RealBrokerStreamExchangeAnnotationsTest` (exchange and routing key),
 * `RealBrokerMqttHeadersTest` (MQTT headers reach the stream) and the paging gates of M3; the log tape, sparklines, pipeline
 * graph and partition balance are pure `core` functions with unit tests.
 * Run with `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM7GateTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM7GateTest {

    companion object {
        private val rabbit: RabbitMQContainer = brokerWithPlugins("rabbitmq:4-management", "rabbitmq_stream", "rabbitmq_stream_management")
            .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
            .apply { addExposedPorts(5552) }

        @JvmStatic @BeforeAll fun start() = rabbit.start()

        @JvmStatic @AfterAll fun stop() = rabbit.stop()
    }

    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val json = Json
    private val auth get() = Base64.getEncoder().encodeToString("${rabbit.adminUsername}:${rabbit.adminPassword}".toByteArray())

    private fun mgmt(method: String, path: String, body: String? = null): HttpResponse<String> {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val request = HttpRequest.newBuilder(URI("http://${rabbit.host}:${rabbit.getMappedPort(15672)}$path"))
            .header("Authorization", "Basic $auth").header("Content-Type", "application/json")
            .method(method, publisher).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private val secrets = object : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = rabbit.adminPassword
        override fun delete(profileName: String) = Unit
    }

    private fun profile() = ConnectionProfile(
        name = "m7-gate", host = rabbit.host, virtualHost = "/", username = rabbit.adminUsername,
        streamPort = rabbit.getMappedPort(5552), managementPort = rabbit.getMappedPort(15672), tls = false,
        topologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    )

    private fun declareQuorumQueue(name: String) =
        assertEquals(201, mgmt("PUT", "/api/queues/%2F/$name", """{"durable":true,"arguments":{"x-queue-type":"quorum"}}""").statusCode())

    private fun publishTo(queue: String, count: Int) = repeat(count) { i ->
        val response = mgmt(
            "POST", "/api/exchanges/%2F/amq.default/publish",
            """{"properties":{"content_type":"text/plain","headers":{"trace":"t-$i","source":"m7-gate"}},"routing_key":"$queue","payload":"msg-$i","payload_encoding":"string"}""",
        )
        assertTrue(response.body().contains("\"routed\":true"), "not routed: ${response.body()}")
    }

    private fun readyMessages(queue: String): Long =
        json.parseToJsonElement(mgmt("GET", "/api/queues/%2F/$queue").body()).jsonObject["messages"]?.jsonPrimitive?.content?.toLong() ?: 0L

    private fun awaitReady(queue: String, expected: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (readyMessages(queue) != expected) {
            check(System.nanoTime() < deadline) { "queue $queue never reached $expected messages (has ${readyMessages(queue)})" }
            TimeUnit.MILLISECONDS.sleep(250)
        }
    }

    /** What a real consumer would see: take everything off the queue (this one acknowledges, so it ends the queue's life). */
    private fun drain(queue: String): List<String> {
        val body = mgmt("POST", "/api/queues/%2F/$queue/get", """{"count":50,"ackmode":"ack_requeue_false","encoding":"auto"}""").body()
        return (json.parseToJsonElement(body) as JsonArray).map { (it as JsonObject)["payload"]!!.jsonPrimitive.content }
    }

    private fun <T> withConnection(block: suspend (inspect: suspend (String, Int) -> QueueInspectionResult) -> T): T = runBlocking {
        val opened = RabbitMqConnectionGateway(secrets).open(profile())
        val connection = assertInstanceOf(OpenResult.Opened::class.java, opened, "got $opened").connection
        try {
            block { queue, count -> connection.queueInspector.inspectQueueMessages("/", queue, count) }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `gate - queue inspection is non-destructive, repeatable, shows properties and headers, never acknowledges`() {
        val queue = "m7-gate-${UUID.randomUUID().toString().take(8)}"
        declareQuorumQueue(queue)
        try {
            publishTo(queue, 5)
            awaitReady(queue, 5)

            withConnection { inspect ->
                repeat(3) { round ->
                    val result = inspect(queue, 10)
                    val messages = assertInstanceOf(QueueInspectionResult.Success::class.java, result, "round $round: $result").messages
                    assertEquals((0..4).map { "msg-$it" }, messages.map { it.payload.decodeToString() }, "round $round order and bodies")
                    assertTrue(messages.all { !it.acknowledged }, "round $round: every message must be shown as not acknowledged")
                    assertEquals((0..4).map { "t-$it" }, messages.map { it.headers["trace"] }, "round $round headers")
                    assertTrue(messages.all { it.headers["source"] == "m7-gate" })
                    assertTrue(messages.all { it.properties["content_type"] == "text/plain" }, "round $round properties: ${messages.first().properties}")
                }
            }

            awaitReady(queue, 5) // the queue still holds all five
            assertEquals((0..4).map { "msg-$it" }, drain(queue), "a real consumer still receives every message after inspections")
        } finally {
            mgmt("DELETE", "/api/queues/%2F/$queue")
        }
    }

    @Test
    fun `gate - inspecting more often than the delivery limit loses no message`() {
        val queue = "m7-gate-limit-${UUID.randomUUID().toString().take(8)}"
        declareQuorumQueue(queue)
        try {
            publishTo(queue, 3)
            awaitReady(queue, 3)

            withConnection { inspect ->
                repeat(30) { round ->
                    val result = inspect(queue, 10)
                    val messages = assertInstanceOf(QueueInspectionResult.Success::class.java, result, "round $round: $result").messages
                    assertEquals(3, messages.size, "round $round: a message vanished (delivery limit reached?)")
                }
            }

            awaitReady(queue, 3)
            val left = drain(queue)
            assertEquals(listOf("msg-0", "msg-1", "msg-2"), left, "after 30 inspections the consumer must still get all three")
            assertFalse(left.isEmpty())
        } finally {
            mgmt("DELETE", "/api/queues/%2F/$queue")
        }
    }

    @Test
    fun `gate - inspection does not count toward an explicit delivery limit of 2`() {
        // Independent of the broker default: with x-delivery-limit 2, a message that really counted each peek as a delivery
        // attempt would be dead-lettered on the third inspection.
        val queue = "m7-gate-limit2-${UUID.randomUUID().toString().take(8)}"
        assertEquals(
            201,
            mgmt("PUT", "/api/queues/%2F/$queue", """{"durable":true,"arguments":{"x-queue-type":"quorum","x-delivery-limit":2}}""").statusCode(),
        )
        try {
            publishTo(queue, 2)
            awaitReady(queue, 2)

            withConnection { inspect ->
                repeat(8) { round ->
                    val messages = assertInstanceOf(QueueInspectionResult.Success::class.java, inspect(queue, 10), "round $round").messages
                    assertEquals(2, messages.size, "round $round: a message was dropped by the delivery limit")
                }
            }

            awaitReady(queue, 2)
            assertEquals(listOf("msg-0", "msg-1"), drain(queue))
        } finally {
            mgmt("DELETE", "/api/queues/%2F/$queue")
        }
    }

    @Test
    fun `gate - inspection of a queue that does not exist is refused, not an error`() {
        withConnection { inspect ->
            val result = inspect("m7-gate-no-such-queue-${UUID.randomUUID()}", 5)
            assertInstanceOf(QueueInspectionResult.Refused::class.java, result, "got $result")
        }
    }
}
