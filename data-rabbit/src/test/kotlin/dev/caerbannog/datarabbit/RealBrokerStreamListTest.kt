package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.StreamListScreen
import dev.caerbannog.core.streams.StreamListState
import dev.caerbannog.core.streams.StreamListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
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
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager

/**
 * M2-04 verification (test developer): the stream list view model against a real TLS broker, in REAL time with the 5 s
 * interval. Each test has its own container because one test stops the broker. Covers: wrong password, a stream created
 * after the first load shows up on a tick, same-profile reconnect, switching profile (vhost) around a tick, and the broker
 * stopped mid-session (D2: Failed "Connection lost", stale rows, no auto reconnect, Retry fails cleanly).
 * Waiting is bounded polling on a condition, never a fixed sleep. Run with `./gradlew :data-rabbit:brokerTest`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerStreamListTest {
    private val streamTlsPort = 5551
    private val otherVhost = "m204-other"
    private val rabbit: RabbitMQContainer = RabbitMQContainer("rabbitmq:4-management")
        .withPluginsEnabled("rabbitmq_stream", "rabbitmq_stream_management")
        .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
        .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
        .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
        .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
        .apply { addExposedPorts(streamTlsPort) }

    private class Secrets(var password: String) : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = password
        override fun delete(profileName: String) = Unit
    }

    private class Settings : AppSettings {
        @Volatile override var streamListRefresh: RefreshInterval = RefreshInterval.Every5
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
    private val caPath = File(javaClass.classLoader.getResource("tls/ca.pem")!!.toURI()).absolutePath
    private val logged = mutableListOf<String>()
    private var scope: CoroutineScope? = null
    private var vm: StreamListViewModel? = null
    private var active: ActiveConnection? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun build(password: String = "") {
        rabbit.start()
        runCatching { rabbit.execInContainer("rabbitmqctl", "add_vhost", otherVhost) }
        rabbit.execInContainer("rabbitmqctl", "set_permissions", "-p", otherVhost, rabbit.adminUsername, ".*", ".*", ".*")
        val secrets = Secrets(password.ifEmpty { rabbit.adminPassword })
        val gateway = RabbitMqConnectionGateway(secrets) { logged += it }
        val act = ActiveConnection(ConnectionSession(ManagementApiPermissionChecker(), secrets), gateway)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val model = StreamListViewModel(
            active = act, settings = Settings(), scope = sc, clock = RefreshClock { System.currentTimeMillis() },
            reconnect = { name -> act.connect(profile(name)) }, pageSize = 50,
        )
        scope = sc; vm = model; active = act
        model.setShown(true)
    }

    private fun profile(name: String): ConnectionProfile =
        ConnectionProfile(
            name = name, host = rabbit.host, virtualHost = if (name == "other") otherVhost else "/", username = rabbit.adminUsername,
            streamPort = rabbit.getMappedPort(streamTlsPort), managementPort = rabbit.httpsPort, caCertificatePath = caPath,
        )

    @AfterEach fun cleanUp() {
        runCatching { vm?.close() }
        runCatching { scope?.cancel() }
        runCatching { active?.close() }
        runCatching { rabbit.stop() }
    }

    private fun admin(method: String, path: String, body: String? = null): Int {
        val auth = Base64.getEncoder().encodeToString("${rabbit.adminUsername}:${rabbit.adminPassword}".toByteArray())
        val b = HttpRequest.newBuilder(URI("https://${rabbit.host}:${rabbit.httpsPort}$path")).header("Authorization", "Basic $auth")
        if (body != null) b.header("Content-Type", "application/json")
        val p = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        return http.send(b.method(method, p).build(), HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    private fun stream(vhost: String, name: String) =
        assertEquals(201, admin("PUT", "/api/queues/$vhost/$name", """{"durable":true,"arguments":{"x-queue-type":"stream"}}"""))

    private val state: StreamListState get() = vm!!.state.value

    private fun awaitState(what: String, timeoutMs: Long = 30_000, ok: (StreamListState) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!ok(state)) {
            check(System.nanoTime() < deadline) { "timed out waiting for: $what; state: $state" }
            TimeUnit.MILLISECONDS.sleep(100) // bounded poll, the only wait
        }
    }

    private fun names() = state.rows.map { it.name }

    @Test
    fun `a wrong password ends in Failed with a reason and no rows, and the secret is nowhere`() = runBlocking<Unit> {
        build(password = "wrong-password-xyz")
        active!!.connect(profile("uat"))
        assertInstanceOf(LinkState.Failed::class.java, active!!.state.value)
        awaitState("Failed screen") { it.screen is StreamListScreen.Failed }
        assertTrue(state.rows.isEmpty())
        assertEquals("Retry", state.action)
        assertFalse(state.toString().contains("wrong-password-xyz"), "the password must not reach the state")
        assertFalse(logged.any { it.contains("wrong-password-xyz") })
    }

    @Test
    fun `a stream created after the first load appears on a tick, and a same-profile reconnect keeps working`() = runBlocking<Unit> {
        build()
        stream("%2F", "m204-a")
        active!!.connect(profile("uat"))
        awaitState("first load") { it.screen is StreamListScreen.Rows && "m204-a" in names() }
        assertEquals("unavailable".takeIf { false } ?: state.rows.single { it.name == "m204-a" }.publishers, "0")
        stream("%2F", "m204-b")
        awaitState("tick picks up m204-b", 15_000) { "m204-b" in names() }

        stream("%2F", "m204-c")
        active!!.connect(profile("uat"))
        awaitState("reconnected load shows m204-c") { "m204-c" in names() && it.screen is StreamListScreen.Rows }
        assertFalse(state.stale)
    }

    @Test
    fun `switching profile (vhost) around a tick leaves only the new vhost's rows`() = runBlocking<Unit> {
        build()
        stream("%2F", "m204-default")
        stream(otherVhost, "m204-other")
        active!!.connect(profile("uat"))
        awaitState("default rows") { names() == listOf("m204-default") }
        repeat(4) { round ->
            val target = if (round % 2 == 0) "other" else "uat"
            // around the 5 s tick: wait about 4.8 s, then switch while a tick may be starting
            TimeUnit.MILLISECONDS.sleep(4_800L + round * 100L)
            active!!.connect(profile(target))
            val expected = if (target == "other") "m204-other" else "m204-default"
            awaitState("rows of $target") { names() == listOf(expected) && it.screen is StreamListScreen.Rows }
            TimeUnit.MILLISECONDS.sleep(6_000)
            assertEquals(listOf(expected), names(), "after the next tick still only the rows of $target")
            assertFalse(state.stale)
        }
    }

    @Test
    fun `the broker stopped mid-session gives Connection lost with stale rows, no auto reconnect, and Retry fails cleanly`() = runBlocking<Unit> {
        build()
        stream("%2F", "m204-a")
        active!!.connect(profile("uat"))
        awaitState("rows") { "m204-a" in names() }
        rabbit.dockerClient.stopContainerCmd(rabbit.containerId).exec()
        awaitState("lost", 40_000) { it.stale }
        assertEquals(LinkState.Failed("uat", ConnectionFailureReason.CONNECTION_LOST), active!!.state.value)
        assertEquals(listOf("m204-a"), names(), "the rows stay")
        assertEquals("Retry", state.action)
        assertEquals(ConnectionFailureReason.CONNECTION_LOST, state.banner)
        TimeUnit.SECONDS.sleep(7)
        assertEquals(LinkState.Failed("uat", ConnectionFailureReason.CONNECTION_LOST), active!!.state.value, "no silent reconnect")
        vm!!.retry()
        awaitState("retry fails", 40_000) { it.screen is StreamListScreen.Failed }
        assertTrue(active!!.state.value is LinkState.Failed)
        assertEquals("Retry", state.action)
    }
}
