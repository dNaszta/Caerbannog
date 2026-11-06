package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionGateway
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.quorum
import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.StreamOffsets
import dev.caerbannog.core.topology.TopologyBannerKind
import dev.caerbannog.datarabbit.JsonFileAppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-08b step 4 (test developer): the composition root builds the Topology view model from the SAME held connection and the connections state:
 * the context (profile with its level, permission result) reaches the banners, the data comes from the held connection's `topology`, and
 * `AppComposition.close()` closes it (no broker call after). Safety as in the other wiring tests: temp folder, in-memory secrets, fake gateway.
 */
class CompositionRootTopologyWiringTest {

    @TempDir
    lateinit var dir: File

    private class MapSecrets(private val map: MutableMap<String, String> = mutableMapOf()) : SecretStore {
        override fun store(profileName: String, password: String) { map[profileName] = password }
        override fun retrieve(profileName: String): String? = map[profileName]
        override fun delete(profileName: String) { map.remove(profileName) }
    }

    private object Reachable : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
    }

    private object Locked : PermissionChecker {
        override suspend fun check(profile: ConnectionProfile, password: String) = PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = true)
    }

    private val topology = FakeTopologyCatalog().addQueue(quorum("orders", ready = 7))

    private val gateway = object : ConnectionGateway {
        override suspend fun open(profile: ConnectionProfile): OpenResult = OpenResult.Opened(object : HeldConnection {
            override val catalog = dev.caerbannog.core.connections.fakes.FakeStreamCatalog()
            override val offsets = object : StreamOffsets {
                override suspend fun firstOffset(vhost: String, stream: String) = FirstOffset.Unavailable
            }
            override val topology = this@CompositionRootTopologyWiringTest.topology
            override val messages = FakeStreamMessageReader()
            override fun close() = Unit
        })
    }

    private val input = ConnectionProfileInput(name = "uat-eu", host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret")

    private fun compose() = createAppComposition(
        File(dir, "connections.json"), MapSecrets(), permissionChecker = Locked, reachabilityChecker = Reachable, gateway = gateway,
        appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = Dispatchers.Unconfined,
    ).also { it.viewModel.addConnection(input) }

    @Test
    fun `the topology view model reads the held connection's topology and its banners come from the connections state`() = runBlocking<Unit> {
        val composition = compose()
        try {
            composition.viewModel.connect("uat-eu")
            composition.topology.setShown(true)

            val state = composition.topology.state.value
            assertEquals(listOf("orders"), state.queues.map { it.name }, "screen ${state.screen}")
            assertEquals("7", state.queues.single().ready)
            // A new profile is at the Read-only level (T5) and the check found no configure permission: both reasons, the level first (owner decision O1).
            assertEquals(listOf(TopologyBannerKind.ReadOnlyLevel, TopologyBannerKind.NoConfigure), state.banners.map { it.kind })
        } finally {
            composition.close()
        }
    }

    @Test
    fun `nothing is asked before the screen is shown, and close stops the view model for good`() = runBlocking<Unit> {
        val composition = compose()
        composition.viewModel.connect("uat-eu")
        assertEquals(emptyList(), topology.calls.toList(), "connected but not shown: no topology call (D3)")

        composition.close()
        composition.topology.setShown(true)
        composition.topology.refreshNow()

        assertEquals(emptyList(), topology.calls.toList(), "a closed composition must not call the broker")
        assertTrue(composition.topology.state.value.queues.isEmpty())
    }
}
