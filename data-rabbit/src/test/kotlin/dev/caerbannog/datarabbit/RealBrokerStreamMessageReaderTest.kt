package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Address
import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.OffsetSpecification
import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.connections.ConnectionProfile
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
 * M3-01 verification against a real TLS RabbitMQ 4 broker.
 *
 * Verifies:
 * 1. SAFETY RULE: Browsing with [RabbitMqStreamMessageReader] uses ephemeral unnamed consumers
 *    with `noTrackingStrategy()`, so browsing NEVER writes tracking entries to the broker, alters
 *    existing consumers' stored offsets, or changes stream message counts.
 * 2. Ordered paged reads across boundaries and chunk prefix filtering.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerStreamMessageReaderTest {

    companion object {
        private const val STREAM_TLS_PORT = 5551

        private val rabbit: RabbitMQContainer = brokerWithPlugins("rabbitmq:4-management", "rabbitmq_stream", "rabbitmq_stream_management")
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

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { runCatching { it.close() } }
        for (name in streamsToDelete) {
            runCatching { env().deleteStream(name) }
        }
        environments.forEach { runCatching { it.close() } }
        closeables.clear()
        streamsToDelete.clear()
        environments.clear()
    }

    private fun env(): Environment {
        val e = Environment.builder()
            .host(rabbit.host)
            .port(rabbit.getMappedPort(STREAM_TLS_PORT))
            .username(rabbit.adminUsername)
            .password(rabbit.adminPassword)
            .virtualHost("/")
            .addressResolver { Address(rabbit.host, rabbit.getMappedPort(STREAM_TLS_PORT)) }
            .tls()
            .sslContext(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build())
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

    private fun publish(e: Environment, stream: String, count: Int) {
        val latch = CountDownLatch(count)
        val p = e.producerBuilder().stream(stream).build()
        closeables += p
        repeat(count) { i ->
            p.send(p.messageBuilder().addData("payload-$i".toByteArray()).build()) { latch.countDown() }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS), "publishing $count messages timed out")
        p.close()
        closeables.remove(p)
    }

    private fun consumeWithStoreAt(e: Environment, stream: String, name: String, storeOffset: Long, totalToRead: Int) {
        val latch = CountDownLatch(totalToRead)
        val count = AtomicInteger()
        val c = e.consumerBuilder()
            .stream(stream)
            .name(name)
            .offset(OffsetSpecification.first())
            .manualTrackingStrategy().builder()
            .messageHandler { ctx, _ ->
                val current = count.getAndIncrement().toLong()
                if (current == storeOffset) {
                    ctx.storeOffset()
                }
                latch.countDown()
            }
            .build()
        closeables += c
        assertTrue(latch.await(30, TimeUnit.SECONDS), "consuming up to $totalToRead timed out")
        Thread.sleep(500)
        c.close()
        closeables.remove(c)
    }

    private fun <T> awaitUntil(what: String, timeoutMs: Long = 30_000, fetch: () -> T, done: (T) -> Boolean): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val v = fetch()
            if (done(v)) return v
            check(System.nanoTime() < deadline) { "timed out waiting for: $what; last value: $v" }
            TimeUnit.MILLISECONDS.sleep(100)
        }
    }

    private fun getTracking(stream: String): JsonObject {
        val auth = Base64.getEncoder().encodeToString("${rabbit.adminUsername}:${rabbit.adminPassword}".toByteArray())
        val req = HttpRequest.newBuilder(URI("https://${rabbit.host}:${rabbit.httpsPort}/api/stream/%2F/$stream/tracking"))
            .header("Authorization", "Basic $auth")
            .GET()
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() == 200) { "GET tracking returned ${res.statusCode()}: ${res.body()}" }
        return Json.parseToJsonElement(res.body()).jsonObject
    }

    private fun getQueue(stream: String): JsonObject {
        val auth = Base64.getEncoder().encodeToString("${rabbit.adminUsername}:${rabbit.adminPassword}".toByteArray())
        val req = HttpRequest.newBuilder(URI("https://${rabbit.host}:${rabbit.httpsPort}/api/queues/%2F/$stream"))
            .header("Authorization", "Basic $auth")
            .GET()
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() == 200) { "GET queue returned ${res.statusCode()}: ${res.body()}" }
        return Json.parseToJsonElement(res.body()).jsonObject
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
    fun `browsing never changes any consumers stored offset or stream tracking entries`() = runBlocking {
        val stream = "safety-stream-${UUID.randomUUID().toString().take(8)}"
        val environment = env()
        createStream(stream)

        // Publish 250 messages
        publish(environment, stream, 250)

        // Attach consumer "billing" and store offset 50
        consumeWithStoreAt(environment, stream, name = "billing", storeOffset = 50L, totalToRead = 60)

        // Verify initial tracking state
        val trackingBefore = awaitUntil("tracking for $stream has billing", 30_000,
            fetch = { getTracking(stream) },
            done = { t -> t["offsets"]?.jsonObject?.containsKey("billing") == true }
        )
        val offsetsBefore = trackingBefore["offsets"]!!.jsonObject
        assertEquals(50L, offsetsBefore["billing"]!!.jsonPrimitive.long)
        assertEquals(setOf("billing"), offsetsBefore.keys, "only billing should have a stored offset")

        val queueBefore = awaitUntil("queue for $stream has messages", 30_000,
            fetch = { getQueue(stream) },
            done = { q -> (q["messages"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) >= 250L }
        )
        val messagesBefore = queueBefore["messages"]!!.jsonPrimitive.long

        // Now browse using RabbitMqStreamMessageReader starting from First and then from LastPage
        val reader = createReader()
        val page1 = reader.readPage("/", stream, BrowseStart.First, pageSize = 100)
        val success1 = assertInstanceOf(BrowseResult.Success::class.java, page1)
        assertEquals(100, success1.messages.size)
        assertEquals(0L, success1.messages.first().offset)

        val page2 = reader.readPage("/", stream, BrowseStart.LastPage(pageSize = 100), pageSize = 100)
        val success2 = assertInstanceOf(BrowseResult.Success::class.java, page2)
        assertTrue(success2.messages.size in 99..100, "last page should have 99 or 100 messages; was ${success2.messages.size}")
        assertEquals(249L, success2.messages.last().offset)

        // ASSERT SAFETY RULE:
        // 1. Stored offset of "billing" is still 50
        val trackingAfter = getTracking(stream)
        val offsetsAfter = trackingAfter["offsets"]!!.jsonObject
        assertEquals(50L, offsetsAfter["billing"]!!.jsonPrimitive.long, "stored offset of billing must remain 50")
        assertEquals(setOf("billing"), offsetsAfter.keys, "no new consumer tracking entries may appear")

        // 2. Stream message count is completely unchanged
        val queueAfter = getQueue(stream)
        val messagesAfter = queueAfter["messages"]!!.jsonPrimitive.long
        assertEquals(messagesBefore, messagesAfter, "browsing must not append tracking entries or mutate messages count")
    }

    @Test
    fun `read real messages in order across page boundaries`() = runBlocking {
        val stream = "ordered-stream-${UUID.randomUUID().toString().take(8)}"
        val environment = env()
        createStream(stream)

        publish(environment, stream, 250)
        val reader = createReader()

        // Page 1: offsets 0 to 99
        val page1 = reader.readPage("/", stream, BrowseStart.First, pageSize = 100)
        val s1 = assertInstanceOf(BrowseResult.Success::class.java, page1)
        assertEquals(100, s1.messages.size)
        assertEquals(0L, s1.messages.first().offset)
        assertEquals(99L, s1.messages.last().offset)
        assertEquals("payload-0", s1.messages.first().payload.decodeToString())

        // Page 2: offsets 100 to 199
        val page2 = reader.readPage("/", stream, BrowseStart.Offset(100L), pageSize = 100)
        val s2 = assertInstanceOf(BrowseResult.Success::class.java, page2)
        assertEquals(100, s2.messages.size)
        assertEquals(100L, s2.messages.first().offset)
        assertEquals(199L, s2.messages.last().offset)

        // Page 3: offsets 200 to 249
        val page3 = reader.readPage("/", stream, BrowseStart.Offset(200L), pageSize = 100)
        val s3 = assertInstanceOf(BrowseResult.Success::class.java, page3)
        assertEquals(50, s3.messages.size)
        assertEquals(200L, s3.messages.first().offset)
        assertEquals(249L, s3.messages.last().offset)
    }

    @Test
    fun `reading middle of a chunk drops prefix messages and starts at requested offset`() = runBlocking {
        val stream = "chunk-test-${UUID.randomUUID().toString().take(8)}"
        val environment = env()
        createStream(stream)

        publish(environment, stream, 250)
        val reader = createReader()

        val page = reader.readPage("/", stream, BrowseStart.Offset(120L), pageSize = 50)
        val s = assertInstanceOf(BrowseResult.Success::class.java, page)
        assertEquals(50, s.messages.size)
        assertEquals(120L, s.messages.first().offset)
        assertEquals(169L, s.messages.last().offset)
        assertTrue(s.messages.none { it.offset < 120L }, "all messages below 120 must be dropped")
    }
}
