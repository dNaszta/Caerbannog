package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationFolderChooser
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.datarabbit.FolderMigrationFileRepository
import dev.caerbannog.datarabbit.JsonFileAppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M5-08c1 scope 8: the Migrations screen's wiring in the composition root. Same safety as the other wiring tests: temp folder, in-memory
 * secrets, the fake gateway of core-testing, no broker, never `~/.caerbannog`, never the keychain, never a dialog (a scripted chooser).
 * The window (Main.kt) builds its view model from these two functions and rebuilds it when `MigrationsInputs` changes.
 */
class CompositionRootMigrationsTest {

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

    private class Permissions(var unlocked: Boolean = true) : PermissionChecker {
        override suspend fun check(profile: ConnectionProfile, password: String) =
            PermissionCheckResult.Checked(topologyUnlocked = unlocked, publishingEnabled = true)
    }

    private class Cancelling : MigrationFolderChooser {
        override suspend fun choose(startIn: String?): String? = null
    }

    private class CountingExecutor : TopologyMigrationExecutor {
        val sent = mutableListOf<MigrationAction>()
        override suspend fun execute(action: MigrationAction): ActionResult { sent += action; return ActionResult.Success }
    }

    private val gateway = FakeConnectionGateway()
    private val permissions = Permissions()
    private val historyFile: Path get() = dir.toPath().resolve("data").resolve("migration-history.json")
    private val settingsFile get() = File(dir, "settings.json")
    private val settings by lazy { JsonFileAppSettings(settingsFile) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun input(name: String = "uat-eu", vhost: String = "/", level: TopologyControlLevel? = TopologyControlLevel.FULL_CONTROL) = ConnectionProfileInput(
        name = name, host = "localhost", virtualHost = vhost, username = "svc", password = "not-a-real-secret", topologyControlLevel = level,
    )

    private fun compose(input: ConnectionProfileInput = input()): AppComposition =
        createAppComposition(
            File(dir, "connections.json"), MapSecrets(), permissionChecker = permissions, reachabilityChecker = Reachable, gateway = gateway,
            appSettings = settings, uiDispatcher = Dispatchers.Unconfined,
            migrationHistoryFile = historyFile, migrationFolderChooser = Cancelling(),
        ).also { it.viewModel.addConnection(input) }

    private fun AppComposition.connected(name: String = "uat-eu"): AppComposition {
        runBlocking { viewModel.connect(name) }
        return this
    }

    private fun AppComposition.inputs() = migrationsInputs(viewModel.state.value)

    /** A real migrations folder with one pending migration "0001" that declares the queue "orders". */
    private fun AppComposition.folderWithOneMigration(): String {
        val folder = File(dir, "migrations").also { it.mkdirs() }.path
        runBlocking {
            FolderMigrationFileRepository(Path.of(folder)).importFile(
                """{ "version": "0001", "description": "orders", "up": [ { "action": "declareQueue", "name": "orders", "type": "quorum" } ] }""",
            )
        }
        migrationFolderPorts.store.folder = folder
        return folder
    }

    // ---- one history, shared with the Connections screen ------------------------------------------------------------------------------

    @Test
    fun `the history repository is the very instance the screen and the connections view model share, so a rename and a delete reach it`() = runBlocking<Unit> {
        val composition = compose()
        try {
            assertSame(composition.migrationHistory, composition.migrationFolderPorts.history, "the screen must read the repository of the composition root")
            composition.migrationHistory.recordApplied("uat-eu", "0001", "abc")

            val renamed = composition.viewModel.updateConnection("uat-eu", input(name = "uat-eu-2"))
            assertIs<SaveConnectionResult.Saved>(renamed)
            assertEquals(mapOf("0001" to "abc"), composition.migrationHistory.getAppliedMigrations("uat-eu-2"), "a rename moves the history")
            assertEquals(emptyMap(), composition.migrationHistory.getAppliedMigrations("uat-eu"))

            composition.viewModel.requestDelete("uat-eu-2")
            composition.viewModel.confirmDelete()
            assertEquals(emptyMap(), composition.migrationHistory.getAppliedMigrations("uat-eu-2"), "a deleted profile does not leave its statuses behind")
            assertTrue(historyFile.toFile().exists(), "the history lives in the injected file")
            assertFalse(historyFile.toFile().readText().contains("0001"), "the file no longer holds the deleted profile's version")
        } finally {
            composition.close()
        }
    }

    @Test
    fun `a profile created again under a deleted name does not inherit the old statuses`() = runBlocking<Unit> {
        val composition = compose()
        try {
            composition.migrationHistory.recordApplied("uat-eu", "0001", "abc")
            composition.viewModel.requestDelete("uat-eu")
            composition.viewModel.confirmDelete()

            composition.viewModel.addConnection(input())

            assertEquals(emptyMap(), composition.migrationHistory.getAppliedMigrations("uat-eu"))
        } finally {
            composition.close()
        }
    }

    // ---- the folder store ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `the folder store is the app settings file`() {
        val composition = compose()
        try {
            assertSame(settings, composition.migrationFolderPorts.store, "the remembered folder lives in settings.json")

            composition.migrationFolderPorts.store.folder = "/work/migrations"

            assertTrue(settingsFile.readText().contains("/work/migrations"), "the choice must reach the file: ${settingsFile.readText()}")
        } finally {
            composition.close()
        }
    }

    // ---- the connection's own level, vhost, permission -----------------------------------------------------------------------------------

    @Test
    fun `the level is the profile's, a Read-only profile gives a screen with apply disabled`() {
        val composition = compose(input(level = TopologyControlLevel.READ_ONLY)).connected()
        try {
            composition.folderWithOneMigration()

            val inputs = composition.inputs()
            val state = composition.createMigrationsViewModel(inputs, scope).state.value

            assertEquals(TopologyControlLevel.READ_ONLY, inputs.controlLevel)
            assertEquals(TopologyControlLevel.READ_ONLY, state.controlLevel)
            assertFalse(state.isApplyPendingEnabled, "a Read-only connection must not apply")
            assertEquals("Apply is disabled: this connection is set to Read-only", state.bannerMessage)
        } finally {
            composition.close()
        }
    }

    @Test
    fun `a Full control profile with the permission can apply, and Apply reaches the connection's executor`() = runBlocking<Unit> {
        val executor = CountingExecutor().also { gateway.setMigrationExecutor("uat-eu", it) }
        val composition = compose().connected()
        try {
            composition.folderWithOneMigration()
            val vm = composition.createMigrationsViewModel(composition.inputs(), scope)
            assertTrue(vm.state.value.isApplyPendingEnabled, "banner: ${vm.state.value.bannerMessage}")

            val result = vm.applyPending()

            assertIs<MigrationApplyResult.Success>(result)
            assertEquals(listOf("orders"), executor.sent.map { (it as MigrationAction.DeclareQueue).name })
            assertEquals(setOf("0001"), composition.migrationHistory.getAppliedMigrations("uat-eu").keys, "the apply is recorded in the shared history")
        } finally {
            composition.close()
        }
    }

    @Test
    fun `the vhost and the permission are the connection's`() {
        permissions.unlocked = false
        val composition = compose(input(vhost = "orders-vhost")).connected()
        try {
            val inputs = composition.inputs()
            val state = composition.createMigrationsViewModel(inputs, scope).state.value

            assertEquals("uat-eu", inputs.connectionName)
            assertEquals("orders-vhost", inputs.vhost)
            assertFalse(inputs.hasConfigurePermission, "the permission check found no configure permission")
            assertEquals("orders-vhost", state.vhost)
            assertFalse(state.hasConfigurePermission)
            assertFalse(state.isApplyPendingEnabled)
        } finally {
            composition.close()
        }
    }

    @Test
    fun `the permission of a connection that may configure is passed as it is`() {
        val composition = compose().connected()
        try {
            val inputs = composition.inputs()

            assertTrue(inputs.hasConfigurePermission)
            assertEquals(TopologyControlLevel.FULL_CONTROL, inputs.controlLevel)
        } finally {
            composition.close()
        }
    }

    // ---- a connection without an executor -----------------------------------------------------------------------------------------------

    @Test
    fun `a null executor blocks apply with the connection's reason, and nothing reaches a broker`() = runBlocking<Unit> {
        val reason = "This broker runs RabbitMQ 3.x: it is a migration source only"
        gateway.setMigrationUnavailable("uat-eu", reason)
        val composition = compose().connected()
        try {
            composition.folderWithOneMigration()
            val inputs = composition.inputs()
            assertNull(inputs.executor)
            assertEquals(reason, inputs.unavailableReason)

            val vm = composition.createMigrationsViewModel(inputs, scope)
            val state = vm.state.value

            assertEquals(reason, state.unavailableReason)
            assertEquals("Migrations are not offered on this connection: $reason", state.bannerMessage)
            assertFalse(state.isApplyPendingEnabled)
            assertFalse(state.isApplySingleEnabled)
            assertIs<MigrationApplyResult.Blocked>(vm.applyPending())
            assertEquals(emptyMap(), composition.migrationHistory.getAppliedMigrations("uat-eu"), "nothing is recorded")
            assertTrue(state.entries.isNotEmpty() && state.isImportEnabled && state.isExportEnabled, "files, import and export keep working")
        } finally {
            composition.close()
        }
    }

    @Test
    fun `no connection at all also blocks apply, with a reason, never with an executor that pretends`() = runBlocking<Unit> {
        val composition = compose()
        try {
            composition.folderWithOneMigration()
            val inputs = composition.inputs()
            assertNull(inputs.executor)
            assertTrue(!inputs.unavailableReason.isNullOrBlank(), "a missing executor always has a reason")

            val vm = composition.createMigrationsViewModel(inputs, scope)

            assertFalse(vm.state.value.isApplyPendingEnabled)
            assertIs<MigrationApplyResult.Blocked>(vm.applyPending())
            assertNotNull(vm.state.value.bannerMessage)
        } finally {
            composition.close()
        }
    }

    // ---- when the view model is rebuilt ---------------------------------------------------------------------------------------------------

    @Test
    fun `the inputs are equal while nothing changes and differ when the vhost, the permission or the level changes`() {
        val composition = compose().connected()
        try {
            val first = composition.inputs()
            assertEquals(first, composition.inputs(), "nothing changed: the window must keep its view model")

            composition.viewModel.setTopologyControlLevel("uat-eu", TopologyControlLevel.READ_ONLY)
            val afterLevel = composition.inputs()
            assertNotEquals(first, afterLevel, "a level change must rebuild the view model")
            assertEquals(TopologyControlLevel.READ_ONLY, afterLevel.controlLevel)

            permissions.unlocked = false
            runBlocking { composition.viewModel.checkConnectionAndPermissions("uat-eu") }
            val afterPermission = composition.inputs()
            assertNotEquals(afterLevel, afterPermission, "a permission change must rebuild the view model")
            assertFalse(afterPermission.hasConfigurePermission)
        } finally {
            composition.close()
        }
    }

    @Test
    fun `the inputs differ for another virtual host and for another connection`() {
        val composition = compose().connected()
        try {
            val first = composition.inputs()
            composition.viewModel.addConnection(input(name = "prod-eu", vhost = "billing"))
            runBlocking { composition.viewModel.connect("prod-eu") }

            val second = composition.inputs()

            assertEquals("prod-eu", second.connectionName)
            assertEquals("billing", second.vhost)
            assertNotEquals(first, second)
        } finally {
            composition.close()
        }
    }

    // ---- no hard-coded level in the wiring ----------------------------------------------------------------------------------------------------

    @Test
    fun `no FULL_CONTROL literal and no in-memory repository remain in the main sources of the desktop app`() {
        val sources = File("src/main/kotlin/dev/caerbannog/desktopapp").listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue(sources.isNotEmpty(), "the source folder was not found from ${File(".").absolutePath}")

        val offenders = sources.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) ->
                !line.trimStart().startsWith("//") &&
                    ("FULL_CONTROL" in line || "InMemoryMigrationFileRepository" in line || "InMemoryMigrationHistoryRepository" in line)
            }.map { "${file.name}:${it.index + 1}: ${it.value.trim()}" }
        }

        assertEquals(emptyList(), offenders, "the screen must use the profile's level and the folder and history repositories")
    }
}
