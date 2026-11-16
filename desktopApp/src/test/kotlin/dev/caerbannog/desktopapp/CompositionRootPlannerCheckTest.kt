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
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.DeclareCheck
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFolderChooser
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import dev.caerbannog.core.planner.CheckState
import dev.caerbannog.core.planner.PlannerViewModel
import dev.caerbannog.core.planner.QueueForm
import dev.caerbannog.core.planner.StepFinding
import dev.caerbannog.datarabbit.JsonFileAppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
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
 * M9-06 step 2 (red until the wiring exists): the check port reaches the planner from the held connection, and a reconnect to the same profile gives
 * the new port. Same safety as [CompositionRootPlannerWiringTest]: temp folder, in-memory secrets, fake gateway, no broker. The `remember` of the
 * planner window cannot be tested without a window, so its KEY is a plain function with a plain value: contract (the architect confirms):
 *  - `MigrationsInputs.declareCheck: DeclareCheck? = null`, filled by `migrationsInputs` from `StreamSource.declareCheck` (null exactly when the connection
 *    cannot run migrations or is not connected);
 *  - `AppComposition.createPlannerViewModel(inputs)` passes it, and a clock whose `nowMillis` is the system time in epoch milliseconds;
 *  - `fun plannerKeyOf(inputs: MigrationsInputs): PlannerKey`, `data class PlannerKey(val context: PlannerContext, val declareCheck: DeclareCheck?)`, which
 *    `PlannerWindowContent` uses as the key of its `remember` (instead of `context` alone).
 */
class CompositionRootPlannerCheckTest {
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

    private object NoExecutor : TopologyMigrationExecutor {
        override suspend fun execute(action: MigrationAction): ActionResult = ActionResult.Success
    }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runAll() { while (true) (queue.poll() ?: return).run() }
    }

    private val threeX = "This broker runs RabbitMQ 3.x: it is a migration source only"

    /** One port per opened connection, in order: a reconnect gets a new one, so "which port was asked" is a fact. */
    private val opened = mutableListOf<Pair<String, FakeDeclareCheck>>()

    private inner class Gateway : ConnectionGateway {
        override suspend fun open(profile: ConnectionProfile): OpenResult {
            val legacy = profile.name == "legacy-eu"
            val port = FakeDeclareCheck().answerNamed("invoices", BrokerFinding.Absent)
            opened += profile.name to port
            return OpenResult.Opened(object : HeldConnection {
                override val catalog = FakeStreamCatalog()
                override val offsets = FakeStreamOffsets()
                override val topology = FakeTopologyCatalog()
                override val messages = FakeStreamMessageReader()
                override val brokerVersion: String? = if (legacy) "3.13.7" else "4.1.0"
                override val migrationExecutor: TopologyMigrationExecutor? = if (legacy) null else NoExecutor
                override val migrationUnavailableReason: String? = if (legacy) threeX else null
                override val declareCheck: DeclareCheck? = port
                override fun close() = Unit
            })
        }
    }

    private fun portsOf(name: String): List<FakeDeclareCheck> = opened.filter { it.first == name }.map { it.second }

    private val ui = QueueDispatcher()
    private val extraScopes = mutableListOf<CoroutineScope>()
    private val compositions = mutableListOf<AppComposition>()

    private fun input(name: String) = ConnectionProfileInput(
        name = name, host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret", topologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    )

    private fun compose(): AppComposition =
        createAppComposition(
            File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = Gateway(),
            appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = ui,
            migrationHistoryFile = File(dir, "history.json").toPath(), migrationFolderChooser = Cancelling(),
            planDraftFile = dir.toPath().resolve("data").resolve("planner-draft.json"),
        ).also { c ->
            compositions += c
            listOf("uat-eu", "prod-eu", "legacy-eu").forEach { c.viewModel.addConnection(input(it)) }
        }

    private fun AppComposition.connect(name: String) { control.connect(name); ui.runAll() }
    private fun AppComposition.disconnect(name: String) { control.disconnect(name); ui.runAll() }
    private fun AppComposition.inputs() = migrationsInputs(viewModel.state.value)

    private fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { extraScopes += it }

    private fun PlannerViewModel.addQueue(name: String) { openWizard(); chooseKind("Queue"); updateForm(QueueForm(name)); next(); addToPlan() }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for: $what" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2))
        }
    }

    @AfterEach
    fun cleanUp() {
        extraScopes.forEach { it.cancel() }
        compositions.forEach { it.close() }
    }

    // ---- the port reaches the planner ----

    @Test
    fun `the inputs carry the port of the connection that can run migrations`() {
        val c = compose().also { it.connect("uat-eu") }
        assertNotNull(c.inputs().declareCheck, "the planner would have no check port")
    }

    @Test
    fun `a connection that cannot run migrations has no port in the inputs, and not connected has none either`() {
        val c = compose()
        assertNull(c.inputs().declareCheck, "not connected")
        c.connect("legacy-eu")
        assertNull(c.inputs().declareCheck, "a 3.x connection must not hand the planner a check")
    }

    @Test
    fun `the planner of the composition root checks through the port of the held connection, with the system clock`() {
        val c = compose().also { it.connect("uat-eu") }
        c.migrationFolderPorts.store.folder = File(dir, "migrations").also { it.mkdirs() }.path
        val planner = c.createPlannerViewModel(c.inputs()) // the default scope, as the window has it
        planner.addQueue("invoices")
        awaitUntil("the plan to have its step") { planner.state.value.plan.steps.size == 1 }
        awaitUntil("the check to be enabled") { planner.state.value.checkEnabled }
        val before = System.currentTimeMillis()
        planner.check()
        awaitUntil("the check to end") { planner.state.value.check is CheckState.Done }
        val after = System.currentTimeMillis()
        val done = planner.state.value.check as CheckState.Done
        assertEquals("uat-eu", done.connectionName)
        assertEquals(StepFinding.WillBeCreated, done.results.single().finding)
        assertTrue(done.atMillis in before..after, "the time ${done.atMillis} is not the system time in $before..$after")
        assertEquals(1, portsOf("uat-eu").single().calls.size)
    }

    @Test
    fun `a 3x connection gives a planner that cannot check, with the connection's reason, and sends the broker nothing`() {
        val c = compose().also { it.connect("legacy-eu") }
        val planner = c.createPlannerViewModel(c.inputs(), scope())
        assertFalse(planner.state.value.checkEnabled)
        assertEquals(threeX, planner.state.value.checkDisabledReason)
        planner.check()
        assertEquals(CheckState.None, planner.state.value.check)
        assertEquals(emptyList(), portsOf("legacy-eu").single().calls, "a 3.x broker was read")
    }

    @Test
    fun `not connected is a planner with the plain label and no check`() {
        val c = compose()
        val planner = c.createPlannerViewModel(c.inputs(), scope())
        assertEquals("Check", planner.state.value.checkLabel)
        assertFalse(planner.state.value.checkEnabled)
        assertEquals(CheckState.None, planner.state.value.check)
    }

    @Test
    fun `the button names the connection of the inputs`() {
        val c = compose().also { it.connect("prod-eu") }
        assertEquals("Check against prod-eu", c.createPlannerViewModel(c.inputs(), scope()).state.value.checkLabel)
    }

    // ---- the key of the window's remember ----

    @Test
    fun `the key is the same for the same context and port, and differs for another connection`() {
        val c = compose().also { it.connect("uat-eu") }
        val one = c.inputs()
        assertEquals(plannerKeyOf(one), plannerKeyOf(c.inputs()), "an unchanged connection must not rebuild the planner")
        c.connect("prod-eu")
        assertNotEquals(plannerKeyOf(one), plannerKeyOf(c.inputs()))
    }

    @Test
    fun `a reconnect to the same profile changes the key because the port is new`() {
        val c = compose().also { it.connect("uat-eu") }
        val first = plannerKeyOf(c.inputs())
        c.disconnect("uat-eu")
        c.connect("uat-eu")
        assertEquals(2, portsOf("uat-eu").size, "the gateway did not open a second connection")
        val second = plannerKeyOf(c.inputs())
        assertEquals(first.context, second.context, "this test needs the same profile with the same context, so that only the port differs")
        assertNotEquals(first, second, "a window keyed on the context alone keeps the port of the closed connection")
    }

    @Test
    fun `after a reconnect the new planner reads through the new connection only`() {
        val c = compose().also { it.connect("uat-eu") }
        c.disconnect("uat-eu")
        c.connect("uat-eu")
        val (old, new) = portsOf("uat-eu")
        val planner = c.createPlannerViewModel(c.inputs(), scope())
        planner.addQueue("invoices")
        planner.check()
        assertIs<CheckState.Done>(planner.state.value.check)
        assertTrue(new.calls.isNotEmpty())
        assertEquals(emptyList(), old.calls, "the closed connection's port was asked")
    }

    @Test
    fun `a planner built from the old inputs after a reconnect gets Connection closed, never a read`() {
        val c = compose().also { it.connect("uat-eu") }
        val stale = c.inputs()
        c.disconnect("uat-eu")
        c.connect("uat-eu")
        val planner = c.createPlannerViewModel(stale, scope())
        planner.addQueue("invoices")
        planner.check()
        val result = (planner.state.value.check as? CheckState.Done)?.results?.single()
        assertEquals(StepFinding.NotChecked("Connection closed"), result?.finding, "the last defence of the guard")
        assertTrue(portsOf("uat-eu").all { it.calls.isEmpty() }, "a read reached a port that is no longer current")
    }
}
