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
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFolderChooser
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.planner.PlannerBanner
import dev.caerbannog.core.planner.PlannerEntry
import dev.caerbannog.core.planner.PlannerViewModel
import dev.caerbannog.core.planner.QueueForm
import dev.caerbannog.datarabbit.FolderMigrationFileRepository
import dev.caerbannog.datarabbit.JsonFileAppSettings
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.planner.PlannerEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-03 step 2: the planner's wiring in the composition root. Same safety as [CompositionRootMigrationsTest]: temp folder, in-memory secrets,
 * fake gateway, no broker, never `~/.caerbannog`, never a dialog. Contract (test developer's proposal, to be confirmed by the architect), all in
 * `desktopApp`:
 *  - `createAppComposition(planDraftFile: Path)` and `AppComposition.planDraftStore` (the real `FilePlanDraftStore` over that file);
 *  - `AppComposition.createPlannerViewModel(inputs: MigrationsInputs, scope: CoroutineScope = newPlannerScope()): PlannerViewModel`, built from the
 *    same inputs as the Migrations view model and the same `migrationFolderPorts`;
 *  - `plannerContextOf(inputs): PlannerContext` (a pure mapping);
 *  - `newPlannerScope(): CoroutineScope`, which runs on `Dispatchers.IO` with parallelism 1, never on the UI dispatcher (follow-up 2 of the M9-02
 *    review: `FilePlanDraftStore` does blocking file I/O under a lock);
 *  - `PlannerEventsAdapter(viewModel): PlannerEvents` (the pattern of `TopologyEventsAdapter`);
 *  - `AppComposition.followPlanner(viewModel, scope): Job`: when the view model reports `saved`, `navigation.planSaved(saved)`; when it reports
 *    `dismissed`, `navigation.closePlanner()`;
 *  - `AppComposition.plannerEntry(): PlannerEntry` (suspend) from the draft store, for the Migrations screen.
 */
class CompositionRootPlannerWiringTest {

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

    /** Records everything it is asked, so "the planner sent nothing" is a fact. */
    private class RecordingExecutor : TopologyMigrationExecutor {
        val sent = ConcurrentLinkedQueue<MigrationAction>()
        override suspend fun execute(action: MigrationAction): ActionResult { sent += action; return ActionResult.Success }
    }

    private val threeX = "This broker runs RabbitMQ 3.x: it is a migration source only"
    private val executor = RecordingExecutor()

    private inner class Gateway : ConnectionGateway {
        override suspend fun open(profile: ConnectionProfile): OpenResult = OpenResult.Opened(object : HeldConnection {
            override val catalog = FakeStreamCatalog()
            override val offsets = FakeStreamOffsets()
            override val topology = FakeTopologyCatalog()
            override val messages = FakeStreamMessageReader()
            override val brokerVersion: String? = if (profile.name == "legacy-eu") "3.13.7" else "4.1.0"
            override val migrationExecutor: TopologyMigrationExecutor? = if (profile.name == "legacy-eu") null else executor
            override val migrationUnavailableReason: String? = if (profile.name == "legacy-eu") threeX else null
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
    private val extraScopes = mutableListOf<CoroutineScope>()
    private val compositions = mutableListOf<AppComposition>()

    private fun input(name: String) = ConnectionProfileInput(name = name, host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret", topologyControlLevel = TopologyControlLevel.FULL_CONTROL)

    private fun compose(): AppComposition =
        createAppComposition(
            File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = Gateway(),
            appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = ui,
            migrationHistoryFile = File(dir, "history.json").toPath(), migrationFolderChooser = Cancelling(),
            planDraftFile = draftFile,
        ).also { c ->
            compositions += c
            listOf("uat-eu", "legacy-eu").forEach { c.viewModel.addConnection(input(it)) }
        }

    private fun AppComposition.connect(name: String) {
        control.connect(name)
        ui.runAll()
    }

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

    private fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { extraScopes += it }

    private fun plannerOf(c: AppComposition, scope: CoroutineScope = scope()): PlannerViewModel =
        c.createPlannerViewModel(c.migrationsInputs(c.viewModel.state.value), scope)

    private fun PlannerViewModel.addQueue(name: String) {
        openWizard(); chooseKind("Queue"); updateForm(QueueForm(name)); next(); addToPlan()
    }

    /** Bounded polling for state that another thread publishes (the IO scope): fails with [what] after five seconds. */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for: $what" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2))
        }
    }

    @org.junit.jupiter.api.AfterEach
    fun cleanUp() {
        extraScopes.forEach { it.cancel() }
        compositions.forEach { it.close() }
    }

    // ---- one folder, one draft ----

    @Test
    fun `the planner uses the same folder store as the Migrations screen, so a saved plan is its next pending migration`() {
        val c = compose().also { it.connect("uat-eu") }
        c.folderWithOneMigration()
        val planner = plannerOf(c)
        planner.addQueue("invoices")
        planner.saveAsMigration()
        assertEquals("0002", planner.state.value.saved?.version, "error: ${planner.state.value.errorMessage}; gate: ${planner.state.value.saveGate}")
        assertTrue(File(dir, "migrations/0002.json").exists())

        val migrations = c.createMigrationsViewModel(c.migrationsInputs(c.viewModel.state.value), scope())
        migrations.loadMigrations()
        migrations.selectVersion("0002")
        val state = migrations.state.value
        assertEquals("0002", state.selectedVersion)
        assertTrue(state.entries.any { it.file.version == "0002" && it.status is MigrationStatus.Pending }, "entries: ${state.entries.map { it.file.version to it.status }}")
        assertEquals(emptyList(), executor.sent.toList(), "saving a plan must not send anything")
    }

    @Test
    fun `the draft is a file of the composition, never in the migrations folder`() {
        val c = compose().also { it.connect("uat-eu") }
        val folder = c.folderWithOneMigration()
        val planner = plannerOf(c)
        planner.addQueue("invoices")
        awaitUntil("a draft file at $draftFile") { java.nio.file.Files.exists(draftFile) }
        assertEquals(setOf("0001.json"), File(folder).list().orEmpty().toSet(), "the draft or something else was written into the migrations folder")
    }

    @Test
    fun `the Migrations screen entry comes from the draft file`() = runBlocking<Unit> {
        val c = compose()
        assertEquals(PlannerEntry.NewPlan, c.plannerEntry())
        c.connect("uat-eu")
        val planner = plannerOf(c)
        planner.addQueue("invoices")
        planner.setDescription("Add invoices")
        planner.flush()
        awaitUntil("the draft file") { java.nio.file.Files.exists(draftFile) && java.nio.file.Files.readString(draftFile).contains("Add invoices") }
        val entry = assertIs<PlannerEntry.ContinuePlan>(c.plannerEntry())
        assertEquals(1, entry.stepCount)
        assertEquals("Add invoices", entry.description)
    }

    @Test
    fun `an unreadable draft file is reported with its path, never overwritten`() = runBlocking<Unit> {
        java.nio.file.Files.createDirectories(draftFile.parent)
        java.nio.file.Files.writeString(draftFile, "{ not a plan")
        val c = compose()
        assertEquals(PlannerEntry.DraftUnreadable(draftFile.toString()), c.plannerEntry())
        assertEquals("{ not a plan", java.nio.file.Files.readString(draftFile))
    }

    // ---- the inputs ----

    @Test
    fun `the planner context is the connection name, virtual host, level and permission`() {
        val c = compose().also { it.connect("uat-eu") }
        val context = plannerContextOf(c.migrationsInputs(c.viewModel.state.value))
        assertEquals("uat-eu", context.connectionName)
        assertEquals("/", context.vhost)
        assertEquals(TopologyControlLevel.FULL_CONTROL, context.controlLevel)
        assertTrue(context.hasConfigurePermission)
        assertNull(context.unavailableReason)
    }

    @Test
    fun `not connected is a state of the planner, not a reason that locks it`() {
        val c = compose()
        val context = plannerContextOf(c.migrationsInputs(c.viewModel.state.value))
        assertNull(context.connectionName)
        assertNull(context.unavailableReason, "not connected must not lock the planner (mockup 12e: plan and save)")
        val planner = plannerOf(c)
        assertIs<PlannerBanner.NotConnected>(planner.state.value.banner)
        assertFalse(planner.state.value.isLocked)
    }

    @Test
    fun `a 3x connection locks the planner with the reason of the gateway and sends the broker nothing`() {
        val c = compose().also { it.connect("legacy-eu") }
        val planner = plannerOf(c)
        val banner = assertIs<PlannerBanner.NotOffered>(planner.state.value.banner)
        assertEquals(threeX, banner.detail)
        assertTrue(planner.state.value.isLocked)
        planner.openWizard()
        assertEquals(dev.caerbannog.core.planner.WizardState.Closed, planner.state.value.wizard)
        assertEquals(emptyList(), executor.sent.toList())
    }

    @Test
    fun `a 3x connection cannot open the planner, the app goes back to Connections`() {
        val c = compose().also { it.connect("uat-eu") }
        c.navigation.open(AppDestination.Migrations)
        c.navigation.openPlanner()
        c.connect("legacy-eu")
        assertEquals(AppDestination.Connections, c.navigation.screen.value)
        assertFalse(c.navigation.plannerOpen.value, "the planner stayed open over a 3.x connection")
        c.navigation.open(AppDestination.Migrations)
        c.navigation.openPlanner()
        ui.runAll() // the fallback of the composition root runs on its scope
        assertFalse(c.navigation.plannerOpen.value, "Migrations cannot be opened on a 3.x connection, so neither can the planner")
        assertEquals(AppDestination.Connections, c.navigation.screen.value)
    }

    // ---- the scope: blocking file I/O never runs on the UI thread ----

    @Test
    fun `the planner scope runs on a worker thread, not on the UI dispatcher`() {
        val s = newPlannerScope().also { extraScopes += it }
        val where = CompletableDeferred<String>()
        s.launch { where.complete(Thread.currentThread().name) }
        val name = runBlocking { kotlinx.coroutines.withTimeout(5_000) { where.await() } }
        assertNotEquals(Thread.currentThread().name, name)
        assertFalse(name.contains("AWT") || name.contains("Swing") || name.contains("main", ignoreCase = true), "ran on a UI-like thread: $name")
        assertTrue(name.startsWith("DefaultDispatcher-worker"), "expected a Dispatchers.IO worker, got $name")
    }

    @Test
    fun `the planner scope runs one thing at a time`() {
        val s = newPlannerScope().also { extraScopes += it }
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val done = AtomicInteger()
        repeat(8) {
            s.launch {
                peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10)
                while (System.nanoTime() < until) Thread.onSpinWait() // blocking work, like file I/O: no suspension point
                running.decrementAndGet()
                done.incrementAndGet()
            }
        }
        awaitUntil("eight tasks") { done.get() == 8 }
        assertEquals(1, peak.get(), "two planner calls ran at the same time")
    }

    @Test
    fun `a failing planner call does not end the scope`() {
        val s = newPlannerScope().also { extraScopes += it }
        val ok = CompletableDeferred<Boolean>()
        s.launch { throw IllegalStateException("one call failed") }
        s.launch { ok.complete(true) }
        assertTrue(runBlocking { kotlinx.coroutines.withTimeout(5_000) { ok.await() } })
    }

    @Test
    fun `the view model of the composition root does not run on the caller's thread`() {
        val c = compose().also { it.connect("uat-eu") }
        c.folderWithOneMigration()
        val planner = c.createPlannerViewModel(c.migrationsInputs(c.viewModel.state.value)) // default scope
        planner.addQueue("invoices")
        awaitUntil("the plan to have its step, published from the IO scope") { planner.state.value.plan.steps.size == 1 }
        planner.saveAsMigration()
        awaitUntil("the save") { planner.state.value.saved != null }
        assertTrue(File(dir, "migrations/0002.json").exists())
    }

    // ---- the adapter and the navigation ----

    @Test
    fun `the adapter maps every event onto the view model`() {
        val c = compose().also { it.connect("uat-eu") }
        c.folderWithOneMigration()
        val planner = plannerOf(c)
        val events: PlannerEvents = PlannerEventsAdapter(planner)
        events.openWizard()
        events.chooseKind("Queue")
        events.updateForm(QueueForm("invoices"))
        events.next()
        events.addToPlan()
        assertEquals(1, planner.state.value.plan.steps.size)
        events.setDescription("Add invoices")
        events.flush()
        events.saveAsMigration()
        assertEquals("0002", planner.state.value.saved?.version)
    }

    @Test
    fun `after a save the planner closes and Migrations is shown with that version waiting`() {
        val c = compose().also { it.connect("uat-eu") }
        c.folderWithOneMigration()
        c.navigation.open(AppDestination.Migrations)
        c.navigation.openPlanner()
        val planner = plannerOf(c)
        val follow: Job = c.followPlanner(planner, scope())
        planner.addQueue("invoices")
        planner.saveAsMigration()
        assertFalse(c.navigation.plannerOpen.value)
        assertEquals(AppDestination.Migrations, c.navigation.screen.value)
        assertEquals("0002", c.navigation.savedPlan.value?.version)
        assertNotNull(c.navigation.savedPlan.value).also { assertTrue(it.message.contains("Nothing was sent to the broker")) }
        follow.cancel()
    }

    @Test
    fun `after a confirmed Dismiss the planner closes and no plan is waiting`() {
        val c = compose().also { it.connect("uat-eu") }
        c.folderWithOneMigration()
        c.navigation.open(AppDestination.Migrations)
        c.navigation.openPlanner()
        val planner = plannerOf(c)
        val follow = c.followPlanner(planner, scope())
        planner.addQueue("invoices")
        planner.dismiss()
        assertTrue(c.navigation.plannerOpen.value, "the question was not asked first")
        planner.confirm()
        assertFalse(c.navigation.plannerOpen.value)
        assertNull(c.navigation.savedPlan.value)
        assertEquals(AppDestination.Migrations, c.navigation.screen.value)
        follow.cancel()
    }

    @Test
    fun `no state of the planner closes it while the user is still working`() {
        val c = compose().also { it.connect("uat-eu") }
        c.folderWithOneMigration()
        c.navigation.open(AppDestination.Migrations)
        c.navigation.openPlanner()
        val planner = plannerOf(c)
        val follow = c.followPlanner(planner, scope())
        planner.addQueue("invoices")
        planner.dismiss()
        planner.cancelQuestion()
        assertTrue(c.navigation.plannerOpen.value)
        follow.cancel()
    }
}
