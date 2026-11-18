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
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationFolderChooser
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.planner.PlanOpeningDecision
import dev.caerbannog.core.planner.PlannerEntry
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

/**
 * M9-12 step 2.5 (RED by compilation until the wiring exists): the composition root for "Edit steps". Same safety as [CompositionRootPlannerWiringTest]: temp
 * folder, in-memory secrets, fake gateway, no broker, never `~/.caerbannog`. Contract, all in `desktopApp`:
 *  - `AppComposition.createPlannerViewModel(inputs, scope = newPlannerScope(), mode: PlannerMode = PlannerMode.NewPlan)`: the same folder ports as the Migrations
 *    screen; in `EditFile` it never touches `planDraftStore`;
 *  - `createMigrationsViewModel` passes `savedProfileNames` = every saved profile of the app's profile list, so `state.planOpening` names a profile on which the
 *    file is applied (the history is one file for the machine);
 *  - `suspend fun AppComposition.copyIntoNewPlan(file: MigrationFile, vhost: String): Boolean`: writes `PlanOpening.planOf(file, vhost)` through the REAL draft
 *    store and, when it was written, opens the planner (`navigation.openPlanner()`); when the draft file cannot be read the store refuses, the copy answers false,
 *    the file stays byte for byte as it was and the planner is not opened.
 */
class CompositionRootPlannerEditFileTest {

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

    private class Cancelling : MigrationFolderChooser {
        override suspend fun choose(startIn: String?): String? = null
    }

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
    private val migrationsDir: File get() = File(dir, "migrations")
    private val scopes = mutableListOf<CoroutineScope>()
    private val compositions = mutableListOf<AppComposition>()

    private fun input(name: String) = ConnectionProfileInput(name = name, host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret", topologyControlLevel = TopologyControlLevel.FULL_CONTROL)

    private fun compose(): AppComposition =
        createAppComposition(
            File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = Gateway(),
            appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = ui,
            migrationHistoryFile = File(dir, "history.json").toPath(), migrationFolderChooser = Cancelling(), planDraftFile = draftFile,
        ).also { c ->
            compositions += c
            listOf("uat-eu", "prod-eu").forEach { c.viewModel.addConnection(input(it)) }
            c.control.connect("uat-eu")
            ui.runAll()
        }

    private val pending = """{ "version": "0005", "description": "Orders", "up": [ { "action": "declareQueue", "name": "orders", "type": "quorum" } ] }"""

    private fun AppComposition.withFolder(): String {
        migrationsDir.mkdirs()
        runBlocking { FolderMigrationFileRepository(migrationsDir.toPath()).importFile(pending) }
        migrationFolderPorts.store.folder = migrationsDir.path
        return migrationsDir.path
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }

    private fun fileOf0005(): MigrationFile = runBlocking { FolderMigrationFileRepository(migrationsDir.toPath()).listFiles() }.single { it.version == "0005" }

    private fun AppComposition.editing(): PlannerViewModel =
        createPlannerViewModel(migrationsInputs(viewModel.state.value), scope(), PlannerMode.EditFile("0005", fileOf0005().checksum))

    private fun PlannerViewModel.addQueue(name: String) {
        openWizard(); chooseKind("Queue"); updateForm(QueueForm(name)); next(); addToPlan()
    }

    private fun writeDraft(text: String) {
        Files.createDirectories(draftFile.parent)
        Files.writeString(draftFile, text)
    }

    @AfterEach
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        compositions.forEach { it.close() }
    }

    @Test
    fun `the Migrations view model refuses a file applied on another saved profile and names it`() {
        val c = compose()
        c.withFolder()
        runBlocking { c.migrationHistory.recordApplied("prod-eu", "0005", "an-old-checksum") }
        val vm = c.createMigrationsViewModel(c.migrationsInputs(c.viewModel.state.value), scope())
        vm.loadMigrations()
        vm.selectVersion("0005")
        val d = assertIs<PlanOpeningDecision.NotOffered>(vm.state.value.planOpening)
        assertEquals("0005 is applied on prod-eu, so it is not edited", d.reason)
        assertTrue(d.canCopy)
    }

    @Test
    fun `a file applied nowhere is openable`() {
        val c = compose()
        c.withFolder()
        val vm = c.createMigrationsViewModel(c.migrationsInputs(c.viewModel.state.value), scope())
        vm.loadMigrations()
        vm.selectVersion("0005")
        assertEquals(PlanOpeningDecision.Openable, vm.state.value.planOpening)
    }

    @Test
    fun `the planner in file mode reads the same folder, writes nothing without a change and leaves the draft file byte for byte`() {
        val c = compose()
        c.withFolder()
        val draft = MigrationParser.exportToJson("draft", "My draft", listOf(MigrationAction.DeclareQueue("draft-q")))
        writeDraft(draft)
        val before = File(migrationsDir, "0005.json").readText()
        val planner = c.editing()
        assertEquals(listOf("Declare quorum queue orders"), planner.state.value.plan.steps.map { it.title })
        assertFalse(planner.state.value.hasChanges)
        planner.saveAsMigration()
        planner.dismiss()
        assertTrue(planner.state.value.dismissed)
        assertEquals(before, File(migrationsDir, "0005.json").readText(), "the file was rewritten without a change")
        assertEquals(draft, Files.readString(draftFile), "the draft file changed")
        assertEquals(setOf("0005.json"), migrationsDir.list().orEmpty().toSet())
    }

    @Test
    fun `a change in file mode saved leaves the draft alone, rewrites the same version and shows it on Migrations`() {
        val c = compose()
        c.withFolder()
        val draft = MigrationParser.exportToJson("draft", "My draft", listOf(MigrationAction.DeclareQueue("draft-q")))
        writeDraft(draft)
        c.navigation.open(AppDestination.Migrations)
        c.navigation.openPlanner()
        val planner = c.editing()
        val follow = c.followPlanner(planner, scope())
        planner.addQueue("invoices")
        planner.saveAsMigration()
        assertEquals("0005", c.navigation.savedPlan.value?.version, "error: ${planner.state.value.errorMessage}")
        assertFalse(c.navigation.plannerOpen.value)
        assertEquals(AppDestination.Migrations, c.navigation.screen.value)
        assertEquals(setOf("0005.json"), migrationsDir.list().orEmpty().toSet(), "another file was written")
        val saved = MigrationParser.parse(File(migrationsDir, "0005.json").readText()) as dev.caerbannog.core.migrations.MigrationValidationResult.Valid
        assertEquals(listOf("orders", "invoices"), saved.migration.up.map { (it as MigrationAction.DeclareQueue).name })
        assertEquals(draft, Files.readString(draftFile), "the draft file changed")
        follow.cancel()
    }

    @Test
    fun `Copy into a new plan writes the steps through the real draft store and opens the planner on that plan`() = runBlocking<Unit> {
        val c = compose()
        c.withFolder()
        c.navigation.open(AppDestination.Migrations)
        val before = File(migrationsDir, "0005.json").readText()
        assertTrue(c.copyIntoNewPlan(fileOf0005(), "/"))
        assertTrue(c.navigation.plannerOpen.value, "the planner was not opened")
        val entry = assertIs<PlannerEntry.ContinuePlan>(c.plannerEntry())
        assertEquals(1, entry.stepCount)
        assertTrue(Files.readString(draftFile).contains("\"orders\""))
        assertEquals(before, File(migrationsDir, "0005.json").readText(), "the copied file changed")
    }

    @Test
    fun `Copy is not started when the draft file cannot be read`() = runBlocking<Unit> {
        val c = compose()
        c.withFolder()
        c.navigation.open(AppDestination.Migrations)
        writeDraft("{ not a plan")
        assertFalse(c.copyIntoNewPlan(fileOf0005(), "/"))
        assertEquals("{ not a plan", Files.readString(draftFile), "the unreadable draft was written over")
        assertFalse(c.navigation.plannerOpen.value, "the planner was opened")
        assertEquals(PlannerEntry.DraftUnreadable(draftFile.toString()), c.plannerEntry())
    }
}
