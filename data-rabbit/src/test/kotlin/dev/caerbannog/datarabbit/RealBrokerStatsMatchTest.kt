package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Address
import com.rabbitmq.stream.Consumer
import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.OffsetSpecification
import com.rabbitmq.stream.Producer
import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.StreamListScreen
import dev.caerbannog.core.streams.StreamListState
import dev.caerbannog.core.streams.StreamListViewModel
import dev.caerbannog.core.streams.StreamListTexts
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
 * M2-09 gate scenario: "Statistics agree with the Management UI" of `03_stream_list.feature`.
 *
 * Runs against a real TLS RabbitMQ 4.x container with `rabbitmq_stream` and `rabbitmq_stream_management`.
 * Asserts that the stream list row statistics (first offset, last offset, message count, publishers, consumers)
 * agree with the RabbitMQ Management UI and the stream protocol `queryStreamStats`, with the documented
 * tolerance: management `messages` includes tracking entries of named consumers (spec "Caveats", M2-02c),
 * so Caerbannog and the Management UI show the same number, and the difference from the number of published
 * messages is bounded by the tracking entries.
 *
 * Polling on values uses bounded timeouts (statistics lag up to 5 s).
 * Run with: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerStatsMatchTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerStatsMatchTest {

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

    private class Secrets(private val password: String) : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = password
        override fun delete(profileName: String) = Unit
    }

    private class Settings : AppSettings {
        @Volatile
        override var streamListRefresh: RefreshInterval = RefreshInterval.Every5
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

    private var scope: CoroutineScope? = null
    private var vm: StreamListViewModel? = null
    private var active: ActiveConnection? = null

    @AfterEach
    fun tearDown() {
        runCatching { vm?.close() }
        runCatching { scope?.cancel() }
        runCatching { active?.close() }
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

    private fun publish(e: Environment, stream: String, count: Int): Producer {
        val latch = CountDownLatch(count)
        val p = e.producerBuilder().stream(stream).build()
        closeables += p
        repeat(count) {
            p.send(p.messageBuilder().addData(ByteArray(16)).build()) { latch.countDown() }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS), "confirms for $count messages on $stream not received in 30s")
        return p
    }

    private fun consumeWithTracking(e: Environment, stream: String, name: String, storeOffsetsAt: Set<Int>, expectMessages: Int): Consumer {
        val latch = CountDownLatch(expectMessages)
        val counter = AtomicInteger()
        val c = e.consumerBuilder()
            .stream(stream)
            .name(name)
            .offset(OffsetSpecification.first())
            .manualTrackingStrategy().builder()
            .messageHandler { ctx, _ ->
                val idx = counter.incrementAndGet()
                if (idx in storeOffsetsAt) {
                    ctx.storeOffset()
                }
                latch.countDown()
            }
            .build()
        closeables += c
        assertTrue(latch.await(30, TimeUnit.SECONDS), "consumer did not receive $expectMessages messages in 30s")
        return c
    }

    private fun getManagementQueue(stream: String): JsonObject {
        val auth = Base64.getEncoder().encodeToString("${rabbit.adminUsername}:${rabbit.adminPassword}".toByteArray())
        val req = HttpRequest.newBuilder(URI("https://${rabbit.host}:${rabbit.httpsPort}/api/queues/%2F/$stream"))
            .header("Authorization", "Basic $auth")
            .GET()
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() == 200) { "GET /api/queues/%2F/$stream returned ${res.statusCode()}: ${res.body()}" }
        return Json.parseToJsonElement(res.body()).jsonObject
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startViewModel(): StreamListViewModel {
        val secrets = Secrets(rabbit.adminPassword)
        val gateway = RabbitMqConnectionGateway(secrets)
        val act = ActiveConnection(ConnectionSession(ManagementApiPermissionChecker(), secrets), gateway)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val model = StreamListViewModel(
            active = act,
            settings = Settings(),
            scope = sc,
            clock = RefreshClock { System.currentTimeMillis() },
            reconnect = { name -> act.connect(profile(name)) },
            pageSize = 50,
        )
        scope = sc
        vm = model
        active = act
        model.setShown(true)
        return model
    }

    private fun profile(name: String): ConnectionProfile =
        ConnectionProfile(
            name = name,
            host = rabbit.host,
            virtualHost = "/",
            username = rabbit.adminUsername,
            streamPort = rabbit.getMappedPort(STREAM_TLS_PORT),
            managementPort = rabbit.httpsPort,
            caCertificatePath = caPath,
        )

    private val state: StreamListState get() = vm!!.state.value

    private fun awaitState(what: String, timeoutMs: Long = 30_000, ok: (StreamListState) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!ok(state)) {
            check(System.nanoTime() < deadline) { "timed out waiting for: $what; state: $state" }
            TimeUnit.MILLISECONDS.sleep(100)
        }
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

    private fun formatWithGrouping(value: Long): String {
        val digits = value.toString()
        val negative = digits.startsWith("-")
        val body = if (negative) digits.drop(1) else digits
        val grouped = body.reversed().chunked(3).joinToString(",").reversed()
        return if (negative) "-$grouped" else grouped
    }

    @Test
    fun `statistics agree with the Management UI on a stream with 1000 messages and tracking entries`() = runBlocking<Unit> {
        val stream = "orders-events-${UUID.randomUUID().toString().take(8)}"
        val environment = env()
        createStream(stream)

        // 1. Publish 1000 messages
        val publishedCount = 1000
        val producer = publish(environment, stream, publishedCount)

        // 2. Consume with named consumer storing offsets twice (creating 2 tracking entries)
        val trackingStores = setOf(200, 400)
        val consumer = consumeWithTracking(environment, stream, name = "named-tracker", storeOffsetsAt = trackingStores, expectMessages = publishedCount)

        // 3. Wait for RabbitMQ management stats to reflect the published messages and tracking entries (lag up to 5s)
        val queueStats = awaitUntil("management messages >= 1000 and consumers == 1", 30_000,
            fetch = { getManagementQueue(stream) },
            done = { q -> (q["messages"]?.jsonPrimitive?.long ?: 0L) >= publishedCount.toLong() && (q["consumers"]?.jsonPrimitive?.long ?: 0L) == 1L }
        )

        val mgmtMessages = queueStats["messages"]!!.jsonPrimitive.long
        val mgmtConsumers = queueStats["consumers"]!!.jsonPrimitive.long
        val streamProtocolStats = environment.queryStreamStats(stream)
        val protocolFirstOffset = streamProtocolStats.firstOffset()

        // 4. Start view model and await settled row state
        startViewModel()
        active!!.connect(profile("gate-profile"))
        awaitState("orders-events row is visible and settled", 30_000) { s ->
            s.screen is StreamListScreen.Rows && s.rows.any {
                it.name == stream &&
                    it.publishers != StreamListTexts.UNAVAILABLE &&
                    it.firstOffset != StreamListTexts.LOADING &&
                    it.lastOffset != StreamListTexts.LOADING
            }
        }

        val row = state.rows.single { it.name == stream }

        // Assert 1: first offset equals stream protocol first offset
        assertEquals(protocolFirstOffset.toString(), row.firstOffset, "firstOffset must match stream protocol")
        assertEquals("0", row.firstOffset, "firstOffset is 0 for newly published stream")

        // Assert 2: publisher count and consumer count equal Management UI
        assertEquals("1", row.publishers, "publishers count equals active producer count")
        assertEquals(mgmtConsumers.toString(), row.consumers, "consumers count equals Management UI consumers")
        assertEquals("1", row.consumers, "consumers count is 1 for the attached consumer")

        // Assert 3: message count equals Management UI messages (formatted with digit grouping)
        val expectedFormattedMessages = formatWithGrouping(mgmtMessages)
        assertEquals(expectedFormattedMessages, row.messages, "message count equals Management UI messages")

        // Assert 4: difference to published message count is bounded by the tracking entries
        val diff = mgmtMessages - publishedCount
        assertTrue(diff in 0L..trackingStores.size.toLong(),
            "management messages ($mgmtMessages) differs from published ($publishedCount) by $diff; expected <= ${trackingStores.size}")

        // Assert 5: last offset equals (firstOffset + messages - 1)
        val expectedLastOffset = (protocolFirstOffset + mgmtMessages - 1).toString()
        assertEquals(expectedLastOffset, row.lastOffset, "lastOffset must equal firstOffset + messages - 1")
    }

    @Test
    fun `statistics agree with the Management UI on a stream with no tracking entries`() = runBlocking<Unit> {
        val stream = "plain-events-${UUID.randomUUID().toString().take(8)}"
        val environment = env()
        createStream(stream)

        val publishedCount = 500
        val producer = publish(environment, stream, publishedCount)
        producer.close()
        closeables.remove(producer)

        // Wait for management queue to settle
        val queueStats = awaitUntil("management messages == 500", 30_000,
            fetch = { getManagementQueue(stream) },
            done = { q -> (q["messages"]?.jsonPrimitive?.long ?: 0L) == publishedCount.toLong() }
        )

        val mgmtMessages = queueStats["messages"]!!.jsonPrimitive.long
        assertEquals(publishedCount.toLong(), mgmtMessages, "no tracking entries: messages == published count exactly")

        startViewModel()
        active!!.connect(profile("gate-profile"))
        awaitState("plain-events row is visible and settled", 30_000) { s ->
            s.screen is StreamListScreen.Rows && s.rows.any {
                it.name == stream &&
                    it.publishers != StreamListTexts.UNAVAILABLE &&
                    it.firstOffset != StreamListTexts.LOADING &&
                    it.lastOffset != StreamListTexts.LOADING
            }
        }

        val row = state.rows.single { it.name == stream }
        assertEquals("0", row.firstOffset)
        assertEquals("500", row.messages)
        assertEquals("499", row.lastOffset)
        assertEquals("0", row.publishers)
        assertEquals("0", row.consumers)
    }
}
