package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.StreamListScreen
import dev.caerbannog.core.streams.StreamListState
import dev.caerbannog.core.streams.StreamListViewModel
import dev.caerbannog.core.streams.StreamRowKind
import dev.caerbannog.core.streams.SuperStreamLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit

/**
 * M2-07 step 2 (@broker, no Docker means skipped): scenario "A super stream on a real broker is grouped" of `06_super_streams.feature`,
 * as a `brokerTest` (the brief puts it in data-rabbit, not in Cucumber). A TLS RabbitMQ 4.x with `rabbitmq_stream` and
 * `rabbitmq_stream_management`; the super stream is declared with `rabbitmq-streams add_super_stream` (the CLI the brief names), which is
 * the shape a real deployment has (marker on a direct exchange, partition streams, bindings with `x-stream-partition-order`).
 * Checks: the real catalog returns the layout in the partition order (also when the routing keys sort differently), a `monitoring` user with
 * narrow permissions sees it (S-A fact), and the stream list view model groups it in REAL time. Names are unique per test and removed in
 * teardown. Waiting is bounded polling on a condition. Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerSuperStreamTest'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerSuperStreamTest {

    companion object {
        private const val STREAM_TLS_PORT = 5551
        private const val MONITOR = "m207-mon"
        private const val MONITOR_PASSWORD = "pw-m207-mon"

        private val rabbit: RabbitMQContainer = RabbitMQContainer("rabbitmq:4-management")
            .withPluginsEnabled("rabbitmq_stream", "rabbitmq_stream_management")
            .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
            .apply { addExposedPorts(STREAM_TLS_PORT) }

        @JvmStatic @BeforeAll fun start() {
            rabbit.start()
            ctl("rabbitmqctl", "add_user", MONITOR, MONITOR_PASSWORD)
            ctl("rabbitmqctl", "set_user_tags", MONITOR, "monitoring")
            ctl("rabbitmqctl", "set_permissions", "-p", "/", MONITOR, "^m207-nothing$", "^m207-nothing$", "^m207-nothing$")
        }

        @JvmStatic @AfterAll fun stop() {
            runCatching { ctl("rabbitmqctl", "delete_user", MONITOR) }
            rabbit.stop()
        }

        private fun ctl(vararg args: String): String {
            val r = rabbit.execInContainer(*args)
            check(r.exitCode == 0) { "${args.toList()} failed: ${r.stderr}" }
            return r.stdout
        }
    }

    private class Secrets(private val passwords: Map<String, String>) : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String? = passwords[profileName]
        override fun delete(profileName: String) = Unit
    }

    private val caPath = File(javaClass.classLoader.getResource("tls/ca.pem")!!.toURI()).absolutePath
    private val superStreams = mutableListOf<String>()
    private val plainStreams = mutableListOf<String>()
    private val held = mutableListOf<HeldConnection>()
    private var scope: CoroutineScope? = null
    private var vm: StreamListViewModel? = null
    private var active: ActiveConnection? = null

    @AfterEach fun cleanUp() {
        runCatching { vm?.close() }
        runCatching { scope?.cancel() }
        runCatching { active?.close() }
        held.forEach { runCatching { it.close() } }
        superStreams.forEach { runCatching { ctl("rabbitmq-streams", "delete_super_stream", it) } }
        plainStreams.forEach { runCatching { ctl("rabbitmq-streams", "delete_stream", it) } }
        held.clear(); superStreams.clear(); plainStreams.clear()
    }

    private fun unique(prefix: String) = "$prefix${UUID.randomUUID().toString().take(8)}"

    private fun addSuperStream(name: String, vararg extra: String) {
        ctl("rabbitmq-streams", "add_super_stream", name, *extra)
        superStreams += name
    }

    private fun profile(user: String) = ConnectionProfile(
        name = user, host = rabbit.host, virtualHost = "/", username = user,
        streamPort = rabbit.getMappedPort(STREAM_TLS_PORT), managementPort = rabbit.httpsPort, caCertificatePath = caPath,
    )

    private fun secrets() = Secrets(mapOf(rabbit.adminUsername to rabbit.adminPassword, MONITOR to MONITOR_PASSWORD))

    private fun connection(user: String): HeldConnection {
        val opened = runBlocking { RabbitMqConnectionGateway(secrets()).open(profile(user)) }
        assertInstanceOf(OpenResult.Opened::class.java, opened, "expected an open connection for $user, got $opened")
        return (opened as OpenResult.Opened).connection.also { held += it }
    }

    private fun layoutOf(connection: HeldConnection, name: String): SuperStreamLayout {
        val result = runBlocking { connection.catalog.superStreams("/") }
        assertInstanceOf(CatalogResult.Success::class.java, result, "expected a layout, got $result")
        @Suppress("UNCHECKED_CAST")
        return (result as CatalogResult.Success<List<SuperStreamLayout>>).value.single { it.name == name }
    }

    private val state: StreamListState get() = vm!!.state.value

    private fun awaitState(what: String, timeoutMs: Long = 40_000, ok: (StreamListState) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!ok(state)) {
            check(System.nanoTime() < deadline) { "timed out waiting for: $what; state: $state" }
            TimeUnit.MILLISECONDS.sleep(100) // bounded poll, the only wait
        }
    }

    private fun startList(user: String = rabbit.adminUsername) {
        val secrets = secrets()
        val act = ActiveConnection(ConnectionSession(ManagementApiPermissionChecker(), secrets), RabbitMqConnectionGateway(secrets))
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val settings = object : AppSettings {
            @Volatile override var streamListRefresh: RefreshInterval = RefreshInterval.Every5
        }
        val model = StreamListViewModel(
            active = act, settings = settings, scope = sc, clock = RefreshClock { System.currentTimeMillis() },
            reconnect = { act.connect(profile(user)) }, pageSize = 50,
        )
        scope = sc; vm = model; active = act
        model.setShown(true)
        runBlocking { act.connect(profile(user)) }
    }

    @Test
    fun `a super stream on a real broker is listed once, as a super stream with 3 partitions, and expands in order`() {
        val orders = unique("m207-orders-")
        addSuperStream(orders, "--partitions", "3")
        val plain = unique("m207-plain-")
        ctl("rabbitmq-streams", "add_stream", plain)
        plainStreams += plain
        startList()

        awaitState("the super stream is listed as one entry") { s -> s.rows.any { it.name == orders } && s.rows.any { it.name == plain } }

        assertInstanceOf(StreamListScreen.Rows::class.java, state.screen)
        val mine = state.rows.filter { it.name.startsWith("m207-") }
        assertEquals(listOf(orders, plain).sorted(), mine.map { it.name }, "partitions are not separate entries")
        val row = mine.single { it.name == orders }
        assertEquals(StreamRowKind.SuperStream, row.kind)
        assertEquals("Super stream", row.type)
        assertEquals(3, row.partitionCount)

        vm!!.toggleExpanded(orders)
        awaitState("the partitions follow") { s -> s.rows.count { it.kind == StreamRowKind.Partition } == 3 }
        val after = state.rows.dropWhile { it.name != orders }
        assertEquals(listOf(orders, "$orders-0", "$orders-1", "$orders-2"), after.take(4).map { it.name })
        assertEquals(listOf(orders, orders, orders), after.drop(1).take(3).map { it.partitionOf })
        assertTrue(state.groupingNotice == null)
    }

    @Test
    fun `the partition order is the order argument, not the order of the bindings the broker lists`() {
        val regions = unique("m207-regions-")
        addSuperStream(regions, "--routing-keys", "us,eu,apac")

        assertEquals(listOf("$regions-us", "$regions-eu", "$regions-apac"), layoutOf(connection(rabbit.adminUsername), regions).partitions)
    }

    @Test
    fun `a monitoring user with narrow permissions sees the grouping`() {
        val orders = unique("m207-orders-")
        addSuperStream(orders, "--partitions", "2")

        val layout = layoutOf(connection(MONITOR), orders)

        assertEquals(listOf("$orders-0", "$orders-1"), layout.partitions)
        assertEquals("/", layout.vhost)
    }
}
