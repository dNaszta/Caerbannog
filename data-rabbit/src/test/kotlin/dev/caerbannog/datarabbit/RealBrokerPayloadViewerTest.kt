package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Address
import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.OffsetSpecification
import dev.caerbannog.core.browser.MessageBrowserState
import dev.caerbannog.core.browser.MessageBrowserViewModel
import dev.caerbannog.core.browser.MessageFilterCriteria
import dev.caerbannog.core.browser.PayloadFormatter
import dev.caerbannog.core.browser.PayloadViewKind
import dev.caerbannog.core.connections.ConnectionProfile
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager
import kotlin.system.measureTimeMillis

/**
 * Gate test and broker safety verification for Milestone M4 (@gate-M4, @safety):
 *
 * Verifies against a real TLS RabbitMQ 4 broker:
 * 1. Filtering safety: stream filtering and scanning never publishes, deletes, or acknowledges messages,
 *    and never creates or alters consumer tracking offsets on the broker.
 * 2. Gate M4 responsiveness: browsing a stream holding 100 messages of 1 MB each loads without OOM,
 *    enforces bounded display capping, and keeps message selection and payload inspection responsive.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerPayloadViewerTest {

    companion object {
        private const val STREAM_TLS_PORT = 5551

        private val rabbit: RabbitMQContainer = RabbitMQContainer("rabbitmq:4-management")
            .withPluginsEnabled("rabbitmq_stream", "rabbitmq_stream_management")
            .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
            .apply { addExposedPorts(STREAM_TLS_PORT) }

        @JvmStatic
        @BeforeAll
        fun startBroker() {
            rabbit.start()
        }

        @JvmStatic
        @AfterAll
        fun stopBroker() {
            rabbit.stop()
        }
    }

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val http: HttpClient by lazy {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        HttpClient.newBuilder()
            .sslContext(ctx)
            .sslParameters(SSLParameters().apply { endpointIdentificationAlgorithm = "" })
            .build()
    }

    private val caPath = File(javaClass.classLoader.getResource("tls/ca.pem")!!.toURI()).absolutePath
    private val closeables = mutableListOf<AutoCloseable>()
    private val environments = mutableListOf<Environment>()
    private val streamsToDelete = mutableListOf<String>()
    private val testScopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun tearDown() {
        testScopes.forEach { it.cancel() }
        testScopes.clear()
        closeables.reversed().forEach { runCatching { it.close() } }
        closeables.clear()
        environments.reversed().forEach { runCatching { it.close() } }
        environments.clear()
        val env = runCatching { env() }.getOrNull()
        if (env != null) {
            streamsToDelete.forEach { runCatching { env.deleteStream(it) } }
            runCatching { env.close() }
        }
        streamsToDelete.clear()
    }

    private fun env(): Environment {
        val ssl = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
        val e = Environment.builder()
            .addressResolver { Address(rabbit.host, rabbit.getMappedPort(STREAM_TLS_PORT)) }
            .uri("rabbitmq-stream+tls://${rabbit.adminUsername}:${rabbit.adminPassword}@${rabbit.host}:${rabbit.getMappedPort(STREAM_TLS_PORT)}/%2f")
            .tls().sslContext(ssl)
            .environmentBuilder()
            .build()
        environments += e
        return e
    }

    private fun createStream(name: String) {
        val e = env()
        e.streamCreator().stream(name).create()
        streamsToDelete += name
    }

    private fun getTracking(stream: String): JsonObject {
        val auth = Base64.getEncoder().encodeToString("${rabbit.adminUsername}:${rabbit.adminPassword}".toByteArray())
        val req = HttpRequest.newBuilder()
            .uri(URI("https://${rabbit.host}:${rabbit.httpsPort}/api/stream/%2F/$stream/tracking"))
            .header("Authorization", "Basic $auth")
            .GET()
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, res.statusCode(), "tracking endpoint failed: ${res.body()}")
        return Json.parseToJsonElement(res.body()).jsonObject
    }

    private fun getQueue(stream: String): JsonObject {
        val auth = Base64.getEncoder().encodeToString("${rabbit.adminUsername}:${rabbit.adminPassword}".toByteArray())
        val req = HttpRequest.newBuilder()
            .uri(URI("https://${rabbit.host}:${rabbit.httpsPort}/api/queues/%2F/$stream"))
            .header("Authorization", "Basic $auth")
            .GET()
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, res.statusCode(), "queue endpoint failed: ${res.body()}")
        return Json.parseToJsonElement(res.body()).jsonObject
    }

    private suspend fun <T> awaitUntil(desc: String, timeoutMs: Long = 30_000, stepMs: Long = 100, fetch: () -> T, done: (T) -> Boolean): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: T = fetch()
        while (System.currentTimeMillis() < deadline) {
            if (done(last)) return last
            delay(stepMs)
            last = fetch()
        }
        if (done(last)) return last
        throw AssertionError("Timed out waiting for $desc: last value = $last")
    }

    private fun createReader(): RabbitMqStreamMessageReader {
        val profile = ConnectionProfile(
            name = "m4-test",
            host = rabbit.host,
            virtualHost = "/",
            username = rabbit.adminUsername,
            streamPort = rabbit.getMappedPort(STREAM_TLS_PORT),
            managementPort = rabbit.httpsPort,
            caCertificatePath = caPath,
        )
        val transport = BrokerTransport.from(profile)
        val streamEnvs = StreamEnvironments(profile, rabbit.adminPassword, transport)
        closeables += streamEnvs
        val requests = ManagementRequests.forProfile(profile)
        val mgmtHttp = ManagementHttp(TlsSupport.buildHttpClient(transport, 15_000), rabbit.adminUsername, rabbit.adminPassword)
        closeables += mgmtHttp
        val catalog = ManagementStreamCatalog(mgmtHttp, requests)
        return RabbitMqStreamMessageReader(streamEnvs, catalog)
    }

    @Test
    fun `filtering is read-only on the broker and never alters tracking offsets or message counts`(): Unit = runBlocking {
        val stream = "orders-events-${UUID.randomUUID().toString().take(8)}"
        val environment = env()
        createStream(stream)

        // Publish 50 messages, some with text matching "A-1202"
        val producer = environment.producerBuilder().stream(stream).build()
        val latch = CountDownLatch(50)
        for (i in 0 until 50) {
            val body = if (i % 10 == 0) "Order A-1202 processed event #$i" else "Order B-$i standard event"
            val msg = producer.messageBuilder()
                .addData(body.toByteArray())
                .applicationProperties()
                .entry("tenant", if (i % 2 == 0) "eu" else "us")
                .messageBuilder()
                .build()
            producer.send(msg) { confirmation ->
                if (confirmation.isConfirmed) latch.countDown()
            }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out publishing initial messages")
        producer.close()

        // Establish an existing consumer with stored tracking offset
        val consumeLatch = CountDownLatch(20)
        val consumer = environment.consumerBuilder()
            .stream(stream)
            .name("order-consumer")
            .offset(OffsetSpecification.first())
            .manualTrackingStrategy()
            .builder()
            .messageHandler { context, _ ->
                if (context.offset() == 15L) {
                    context.storeOffset()
                }
                consumeLatch.countDown()
            }
            .build()
        assertTrue(consumeLatch.await(30, TimeUnit.SECONDS), "Timed out establishing consumer offset")
        Thread.sleep(500)
        consumer.close()

        // Verify initial broker state
        awaitUntil("order-consumer offset stored at 15", 30_000,
            fetch = { getTracking(stream) },
            done = { t -> t["offsets"]?.jsonObject?.get("order-consumer")?.jsonPrimitive?.long == 15L }
        )
        val queueBefore = awaitUntil("queue message count populated", 30_000,
            fetch = { getQueue(stream) },
            done = { q -> q["messages"]?.jsonPrimitive?.longOrNull != null }
        )
        val messagesBefore = queueBefore["messages"]!!.jsonPrimitive.long

        // Drive ViewModel filtering against real broker
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { testScopes += it }
        val reader = createReader()
        val vm = MessageBrowserViewModel(reader = reader, vhost = "/", stream = stream, scope = scope, initialPageSize = 50)

        vm.browseFirst()
        withTimeout(15_000) { vm.state.filterIsInstance<MessageBrowserState.Loaded>().first() }

        // Filter by "A-1202"
        vm.filter(MessageFilterCriteria.Text("A-1202"))
        withTimeout(15_000) { vm.scanProgress.first { it != null && !it.isRunning } }

        val filteredState = vm.state.value as MessageBrowserState.Loaded
        assertTrue(filteredState.isFilterActive)
        assertEquals(5, filteredState.messages.size)
        assertTrue(filteredState.messages.all { it.payload.decodeToString().contains("A-1202") })

        // Clear filter
        vm.clearFilter()
        val clearedState = withTimeout(15_000) {
            vm.state.filterIsInstance<MessageBrowserState.Loaded>().first { !it.isFilterActive }
        }
        assertEquals(50, clearedState.messages.size)

        // ASSERT BROKER SAFETY:
        // 1. Consumer tracking offsets are completely unchanged (no new tracking entry, order-consumer still 15)
        val trackingAfter = getTracking(stream)
        val offsetsAfter = trackingAfter["offsets"]!!.jsonObject
        assertEquals(15L, offsetsAfter["order-consumer"]!!.jsonPrimitive.long, "order-consumer offset must remain 15")
        assertEquals(setOf("order-consumer"), offsetsAfter.keys, "no new consumers or tracking entries may be created")

        // 2. Broker queue message count is completely unaltered
        val queueAfter = getQueue(stream)
        val messagesAfter = queueAfter["messages"]!!.jsonPrimitive.long
        assertEquals(messagesBefore, messagesAfter, "message count on broker must not change during filter/scan")
    }

    @Test
    fun `gate-M4 - large bodies keep the screen responsive`(): Unit = runBlocking {
        val stream = "gate-m4-large-${UUID.randomUUID().toString().take(8)}"
        val environment = env()
        createStream(stream)

        // Publish 100 messages of 1 MB (1,000,000 bytes) each
        val oneMb = 1_000_000
        val largePayload = ByteArray(oneMb) { (it % 26 + 'A'.code).toByte() }
        val producer = environment.producerBuilder().stream(stream).build()
        val latch = CountDownLatch(100)
        val confirmed = AtomicInteger()

        val publishElapsed = measureTimeMillis {
            for (i in 0 until 100) {
                val msg = producer.messageBuilder()
                    .addData(largePayload)
                    .properties()
                    .contentType("text/plain")
                    .messageId("msg-$i")
                    .messageBuilder()
                    .applicationProperties()
                    .entry("batch", "1mb-load-test")
                    .entry("index", i.toString())
                    .messageBuilder()
                    .build()
                producer.send(msg) { confirmation ->
                    if (confirmation.isConfirmed) {
                        confirmed.incrementAndGet()
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(60, TimeUnit.SECONDS), "Timed out publishing 100 1MB messages: $confirmed of 100")
        }
        producer.close()
        println("Published 100 x 1MB messages in ${publishElapsed}ms")

        // Wait for broker queue statistics
        awaitUntil("100 messages visible in stream", 30_000,
            fetch = { getQueue(stream) },
            done = { q -> (q["messages"]?.jsonPrimitive?.longOrNull ?: 0L) >= 100L }
        )

        // Browse the stream from First
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { testScopes += it }
        val reader = createReader()
        val vm = MessageBrowserViewModel(reader = reader, vhost = "/", stream = stream, scope = scope, initialPageSize = 100)

        val browseElapsed = measureTimeMillis {
            vm.browseFirst()
            val loaded = withTimeout(30_000) { vm.state.filterIsInstance<MessageBrowserState.Loaded>().first() }
            assertEquals(100, loaded.messages.size)
            assertEquals(0L, loaded.messages.first().offset)
            assertEquals(99L, loaded.messages.last().offset)
        }
        println("Browsed 100 x 1MB messages in ${browseElapsed}ms")

        val loaded = vm.state.value as MessageBrowserState.Loaded
        val formatter = PayloadFormatter()

        // Verify payload formatting and responsiveness:
        // Inspection with default capping (64 KB) must be fast (< 500 ms) and prevent allocating huge UI strings
        val firstMessage = loaded.messages.first()
        val inspectionElapsed = measureTimeMillis {
            val inspection = formatter.inspect(firstMessage, requestedKind = PayloadViewKind.TEXT, showAll = false)
            assertTrue(inspection.isCapped, "1 MB payload preview must be capped")
            assertTrue(inspection.canShowAll, "Capped payload must allow 'Show all'")
            assertEquals(PayloadFormatter.DEFAULT_CAP_LIMIT, inspection.displayText.length, "Preview should be capped to 64KB")
            assertEquals("text/plain", inspection.properties["contentType"] ?: inspection.properties["content-type"])
            assertEquals("1mb-load-test", inspection.applicationProperties["batch"])
        }
        assertTrue(inspectionElapsed < 500, "Inspection of capped 1 MB payload must complete swiftly (took ${inspectionElapsed}ms)")

        // When showAll is explicitly chosen, full 1MB content is returned
        val fullInspection = formatter.inspect(firstMessage, requestedKind = PayloadViewKind.TEXT, showAll = true)
        assertFalse(fullInspection.isCapped, "Explicit showAll must not be capped")
        assertEquals(oneMb, fullInspection.displayText.length)

        // Verify rapid selection / scrolling across messages remains responsive
        val scrollElapsed = measureTimeMillis {
            for (offset in 0 until 100 step 10) {
                vm.selectMessage(offset.toLong())
                val currentLoaded = vm.state.value as MessageBrowserState.Loaded
                assertEquals(offset.toLong(), currentLoaded.selectedMessage?.offset)
            }
        }
        assertTrue(scrollElapsed < 200, "Selecting across 100 large messages must remain responsive (took ${scrollElapsed}ms)")
    }
}
