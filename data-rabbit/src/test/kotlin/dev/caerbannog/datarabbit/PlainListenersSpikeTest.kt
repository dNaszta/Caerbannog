package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Environment
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
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
 * M2-14 spikes S-D and S-E (test developer), recorded in docs/tasks/M2-14-plain-connections.md "Spike results".
 * S-D: does the broker boot with the shared fixture conf (`tls/rabbitmq.conf`, now with the plain listeners
 * 5552 and 15672 next to the TLS ones) in Testcontainers, and serve both?
 * S-E: does the stream client built WITHOUT `.tls()` reach the plain stream port with `host` and `port` only?
 * Kept as a small regression check that the shared fixture keeps offering plain listeners. Needs Docker.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class PlainListenersSpikeTest {

    companion object {
        private val rabbit: RabbitMQContainer = RabbitMQContainer("rabbitmq:4-management")
            .withPluginsEnabled("rabbitmq_stream", "rabbitmq_stream_management")
            .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
            .apply { addExposedPorts(5551, 5552) }

        @JvmStatic @BeforeAll fun start() = rabbit.start()

        @JvmStatic @AfterAll fun stop() = rabbit.stop()
    }

    private val http = HttpClient.newHttpClient()

    private fun plainGet(path: String): HttpResponse<String> {
        val auth = Base64.getEncoder().encodeToString("${rabbit.adminUsername}:${rabbit.adminPassword}".toByteArray())
        val port = rabbit.getMappedPort(15672)
        val request = HttpRequest.newBuilder(URI("http://${rabbit.host}:$port$path")).header("Authorization", "Basic $auth").build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `plain management answers whoami and the stream plugin route`() {
        assertEquals(200, plainGet("/api/whoami").statusCode())
        assertEquals(200, plainGet("/api/stream/connections").statusCode())
    }

    @Test
    fun `a stream client built without tls reaches the plain stream port with host and port only`() {
        val name = "m214-spike-${System.nanoTime()}"
        Environment.builder()
            .host(rabbit.host).port(rabbit.getMappedPort(5552))
            .username(rabbit.adminUsername).password(rabbit.adminPassword)
            .build().use { env ->
                env.streamCreator().stream(name).create()
                try {
                    val confirmed = CountDownLatch(3)
                    env.producerBuilder().stream(name).build().use { producer ->
                        repeat(3) { producer.send(producer.messageBuilder().addData(byteArrayOf(1)).build()) { confirmed.countDown() } }
                        assertTrue(confirmed.await(30, TimeUnit.SECONDS), "publish confirms did not arrive")
                    }
                    assertEquals(0L, env.queryStreamStats(name).firstOffset())
                    assertEquals(200, plainGet("/api/queues/%2F/$name").statusCode())
                } finally {
                    env.deleteStream(name)
                }
            }
    }
}
