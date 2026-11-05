package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Address
import com.rabbitmq.stream.Consumer
import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.OffsetSpecification
import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.Capabilities
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.ConsumerRow
import dev.caerbannog.core.streams.PluginStatus
import dev.caerbannog.core.streams.PublisherRow
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.core.streams.StreamClientsViewModel
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.streams.StreamListScreen
import dev.caerbannog.core.streams.StreamListViewModel
import dev.caerbannog.core.streams.Visibility
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M2-06a step 2 (@broker, needs Docker): spikes S-A (consumer lag and what "Caught up" may claim, lag ruling of 2026-11-05), S-B (unnamed publisher, row right
 * after attach) and S-C (a second vhost) of docs/tasks/M2-06-publishers-consumers.md, kept as assertions, plus the counts check of
 * scenario "Counts on the tab match counts in the stream list" with the three tag levels (administrator FULL, management OWN, no tag NONE).
 * The tab's view model is exercised against fakes in `core`; here the real catalog answers the same questions the tab asks.
 * Spike output lines start with "SPIKE-" (printed once, for the Status record). Stats lag up to 5 s: bounded polling on the value.
 * Run with `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerClientsTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerClientsTest {

    companion object {
        private const val STREAM_TLS_PORT = 5551
        private const val OTHER_VHOST = "m206-other"
        private val users = mapOf("m206-mgmt" to "pw-mgmt", "m206-plain" to "pw-plain", "m206-mon" to "pw-mon")

        private val rabbit: RabbitMQContainer = RabbitMQContainer("rabbitmq:4-management")
            .withPluginsEnabled("rabbitmq_stream", "rabbitmq_stream_management")
            .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
            .apply { addExposedPorts(STREAM_TLS_PORT) }

        @JvmStatic @BeforeAll fun start() {
            rabbit.start()
            ctl("add_vhost", OTHER_VHOST)
            ctl("set_permissions", "-p", OTHER_VHOST, rabbit.adminUsername, ".*", ".*", ".*")
            for ((u, p) in users) {
                ctl("add_user", u, p)
                ctl("set_permissions", "-p", "/", u, ".*", ".*", ".*")
            }
            ctl("set_user_tags", "m206-mgmt", "management")
            ctl("set_user_tags", "m206-mon", "monitoring")
            ctl("set_user_tags", "m206-plain")
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
    }

    private class Passwords(private val map: Map<String, String>) : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String? = map[profileName]
        override fun delete(profileName: String) = Unit
    }

    private val caPath = File(javaClass.classLoader.getResource("tls/ca.pem")!!.toURI()).absolutePath
    private val connections = mutableListOf<HeldConnection>()
    private val environments = mutableListOf<Environment>()
    private val closeables = mutableListOf<AutoCloseable>()
    private val streams = mutableListOf<Pair<String, String>>() // vhost, name

    @AfterEach fun cleanUp() {
        closeables.reversed().forEach { runCatching { it.close() } }
        connections.forEach { runCatching { it.close() } }
        // the owning environment deletes the stream, then closes
        for ((vhost, name) in streams) runCatching { env(vhost).deleteStream(name) }
        environments.forEach { runCatching { it.close() } }
        closeables.clear(); connections.clear(); environments.clear(); streams.clear()
    }

    private fun unique(prefix: String) = "$prefix${UUID.randomUUID().toString().take(8)}"

    private fun env(vhost: String = "/", user: String = rabbit.adminUsername, password: String = rabbit.adminPassword): Environment {
        val e = Environment.builder().host(rabbit.host).port(rabbit.getMappedPort(STREAM_TLS_PORT))
            .username(user).password(password).virtualHost(vhost)
            .addressResolver { Address(rabbit.host, rabbit.getMappedPort(STREAM_TLS_PORT)) }
            .tls().sslContext(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build())
            .environmentBuilder().build()
        environments += e
        return e
    }

    private fun createStream(name: String, vhost: String = "/"): Environment {
        val e = env(vhost)
        e.streamCreator().stream(name).create()
        streams += vhost to name
        return e
    }

    private fun publish(e: Environment, stream: String, count: Int, reference: String? = null) {
        val latch = CountDownLatch(count)
        val b = e.producerBuilder().stream(stream)
        if (reference != null) b.name(reference)
        val p = b.build()
        closeables += p
        repeat(count) { p.send(p.messageBuilder().addData(ByteArray(10)).build()) { latch.countDown() } }
        assertTrue(latch.await(30, TimeUnit.SECONDS), "confirms for $count messages on $stream not received")
    }

    private enum class Tracking { None, Storing, StoresThreeTimes }

    private fun consume(e: Environment, stream: String, name: String?, tracking: Tracking, expect: Int): Consumer {
        val got = CountDownLatch(expect)
        val seen = java.util.concurrent.atomic.AtomicInteger()
        val b = e.consumerBuilder().stream(stream).offset(OffsetSpecification.first()).messageHandler { ctx, _ ->
            val n = seen.incrementAndGet()
            // an application that stores its offset on its own, three times while it reads: three tracking entries in the stream
            if (tracking == Tracking.StoresThreeTimes && n in setOf(20, 40, 60)) ctx.storeOffset()
            got.countDown()
        }
        if (name != null) b.name(name)
        when (tracking) {
            Tracking.None -> b.noTrackingStrategy()
            Tracking.Storing -> b.autoTrackingStrategy().messageCountBeforeStorage(10).builder()
            Tracking.StoresThreeTimes -> b.manualTrackingStrategy().builder()
        }
        val c = b.build()
        closeables += c
        assertTrue(got.await(30, TimeUnit.SECONDS), "consumer ${name ?: "unnamed"} did not receive $expect messages")
        return c
    }

    private fun connection(user: String = rabbit.adminUsername, password: String = rabbit.adminPassword, vhost: String = "/"): HeldConnection {
        val profile = ConnectionProfile(
            name = "rb-test", host = rabbit.host, virtualHost = vhost, username = user,
            streamPort = rabbit.getMappedPort(STREAM_TLS_PORT), managementPort = rabbit.httpsPort, caCertificatePath = caPath,
        )
        val result = runBlocking { RabbitMqConnectionGateway(Passwords(mapOf("rb-test" to password))).open(profile) }
        assertInstanceOf(OpenResult.Opened::class.java, result, "expected an open connection, got $result")
        return (result as OpenResult.Opened).connection.also { connections += it }
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

    private fun consumersOf(held: HeldConnection, stream: String, vhost: String = "/"): List<ConsumerRow> = success(runBlocking { held.catalog.consumers(vhost, stream) })
    private fun publishersOf(held: HeldConnection, stream: String, vhost: String = "/"): List<PublisherRow> = success(runBlocking { held.catalog.publishers(vhost, stream) })

    // ---- S-A ----

    @Test
    fun `S-A unnamed and non-storing consumers settle at lag 0, every lag is within 0 and stores plus 1, nobody borrows another applications lag`() = runBlocking<Unit> {
        val stream = unique("m206-lag-")
        val e = createStream(stream)
        publish(e, stream, 100, reference = "pub-$stream")
        consume(e, stream, null, Tracking.None, 100)
        consume(e, stream, "plain-$stream", Tracking.None, 100)
        consume(e, stream, "storing-$stream", Tracking.Storing, 100)
        // a second application: its own environment (own connection), its own storing consumer on the same stream
        consume(env(), stream, "other-app-$stream", Tracking.StoresThreeTimes, 100)
        val held = connection()

        // Settled = every row has a connection name and an offset at or beyond the last delivered one (99); sampled for the Status record.
        val samples = mutableListOf<List<ConsumerRow>>()
        val settled = awaitUntil(
            "four consumer rows of $stream with settled offsets",
            fetch = { consumersOf(held, stream).also { samples += it } },
            done = { rows -> rows.size == 4 && rows.all { it.connectionName.isNotEmpty() && it.offset >= 99 } },
        )
        // Stores are asynchronous and the stats lag: give the storing consumers a bounded chance to show a lag above 0 (recorded, not required).
        val later = runCatching {
            awaitUntil("a storing consumer reading lag >= 1", timeoutMs = 20_000, fetch = { consumersOf(held, stream).also { samples += it } }, done = { rows -> rows.size == 4 && rows.any { it.offsetLag >= 1 } })
        }.getOrElse { consumersOf(held, stream) }
        for ((label, rows) in listOf("settled" to settled, "later" to later)) {
            for (r in rows.sortedBy { it.name.orEmpty() }) {
                println("SPIKE-A $label name=${r.name} offset=${r.offset} offset_lag=${r.offsetLag} active=${r.active} consumed=${r.consumed}")
            }
        }
        println("SPIKE-A max lag seen across ${samples.size} polls: ${samples.flatten().maxOfOrNull { it.offsetLag }}")

        // Number of offset stores each consumer did (auto tracking: one per 10 of the 100 messages; the other application: 3).
        val stores = mapOf<String?, Int>(null to 0, "plain-$stream" to 0, "storing-$stream" to 10, "other-app-$stream" to 3)
        // (2) every lag is >= 0 and at most the stores of that consumer plus 1 (loose upper bound; the value varies between runs and is not pinned)
        for (rows in samples + listOf(settled, later)) {
            for (r in rows.filter { it.connectionName.isNotEmpty() }) {
                val limit = stores.getValue(r.name?.takeIf { it.isNotEmpty() }) + 1
                assertTrue(r.offsetLag in 0..limit.toLong(), "consumer '${r.name}' reports offset_lag ${r.offsetLag}, expected 0..$limit (its stores plus 1)")
            }
        }
        // (1) and (3): the unnamed and the named non-storing consumer are exactly 0, although another application stored on the same stream
        for ((label, rows) in listOf("settled" to settled, "later" to later)) {
            for (r in rows.filter { it.name.isNullOrEmpty() || it.name == "plain-$stream" }) {
                assertEquals(0L, r.offsetLag, "$label: consumer '${r.name}' never stores an offset and has read everything, so offset_lag must be 0 (it must not borrow the lag of another application's stores)")
            }
        }
        // No assertion that a storing consumer reads 0 or 1 (lag ruling).
    }

    // ---- S-B ----

    @Test
    fun `S-B an unnamed publisher is a row with no reference, also right after attach`() = runBlocking<Unit> {
        val stream = unique("m206-unnamed-")
        val e = createStream(stream)
        val held = connection()
        val producer = e.producerBuilder().stream(stream).build()
        closeables += producer
        // Right after attach: whatever the broker answers must be a value (a row with an empty connection name, or an empty list), never a failure.
        val first = held.let { runBlocking { it.catalog.publishers("/", stream) } }
        println("SPIKE-B right after attach: $first")
        assertInstanceOf(CatalogResult.Success::class.java, first, "the row of a publisher that just attached must be a value, got $first")
        val latch = CountDownLatch(5)
        repeat(5) { producer.send(producer.messageBuilder().addData(ByteArray(3)).build()) { latch.countDown() } }
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        val rows = awaitUntil("one publisher row with published 5", fetch = { publishersOf(held, stream) }, done = { it.size == 1 && it[0].published == 5L && it[0].connectionName.isNotEmpty() })
        println("SPIKE-B settled: ${rows[0]}")
        // Finding of this spike: the broker sends reference "" for an unnamed producer and the catalog passes it on unchanged (not null).
        assertTrue(rows[0].reference.isNullOrEmpty(), "an unnamed producer has no reference, got '${rows[0].reference}'")
    }

    // ---- S-C ----

    @Test
    fun `S-C publishers and consumers of a stream in another vhost are that vhosts only`() = runBlocking<Unit> {
        val name = unique("m206-vh-")
        val root = createStream(name)
        val other = createStream(name, OTHER_VHOST)
        publish(root, name, 2, reference = "root-pub")
        publish(other, name, 3, reference = "other-pub")
        consume(root, name, "root-cons", Tracking.None, 2)
        consume(other, name, "other-cons", Tracking.None, 3)

        val rootHeld = connection()
        val otherHeld = connection(vhost = OTHER_VHOST)
        awaitUntil("both vhosts show their rows", fetch = { Triple(publishersOf(rootHeld, name), publishersOf(otherHeld, name, OTHER_VHOST), consumersOf(otherHeld, name, OTHER_VHOST)) }, done = { (a, b, c) -> a.isNotEmpty() && b.isNotEmpty() && c.isNotEmpty() })

        assertEquals(listOf("root-pub"), publishersOf(rootHeld, name).map { it.reference })
        assertEquals(listOf("other-pub"), publishersOf(otherHeld, name, OTHER_VHOST).map { it.reference })
        assertEquals(listOf("root-cons"), consumersOf(rootHeld, name).map { it.name })
        assertEquals(listOf("other-cons"), consumersOf(otherHeld, name, OTHER_VHOST).map { it.name })
    }

    // ---- counts (scenario "Counts on the tab match counts in the stream list") ----

    @Test
    fun `an administrator sees 2 publishers and 3 consumers, the list row and the tab data agree`() = runBlocking<Unit> {
        val stream = unique("m206-counts-")
        val e = createStream(stream)
        publish(e, stream, 4, reference = "pub-a-$stream")
        closeables += e.producerBuilder().stream(stream).name("pub-b-$stream").build()
        repeat(3) { i -> consume(e, stream, "cons-$i-$stream", Tracking.None, 4) }
        val held = connection()
        assertEquals(CatalogResult.Success(Capabilities(PluginStatus.PRESENT, Visibility.FULL)), held.catalog.capabilities())

        val tab = awaitUntil(
            "2 publishers and 3 consumers of $stream",
            fetch = { publishersOf(held, stream).size to consumersOf(held, stream).size },
            done = { it == 2 to 3 },
        )
        val listConsumers = awaitUntil(
            "the list row of $stream counts 3 consumers",
            fetch = { success(runBlocking { held.catalog.listStreams(StreamListRequest(vhost = "/", nameFilter = stream)) }).rows.single { it.name == stream }.consumers },
            done = { it == 3 },
        )
        val listPublishers = awaitUntil("publisherCounts of $stream is 2", fetch = { success(runBlocking { held.catalog.publisherCounts("/") })[stream] }, done = { it == 2 })
        assertEquals(tab.first, listPublishers, "publishers: tab vs list column")
        assertEquals(tab.second, listConsumers, "consumers: tab vs list column")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the tab view model on a real broker lists 2 publishers and 3 consumers and agrees with the list row (scenario Counts on the tab match counts in the stream list)`() = runBlocking<Unit> {
        val stream = unique("m206-vm-")
        val e = createStream(stream)
        publish(e, stream, 4, reference = "pub-a-$stream")
        closeables += e.producerBuilder().stream(stream).name("pub-b-$stream").build()
        repeat(3) { i -> consume(e, stream, "cons-$i-$stream", Tracking.None, 4) }

        val secrets = Passwords(mapOf("rb-test" to rabbit.adminPassword))
        val settings = object : AppSettings { @Volatile override var streamListRefresh: RefreshInterval = RefreshInterval.Every5 }
        val active = ActiveConnection(ConnectionSession(ManagementApiPermissionChecker(), secrets), RabbitMqConnectionGateway(secrets))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val clock = RefreshClock { System.currentTimeMillis() }
        val tab = StreamClientsViewModel(active, settings, scope, clock) { }
        val list = StreamListViewModel(active, settings, scope, clock, { }, pageSize = 50)
        try {
            active.connect(
                ConnectionProfile(
                    name = "rb-test", host = rabbit.host, virtualHost = "/", username = rabbit.adminUsername,
                    streamPort = rabbit.getMappedPort(STREAM_TLS_PORT), managementPort = rabbit.httpsPort, caCertificatePath = caPath,
                ),
            )
            tab.show(stream)
            list.setShown(true)
            // settled by value, bounded: both counts, no settling row left (rows with an empty connection name read "unknown")
            val settled = awaitUntil(
                "the tab shows 2 publishers and 3 settled consumers of $stream",
                fetch = { tab.state.value },
                done = { it.screen == StreamClientsScreen.Content && it.publishers.size == 2 && it.consumers.size == 3 && it.publishers.none { p -> p.settling } && it.consumers.none { c -> c.connection == "unknown" } && it.lastOffset == "3" },
            )
            val row = awaitUntil(
                "the list row of $stream shows 2 publishers and 3 consumers",
                fetch = { list.state.value.rows.firstOrNull { it.name == stream } },
                done = { it != null && it.publishers == "2" && it.consumers == "3" },
            )
            assertEquals(row!!.publishers, settled.publishers.size.toString(), "publishers: tab vs list column")
            assertEquals(row.consumers, settled.consumers.size.toString(), "consumers: tab vs list column")
            assertEquals("3", settled.lastOffset, "4 messages from offset 0")
            assertTrue(list.state.value.screen is StreamListScreen.Rows)
            assertEquals(null, settled.ownConnectionsNotice, "an administrator sees every connection")
        } finally {
            runCatching { tab.close() }
            runCatching { list.close() }
            runCatching { scope.cancel() }
            runCatching { active.close() }
        }
    }

    @Test
    fun `a management-tag user sees OWN_CONNECTIONS_ONLY and only the rows of its own connections`() = runBlocking<Unit> {
        val stream = unique("m206-own-")
        val e = createStream(stream)
        publish(e, stream, 3, reference = "admin-pub")
        consume(e, stream, "admin-cons", Tracking.None, 3)
        val own = env(user = "m206-mgmt", password = "pw-mgmt")
        consume(own, stream, "own-cons", Tracking.None, 3)
        val held = connection(user = "m206-mgmt", password = "pw-mgmt")
        assertEquals(CatalogResult.Success(Capabilities(PluginStatus.PRESENT, Visibility.OWN_CONNECTIONS_ONLY)), held.catalog.capabilities())

        val consumers = awaitUntil("the own consumer row", fetch = { consumersOf(held, stream) }, done = { it.isNotEmpty() })
        assertEquals(listOf("own-cons"), consumers.map { it.name }, "only the user's own connection is visible (S3b)")
        assertEquals(emptyList<PublisherRow>(), publishersOf(held, stream), "the admin's publisher is not visible to a management-tag user")
    }

    @Test
    fun `a user without a management tag cannot connect to read statistics`() = runBlocking<Unit> {
        val profile = ConnectionProfile(
            name = "rb-test", host = rabbit.host, virtualHost = "/", username = "m206-plain",
            streamPort = rabbit.getMappedPort(STREAM_TLS_PORT), managementPort = rabbit.httpsPort, caCertificatePath = caPath,
        )
        val result = RabbitMqConnectionGateway(Passwords(mapOf("rb-test" to "pw-plain"))).open(profile)
        // Either the connection opens with visibility NONE (the tab then shows the message) or it is refused; both are acceptable, a crash is not.
        if (result is OpenResult.Opened) {
            connections += result.connection
            assertEquals(CatalogResult.Success(Capabilities(PluginStatus.UNKNOWN, Visibility.NONE)), result.connection.catalog.capabilities())
            assertEquals(CatalogResult.NotAuthorised, result.connection.catalog.publishers("/", "nothing"))
        }
    }
}
