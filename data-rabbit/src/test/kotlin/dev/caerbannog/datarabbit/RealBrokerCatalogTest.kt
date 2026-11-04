package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Address
import com.rabbitmq.stream.Environment
import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.streams.Capabilities
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.PluginStatus
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.streams.StreamPage
import dev.caerbannog.core.streams.StreamSummary
import dev.caerbannog.core.streams.Visibility
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager

/**
 * M2-03 part 2 @broker tests (no Docker means skipped, never failed): the real gateway, catalog and offsets against
 * a TLS RabbitMQ 4.x with `rabbitmq_stream` and `rabbitmq_stream_management`, built on the spike's container setup
 * (`StreamManagementApiSpikeTest`), not on its assertions. Brief acceptance: 13 mixed queues and streams list only
 * streams with the broker's pagination numbers; publishers and consumers rows for a stream with a client attached;
 * a container without the plugin gives PluginMissing; `monitoring` FULL, `management` OWN_CONNECTIONS_ONLY, no tag
 * NONE (also confirms the `GET /api/whoami` tag shape); both 401 bodies; real `StreamOffsets` Value, Empty,
 * Unavailable (denied, missing), a second vhost, and "writes nothing". Stats lag up to 5 s: bounded polling on the
 * value, never a fixed sleep. Each test uses unique names and deletes what it made; the trust-all context below
 * is for the test's own admin calls only, the code under test uses the profile CA.
 * Run with `./gradlew :data-rabbit:brokerTest`. Not run before the production classes exist (written test-first).
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerCatalogTest {

    companion object {
        private const val STREAM_TLS_PORT = 5551
        private const val OTHER_VHOST = "m203-other"
        private const val ALLOWED = "m203-allowed-"
        private val users = mapOf("m203-mon" to "pw-mon", "m203-mgmt" to "pw-mgmt", "m203-plain" to "pw-plain")

        private fun newContainer(vararg plugins: String): RabbitMQContainer =
            RabbitMQContainer("rabbitmq:4-management")
                .withPluginsEnabled(*plugins)
                .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
                .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
                .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
                .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
                .apply { addExposedPorts(STREAM_TLS_PORT) }

        private val rabbit = newContainer("rabbitmq_stream", "rabbitmq_stream_management")

        @JvmStatic @BeforeAll fun start() {
            rabbit.start()
            ctl("add_vhost", OTHER_VHOST)
            ctl("set_permissions", "-p", OTHER_VHOST, rabbit.adminUsername, ".*", ".*", ".*")
            for ((u, p) in users) {
                ctl("add_user", u, p)
                ctl("set_permissions", "-p", "/", u, "^$ALLOWED.*", "^$ALLOWED.*", "^$ALLOWED.*")
            }
            ctl("set_user_tags", "m203-mon", "monitoring")
            ctl("set_user_tags", "m203-mgmt", "management")
            ctl("set_user_tags", "m203-plain")
        }

        @JvmStatic @AfterAll fun stop() {
            runCatching { for (u in users.keys) ctl("delete_user", u) }
            runCatching { ctl("delete_vhost", OTHER_VHOST) }
            rabbit.stop()
        }

        private fun ctl(vararg args: String) {
            val r = rabbit.execInContainer("rabbitmqctl", *args)
            check(r.exitCode == 0) { "rabbitmqctl ${args.toList()} failed: ${r.stderr}" }
        }

        private val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        private val http: HttpClient by lazy {
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
            HttpClient.newBuilder().sslContext(ctx).sslParameters(SSLParameters().apply { endpointIdentificationAlgorithm = "" }).build()
        }
    }

    private class Passwords(private val map: Map<String, String>) : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String? = map[profileName]
        override fun delete(profileName: String) = Unit
    }

    private val caPath = File(javaClass.classLoader.getResource("tls/ca.pem")!!.toURI()).absolutePath
    private val wrongCaPath = File(javaClass.classLoader.getResource("certs/valid-certificate.pem")!!.toURI()).absolutePath
    private val connections = mutableListOf<HeldConnection>()
    private val environments = mutableListOf<Environment>()
    private val closeables = mutableListOf<AutoCloseable>()
    private val streamsToDelete = mutableListOf<Pair<String, String>>() // vhost (encoded), name

    @AfterEach fun cleanUp() {
        closeables.reversed().forEach { runCatching { it.close() } }
        connections.forEach { runCatching { it.close() } }
        environments.forEach { runCatching { it.close() } }
        streamsToDelete.forEach { (v, n) -> runCatching { adminCall("DELETE", "/api/queues/$v/$n") } }
        closeables.clear(); connections.clear(); environments.clear(); streamsToDelete.clear()
    }

    // ---- plumbing ----

    private fun unique(prefix: String) = "$prefix${UUID.randomUUID().toString().take(8)}"

    private fun adminCall(method: String, path: String, body: String? = null): Int {
        val auth = Base64.getEncoder().encodeToString("${rabbit.adminUsername}:${rabbit.adminPassword}".toByteArray())
        val b = HttpRequest.newBuilder(URI("https://${rabbit.host}:${rabbit.httpsPort}$path")).header("Authorization", "Basic $auth")
        if (body != null) b.header("Content-Type", "application/json")
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        return http.send(b.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    private fun declareQueue(name: String, type: String) {
        assertEquals(201, adminCall("PUT", "/api/queues/%2F/$name", """{"durable":true,"arguments":{"x-queue-type":"$type"}}"""), "declare $type $name")
        streamsToDelete += "%2F" to name
    }

    private fun env(vhost: String = "/"): Environment {
        val e = Environment.builder().host(rabbit.host).port(rabbit.getMappedPort(STREAM_TLS_PORT))
            .username(rabbit.adminUsername).password(rabbit.adminPassword).virtualHost(vhost)
            .addressResolver { Address(rabbit.host, rabbit.getMappedPort(STREAM_TLS_PORT)) }
            .tls().sslContext(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build())
            .environmentBuilder().build()
        environments += e
        return e
    }

    private fun createStream(name: String, vhost: String = "/"): Environment {
        val e = env(vhost)
        e.streamCreator().stream(name).create()
        streamsToDelete += (if (vhost == "/") "%2F" else vhost) to name
        return e
    }

    private fun publish(e: Environment, stream: String, count: Int, reference: String? = null) {
        val latch = CountDownLatch(count)
        val b = e.producerBuilder().stream(stream)
        if (reference != null) b.name(reference)
        val p = b.build()
        closeables += p
        repeat(count) { p.send(p.messageBuilder().addData(ByteArray(10) { 'x'.code.toByte() }).build()) { latch.countDown() } }
        assertTrue(latch.await(30, TimeUnit.SECONDS), "confirms for $count messages on $stream not received")
    }

    private fun profile(user: String = rabbit.adminUsername, vhost: String = "/", ca: String = caPath, port: Int = rabbit.httpsPort) =
        ConnectionProfile(
            name = "rb-test", host = rabbit.host, virtualHost = vhost, username = user,
            streamPort = rabbit.getMappedPort(STREAM_TLS_PORT), managementPort = port, caCertificatePath = ca,
        )

    private fun open(user: String = rabbit.adminUsername, password: String = rabbit.adminPassword, vhost: String = "/", ca: String = caPath): OpenResult =
        runBlocking {
            RabbitMqConnectionGateway(Passwords(mapOf("rb-test" to password))).open(profile(user, vhost, ca))
        }.also { if (it is OpenResult.Opened) connections += it.connection }

    private fun connection(user: String = rabbit.adminUsername, password: String = rabbit.adminPassword, vhost: String = "/"): HeldConnection {
        val result = open(user, password, vhost)
        assertInstanceOf(OpenResult.Opened::class.java, result, "expected an open connection, got $result")
        return (result as OpenResult.Opened).connection
    }

    private fun <T> awaitUntil(what: String, timeoutMs: Long = 40_000, fetch: () -> T, done: (T) -> Boolean): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var last = fetch()
        while (!done(last)) {
            check(System.nanoTime() < deadline) { "timed out after ${timeoutMs}ms waiting for: $what; last value: $last" }
            TimeUnit.MILLISECONDS.sleep(250) // poll interval inside the bounded helper only
            last = fetch()
        }
        return last
    }

    private fun <T> success(result: CatalogResult<T>): T {
        assertInstanceOf(CatalogResult.Success::class.java, result, "expected a value, got $result")
        @Suppress("UNCHECKED_CAST")
        return (result as CatalogResult.Success<T>).value
    }

    // ---- scenarios ----

    @Test
    fun `an administrator connects and sees full visibility with the plugin present`() = runBlocking<Unit> {
        val held = connection()
        assertEquals(CatalogResult.Success(Capabilities(PluginStatus.PRESENT, Visibility.FULL)), held.catalog.capabilities())
    }

    @Test
    fun `the list of 13 mixed queues and streams returns only the 5 streams and the brokers numbers`() = runBlocking<Unit> {
        val prefix = unique("mix-")
        repeat(8) { declareQueue("$prefix-q$it", if (it % 2 == 0) "quorum" else "classic") }
        repeat(5) { createStream("$prefix-s$it") }
        val held = connection()

        val page: StreamPage = awaitUntil(
            "13 queues and streams listed for $prefix",
            fetch = { success(runBlocking { held.catalog.listStreams(StreamListRequest(pageSize = 100, nameFilter = prefix)) }) },
            done = { it.filteredCount == 13 },
        )

        assertEquals(13, page.itemCount)
        assertEquals(5, page.rows.size, "only streams: ${page.rows.map { it.name }}")
        assertTrue(page.rows.all { it.name.startsWith(prefix) })
        assertEquals(1, page.pageCount)
    }

    @Test
    fun `a small page size pages over all queues and a page can hold fewer streams than the page size`() = runBlocking<Unit> {
        val prefix = unique("pg-")
        repeat(3) { declareQueue("$prefix-q$it", "quorum") }
        repeat(2) { createStream("$prefix-s$it") }
        val held = connection()

        val first = awaitUntil("5 entries for $prefix", fetch = { success(runBlocking { held.catalog.listStreams(StreamListRequest(page = 1, pageSize = 2, nameFilter = prefix)) }) }, done = { it.filteredCount == 5 })

        assertEquals(3, first.pageCount)
        assertTrue(first.rows.size <= 2, "a page never holds more entries than the page size")
        assertEquals(CatalogResult.BadPaging, held.catalog.listStreams(StreamListRequest(pageSize = 501)))
        assertEquals(CatalogResult.BadPaging, held.catalog.listStreams(StreamListRequest(page = 99, pageSize = 2, nameFilter = prefix)))
    }

    @Test
    fun `stream detail has the message count and the retention arguments`() = runBlocking<Unit> {
        val name = unique("detail-")
        // The broker reports x-max-age in the form it was declared with: "7D" as text here (management API), but
        // "604800s" when the stream client converts maxAge(Duration). So the stream is declared with the text form
        // (architect ruling, M2-03 part 2 step 3); the mapper passes the broker text through unchanged.
        assertEquals(201, adminCall("PUT", "/api/queues/%2F/$name", """{"durable":true,"arguments":{"x-queue-type":"stream","x-max-age":"7D"}}"""), "declare stream $name with x-max-age=7D")
        streamsToDelete += "%2F" to name
        val e = env()
        publish(e, name, 10)
        val held = connection()

        val detail: StreamSummary = awaitUntil("messages == 10 on $name", fetch = { success(runBlocking { held.catalog.streamDetail("/", name) }) }, done = { it.messages == 10L })

        assertEquals("7D", detail.retention.maxAge)
        assertEquals(CatalogResult.NotFound, held.catalog.streamDetail("/", "$name-nope"))
    }

    @Test
    fun `publishers and consumers rows appear for a stream with a client attached`() = runBlocking<Unit> {
        val name = unique("clients-")
        val e = createStream(name)
        publish(e, name, 10, reference = "pub-$name")
        val got = CountDownLatch(10)
        closeables += e.consumerBuilder().stream(name).name("cons-$name").offset(com.rabbitmq.stream.OffsetSpecification.first())
            .messageHandler { _, _ -> got.countDown() }.build()
        assertTrue(got.await(30, TimeUnit.SECONDS))
        val held = connection()

        val publishers = awaitUntil("publisher row for $name", fetch = { success(runBlocking { held.catalog.publishers("/", name) }) }, done = { rows -> rows.any { it.published == 10L } })
        val consumers = awaitUntil("consumer row for $name", fetch = { success(runBlocking { held.catalog.consumers("/", name) }) }, done = { it.isNotEmpty() })

        assertEquals(listOf("pub-$name"), publishers.map { it.reference })
        assertEquals(listOf("cons-$name"), consumers.map { it.name })
    }

    @Test
    fun `a stream with nothing attached has empty lists, not errors`() = runBlocking<Unit> {
        val name = unique("quiet-")
        createStream(name)
        val held = connection()
        assertEquals(CatalogResult.Success(emptyList<Any>()), held.catalog.publishers("/", name))
        assertEquals(CatalogResult.Success(emptyList<Any>()), held.catalog.consumers("/", name))
    }

    @Test
    fun `the visibility follows the tag of the user`() = runBlocking<Unit> {
        assertEquals(Visibility.FULL, success(runBlocking { connection("m203-mon", "pw-mon").catalog.capabilities() }).visibility)
        assertEquals(Visibility.OWN_CONNECTIONS_ONLY, success(runBlocking { connection("m203-mgmt", "pw-mgmt").catalog.capabilities() }).visibility)
    }

    @Test
    fun `a user without the management tag is connected, visibility none, lists not authorised`() = runBlocking<Unit> {
        val held = connection("m203-plain", "pw-plain")
        assertEquals(Visibility.NONE, success(held.catalog.capabilities()).visibility)
        assertEquals(CatalogResult.NotAuthorised, held.catalog.listStreams(StreamListRequest()))
        assertEquals(CatalogResult.NotAuthorised, held.catalog.publishers("/", "x"))
        assertEquals(CatalogResult.NotAuthorised, held.catalog.consumers("/", "x"))
    }

    @Test
    fun `a wrong password is Failed credentials refused and a wrong CA is Failed certificate not trusted`() {
        assertEquals(OpenResult.Failed(ConnectionFailureReason.CREDENTIALS_REFUSED), open("m203-plain", "not-the-password"))
        assertEquals(OpenResult.Failed(ConnectionFailureReason.CREDENTIALS_REFUSED), open("no-such-user", "x"))
        assertEquals(OpenResult.Failed(ConnectionFailureReason.CERTIFICATE_NOT_TRUSTED), open(ca = wrongCaPath))
    }

    // ---- real StreamOffsets ----

    @Test
    fun `first offsets are a value, empty, or unavailable`() = runBlocking<Unit> {
        val full = unique("off-full-")
        val empty = unique("off-empty-")
        publish(createStream(full), full, 3)
        createStream(empty)
        val held = connection()

        assertEquals(FirstOffset.Value(0), held.offsets.firstOffset("/", full))
        assertEquals(FirstOffset.Empty, held.offsets.firstOffset("/", empty))
        assertEquals(FirstOffset.Unavailable, held.offsets.firstOffset("/", unique("off-missing-")), "a stream that does not exist is unavailable, not empty")
    }

    @Test
    fun `a stream the user may not read is unavailable and the connection stays usable`() = runBlocking<Unit> {
        val denied = unique("off-denied-")
        val allowed = unique("${ALLOWED}off-")
        createStream(denied)
        publish(createStream(allowed), allowed, 2)
        val held = connection("m203-plain", "pw-plain")

        assertEquals(FirstOffset.Unavailable, held.offsets.firstOffset("/", denied))
        assertEquals(FirstOffset.Value(0), held.offsets.firstOffset("/", allowed), "a no-tag user with read permission still gets offsets (M2-03a)")
    }

    @Test
    fun `a stream in another vhost is served by that vhosts own environment, and unavailable without access`() = runBlocking<Unit> {
        val name = unique("off-vh-")
        publish(createStream(name, OTHER_VHOST), name, 2)
        val admin = connection()
        assertEquals(FirstOffset.Value(0), admin.offsets.firstOffset(OTHER_VHOST, name))

        val plain = connection("m203-plain", "pw-plain")
        assertEquals(FirstOffset.Unavailable, plain.offsets.firstOffset(OTHER_VHOST, name), "no permission on that vhost")
    }

    @Test
    fun `reading first offsets writes nothing`() = runBlocking<Unit> {
        val name = unique("off-ro-")
        val e = createStream(name)
        publish(e, name, 5)
        val held = connection()
        awaitUntil("messages == 5", fetch = { success(runBlocking { held.catalog.streamDetail("/", name) }) }, done = { it.messages == 5L })

        repeat(20) { assertEquals(FirstOffset.Value(0), held.offsets.firstOffset("/", name)) }

        // Control (M2-03a style): one real publish must move the counter by exactly one. If any of the 20 reads had
        // written, the counter would read more than 6. The management counter lags, so poll, then read twice.
        publish(e, name, 1)
        val settled = awaitUntil("messages == 6 (5 + the control publish)", fetch = { success(runBlocking { held.catalog.streamDetail("/", name) }) }, done = { (it.messages ?: 0) >= 6L })
        assertEquals(6L, settled.messages, "reads must add nothing; only the control publish counts")
        assertEquals(6L, success(held.catalog.streamDetail("/", name)).messages)
    }

    @Test
    fun `close is idempotent and a closed connection fails its calls instead of hanging`() = runBlocking<Unit> {
        val held = connection()
        held.close()
        held.close()
        assertTrue(held.catalog.listStreams(StreamListRequest()) !is CatalogResult.Success, "a closed holder must not answer from a closed client")
    }

    // ---- stream side TLS (added by the developer at the build, brief part 2 step 2 note 3) ----

    @Test
    fun `the stream client refuses a server certificate the profile CA did not issue, and trusts the one that did`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(rabbit.isRunning, "no broker container")
        val name = unique("tls-")
        publish(createStream(name), name, 1)

        StreamEnvironments(profile(ca = caPath), rabbit.adminPassword, TlsSupport.trustManagerFactory(caPath)).use {
            assertEquals(FirstOffset.Value(0), FirstOffsetMapper.map { it.read("/", name) }, "control: the right CA works on the stream port")
        }
        StreamEnvironments(profile(ca = wrongCaPath), rabbit.adminPassword, TlsSupport.trustManagerFactory(wrongCaPath)).use {
            val refused = runCatching { it.read("/", name) }.exceptionOrNull()
            assertTrue(refused != null, "a wrong CA must stop the stream connection, nothing may be read")
            assertEquals(FirstOffset.Unavailable, FirstOffsetMapper.map { it.read("/", name) })
        }
    }

    // ---- no plugin ----

    @Test
    fun `a broker without rabbitmq_stream_management answers PluginMissing`() = runBlocking<Unit> {
        val bare = newContainer("rabbitmq_stream")
        bare.start()
        try {
            val profile = ConnectionProfile(
                name = "rb-test", host = bare.host, virtualHost = "/", username = bare.adminUsername,
                streamPort = bare.getMappedPort(STREAM_TLS_PORT), managementPort = bare.httpsPort, caCertificatePath = caPath,
            )
            val result = RabbitMqConnectionGateway(Passwords(mapOf("rb-test" to bare.adminPassword))).open(profile)
            assertInstanceOf(OpenResult.Opened::class.java, result, "$result")
            val held = (result as OpenResult.Opened).connection
            try {
                assertEquals(CatalogResult.PluginMissing, held.catalog.publishers("/", "any"))
                assertEquals(CatalogResult.PluginMissing, held.catalog.consumers("/", "any"))
                assertEquals(CatalogResult.Success(Capabilities(PluginStatus.MISSING, Visibility.FULL)), held.catalog.capabilities())
                assertInstanceOf(CatalogResult.Success::class.java, held.catalog.listStreams(StreamListRequest()), "the queue list still works")
            } finally {
                held.close()
            }
        } finally {
            bare.stop()
        }
    }
}
