package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Environment
import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.mqtt.MqttHeader
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttRequest
import dev.caerbannog.core.publish.PublishOutcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Live check of MQTT headers: a message published with the MQTT 5 client and user properties, routed by `amq.topic` into
 * a stream, must come back from [RabbitMqStreamMessageReader] with those headers visible (in the message's properties
 * or application properties), and a message without headers must carry none.
 * Run with `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerMqttHeadersTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerMqttHeadersTest {

    companion object {
        private val rabbit: RabbitMQContainer = brokerWithPlugins("rabbitmq:4-management", "rabbitmq_stream", "rabbitmq_stream_management", "rabbitmq_mqtt")
            .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
            .apply { addExposedPorts(5551, 5552, 1883) }

        @JvmStatic @BeforeAll fun start() = rabbit.start()

        @JvmStatic @AfterAll fun stop() = rabbit.stop()
    }

    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val auth get() = Base64.getEncoder().encodeToString("${rabbit.adminUsername}:${rabbit.adminPassword}".toByteArray())

    private fun mgmt(method: String, path: String, body: String? = null): HttpResponse<String> {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val request = HttpRequest.newBuilder(URI("http://${rabbit.host}:${rabbit.getMappedPort(15672)}$path"))
            .header("Authorization", "Basic $auth").header("Content-Type", "application/json")
            .method(method, publisher).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun plainProfile() = ConnectionProfile(
        name = "annotations", host = rabbit.host, virtualHost = "/", username = rabbit.adminUsername,
        streamPort = rabbit.getMappedPort(5552), managementPort = rabbit.getMappedPort(15672), tls = false, mqttPort = rabbit.getMappedPort(1883),
    )

    private fun readAll(stream: String): List<StreamMessage> = runBlocking {
        val profile = plainProfile()
        val transport = BrokerTransport.from(profile)
        StreamEnvironments(profile, rabbit.adminPassword, transport).use { envs ->
            val mgmtHttp = ManagementHttp(TlsSupport.buildHttpClient(transport, 15_000), rabbit.adminUsername, rabbit.adminPassword)
            mgmtHttp.use {
                val reader = RabbitMqStreamMessageReader(envs, ManagementStreamCatalog(mgmtHttp, ManagementRequests.forProfile(profile)))
                val result = reader.readPage("/", stream, BrowseStart.First, pageSize = 100)
                assertInstanceOf(BrowseResult.Success::class.java, result, "got $result").messages
            }
        }
    }

    @Test
    fun `MQTT 5 user properties published through amq topic reach the stream message`() {
        val stream = "mqtt-headers-${System.nanoTime()}"
        Environment.builder().host(rabbit.host).port(rabbit.getMappedPort(5552))
            .username(rabbit.adminUsername).password(rabbit.adminPassword).build().use { env ->
            env.streamCreator().stream(stream).create()
            try {
                assertEquals(201, mgmt("POST", "/api/bindings/%2F/e/amq.topic/q/$stream", """{"routing_key":"sensors.room1"}""").statusCode())
                val publisher = RabbitMqMqttPublisher(plainProfile(), rabbit.adminPassword, BrokerTransport.Plain)

                val withHeaders = runBlocking {
                    publisher.publish(
                        PublishMqttRequest(
                            "sensors/room1", "with".encodeToByteArray(), MqttQos.AT_LEAST_ONCE,
                            headers = listOf(MqttHeader("trace-id", "abc-123"), MqttHeader("tenant", "acme")),
                        ),
                    )
                }
                assertTrue(withHeaders is PublishOutcome.Success, "got $withHeaders")
                val plain = runBlocking { publisher.publish(PublishMqttRequest("sensors/room1", "plain".encodeToByteArray(), MqttQos.AT_LEAST_ONCE)) }
                assertTrue(plain is PublishOutcome.Success, "got $plain")

                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                var messages = readAll(stream)
                while (messages.size < 2) {
                    check(System.nanoTime() < deadline) { "timed out waiting for 2 messages; got $messages" }
                    TimeUnit.MILLISECONDS.sleep(200)
                    messages = readAll(stream)
                }
                val with = messages.single { it.payload.decodeToString() == "with" }
                val without = messages.single { it.payload.decodeToString() == "plain" }
                val seen = with.applicationProperties + with.properties
                assertEquals("abc-123", seen["trace-id"]?.toString(), "headers seen on the stream message: $seen")
                assertEquals("acme", seen["tenant"]?.toString(), "headers seen on the stream message: $seen")
                assertTrue("trace-id" !in without.applicationProperties && "trace-id" !in without.properties, "headerless message: $without")
            } finally {
                env.deleteStream(stream)
            }
        }
    }
}
