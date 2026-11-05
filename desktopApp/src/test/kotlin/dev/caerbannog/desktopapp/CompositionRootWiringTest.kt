package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.ConnectionGateway
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.streams.Capabilities
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.ConsumerRow
import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.PublisherRow
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.streams.StreamOffsets
import dev.caerbannog.core.streams.StreamPage
import dev.caerbannog.core.streams.StreamSummary
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * M2-03 part 2, composition root (brief checklist: "wire ActiveConnection(session, realGateway) into desktopApp and the
 * view model, and call close() in onCloseRequest"). Without a window this can pin everything except the one line in
 * `Main.kt` that passes the function below to `Window(onCloseRequest = ...)`; that line is NOT covered here (a UI test
 * of the real window is out of scope; the test engineer checks the line by reading it, see the report).
 * Safety as in CompositionRootTest: never the default keychain store or `~/.caerbannog`; no broker (a fake gateway, or the
 * real gateway aimed at a closed localhost port).
 *
 * Contract, package `dev.caerbannog.desktopapp`:
 *   class AppComposition(val viewModel: ConnectionsViewModel, val active: ActiveConnection) { fun close() }   // close() = active.close()
 *   fun createAppComposition(profilesFile: File, secretStore: SecretStore, permissionChecker: PermissionChecker,
 *       reachabilityChecker: BrokerReachabilityChecker, gateway: ConnectionGateway = RabbitMqConnectionGateway(secretStore)): AppComposition
 *   fun handleCloseRequest(composition: AppComposition, exitApplication: () -> Unit)   // closes first, then exits, always exits
 * `createConnectionsViewModel` keeps working (CompositionRootTest); Main uses the composition and
 * `onCloseRequest = { handleCloseRequest(composition, ::exitApplication) }`.
 */
class CompositionRootWiringTest {

    @TempDir
    lateinit var dir: File

    private val log: MutableList<String> = Collections.synchronizedList(ArrayList())

    private class MapSecrets(private val map: MutableMap<String, String> = mutableMapOf()) : SecretStore {
        override fun store(profileName: String, password: String) { map[profileName] = password }
        override fun retrieve(profileName: String): String? = map[profileName]
        override fun delete(profileName: String) { map.remove(profileName) }
    }

    private object Reachable : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
    }

    private object Permissive : PermissionChecker {
        override suspend fun check(profile: ConnectionProfile, password: String) = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
    }

    private object NoCatalog : StreamCatalog {
        override suspend fun listStreams(request: StreamListRequest): CatalogResult<StreamPage> = CatalogResult.Unexpected
        override suspend fun streamDetail(vhost: String, name: String): CatalogResult<StreamSummary> = CatalogResult.Unexpected
        override suspend fun publishers(vhost: String, stream: String): CatalogResult<List<PublisherRow>> = CatalogResult.Unexpected
        override suspend fun publisherCounts(vhost: String): CatalogResult<Map<String, Int>> = CatalogResult.Unexpected
        override suspend fun consumers(vhost: String, stream: String): CatalogResult<List<ConsumerRow>> = CatalogResult.Unexpected
        override suspend fun superStreams(vhost: String): CatalogResult<List<dev.caerbannog.core.streams.SuperStreamLayout>> = CatalogResult.Unexpected
        override suspend fun capabilities(): CatalogResult<Capabilities> = CatalogResult.Unexpected
    }

    private object NoOffsets : StreamOffsets {
        override suspend fun firstOffset(vhost: String, stream: String) = FirstOffset.Unavailable
    }

    private inner class LoggingGateway : ConnectionGateway {
        override suspend fun open(profile: ConnectionProfile): OpenResult {
            log += "open:${profile.name}"
            return OpenResult.Opened(object : HeldConnection {
                override val catalog = NoCatalog
                override val offsets = NoOffsets
                override val topology = FakeTopologyCatalog()
                override fun close() { log += "close:${profile.name}" }
            })
        }
    }

    private val input = ConnectionProfileInput(name = "uat-eu", host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret")

    private fun compose(gateway: ConnectionGateway, secrets: SecretStore = MapSecrets()) = createAppComposition(
        File(dir, "connections.json"), secrets, permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = gateway,
    ).also { it.viewModel.addConnection(input) }

    @Test
    fun `Connect in the view model reaches the gateway, so ActiveConnection is wired`() = runBlocking<Unit> {
        val composition = compose(LoggingGateway())

        composition.viewModel.connect("uat-eu")

        assertEquals(listOf("open:uat-eu"), log.toList())
        assertEquals(LinkState.Connected("uat-eu"), composition.active.state.value)
    }

    @Test
    fun `the view model and the composition share one ActiveConnection`() = runBlocking<Unit> {
        val composition = compose(LoggingGateway())
        composition.viewModel.connect("uat-eu")
        composition.active.disconnect()
        assertEquals(listOf("open:uat-eu", "close:uat-eu"), log.toList(), "Disconnect on the composition's active connection closes what the view model opened")
    }

    @Test
    fun `closing the composition closes the held connection once`() = runBlocking<Unit> {
        val composition = compose(LoggingGateway())
        composition.viewModel.connect("uat-eu")

        composition.close()
        composition.close()

        assertEquals(listOf("open:uat-eu", "close:uat-eu"), log.toList())
        assertIs<LinkState.Disconnected>(composition.active.state.value)
    }

    @Test
    fun `the close request closes the connection before the application exits`() = runBlocking<Unit> {
        val composition = compose(LoggingGateway())
        composition.viewModel.connect("uat-eu")

        handleCloseRequest(composition) { log += "exit" }

        assertEquals(listOf("open:uat-eu", "close:uat-eu", "exit"), log.toList())
    }

    @Test
    fun `closing the window while a Connect is still running exits and leaves no connection open`() = runBlocking<Unit> {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slow = object : ConnectionGateway {
            override suspend fun open(profile: ConnectionProfile): OpenResult {
                log += "open:${profile.name}"
                started.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "test never released the open" }
                return OpenResult.Opened(object : HeldConnection {
                    override val catalog = NoCatalog
                    override val offsets = NoOffsets
                    override val topology = FakeTopologyCatalog()
                    override fun close() { log += "close:${profile.name}" }
                })
            }
        }
        val composition = compose(slow)
        val connect = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) { composition.viewModel.connect("uat-eu") }
        check(started.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "the open never started" }

        handleCloseRequest(composition) { log += "exit" }
        release.countDown()
        connect.join()

        assertEquals(listOf("open:uat-eu", "exit", "close:uat-eu"), log.toList(), "exit does not wait; what the late open produced is closed")
        assertIs<LinkState.Disconnected>(composition.active.state.value)
    }

    @Test
    fun `the close request still exits when nothing is connected`() {
        val composition = compose(LoggingGateway())

        handleCloseRequest(composition) { log += "exit" }

        assertEquals(listOf("exit"), log.toList())
    }

    @Test
    fun `the default gateway is the real one and reads the password from the injected store, not the keychain`() = runBlocking<Unit> {
        val closed = ServerSocket(0).use { it.localPort }
        val secrets = MapSecrets()
        val composition = createAppComposition(
            File(dir, "connections.json"), secrets, permissionChecker = Permissive, reachabilityChecker = Reachable,
        )
        composition.viewModel.addConnection(input.copy(host = "127.0.0.1", managementPort = closed, streamPort = closed))

        composition.viewModel.connect("uat-eu")

        // The real gateway tried the closed port: a fake would have answered Opened. The password came from `secrets`.
        assertEquals(LinkState.Failed("uat-eu", ConnectionFailureReason.BROKER_NOT_REACHABLE), composition.active.state.value)
        composition.close()
    }

    // --- M2-04 F1: the composition root passes a logger -----------------------------------------------------------

    /**
     * Contract: `createAppComposition(..., log: (String) -> Unit = <a stderr line writer>, gateway = RabbitMqConnectionGateway(secretStore, log))`.
     * Before this, the default gateway got no logger and a failed close of a held connection vanished. What is covered here: the
     * parameter exists, the default gateway still works with it, and nothing a failed Connect writes to it carries the password
     * or the host. What is NOT covered: that a failed close reaches this sink (it needs a held real connection whose close throws;
     * the holder side is pinned by data-rabbit RabbitMqHeldConnectionTest "a throwing close logs the class name only", the
     * wiring by reading CompositionRoot.kt, see the step 2 report).
     */
    @Test
    fun `the composition root takes a logger and a failed connect writes no secret to it`() = runBlocking<Unit> {
        val lines: MutableList<String> = Collections.synchronizedList(ArrayList())
        val closed = ServerSocket(0).use { it.localPort }
        val composition = createAppComposition(
            File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable,
            log = { lines += it },
        )
        composition.viewModel.addConnection(input.copy(host = "127.0.0.1", managementPort = closed, streamPort = closed))

        composition.viewModel.connect("uat-eu")

        assertEquals(LinkState.Failed("uat-eu", ConnectionFailureReason.BROKER_NOT_REACHABLE), composition.active.state.value)
        val text = lines.joinToString("\n")
        assertFalse(text.contains("not-a-real-secret"), "the password is in the log: <$text>")
        composition.close()
    }
}
