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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
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
        private const val UNNAMED_KEY = "<unnamed>"

        /**
         * The catalog observation of S-A and S-A2 (a fixed schedule, not a wait for a state): 12 s = two statistics intervals of 5 s plus margin,
         * sampled every 500 ms (25 samples), so it covers the first statistics window and reaches the steady state.
         */
        private const val OBSERVATION_MS = 12_000L
        private const val OBSERVATION_EVERY_MS = 500L

        /** The key of a consumer in the lag comparison: its name, and one fixed key for an unnamed one (absent, null or empty on either side). */
        private fun lagKey(name: String?) = name?.takeIf { it.isNotEmpty() } ?: UNNAMED_KEY
        private val users = mapOf("m206-mgmt" to "pw-mgmt", "m206-plain" to "pw-plain", "m206-mon" to "pw-mon")

        private val rabbit: RabbitMQContainer = brokerWithPlugins("rabbitmq:4-management", "rabbitmq_stream", "rabbitmq_stream_management")
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

    /**
     * D2: a screen that saw the broker unreachable keeps its rows, marked stale with a banner, and polls no more until the user reconnects, so
     * waiting on is hopeless. Fail at once and say so, instead of timing out after the bound with a value that never changes.
     */
    private fun failIfLinkLost(screen: String, banner: String?, state: Any) {
        check(banner == null) { "$screen reported the link lost ('$banner') while the test waited; it polls no more (D2). State: $state" }
    }

    private fun <T> success(result: CatalogResult<T>): T {
        assertInstanceOf(CatalogResult.Success::class.java, result, "expected a value, got $result")
        @Suppress("UNCHECKED_CAST")
        return (result as CatalogResult.Success<T>).value
    }

    private fun consumersOf(held: HeldConnection, stream: String, vhost: String = "/"): List<ConsumerRow> = success(runBlocking { held.catalog.consumers(vhost, stream) })
    private fun publishersOf(held: HeldConnection, stream: String, vhost: String = "/"): List<PublisherRow> = success(runBlocking { held.catalog.publishers(vhost, stream) })

    // ---- S-A ----

    /** One consumer as the lag comparison sees it: `Long?` with no default, so a missing field is a difference and not a quiet 0. */
    private data class Lag(val offset: Long?, val offsetLag: Long?) {
        override fun toString() = "offset=$offset offset_lag=$offsetLag"
    }

    /** One catalog read and one raw read of the same endpoint, both keyed by consumer name. [toString] is what `awaitUntil` prints on a time-out. */
    private class LagReads(val catalog: Map<String, Lag>, val raw: Map<String, Lag>) {
        val agree get() = catalog == raw
        override fun toString() = "catalog $catalog, raw $raw"
    }

    /**
     * The consumers endpoint read a second way (S-A pass-through): a second `ManagementHttp` of its own, GET only, TLS, parsed with kotlinx.serialization
     * straight from the body and NOT through `ManagementJson`, so that the comparison with the catalog can see what the mapper adds or takes away.
     */
    private class RawConsumerReader(private val http: ManagementHttp, private val requests: ManagementRequests) {
        fun read(stream: String): Map<String, Lag> {
            val reply = runBlocking { http.get(requests.consumers("/")) }
            assertInstanceOf(ManagementReply.Response::class.java, reply, "the raw consumers read expected a response, got $reply")
            val response = reply as ManagementReply.Response
            assertEquals(200, response.status, "the raw consumers read of $stream expected status 200, got ${response.status}")
            val rows = Json.parseToJsonElement(response.body).jsonArray.map { it.jsonObject }
                .filter { ((it["queue"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull == stream }
            // absent, JSON null and empty are one key; contentOrNull because `.content` of a JSON null reads the text "null"
            val byName = rows.associate { row ->
                val name = ((row["properties"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
                lagKey(name) to Lag((row["offset"] as? JsonPrimitive)?.longOrNull, (row["offset_lag"] as? JsonPrimitive)?.longOrNull)
            }
            check(byName.size == rows.size) { "two consumers of $stream share a name in the raw rows, the comparison by name would collapse them: $rows" }
            return byName
        }
    }

    private fun rawConsumerReader(): RawConsumerReader {
        val transport = BrokerTransport.Tls(TlsSupport.trustManagerFactory(caPath))
        val http = ManagementHttp(TlsSupport.buildHttpClient(transport, 15_000), rabbit.adminUsername, rabbit.adminPassword)
        closeables += http
        return RawConsumerReader(http, ManagementRequests("https://${rabbit.host}:${rabbit.httpsPort}", transport))
    }

    /**
     * S-A pass-through: waits by value until a catalog read and a raw read of the consumers endpoint agree on `offset` and `offset_lag` of every
     * consumer. The two GETs can straddle a statistics refresh (`offset` flips from 100 to 99 there), so one disagreement is not yet a finding;
     * a mapper that adds, subtracts or invents a lag never agrees and times out with both maps printed. The catalog reads join [samples].
     */
    private fun awaitCatalogAgreesWithRaw(what: String, held: HeldConnection, raw: RawConsumerReader, stream: String, samples: MutableList<List<ConsumerRow>>): LagReads =
        awaitUntil(
            "the catalog and the raw read agree on offset and offset_lag of every consumer of $stream ($what)",
            fetch = {
                val rows = consumersOf(held, stream).also { samples += it }
                val catalog = rows.associate { lagKey(it.name) to Lag(it.offset, it.offsetLag) }
                check(catalog.size == rows.size) { "two consumers of $stream share a name in the catalog rows, the comparison by name would collapse them: $rows" }
                LagReads(catalog.toSortedMap(), raw.read(stream).toSortedMap())
            },
            done = { it.agree },
        )

    @Test
    fun `S-A the lag is the brokers number, within 0 and the stores of the whole stream plus 1 where offsets are stored, and the catalog adds and subtracts nothing`() = runBlocking<Unit> {
        val stream = unique("m206-lag-")
        val e = createStream(stream)
        publish(e, stream, 100, reference = "pub-$stream")
        consume(e, stream, null, Tracking.None, 100)
        consume(e, stream, "plain-$stream", Tracking.None, 100)
        consume(e, stream, "storing-$stream", Tracking.Storing, 100)
        // a second application: its own environment (own connection), its own storing consumer on the same stream
        consume(env(), stream, "other-app-$stream", Tracking.StoresThreeTimes, 100)
        val held = connection()
        val raw = rawConsumerReader()

        // Settled = every row has a connection name and an offset at or beyond the last delivered one (99, true in the first statistics window
        // where the broker reads 100 and in the steady state where it reads 99); sampled for the Status record.
        val samples = mutableListOf<List<ConsumerRow>>()
        val settled = awaitUntil(
            "four consumer rows of $stream with settled offsets",
            fetch = { consumersOf(held, stream).also { samples += it } },
            done = { rows -> rows.size == 4 && rows.all { it.connectionName.isNotEmpty() && it.offset >= 99 } },
        )
        // Pass-through, first time: what the tab reads is what the broker sends (nothing added, nothing taken away, no invented 0).
        val settledReads = awaitCatalogAgreesWithRaw("settled rows", held, raw, stream, samples)
        for (r in settled.sortedBy { it.name.orEmpty() }) {
            println("SPIKE-A settled name=${r.name} offset=${r.offset} offset_lag=${r.offsetLag} active=${r.active} consumed=${r.consumed}")
        }
        println("SPIKE-A settled raw rows (read with the agreement check): ${settledReads.raw}")

        // Stores are asynchronous and the stats lag: give the consumers a bounded chance to show a lag above 0 (recorded, not required). The wait ends
        // as soon as one row reads 1 or more, which can already be the first window (the other application's consumer reads 1 there) or only the
        // steady state (every consumer reads the same number there).
        val later = runCatching {
            awaitUntil("a consumer reading lag >= 1", timeoutMs = 20_000, fetch = { consumersOf(held, stream).also { samples += it } }, done = { rows -> rows.size == 4 && rows.any { it.offsetLag >= 1 } })
        }.getOrElse { consumersOf(held, stream) }
        for (r in later.sortedBy { it.name.orEmpty() }) {
            println("SPIKE-A later name=${r.name} offset=${r.offset} offset_lag=${r.offsetLag} active=${r.active} consumed=${r.consumed}")
        }
        // Pass-through, second time: by now a raw row has a lag of 1 or more (unless the wait ran out), so a mapper that returned 0, subtracted or
        // added to the lag would not agree. Not guaranteed to be the steady state, so it is not called that.
        val laterReads = awaitCatalogAgreesWithRaw("rows after the bounded wait for a lag of 1 or more", held, raw, stream, samples)
        println("SPIKE-A later raw rows (raw offset 100 = first statistics window, 99 = steady state): ${laterReads.raw}")

        // The steady state, observed: the wait above can end inside the first statistics window (the other application's consumer reads lag 1 or more
        // there at once), so on its own it need not reach the second statistics refresh. Observe the catalog on a fixed schedule for two statistics
        // intervals and keep every sample for the stream-wide bound below. A bounded observation, not a wait for a state; no per-sample pass-through,
        // because two GETs can straddle a refresh (offset and lag flip there) and a wait for agreement per sample would not be an observation.
        // Every sample is printed first and asserted after, so the print shows the whole run when one sample fails: four rows, none vanished.
        val observation = observe(OBSERVATION_MS, OBSERVATION_EVERY_MS) { consumersOf(held, stream) }
        for ((atMs, rows) in observation) {
            println("SPIKE-A +${atMs}ms ${rows.sortedBy { it.name.orEmpty() }.joinToString { "${lagKey(it.name)}=${it.offset}/${it.offsetLag}" }}")
            samples += rows
        }
        for ((atMs, rows) in observation) {
            assertEquals(4, rows.size, "+${atMs}ms: expected the four consumers of $stream, got $rows")
        }
        // Pass-through, third time: two statistics intervals have passed, so this is the steady-state check; it is sharp because the broker sends the
        // same number for every consumer there. Its bounded wait handles a straddle of a refresh (the raw reader sat idle for the observation; the
        // retry of a dropped GET and the short keep-alive cover that). Nothing is asserted about the value (99 and the lag are the broker's), and no
        // non-zero value is required.
        val steadyReads = awaitCatalogAgreesWithRaw("rows after the ${OBSERVATION_MS / 1000} s observation", held, raw, stream, samples)
        println("SPIKE-A raw rows after the observation (raw offset 99 = steady state): ${steadyReads.raw}")
        println("SPIKE-A max lag seen across ${samples.size} reads: ${samples.flatten().maxOfOrNull { it.offsetLag }}")

        // Number of offset stores each application did (auto tracking: one per 10 of the 100 messages; the other application: 3). Every one of them is
        // an entry in the stream, so the broker counts them after the last message for EVERY consumer, also for one that never stores (steady state).
        val stores = mapOf<String?, Int>(null to 0, "plain-$stream" to 0, "storing-$stream" to 10, "other-app-$stream" to 3)
        val totalStreamStores = stores.values.sum()
        // The one bound the broker keeps: at least 0 and at most the stores of the whole stream plus 1 (the ruling's slack), for every row of every
        // sample. It does not say who stored: the unnamed and the plain consumer are inside the bound too, and no row is required to read 0 or more than 0.
        val limit = totalStreamStores + 1L
        for (rows in samples + listOf(settled, later)) {
            for (r in rows) {
                assertTrue(r.offsetLag in 0..limit, "consumer '${r.name}' reports offset_lag ${r.offsetLag}, expected 0..$limit: every application's stored offsets on the stream count ($totalStreamStores stores in all) for every consumer, not only for the one that stored them")
            }
        }
    }

    @Test
    fun `S-A2 a stream without stored offsets reads lag 0 for every consumer, in the first statistics window and in the steady state`() = runBlocking<Unit> {
        val stream = unique("m206-nolag-")
        val e = createStream(stream)
        publish(e, stream, 100, reference = "pub-$stream")
        // Nobody stores an offset on this stream: noTrackingStrategy for both, so the stream holds the 100 messages and no tracking entry.
        consume(e, stream, null, Tracking.None, 100)
        consume(e, stream, "named-$stream", Tracking.None, 100)
        val held = connection()

        val settled = awaitUntil(
            "two consumer rows of $stream with settled offsets",
            fetch = { consumersOf(held, stream) },
            done = { rows -> rows.size == 2 && rows.all { it.connectionName.isNotEmpty() && it.offset >= 99 } },
        )
        println("SPIKE-A2 settled ${settled.sortedBy { it.name.orEmpty() }.joinToString { "name=${it.name} offset=${it.offset} offset_lag=${it.offsetLag}" }}")

        // The observation covers the first window and the steady state. Every sample is asserted: two rows (a vanished row must not pass as
        // "nothing is behind") and lag 0 for both, which is what makes "Caught up" reachable on a real broker.
        val samples = observe(OBSERVATION_MS, OBSERVATION_EVERY_MS) { consumersOf(held, stream) }
        for ((atMs, rows) in samples) {
            println("SPIKE-A2 +${atMs}ms ${rows.sortedBy { it.name.orEmpty() }.joinToString { "${lagKey(it.name)}=${it.offset}/${it.offsetLag}" }}")
        }
        for ((atMs, rows) in samples) {
            assertEquals(2, rows.size, "+${atMs}ms: expected the unnamed and the named consumer of $stream, got $rows")
            for (r in rows) {
                assertEquals(0L, r.offsetLag, "+${atMs}ms: consumer '${r.name}' has read everything on a stream where nobody stored an offset, so offset_lag must be 0 (offset ${r.offset}); rows: $rows")
            }
        }
    }

    /**
     * An observation, not a wait for a state: reads [fetch] on a fixed schedule (every [everyMs], for [durationMs] from the first read) and
     * returns every sample with the milliseconds since the first read, so the caller asserts on all of them and a late deviation is not missed.
     * The sleep is inside this bounded helper only; a failing [fetch] ends the observation.
     */
    private fun <T> observe(durationMs: Long, everyMs: Long, fetch: () -> T): List<Pair<Long, T>> {
        val start = System.nanoTime()
        val samples = mutableListOf<Pair<Long, T>>()
        var n = 0L
        while (n * everyMs <= durationMs) {
            val wait = start + TimeUnit.MILLISECONDS.toNanos(n * everyMs) - System.nanoTime()
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait)
            samples += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) to fetch()
            n++
        }
        return samples
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
            // One screen at a time, as in the app (a hidden screen calls nothing): the tab first, then the list. Both polling at once puts two
            // concurrent callers on the shared management client, and the broker closes their idle connections together after 5 s (see
            // ManagementHttpClosedConnectionTest, finding of 2026-11-14): a lost link, and this test timed out in CI. The assertions are unchanged.
            tab.show(stream)
            // Settled by value, bounded: both counts, no settling row left (rows with an empty connection name read "unknown"). A fresh client shows
            // such rows until the broker's statistics catch up (interval 5 s, measured 0.8 to 4.7 s in the spec) and the tab's next tick (5 s) reads
            // them: about 10 s at worst, so the 40 s of awaitUntil is four times that, for a loaded runner. A longer bound would not help a lost link.
            val settled = awaitUntil(
                "the tab shows 2 publishers and 3 settled consumers of $stream",
                fetch = { tab.state.value.also { failIfLinkLost("the tab", it.banner, it) } },
                done = { it.screen == StreamClientsScreen.Content && it.publishers.size == 2 && it.consumers.size == 3 && it.publishers.none { p -> p.settling } && it.consumers.none { c -> c.connection == "unknown" } && it.lastOffset == "3" },
            )
            tab.hide()
            list.setShown(true)
            val row = awaitUntil(
                "the list row of $stream shows 2 publishers and 3 consumers",
                fetch = { list.state.value.also { failIfLinkLost("the list", it.banner, it) }.rows.firstOrNull { it.name == stream } },
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
