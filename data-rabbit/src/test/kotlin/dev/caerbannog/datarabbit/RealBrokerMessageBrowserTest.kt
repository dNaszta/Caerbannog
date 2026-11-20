package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Address
import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.OffsetSpecification
import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.MessageBrowserState
import dev.caerbannog.core.browser.MessageBrowserViewModel
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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
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

/**
 * Gate test for Milestone M3 (@gate-M3):
 *
 * Verifies against a real TLS RabbitMQ 4 broker:
 * 1. Consumer offset safety: MessageBrowserViewModel browsing uses unnamed consumers with noTrackingStrategy(),
 *    so browsing NEVER registers tracking offsets, alters stored consumer offsets, or increases message count.
 * 2. Paging correctness and edge states across message chunk boundaries (First, Next, Next, Previous).
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerMessageBrowserTest {

    companion object {
        private const val STREAM_TLS_PORT = 5551

        private val rabbit: RabbitMQContainer = brokerWithPlugins(BrokerImages.CURRENT_4X, "rabbitmq_stream", "rabbitmq_stream_management")
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

    private fun publish(env: Environment, stream: String, count: Int) {
        val producer = env.producerBuilder().stream(stream).build()
        val latch = CountDownLatch(count)
        val published = AtomicInteger()
        for (i in 0 until count) {
            val msg = producer.messageBuilder().addData("payload-$i".toByteArray()).build()
            producer.send(msg) { confirmation ->
                if (confirmation.isConfirmed) {
                    published.incrementAndGet()
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out publishing messages: $published of $count")
        producer.close()
    }

    private fun consumeWithStoreAt(env: Environment, stream: String, name: String, storeOffset: Long, totalToRead: Int) {
        val latch = CountDownLatch(totalToRead)
        val consumer = env.consumerBuilder()
            .stream(stream)
            .name(name)
            .offset(OffsetSpecification.first())
            .manualTrackingStrategy()
            .builder()
            .messageHandler { context, _ ->
                if (context.offset() == storeOffset) {
                    context.storeOffset()
                }
                latch.countDown()
            }
            .build()
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out consuming messages")
        Thread.sleep(500)
        consumer.close()
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
            name = "m3-test",
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
    fun `gate-M3 safety - browsing never alters consumer stored offsets or tracking entries`() = runBlocking {
        val stream = "gate-m3-safety-${UUID.randomUUID().toString().take(8)}"
        val environment = env()
        createStream(stream)

        publish(environment, stream, 250)
        consumeWithStoreAt(environment, stream, name = "billing", storeOffset = 50L, totalToRead = 60)

        // Wait until billing tracking offset is visible on broker
        awaitUntil("billing offset stored at 50", 30_000,
            fetch = { getTracking(stream) },
            done = { t -> t["offsets"]?.jsonObject?.get("billing")?.jsonPrimitive?.long == 50L }
        )

        val queueBefore = awaitUntil("queue messages metric populated", 30_000,
            fetch = { getQueue(stream) },
            done = { q -> q["messages"]?.jsonPrimitive?.longOrNull != null }
        )
        val messagesBefore = queueBefore["messages"]!!.jsonPrimitive.long

        // Drive MessageBrowserViewModel against real broker
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { testScopes += it }
        val reader = createReader()
        val vm = MessageBrowserViewModel(reader = reader, vhost = "/", stream = stream, scope = scope, initialPageSize = 100)

        vm.browseFirst()
        val loaded1 = withTimeout(15_000) { vm.state.filterIsInstance<MessageBrowserState.Loaded>().first() }
        assertEquals(0L, loaded1.messages.first().offset)
        assertEquals(99L, loaded1.messages.last().offset)

        vm.browseLastPage()
        val loaded2 = withTimeout(15_000) {
            vm.state.filterIsInstance<MessageBrowserState.Loaded>().first { it.startMode is BrowseStart.LastPage }
        }
        assertEquals(249L, loaded2.messages.last().offset)

        // ASSERT SAFETY:
        // 1. "billing" offset is still 50
        val trackingAfter = getTracking(stream)
        val offsetsAfter = trackingAfter["offsets"]!!.jsonObject
        assertEquals(50L, offsetsAfter["billing"]!!.jsonPrimitive.long, "billing stored offset must remain 50")
        assertEquals(setOf("billing"), offsetsAfter.keys, "no new consumer names or tracking offsets may appear")

        // 2. messages count on queue is completely unchanged
        val queueAfter = awaitUntil("queue messages metric fetched", 30_000,
            fetch = { getQueue(stream) },
            done = { q -> q["messages"]?.jsonPrimitive?.longOrNull != null }
        )
        val messagesAfter = queueAfter["messages"]!!.jsonPrimitive.long
        assertEquals(messagesBefore, messagesAfter, "message count on broker must be unaltered by browsing")
    }

    @Test
    fun `gate-M3 paging - read real messages in order across page boundaries and detect stream edges`() = runBlocking {
        val stream = "gate-m3-paging-${UUID.randomUUID().toString().take(8)}"
        val environment = env()
        createStream(stream)

        publish(environment, stream, 250)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { testScopes += it }
        val reader = createReader()
        val vm = MessageBrowserViewModel(reader = reader, vhost = "/", stream = stream, scope = scope, initialPageSize = 100)

        // 1. browseFirst(): offsets 0 to 99
        vm.browseFirst()
        val page1 = withTimeout(15_000) { vm.state.filterIsInstance<MessageBrowserState.Loaded>().first() }
        assertEquals(100, page1.messages.size)
        assertEquals(0L, page1.messages.first().offset)
        assertEquals(99L, page1.messages.last().offset)
        assertFalse(page1.canGoPrevious, "cannot go previous from first offset")
        assertTrue(page1.canGoNext, "can go next from offset 99 with lastOffset 249")

        // 2. nextPage(): offsets 100 to 199
        vm.nextPage()
        val page2 = withTimeout(15_000) {
            vm.state.filterIsInstance<MessageBrowserState.Loaded>().first { it.messages.first().offset == 100L }
        }
        assertEquals(100, page2.messages.size)
        assertEquals(100L, page2.messages.first().offset)
        assertEquals(199L, page2.messages.last().offset)
        assertTrue(page2.canGoPrevious, "can go previous from offset 100")
        assertTrue(page2.canGoNext, "can go next from offset 199")

        // 3. nextPage(): offsets 200 to 249
        vm.nextPage()
        val page3 = withTimeout(15_000) {
            vm.state.filterIsInstance<MessageBrowserState.Loaded>().first { it.messages.first().offset == 200L }
        }
        assertEquals(50, page3.messages.size)
        assertEquals(200L, page3.messages.first().offset)
        assertEquals(249L, page3.messages.last().offset)
        assertTrue(page3.canGoPrevious, "can go previous from offset 200")
        assertFalse(page3.canGoNext, "cannot go next past end of stream (offset 249)")

        // 4. previousPage(): back to 100..199
        vm.previousPage()
        val page4 = withTimeout(15_000) {
            vm.state.filterIsInstance<MessageBrowserState.Loaded>().first { it.messages.first().offset == 100L }
        }
        assertEquals(100, page4.messages.size)
        assertEquals(100L, page4.messages.first().offset)
        assertEquals(199L, page4.messages.last().offset)

        // 5. previousPage(): back to 0..99
        vm.previousPage()
        val page5 = withTimeout(15_000) {
            vm.state.filterIsInstance<MessageBrowserState.Loaded>().first { it.messages.first().offset == 0L }
        }
        assertEquals(100, page5.messages.size)
        assertEquals(0L, page5.messages.first().offset)
        assertFalse(page5.canGoPrevious, "cannot go previous at start of stream")
    }
}
