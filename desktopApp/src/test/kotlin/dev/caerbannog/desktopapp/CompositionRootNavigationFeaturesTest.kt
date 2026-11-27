package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionFeature
import dev.caerbannog.core.connections.ConnectionGateway
import dev.caerbannog.core.connections.ConnectionNotes
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.datarabbit.JsonFileAppSettings
import dev.caerbannog.ui.appframe.AppDestination
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals

/**
 * M8-02a (owner decision 2026-11-10): the composition root passes `availableFeatures(permission result of the current connection)` and the
 * reason to the app frame, and a destination that becomes disabled falls back to Connections.
 * Contract (test developer's proposal, stub in `CompositionRoot.kt`): `AppComposition.navigationFeatures: StateFlow<NavigationFeatures>` with
 * `NavigationFeatures(features: Set<ConnectionFeature>, reason: String?)`; the fallback runs on the composition's scope (the queue
 * dispatcher below, `runAll()`). Same safety as [CompositionRootNavigationTest]: temp folder, in-memory secrets, fake gateway, no broker.
 */
class CompositionRootNavigationFeaturesTest {

    @TempDir
    lateinit var dir: File

    private class MapSecrets : SecretStore {
        private val map = mutableMapOf<String, String>()
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

    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runAll() {
            while (true) (queue.poll() ?: return).run()
        }
    }

    /** Every open succeeds; the version of the broker behind each profile name is [versions] (the overview answer). */
    private class VersionedGateway(val versions: Map<String, String>) : ConnectionGateway {
        override suspend fun open(profile: ConnectionProfile): OpenResult = OpenResult.Opened(object : HeldConnection {
            override val catalog = FakeStreamCatalog()
            override val offsets = FakeStreamOffsets()
            override val topology = FakeTopologyCatalog()
            override val messages = FakeStreamMessageReader()
            override val brokerVersion: String? = versions[profile.name]
            override fun close() = Unit
        })
    }

    private val queue = QueueDispatcher()
    private val gateway = VersionedGateway(mapOf("legacy-eu" to "3.13.7", "new-eu" to "4.1.0"))

    private fun compose() = createAppComposition(
        File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = gateway,
        appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = queue,
        migrationHistoryFile = File(dir, "history.json").toPath(),
    ).also { composition ->
        for (name in gateway.versions.keys) {
            composition.viewModel.addConnection(ConnectionProfileInput(name = name, host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret"))
        }
    }

    private val all = ConnectionFeature.entries.toSet()
    private val reason = ConnectionNotes.MIGRATION_SOURCE_PILL

    private fun connect(composition: AppComposition, name: String) {
        composition.control.connect(name)
        queue.runAll()
    }

    @Test
    fun `before any connection every feature is on and there is no reason`() {
        val c = compose()
        try {
            assertEquals(NavigationFeatures(all, null), c.navigationFeatures.value)
        } finally { c.close() }
    }

    @Test
    fun `a 4x connection offers all eight features and no reason`() {
        val c = compose()
        try {
            connect(c, "new-eu")
            assertEquals(8, all.size)
            assertEquals(NavigationFeatures(all, null), c.navigationFeatures.value)
        } finally { c.close() }
    }

    @Test
    fun `a 3x connection offers only Migrate from 3x and the pill text as the reason`() {
        val c = compose()
        try {
            connect(c, "legacy-eu")
            assertEquals(NavigationFeatures(setOf(ConnectionFeature.MigrateFromThreeX), reason), c.navigationFeatures.value)
            assertEquals("RabbitMQ 3.x: migration source only", c.navigationFeatures.value.reason)
        } finally { c.close() }
    }

    @Test
    fun `the chip of a connected 3x broker shows the migration source pill and its label`() {
        val c = compose()
        try {
            connect(c, "legacy-eu")
            val chip = c.control.state.value.chip
            assertEquals("RabbitMQ 3.x: migration source only", chip.pill?.text)
            assertEquals(dev.caerbannog.core.connections.PillTone.WARNING, chip.pill?.tone)
        } finally { c.close() }
    }

    @Test
    fun `disconnecting or going back to a 4x connection turns every feature on again`() {
        val c = compose()
        try {
            connect(c, "legacy-eu")
            connect(c, "new-eu")
            assertEquals(NavigationFeatures(all, null), c.navigationFeatures.value)
        } finally { c.close() }
    }

    @Test
    fun `connecting a 3x broker while on Streams shows Connections`() {
        val c = compose()
        try {
            connect(c, "new-eu")
            c.navigation.open(AppDestination.Streams)
            connect(c, "legacy-eu")
            assertEquals(AppDestination.Connections, c.navigation.screen.value)
        } finally { c.close() }
    }

    @Test
    fun `the same fallback applies from Topology`() = fallbackFrom(AppDestination.Topology)

    @Test
    fun `the same fallback applies from Migrations`() = fallbackFrom(AppDestination.Migrations)

    // One composition per test (own folder and secrets): profiles saved in connections.json have no password in a fresh secret store.
    private fun fallbackFrom(destination: AppDestination) {
        val c = compose()
        try {
            connect(c, "new-eu")
            c.navigation.open(destination)
            connect(c, "legacy-eu")
            assertEquals(AppDestination.Connections, c.navigation.screen.value, "from $destination")
        } finally { c.close() }
    }

    @Test
    fun `a destination the 3x connection still offers is kept`() {
        val c = compose()
        try {
            connect(c, "new-eu")
            c.navigation.open(AppDestination.MigrateFromThree)
            connect(c, "legacy-eu")
            assertEquals(AppDestination.MigrateFromThree, c.navigation.screen.value)
        } finally { c.close() }
    }
}
