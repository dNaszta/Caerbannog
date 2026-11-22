package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Environment
import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.core.connections.ConnectionProfile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Live check of the "Exchange" and "Routing Key" rows in the stream message details: does a real broker put the
 * origin exchange and routing key on a stream message, and does [RabbitMqStreamMessageReader] surface them?
 * Messages published through an exchange (management API publish, bound to the stream) vs. straight to the stream.
 * Run with `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerStreamExchangeAnnotationsTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerStreamExchangeAnnotationsTest {

    companion object {
        private val rabbit: RabbitMQContainer = brokerWithPlugins(BrokerImages.CURRENT_4X, "rabbitmq_stream", "rabbitmq_stream_management")
            .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
            .apply { addExposedPorts(5551, 5552) }

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
        streamPort = rabbit.getMappedPort(5552), managementPort = rabbit.getMappedPort(15672), tls = false,
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
    fun `a message routed through an exchange reports that exchange and routing key, a direct stream publish reports none`() {
        val stream = "annotations-${System.nanoTime()}"
        Environment.builder().host(rabbit.host).port(rabbit.getMappedPort(5552))
            .username(rabbit.adminUsername).password(rabbit.adminPassword).build().use { env ->
            env.streamCreator().stream(stream).create()
            try {
                // Direct publish through the stream protocol.
                val confirmed = CountDownLatch(1)
                env.producerBuilder().stream(stream).build().use { p ->
                    p.send(p.messageBuilder().addData("direct".toByteArray()).build()) { confirmed.countDown() }
                    assertTrue(confirmed.await(30, TimeUnit.SECONDS), "direct publish not confirmed")
                }
                // Routed publish: bind the stream to amq.direct, publish via the management API.
                assertEquals(201, mgmt("POST", "/api/bindings/%2F/e/amq.direct/q/$stream", """{"routing_key":"orders.created"}""").statusCode())
                val published = mgmt(
                    "POST", "/api/exchanges/%2F/amq.direct/publish",
                    """{"properties":{},"routing_key":"orders.created","payload":"routed","payload_encoding":"string"}""",
                )
                assertTrue(published.body().contains("\"routed\":true"), "not routed: ${published.body()}")

                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                var messages = readAll(stream)
                while (messages.size < 2) {
                    check(System.nanoTime() < deadline) { "timed out waiting for 2 messages; got $messages" }
                    TimeUnit.MILLISECONDS.sleep(200)
                    messages = readAll(stream)
                }
                assertEquals(2, messages.size, "messages: $messages")

                val direct = messages.single { it.payload.decodeToString() == "direct" }
                val routed = messages.single { it.payload.decodeToString() == "routed" }
                assertNull(direct.exchange, "direct publish exchange")
                assertNull(direct.routingKey, "direct publish routing key")
                assertEquals("amq.direct", routed.exchange, "routed exchange (annotations: see reader)")
                assertEquals("orders.created", routed.routingKey, "routed routing key")
            } finally {
                env.deleteStream(stream)
            }
        }
    }
}
