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
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationFolderChooser
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.planner.PlanOpeningDecision
import dev.caerbannog.core.planner.PlannerMode
import dev.caerbannog.core.planner.PlannerViewModel
import dev.caerbannog.core.planner.QueueForm
import dev.caerbannog.datarabbit.FolderMigrationFileRepository
import dev.caerbannog.datarabbit.JsonFileAppSettings
import dev.caerbannog.ui.appframe.AppDestination
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M9-12 step 4 (test developer): the real folder and the real draft file, files changed by "someone else", and a hash file through the composition root. */
class CompositionRootPlannerEditFileExploratoryTest {
    @TempDir
    lateinit var dir: File

    private class MapSecrets : SecretStore {
        private val map = mutableMapOf<String, String>()
        override fun store(profileName: String, password: String) { map[profileName] = password }
        override fun retrieve(profileName: String): String? = map[profileName]
        override fun delete(profileName: String) { map.remove(profileName) }
    }
    private object Reachable : BrokerReachabilityChecker { override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable }
    private object Permissive : PermissionChecker {
        override suspend fun check(profile: ConnectionProfile, password: String) = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
    }
    private class Cancelling : MigrationFolderChooser { override suspend fun choose(startIn: String?): String? = null }
    private class Gateway : ConnectionGateway {
        override suspend fun open(profile: ConnectionProfile): OpenResult = OpenResult.Opened(object : HeldConnection {
            override val catalog = FakeStreamCatalog()
            override val offsets = FakeStreamOffsets()
            override val topology = FakeTopologyCatalog()
            override val messages = FakeStreamMessageReader()
            override val brokerVersion: String? = "4.1.0"
            override val migrationExecutor: TopologyMigrationExecutor? = object : TopologyMigrationExecutor {
                override suspend fun execute(action: MigrationAction) = dev.caerbannog.core.migrations.ActionResult.Success
            }
            override val migrationUnavailableReason: String? = null
            override fun close() = Unit
        })
    }
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runAll() { while (true) (queue.poll() ?: return).run() }
    }

    private val ui = QueueDispatcher()
    private val draftFile: Path get() = dir.toPath().resolve("data").resolve("planner-draft.json")
    private val folder: File get() = File(dir, "migrations")
    private val scopes = mutableListOf<CoroutineScope>()
    private val compositions = mutableListOf<AppComposition>()

    private fun compose(): AppComposition = createAppComposition(
        File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = Gateway(),
        appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = ui,
        migrationHistoryFile = File(dir, "history.json").toPath(), migrationFolderChooser = Cancelling(), planDraftFile = draftFile,
    ).also { c ->
        compositions += c
        c.viewModel.addConnection(ConnectionProfileInput(name = "uat-eu", host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret", topologyControlLevel = TopologyControlLevel.FULL_CONTROL))
        c.control.connect("uat-eu"); ui.runAll()
        folder.mkdirs()
        c.migrationFolderPorts.store.folder = folder.path
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
    private fun put(raw: String) { val v = (MigrationParser.parse(raw) as dev.caerbannog.core.migrations.MigrationValidationResult.Valid).migration.version; File(folder, "$v.json").writeText(raw) }
    private fun fileOf(v: String): MigrationFile = runBlocking { FolderMigrationFileRepository(folder.toPath()).listFiles() }.single { it.version == v }
    private fun AppComposition.editing(v: String): PlannerViewModel = createPlannerViewModel(migrationsInputs(viewModel.state.value), scope(), PlannerMode.EditFile(v, fileOf(v).checksum))
    private fun AppComposition.migrationsOf(): dev.caerbannog.core.migrations.MigrationsViewModel =
        createMigrationsViewModel(migrationsInputs(viewModel.state.value), scope()).also { it.loadMigrations() }
    private fun PlannerViewModel.addQueue(n: String) { openWizard(); chooseKind("Queue"); updateForm(QueueForm(n)); next(); addToPlan() }

    private val plain = """{ "version": "0005", "description": "Orders", "up": [ { "action": "declareQueue", "name": "orders", "type": "quorum" } ] }"""

    @AfterEach
    fun cleanUp() { scopes.forEach { it.cancel() }; compositions.forEach { it.close() } }

    @Test
    fun `a file someone else rewrote on the real disk is not overwritten`() {
        val c = compose(); put(plain)
        val planner = c.editing("0005")
        planner.addQueue("invoices")
        val theirs = plain.replace("Orders", "Theirs")
        File(folder, "0005.json").writeText(theirs)
        planner.saveAsMigration()
        assertEquals("0005.json changed on disk since it was opened", planner.state.value.errorMessage)
        assertEquals(theirs, File(folder, "0005.json").readText())
        assertEquals(setOf("0005.json"), folder.list().orEmpty().toSet(), "a temp file or another file was left behind")
    }

    @Test
    fun `a file someone else deleted on the real disk is not created again`() {
        val c = compose(); put(plain)
        val planner = c.editing("0005")
        planner.addQueue("invoices")
        File(folder, "0005.json").delete()
        planner.saveAsMigration()
        assertEquals("0005.json changed on disk since it was opened", planner.state.value.errorMessage)
        assertFalse(File(folder, "0005.json").exists())
    }

    @Test
    fun `a file touched by a trailing newline only is not a change on disk and is saved`() {
        val c = compose(); put(plain)
        val planner = c.editing("0005")
        planner.addQueue("invoices")
        File(folder, "0005.json").writeText(plain + "\n")
        planner.saveAsMigration()
        assertEquals("0005", planner.state.value.saved?.version, planner.state.value.errorMessage)
    }

    @Test
    fun `a hash file is refused by the Migrations screen, never copied, and a forced planner on it holds no step and no hash`() {
        val c = compose()
        val raw = MigrationParser.exportToJson("0001", "access", listOf(MigrationAction.DeclareUser("svc", SecretValue.of("SENTINEL-HASH-XYZ"), "rabbit_password_hashing_sha256")))
        put(raw)
        val vm = c.migrationsOf(); vm.selectVersion("0001")
        val d = assertIs<PlanOpeningDecision.NotOffered>(vm.state.value.planOpening)
        assertEquals(false, d.canCopy)
        c.navigation.open(AppDestination.Migrations)
        assertFalse(runBlocking { c.copyIntoNewPlan(fileOf("0001"), "/") })
        assertFalse(Files.exists(draftFile), "a draft was written for a hash file")
        val forced = c.editing("0001")
        assertEquals(emptyList(), forced.state.value.plan.steps)
        assertFalse(forced.state.value.toString().contains("SENTINEL"), "the hash is in the planner state")
    }

    /**
     * FINDING F1 (minor, defence in depth), quarantined for the architect: `PlannerViewModel.readFile` gives an EMPTY plan for a file that holds an access action,
     * but nothing then keeps Save from overwriting that file (its checksum does match). Only reachable by bypassing the Migrations screen, which offers no button
     * for such a file. Expected: Save stays disabled for it (or the planner refuses to open it).
     */
    @org.junit.jupiter.api.Disabled("F1: a forced planner on a hash file can overwrite it; the screen never offers the door")
    @Test
    fun `a forced planner on a hash file cannot overwrite it`() {
        val c = compose()
        val raw = MigrationParser.exportToJson("0001", "access", listOf(MigrationAction.DeclareUser("svc", SecretValue.of("SENTINEL-HASH-XYZ"), "rabbit_password_hashing_sha256")))
        put(raw)
        val forced = c.editing("0001")
        forced.addQueue("x")
        forced.saveAsMigration()
        assertEquals(raw, File(folder, "0001.json").readText(), "a hash file was rewritten")
    }

    @Test
    fun `an applied file is refused and the copy is the only way, and the original is never touched`() {
        val c = compose(); put(plain)
        runBlocking { c.migrationHistory.recordApplied("uat-eu", "0005", fileOf("0005").checksum) }
        val vm = c.migrationsOf(); vm.selectVersion("0005")
        val d = assertIs<PlanOpeningDecision.NotOffered>(vm.state.value.planOpening)
        assertEquals("0005 is applied on uat-eu, so it is not edited", d.reason)
        c.navigation.open(AppDestination.Migrations)
        assertTrue(runBlocking { c.copyIntoNewPlan(fileOf("0005"), "/") })
        assertEquals(plain, File(folder, "0005.json").readText())
    }

    @Test
    fun `an unknown-keys file is refused on the real folder and its bytes stay`() {
        val c = compose()
        val raw = """{ "version": "0007", "description": "d", "up": [ { "action": "declareQueue", "name": "orders", "comment": "keep me" } ] }"""
        put(raw)
        val vm = c.migrationsOf(); vm.selectVersion("0007")
        assertEquals(
            "This file holds entries the planner does not know. Edit it as JSON so nothing is lost.",
            assertIs<PlanOpeningDecision.NotOffered>(vm.state.value.planOpening).reason,
        )
        assertEquals(raw, File(folder, "0007.json").readText())
    }
}
