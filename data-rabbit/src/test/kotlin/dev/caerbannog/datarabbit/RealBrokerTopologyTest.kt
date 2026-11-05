package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyCatalog
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
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
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager

/**
 * M2-08a step 2 (@broker, no Docker means skipped): the REAL [ManagementTopologyCatalog] over TLS against a RabbitMQ 4.x with `rabbitmq_stream` and
 * `rabbitmq_stream_management`. It confirms the facts the spikes measured on the dev broker (brief M2-08, S-A to S-C, fixtures `fixtures/m2-08/`):
 * quorum and classic queue fields, `policy` in the list, streams counted but not listed, paging past 500 queues, the default exchange and its
 * implicit bindings, a binding to an exchange, what a `monitoring`, a `management` and a no-tag user see, a vhost without permission is NotFound,
 * and that reading the topology leaves the messages of a queue exactly as they were. Names are unique, everything is deleted in teardown, waiting is
 * bounded polling on a condition (queue statistics lag a few seconds). Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerTopologyTest'`.
 * A real offline member is NOT tested (S-D: one node cannot produce it).
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerTopologyTest {

    companion object {
        private const val STREAM_TLS_PORT = 5551
        private const val SECRET_VHOST = "m208-secret"
        private const val MANY_VHOST = "m208-many"
        private val users = mapOf("m208-mon" to ("pw-mon" to "monitoring"), "m208-mgmt" to ("pw-mgmt" to "management"), "m208-plain" to ("pw-plain" to ""))

        private val rabbit: RabbitMQContainer = RabbitMQContainer("rabbitmq:4-management")
            .withPluginsEnabled("rabbitmq_stream", "rabbitmq_stream_management")
            .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
            .apply { addExposedPorts(STREAM_TLS_PORT) }

        @JvmStatic @BeforeAll fun start() {
            rabbit.start()
            ctl("add_vhost", SECRET_VHOST)
            ctl("set_permissions", "-p", SECRET_VHOST, rabbit.adminUsername, ".*", ".*", ".*")
            ctl("add_vhost", MANY_VHOST)
            ctl("set_permissions", "-p", MANY_VHOST, rabbit.adminUsername, ".*", ".*", ".*")
            for ((u, p) in users) {
                ctl("add_user", u, p.first)
                // Narrow permissions on purpose: S-C measured that they hide nothing from a user with a management tag.
                ctl("set_permissions", "-p", "/", u, "^m208-nothing$", "^m208-nothing$", "^m208-nothing$")
                if (p.second.isNotEmpty()) ctl("set_user_tags", u, p.second)
            }
        }

        @JvmStatic @AfterAll fun stop() {
            runCatching { for (u in users.keys) ctl("delete_user", u) }
            runCatching { ctl("delete_vhost", SECRET_VHOST) }
            runCatching { ctl("delete_vhost", MANY_VHOST) }
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

    private val caPath = File(javaClass.classLoader.getResource("tls/ca.pem")!!.toURI()).absolutePath
    private val queues = mutableListOf<String>()
    private val exchanges = mutableListOf<String>()
    private val policies = mutableListOf<String>()
    private val clients = mutableListOf<ManagementHttp>()

    @AfterEach fun cleanUp() {
        clients.forEach { runCatching { it.close() } }
        queues.forEach { runCatching { admin("DELETE", "/api/queues/%2F/$it") } }
        exchanges.forEach { runCatching { admin("DELETE", "/api/exchanges/%2F/$it") } }
        policies.forEach { runCatching { admin("DELETE", "/api/policies/%2F/$it") } }
        clients.clear(); queues.clear(); exchanges.clear(); policies.clear()
    }

    private fun unique(prefix: String) = "$prefix${UUID.randomUUID().toString().take(8)}"

    private fun admin(method: String, path: String, body: String? = null): Pair<Int, String> {
        val auth = Base64.getEncoder().encodeToString("${rabbit.adminUsername}:${rabbit.adminPassword}".toByteArray())
        val b = HttpRequest.newBuilder(URI("https://${rabbit.host}:${rabbit.httpsPort}$path")).header("Authorization", "Basic $auth")
        if (body != null) b.header("Content-Type", "application/json")
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val response = http.send(b.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    private fun declare(name: String, type: String, vhost: String = "%2F") {
        assertEquals(201, admin("PUT", "/api/queues/$vhost/$name", """{"durable":true,"arguments":{"x-queue-type":"$type"}}""").first, "declare $type $name")
        if (vhost == "%2F") queues += name
    }

    private fun publish(queue: String, count: Int) = repeat(count) {
        assertEquals(200, admin("POST", "/api/exchanges/%2F/amq.default/publish", """{"properties":{},"routing_key":"$queue","payload":"m208 $it","payload_encoding":"string"}""").first)
    }

    private fun catalogFor(user: String, password: String): TopologyCatalog {
        val transport = BrokerTransport.Tls(TlsSupport.trustManagerFactory(caPath))
        val client = ManagementHttp(TlsSupport.buildHttpClient(transport, 15_000), user, password).also { clients += it }
        return ManagementTopologyCatalog(client, ManagementRequests("https://${rabbit.host}:${rabbit.httpsPort}", transport))
    }

    private fun admin(): TopologyCatalog = catalogFor(rabbit.adminUsername, rabbit.adminPassword)

    private fun allQueues(catalog: TopologyCatalog, vhost: String = "/"): List<QueueRow> = runBlocking {
        val first = catalog.queues(vhost, 1)
        assertInstanceOf(CatalogResult.Success::class.java, first, "expected a page, got $first")
        val head = (first as CatalogResult.Success<QueuePage>).value
        head.rows + (2..head.pageCount).flatMap { p -> ((catalog.queues(vhost, p) as CatalogResult.Success<QueuePage>).value.rows) }
    }

    private fun awaitQueue(catalog: TopologyCatalog, name: String, what: String, ok: (QueueRow) -> Boolean): QueueRow {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (true) {
            val row = allQueues(catalog).firstOrNull { it.name == name }
            if (row != null && ok(row)) return row
            check(System.nanoTime() < deadline) { "timed out waiting for: $what; row: $row" }
            TimeUnit.MILLISECONDS.sleep(500) // bounded poll: the broker's statistics lag a few seconds
        }
    }

    // --- S-A: the fields ---

    @Test
    fun `a quorum queue with messages and a policy carries the recorded fields`() {
        val q = unique("m208-q-")
        val policy = unique("m208-pol-")
        declare(q, "quorum")
        assertEquals(201, admin("PUT", "/api/policies/%2F/$policy", """{"pattern":"^$q$","apply-to":"queues","definition":{"max-length":1000},"priority":5}""").first)
        policies += policy
        publish(q, 3)

        val row = awaitQueue(admin(), q, "3 ready messages and the policy name") { it.readyMessages == 3L && it.policy == policy }

        assertEquals("quorum", row.type)
        assertTrue(row.isQuorum)
        assertEquals("/", row.vhost)
        assertEquals(0L, row.unackedMessages)
        assertEquals(0, row.consumers)
        assertEquals("running", row.state)
        assertEquals(1, row.members.size, "a single node has one member")
        assertEquals(row.members, row.online)
        assertEquals(row.members.single(), row.leader)
        assertFalse(row.hasOfflineMember)
    }

    @Test
    fun `a classic queue has counts but no leader, members or online list`() {
        val q = unique("m208-c-")
        declare(q, "classic")
        publish(q, 2)

        val row = awaitQueue(admin(), q, "2 ready messages") { it.readyMessages == 2L }

        assertEquals("classic", row.type)
        assertNull(row.leader)
        assertEquals(emptyList<String>(), row.members)
        assertEquals(emptyList<String>(), row.online)
        assertNull(row.policy)
        assertFalse(row.hasOfflineMember)
    }

    @Test
    fun `queueDetail returns the same queue with its policy and NotFound for a missing one`() {
        val q = unique("m208-d-")
        declare(q, "quorum")
        val catalog = admin()

        val detail = runBlocking { catalog.queueDetail("/", q) }

        assertInstanceOf(CatalogResult.Success::class.java, detail, "got $detail")
        assertEquals(q, (detail as CatalogResult.Success<QueueRow>).value.name)
        assertEquals(CatalogResult.NotFound, runBlocking { catalog.queueDetail("/", unique("m208-missing-")) })
    }

    @Test
    fun `a stream is counted by the broker but is never a row, and its detail is NotFound`() {
        val s = unique("m208-s-")
        declare(s, "stream")
        val catalog = admin()

        val page = (runBlocking { catalog.queues("/", 1) } as CatalogResult.Success<QueuePage>).value

        assertFalse(page.rows.any { it.name == s }, "a stream is no queue here")
        assertTrue(page.totalCount > page.rows.size, "the stream is in the broker's total")
        assertEquals(CatalogResult.NotFound, runBlocking { catalog.queueDetail("/", s) })
    }

    // --- S-B: paging ---

    @Test
    fun `a vhost with 520 queues is two pages of 500 and 20 and every queue is found once`() {
        repeat(520) { declare("m208-many-%04d".format(it), "classic", vhost = MANY_VHOST) }
        val catalog = admin()

        val first = (runBlocking { catalog.queues(MANY_VHOST, 1) } as CatalogResult.Success<QueuePage>).value
        val second = (runBlocking { catalog.queues(MANY_VHOST, 2) } as CatalogResult.Success<QueuePage>).value

        assertEquals(listOf(500, 20), listOf(first.rows.size, second.rows.size))
        assertEquals(2, first.pageCount)
        assertEquals(500, first.pageSize)
        assertEquals(520, first.totalCount)
        assertEquals(520, (first.rows + second.rows).map { it.name }.toSet().size)
        assertEquals("m208-many-0000", first.rows.first().name)
        assertEquals(CatalogResult.BadPaging, runBlocking { catalog.queues(MANY_VHOST, 3) })
        assertEquals(CatalogResult.BadPaging, runBlocking { catalog.queues(MANY_VHOST, 0) })
    }

    // --- exchanges and bindings ---

    @Test
    fun `the default exchange and its implicit binding are listed, a declared binding and an exchange to exchange binding are shown as such`() {
        val q = unique("m208-b-")
        val topic = unique("m208-ex-")
        val fanout = unique("m208-fx-")
        declare(q, "quorum")
        assertEquals(201, admin("PUT", "/api/exchanges/%2F/$topic", """{"type":"topic","durable":true,"arguments":{}}""").first)
        exchanges += topic
        assertEquals(201, admin("PUT", "/api/exchanges/%2F/$fanout", """{"type":"fanout","durable":true,"arguments":{}}""").first)
        exchanges += fanout
        assertEquals(201, admin("POST", "/api/bindings/%2F/e/$topic/q/$q", """{"routing_key":"orders.#"}""").first)
        assertEquals(201, admin("POST", "/api/bindings/%2F/e/$topic/e/$fanout", """{"routing_key":"all"}""").first)
        val catalog = admin()

        val exchangeRows = (runBlocking { catalog.exchanges("/") } as CatalogResult.Success<List<ExchangeRow>>).value
        val bindingRows = (runBlocking { catalog.bindings("/") } as CatalogResult.Success<List<BindingRow>>).value

        assertTrue(exchangeRows.single { it.isDefault }.type == "direct")
        assertEquals("topic", exchangeRows.single { it.name == topic }.type)
        assertTrue(bindingRows.any { it.source.isEmpty() && it.destination == q && it.routingKey == q }, "the implicit default exchange binding")
        assertEquals(BindingRow(topic, "/", q, BindingDestinationType.QUEUE, "orders.#"), bindingRows.single { it.source == topic && it.destination == q })
        assertEquals(BindingDestinationType.EXCHANGE, bindingRows.single { it.source == topic && it.destination == fanout }.destinationType)
    }

    // --- S-C: who sees what ---

    @Test
    fun `a monitoring and a management user with narrow permissions see every queue, exchange and binding of the vhost`() {
        val q = unique("m208-v-")
        declare(q, "quorum")
        for ((user, secret) in listOf("m208-mon" to "pw-mon", "m208-mgmt" to "pw-mgmt")) {
            val catalog = catalogFor(user, secret)
            assertTrue(allQueues(catalog).any { it.name == q }, "$user sees the queue although its permissions match nothing")
            assertInstanceOf(CatalogResult.Success::class.java, runBlocking { catalog.exchanges("/") }, user)
            assertInstanceOf(CatalogResult.Success::class.java, runBlocking { catalog.bindings("/") }, user)
            assertInstanceOf(CatalogResult.Success::class.java, runBlocking { catalog.queueDetail("/", q) }, user)
        }
    }

    @Test
    fun `a user without a management tag gets NotAuthorised on every call`() {
        val catalog = catalogFor("m208-plain", "pw-plain")
        runBlocking {
            assertEquals(CatalogResult.NotAuthorised, catalog.queues("/", 1))
            assertEquals(CatalogResult.NotAuthorised, catalog.exchanges("/"))
            assertEquals(CatalogResult.NotAuthorised, catalog.bindings("/"))
            assertEquals(CatalogResult.NotAuthorised, catalog.queueDetail("/", "any"))
        }
    }

    @Test
    fun `a vhost the user has no permission on is NotFound, not NotAuthorised`() {
        val catalog = catalogFor("m208-mon", "pw-mon")
        runBlocking {
            assertEquals(CatalogResult.NotFound, catalog.queues(SECRET_VHOST, 1))
            assertEquals(CatalogResult.NotFound, catalog.exchanges(SECRET_VHOST))
            assertEquals(CatalogResult.NotFound, catalog.bindings(SECRET_VHOST))
        }
    }

    @Test
    fun `wrong password is NotAuthorised`() {
        assertEquals(CatalogResult.NotAuthorised, runBlocking { catalogFor("m208-mon", "wrong").queues("/", 1) })
    }

    // --- the safety rule ---

    @Test
    fun `reading the whole topology leaves the messages of a queue exactly as they were`() {
        val q = unique("m208-safe-")
        declare(q, "quorum")
        publish(q, 5)
        val catalog = admin()
        awaitQueue(catalog, q, "5 ready messages") { it.readyMessages == 5L }

        repeat(3) {
            allQueues(catalog)
            runBlocking {
                catalog.exchanges("/")
                catalog.bindings("/")
                catalog.queueDetail("/", q)
            }
        }

        val after = (runBlocking { catalog.queueDetail("/", q) } as CatalogResult.Success<QueueRow>).value
        assertEquals(5L, after.readyMessages, "nothing was read, got or purged")
        assertEquals(0L, after.unackedMessages)
        val body = admin("GET", "/api/queues/%2F/$q").second
        assertTrue(body.contains("\"messages_ready\":5"), "the broker's own count: $body")
    }
}
