package dev.caerbannog.acceptance.planner

import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.planner.DraftLoad
import dev.caerbannog.core.planner.PlannerBanner
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.WizardState
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M9-03 steps for the `@m9-03` scenarios of `19_topology_planner.feature` and the outline of `09_topology_queues.feature`. They look at the REAL
 * planner and Topology screens (Compose UI test API, [PlannerScreens]) next to the view model state: a text or a disabled button is checked on
 * the screen, so the screen cannot drift from the state. The Migrations screen after a save is the existing one, as the window selects the
 * saved version (`AppNavigation.planSaved`, its own test in `desktopApp`). The 3.x connection is the REAL gateway against a local stub that
 * counts requests, as in `M508aExecutorSteps`.
 */
class PlannerScreenSteps {
    private val d get() = PlannerWorld.driver
    private val screen get() = PlannerScreens.forViewModel(d.vm)

    /** The Background of feature 09 names no virtual host; the planner's own Background names one (`PlannerSteps`). */
    @Given("I am connected to {string}")
    fun connectedTo(profile: String) { d.profile = profile; d.restart() }

    // ---- The planner is opened from the Migrations screen ----

    @Then("the planner opens with an empty plan and the text {string}")
    fun opensEmpty(text: String) {
        assertEquals(PlannerTexts.NO_STEPS_YET, text, "the text of the scenario is the constant of the planner")
        assertTrue(d.state.isEmpty && d.plan.steps.isEmpty(), "the plan has steps: ${d.titles()}")
        assertTrue(screen.textOf("planner:emptyState").orEmpty().contains(text), "the screen says: ${screen.textOf("planner:emptyState")}")
    }

    @Then("{string}, {string} and {string} are disabled")
    fun threeDisabled(a: String, b: String, c: String) {
        assertEquals(listOf("Check", "Save as migration", "Dismiss"), listOf(a, b, c))
        listOf("planner:checkBtn", "planner:saveBtn", "planner:dismissBtn").forEach { tag ->
            assertFalse(screen.isEnabled(tag), "$tag is enabled on an empty plan")
        }
        assertFalse(d.state.checkEnabled)
        assertFalse(d.saveEnabled())
        assertFalse(d.state.canDismiss)
    }

    // ---- After saving, Apply is pressed on the Migrations screen ----

    @Then("the Migrations screen is shown with {string} selected and its dry run")
    fun migrationsShown(version: String) {
        assertEquals(version, d.state.saved?.version, "the plan was not saved: ${d.state.errorMessage}")
        val vm = d.migrationsScreen()
        vm.selectVersion(version) // what the window does with the saved plan
        val state = vm.state.value
        assertEquals(version, state.selectedVersion)
        val dryRun = assertNotNull(state.dryRun, "no dry run for $version")
        assertEquals(listOf("Declare quorum queue orders"), dryRun.actions.map { it.action.description })
        assertEquals(version, d.state.saved?.message?.substringAfter("Plan saved as ")?.substringBefore("."))
    }

    @Then("{string} is offered there")
    fun applyOffered(label: String) {
        val version = label.removePrefix("Apply ")
        val vm = d.migrationsScreen()
        vm.selectVersion(version)
        val migrations = dev.caerbannog.acceptance.migrations.MigrationsScreens.forViewModel(vm)
        assertEquals(label, migrations.textOf("migrations:applySingleBtn")?.trim(), "the Migrations screen offers: ${migrations.textOf("migrations:applySingleBtn")}")
        assertTrue(migrations.isEnabled("migrations:applySingleBtn"), "$label is drawn but disabled: ${vm.state.value.dryRun}")
    }

    // ---- limited states ----

    @Then("the planner says {string}")
    fun plannerSays(text: String) {
        val banner = assertNotNull(d.state.banner, "no banner")
        assertEquals(text, banner.title, "banner title")
        assertTrue(screen.textOf("planner:banner").orEmpty().contains(text), "the screen says: ${screen.textOf("planner:banner")}")
    }

    @Then("the planner says the user cannot change topology on {string} and that the plan can be saved and applied on a connection that allows it")
    fun plannerSaysNoPermission(connection: String) {
        val banner = assertIs<PlannerBanner.NoConfigurePermission>(d.state.banner)
        assertTrue(banner.title.contains("cannot change topology on $connection"), banner.title)
        assertTrue(banner.title.contains("You can plan, check and save, then apply the migration on a connection that allows it."), banner.title)
        assertTrue(screen.textOf("planner:banner").orEmpty().contains(banner.title), "the screen says: ${screen.textOf("planner:banner")}")
    }

    @Then("I can add a step and save the plan as a migration")
    fun canAddAndSave() {
        assertTrue(screen.isEnabled("planner:addStepBtn"), "Add step is disabled")
        d.addWithWizard(d.queueForm("orders", "quorum"))
        assertTrue(screen.isEnabled("planner:saveBtn"), "Save as migration is disabled: ${d.state.saveGate}")
        d.vm.saveAsMigration()
        assertNotNull(d.state.saved, "the plan was not saved: ${d.state.errorMessage}")
        assertEquals(emptyList(), d.executor.executedActions, "saving must send nothing")
    }

    @Given("the user has no configure permission on {string}")
    fun noConfigure(vhost: String) {
        assertEquals(d.vhost, vhost)
        d.configurePermission = false
        d.restart()
    }

    // ---- 3.x ----

    private var stub: com.sun.net.httpserver.HttpServer? = null
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var held: dev.caerbannog.core.connections.HeldConnection? = null
    private var requestsAfterConnect = 0

    @After
    fun stopStub() {
        dev.caerbannog.acceptance.migrations.MigrationsScreens.endScenario()
        runCatching { held?.close() }
        stub?.stop(0)
        stub = null
        held = null
    }

    @Given("I connect to {string}, a broker whose overview reports RabbitMQ {word}")
    fun connectToLegacy(name: String, version: String) {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 10)
        server.createContext("/") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI.rawPath}"
            val body = when (ex.requestURI.rawPath) {
                "/api/whoami" -> """{"name":"svc","tags":["administrator"]}"""
                "/api/overview" -> """{"rabbitmq_version":"$version","erlang_version":"26"}"""
                else -> "{}"
            }.toByteArray()
            ex.sendResponseHeaders(if (ex.requestURI.rawPath == "/api/whoami" || ex.requestURI.rawPath == "/api/overview") 200 else 404, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        stub = server
        val profile = ConnectionProfile(
            name = name, host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = 1, managementPort = server.address.port, tls = false,
        )
        val secrets = object : SecretStore {
            override fun store(profileName: String, password: String) = Unit
            override fun retrieve(profileName: String): String = "pw"
            override fun delete(profileName: String) = Unit
        }
        val opened = runBlocking { dev.caerbannog.datarabbit.RabbitMqConnectionGateway(secrets).open(profile) }
        held = (opened as? OpenResult.Opened)?.connection
        val connection = assertNotNull(held, "the connection did not open: $opened")
        assertEquals(null, connection.migrationExecutor)
        d.profile = name
        d.unavailableReason = connection.migrationUnavailableReason
        requestsAfterConnect = requests.size
        CheckWorld.held = connection; CheckWorld.stubRequests = requests; CheckWorld.requestsAfterConnect = requestsAfterConnect
        assertTrue(requests.all { it.startsWith("GET ") }, "a request that changes something was sent: $requests")
        d.restart()
    }

    @When("the planner is shown")
    fun plannerShown() { d.restart(); d.vm; screen.isDrawn("planner:banner") }

    @Then("it says {string} with the reason {string}")
    fun saysWithReason(title: String, reason: String) {
        val banner = assertIs<PlannerBanner.NotOffered>(d.state.banner)
        assertEquals(title, banner.title)
        assertEquals(reason, banner.detail)
        val text = screen.textOf("planner:banner").orEmpty()
        assertTrue(text.contains(title) && text.contains(reason), "the screen says: $text")
    }

    @Then("there is no wizard, no check and no save")
    fun noWizardNoCheckNoSave() {
        assertTrue(d.state.isLocked)
        d.vm.openWizard()
        assertEquals(WizardState.Closed, d.state.wizard, "the wizard opened on a 3.x connection")
        d.vm.saveAsMigration()
        assertEquals(null, d.state.saved)
        listOf("planner:addStepBtn", "planner:checkBtn", "planner:saveBtn", "planWizard").forEach {
            assertFalse(screen.isDrawn(it), "$it is drawn on a 3.x connection")
        }
        assertEquals(emptyList(), d.migrationFolder.written, "a file was written")
    }

    @Then("the planner sent no request at all to {string}")
    fun noRequestSent(name: String) {
        assertEquals(requestsAfterConnect, requests.size, "the planner sent a request to $name: ${requests.drop(requestsAfterConnect)}")
        assertEquals(emptyList(), d.executor.executedActions, "the planner used the executor")
    }

    @Then("the draft still has {int} steps")
    fun draftKept(count: Int) {
        val load = runBlocking { d.store.load() }
        assertEquals(count, assertIs<DraftLoad.Loaded>(load).plan.steps.size)
    }

    // ---- not connected ----

    @Given("I am not connected")
    fun notConnected() { d.connected = false; d.restart() }

    @When("I start a new plan and add a quorum queue {string} with the wizard")
    fun newPlanAndQueue(name: String) {
        d.newPlan()
        d.addWithWizard(d.queueForm(name, "quorum"))
    }

    @Then("I can save the plan as a migration")
    fun canSave() {
        assertTrue(screen.isEnabled("planner:saveBtn"), "Save as migration is disabled: ${d.state.saveGate}")
        d.vm.saveAsMigration()
        assertNotNull(d.state.saved, "the plan was not saved: ${d.state.errorMessage}")
    }

    // ---- the JSON view ----

    @When("I choose the {string} view of the review")
    fun chooseJsonView(view: String) {
        assertEquals("JSON", view)
        screen.click("planner:reviewViewSwitch:Json")
        assertTrue(screen.isDrawn("planner:jsonView"), "the JSON view is not drawn after the switch was pressed")
    }

    @Then("I see the migration text with the action {string} for {string}")
    fun seeMigrationText(action: String, name: String) {
        val text = screen.textOf("planner:jsonView").orEmpty()
        assertTrue(text.contains("\"$action\"") && text.contains("\"$name\""), "the JSON view shows: $text")
        val parsed = MigrationParser.parse(d.state.migrationText)
        assertIs<dev.caerbannog.core.migrations.MigrationValidationResult.Valid>(parsed, "the text is not a valid migration")
    }

    @Then("the text cannot be edited there")
    fun textNotEditable() = assertFalse(screen.isEditable("planner:jsonView"), "the JSON view is editable")

    // ---- 09: the Topology screen stays read-only; changes are made with a plan ----

    private var topologyQueue: String? = null
    private var topology: TopologyScreenDriver? = null

    @After
    fun stopTopology() { topology?.dispose(); topology = null }

    @Given("a quorum queue {string}")
    fun aQuorumQueue(name: String) { topologyQueue = name }

    @When("I select the queue {string} on the topology screen")
    fun selectOnTopology(name: String) {
        assertEquals(topologyQueue, name)
        topology = TopologyScreenDriver(name)
    }

    @Then("there is no control to {}")
    fun noControlTo(change: String) {
        val words = topology?.controlWords() ?: error("the topology screen was not shown")
        val forbidden = when (change) {
            "declare a queue" -> listOf("declare")
            "change the policy of a queue" -> listOf("policy")
            "bind an exchange to a queue" -> listOf("bind")
            "delete a queue" -> listOf("delete", "purge")
            else -> error("no rule for \"$change\"")
        }
        assertEquals(emptyList(), forbidden.filter { it in words }, "controls of the topology screen name: $words")
    }

    @Then("^(a (?:queue|policy|binding) is declared by the (?:Queue|Policy|Binding) step of a plan|no step of the wizard deletes anything and a delete action in a migration file needs the level Full control)$")
    fun whereChangesAreMade(where: String) {
        when (where) {
            "a queue is declared by the Queue step of a plan" -> assertTrue("Queue" in PlannerTexts.WIZARD_KINDS)
            "a policy is declared by the Policy step of a plan" -> assertTrue("Policy" in PlannerTexts.WIZARD_KINDS)
            "a binding is declared by the Binding step of a plan" -> assertTrue("Binding" in PlannerTexts.WIZARD_KINDS)
            "no step of the wizard deletes anything and a delete action in a migration file needs the level Full control" -> {
                assertTrue(PlannerTexts.WIZARD_KINDS.none { it.contains("delete", true) || it.contains("remove", true) || it.contains("purge", true) })
                val file = MigrationParser.exportToJson("0005", "d", listOf(MigrationAction.DeleteQueue("orders"))).let {
                    (MigrationParser.parse(it) as dev.caerbannog.core.migrations.MigrationValidationResult.Valid).migration
                }
                val dry = MigrationRunner(d.folders.history, d.executor)
                    .dryRun(file, hasConfigurePermission = true, controlLevel = TopologyControlLevel.DECLARE_AND_EDIT, vhost = "/")
                val reasons = dry.actions.map(ActionDryRun::reason)
                assertEquals(listOf("Destructive actions require Full control level"), reasons)
                val allowed = MigrationRunner(d.folders.history, d.executor)
                    .dryRun(file, hasConfigurePermission = true, controlLevel = TopologyControlLevel.FULL_CONTROL, vhost = "/")
                assertTrue(allowed.actions.all { it.allowed })
            }
            else -> error("no rule for \"$where\"")
        }
    }

}
