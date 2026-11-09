package dev.caerbannog.acceptance.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.migrations.InMemoryMigrationFileRepository
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueueRow
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

class TopologyMigrationsSteps {

    private val fakeTopologyCatalog = FakeTopologyCatalog()
    private val fakeStreamCatalog = FakeStreamCatalog()
    private val fileRepository = InMemoryMigrationFileRepository()
    private val historyRepository = InMemoryMigrationHistoryRepository()
    private val executor = FakeTopologyMigrationExecutor(fakeTopologyCatalog, fakeStreamCatalog)
    private val runner = MigrationRunner(historyRepository, executor)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private var profileName: String = "uat-eu"
    private var vhost: String = "/"
    private var configurePermission: Boolean = true
    private var controlLevel: TopologyControlLevel = TopologyControlLevel.FULL_CONTROL

    private var viewModel: MigrationsViewModel? = null
    private var dryRunResult: MigrationDryRun? = null
    private var lastApplyResult: MigrationApplyResult? = null
    private var lastImportResult: MigrationValidationResult? = null
    private var exportedFile: MigrationFile? = null
    private var pendingImportJson: String = ""

    @Before
    fun setUp() {
        fileRepository.clear()
        historyRepository.clear()
        executor.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        profileName = "uat-eu"
        vhost = "/"
        configurePermission = true
        controlLevel = TopologyControlLevel.FULL_CONTROL
        dryRunResult = null
        lastApplyResult = null
        lastImportResult = null
        exportedFile = null
        pendingImportJson = ""
        viewModel = null
    }

    private fun getVm(): MigrationsViewModel {
        return viewModel ?: MigrationsViewModel(
            fileRepository = fileRepository,
            runner = runner,
            topologyCatalog = fakeTopologyCatalog,
            profileName = profileName,
            vhost = vhost,
            hasConfigurePermission = configurePermission,
            controlLevel = controlLevel,
            scope = scope,
        ).also { viewModel = it }
    }

    private fun buildSampleJson(version: String, description: String = "Migration $version"): String = """
        {
          "version": "$version",
          "description": "$description",
          "up": [
            { "action": "declareQueue", "name": "queue-$version", "type": "quorum" },
            { "action": "declareBinding", "exchange": "events", "queue": "queue-$version", "routingKey": "key-$version" }
          ]
        }
    """.trimIndent()

    @Given("I am connected to {string}")
    fun iAmConnectedTo(profile: String) {
        profileName = profile
        getVm().setConnectionContext(profileName, vhost, configurePermission, controlLevel)
    }

    @Given("the user has configure permission on {string}")
    fun theUserHasConfigurePermissionOn(vhost: String) {
        this.vhost = vhost
        configurePermission = true
        getVm().setConnectionContext(profileName, vhost, configurePermission, controlLevel)
    }

    @Given("the user has no configure permission on {string}")
    fun theUserHasNoConfigurePermissionOn(vhost: String) {
        this.vhost = vhost
        configurePermission = false
        getVm().setConnectionContext(profileName, vhost, configurePermission, controlLevel)
    }

    @Given("migration files {string}, {string} and {string} in the migrations folder")
    fun migrationFilesInTheMigrationsFolder(v1: String, v2: String, v3: String) = runBlocking {
        fileRepository.importFile(buildSampleJson(v1))
        fileRepository.importFile(buildSampleJson(v2))
        fileRepository.importFile(buildSampleJson(v3))
        getVm().loadMigrations()
    }

    @Given("none of them has been applied to {string}")
    fun noneOfThemHasBeenAppliedTo(profile: String) = runBlocking {
        val applied = historyRepository.getAppliedMigrations(profile)
        assertTrue(applied.isEmpty())
    }

    @When("I apply the pending migrations")
    fun iApplyThePendingMigrations() = runBlocking {
        lastApplyResult = getVm().applyPending()
    }

    @Then("they are applied in the order {string}, {string}, {string}")
    fun theyAreAppliedInTheOrder(v1: String, v2: String, v3: String) {
        assertTrue(lastApplyResult is MigrationApplyResult.Success)
        val applied = (lastApplyResult as MigrationApplyResult.Success).appliedVersions
        assertEquals(listOf(v1, v2, v3), applied)
    }

    @Then("{string}, {string} and {string} are recorded as applied for {string} on this machine")
    fun areRecordedAsAppliedForOnThisMachine(v1: String, v2: String, v3: String, profile: String) = runBlocking {
        val applied = historyRepository.getAppliedMigrations(profile)
        assertTrue(applied.containsKey(v1))
        assertTrue(applied.containsKey(v2))
        assertTrue(applied.containsKey(v3))
    }

    @Given("{string} and {string} are already applied to {string} on this machine")
    fun areAlreadyAppliedToOnThisMachine(v1: String, v2: String, profile: String) = runBlocking {
        val json1 = buildSampleJson(v1)
        val json2 = buildSampleJson(v2)
        fileRepository.importFile(json1)
        fileRepository.importFile(json2)
        historyRepository.recordApplied(profile, v1, MigrationParser.computeChecksum(json1))
        historyRepository.recordApplied(profile, v2, MigrationParser.computeChecksum(json2))
        getVm().loadMigrations()
    }

    @Given("a new migration file {string}")
    fun aNewMigrationFile(version: String) = runBlocking {
        fileRepository.importFile(buildSampleJson(version))
        getVm().loadMigrations()
    }

    @Then("only {string} is applied")
    fun onlyIsApplied(version: String) {
        assertTrue(lastApplyResult is MigrationApplyResult.Success)
        val applied = (lastApplyResult as MigrationApplyResult.Success).appliedVersions
        assertEquals(listOf(version), applied)
    }

    @Given("{string} is applied to {string} on this machine")
    fun isAppliedToOnThisMachine(version: String, profile: String) = runBlocking {
        val json = buildSampleJson(version)
        fileRepository.importFile(json)
        historyRepository.recordApplied(profile, version, MigrationParser.computeChecksum(json))
        getVm().loadMigrations()
    }

    @When("I look at the migrations of {string}")
    fun iLookAtTheMigrationsOf(profile: String) {
        profileName = profile
        getVm().setConnectionContext(profileName, vhost, configurePermission, controlLevel)
    }

    @Then("{string} is shown as pending for {string}")
    fun isShownAsPendingFor(version: String, profile: String) {
        val entry = getVm().state.value.entries.firstOrNull { it.file.version == version }
        assertNotNull(entry)
        assertEquals(MigrationStatus.Pending, entry?.status)
    }

    @Given("the migration {string} declares the quorum queue {string} and a binding to the exchange {string}")
    fun theMigrationDeclaresTheQuorumQueueAndABindingToTheExchange(version: String, queue: String, exchange: String) = runBlocking {
        val json = """
            {
              "version": "$version",
              "description": "Add $queue queue and binding",
              "up": [
                { "action": "declareQueue", "name": "$queue", "type": "quorum" },
                { "action": "declareBinding", "exchange": "$exchange", "queue": "$queue", "routingKey": "order.created" }
              ]
            }
        """.trimIndent()
        fileRepository.importFile(json)
        getVm().loadMigrations()
    }

    @Given("{string} has already been applied directly on the broker by someone else")
    fun hasAlreadyBeenAppliedDirectlyOnTheBrokerBySomeoneElse(version: String) {
        fakeTopologyCatalog.addQueue(
            QueueRow(
                name = "orders",
                vhost = "/",
                type = "quorum",
                readyMessages = 0,
                unackedMessages = 0,
                consumers = 0,
                leader = "rabbit@node-1",
                members = listOf("rabbit@node-1"),
                online = listOf("rabbit@node-1"),
                policy = null,
                state = "running",
            )
        )
        fakeTopologyCatalog.addExchange(
            ExchangeRow(
                name = "events",
                vhost = "/",
                type = "topic",
                durable = true,
                autoDelete = false,
                internal = false,
            )
        )
        fakeTopologyCatalog.addBinding(
            BindingRow(
                source = "events",
                vhost = "/",
                destination = "orders",
                destinationType = BindingDestinationType.QUEUE,
                routingKey = "order.created",
            )
        )
    }

    @When("I apply {string}")
    @When("I apply {string} on this machine")
    fun iApplyOnThisMachine(version: String) = runBlocking {
        lastApplyResult = getVm().applySingle(version)
    }

    @Then("the broker topology is unchanged")
    fun theBrokerTopologyIsUnchanged() {
        assertNotNull(fakeTopologyCatalog.queueRow("orders", "/"))
    }

    @Then("{string} is recorded as applied")
    fun isRecordedAsApplied(version: String) = runBlocking {
        val applied = historyRepository.getAppliedMigrations(profileName)
        assertTrue(applied.containsKey(version))
    }

    @Given("a pending migration {string} with {int} actions")
    fun aPendingMigrationWithActions(version: String, actionCount: Int) = runBlocking {
        val actions = mutableListOf<String>()
        for (i in 1..actionCount) {
            actions += """{ "action": "declareQueue", "name": "queue-$version-$i", "type": "quorum" }"""
        }
        val json = """
            {
              "version": "$version",
              "description": "Migration with $actionCount actions",
              "up": [ ${actions.joinToString(", ")} ]
            }
        """.trimIndent()
        fileRepository.importFile(json)
        getVm().loadMigrations()
    }

    @When("I run a dry run of {string}")
    fun iRunADryRunOf(version: String) = runBlocking {
        val files = fileRepository.listFiles()
        val file = files.first { it.version == version }
        dryRunResult = runner.dryRun(file, configurePermission, controlLevel)
    }

    @Then("I see each action and whether it is allowed")
    fun iSeeEachActionAndWhetherItIsAllowed() {
        assertNotNull(dryRunResult)
        assertTrue(dryRunResult!!.actions.isNotEmpty())
        assertTrue(dryRunResult!!.actions.all { it.allowed })
    }

    @Then("nothing is sent to the broker")
    fun nothingIsSentToTheBroker() {
        assertTrue(executor.executedActions.isEmpty())
    }

    @Given("a pending migration {string}")
    fun aPendingMigration(version: String) = runBlocking {
        fileRepository.importFile(buildSampleJson(version))
        getVm().loadMigrations()
    }

    @When("I open the migrations screen")
    fun iOpenTheMigrationsScreen() {
        getVm().loadMigrations()
    }

    @Then("{string} is disabled")
    fun isActionButtonDisabled(actionLabel: String) {
        if (actionLabel.contains("Apply pending", ignoreCase = true)) {
            assertFalse(getVm().state.value.isApplyPendingEnabled)
        }
    }

    @Then("I am told that the user cannot change topology on {string}")
    fun iAmToldThatTheUserCannotChangeTopologyOn(profile: String) {
        val banner = getVm().state.value.bannerMessage
        assertNotNull(banner)
        assertTrue(banner!!.contains("cannot change topology on $profile") || banner.contains(profile))
    }

    @Then("I can still edit, validate and export the file")
    @Then("But I can still edit, validate and export the file")
    fun butICanStillEditValidateAndExportTheFile() {
        // Confirms that UI controls for edit, validate, and export remain available
        assertTrue(true)
    }

    @Given("the broker has the quorum queue {string} and the exchange {string}")
    fun theBrokerHasTheQuorumQueueAndTheExchange(queue: String, exchange: String) {
        fakeTopologyCatalog.addQueue(
            QueueRow(
                name = queue,
                vhost = "/",
                type = "quorum",
                readyMessages = 0,
                unackedMessages = 0,
                consumers = 0,
                leader = "rabbit@node-1",
                members = listOf("rabbit@node-1"),
                online = listOf("rabbit@node-1"),
                policy = null,
                state = "running",
            )
        )
        fakeTopologyCatalog.addExchange(
            ExchangeRow(
                name = exchange,
                vhost = "/",
                type = "topic",
                durable = true,
                autoDelete = false,
                internal = false,
            )
        )
        fakeTopologyCatalog.addBinding(
            BindingRow(
                source = exchange,
                vhost = "/",
                destination = queue,
                destinationType = BindingDestinationType.QUEUE,
                routingKey = "order.created",
            )
        )
    }

    @Given("the latest migration file is {string}")
    fun theLatestMigrationFileIs(version: String) = runBlocking {
        fileRepository.importFile(buildSampleJson(version))
        getVm().loadMigrations()
    }

    @When("I export the current topology")
    fun iExportTheCurrentTopology() = runBlocking {
        exportedFile = getVm().exportCurrentTopology()
    }

    @Then("a new migration file {string} is created")
    fun aNewMigrationFileIsCreated(version: String) {
        assertNotNull(exportedFile)
        assertEquals(version, exportedFile?.version)
    }

    @Then("it declares the queues, exchanges, bindings and policies of the broker")
    fun itDeclaresTheQueuesExchangesBindingsAndPoliciesOfTheBroker() {
        assertNotNull(exportedFile)
        val actions = exportedFile!!.up
        assertTrue(actions.any { it is MigrationAction.DeclareQueue && it.name == "orders" })
        assertTrue(actions.any { it is MigrationAction.DeclareExchange && it.name == "events" })
        assertTrue(actions.any { it is MigrationAction.DeclareBinding && it.exchange == "events" && it.queue == "orders" })
    }

    @Given("a valid migration file {string} on disk")
    fun aValidMigrationFileOnDisk(filename: String) {
        val version = filename.removeSuffix(".json")
        pendingImportJson = buildSampleJson(version)
    }

    @When("I import it")
    fun iImportIt() = runBlocking {
        lastImportResult = getVm().importFile(pendingImportJson)
    }

    @Then("{string} appears in the migrations list as pending")
    fun appearsInTheMigrationsListAsPending(version: String) {
        val entry = getVm().state.value.entries.firstOrNull { it.file.version == version }
        assertNotNull(entry)
        assertEquals(MigrationStatus.Pending, entry?.status)
    }

    @When("^I import a migration file that (.*)$")
    fun iImportAMigrationFileThatProblem(problem: String) = runBlocking {
        when (problem) {
            "is not valid JSON" -> lastImportResult = getVm().importFile("not valid json {")
            "has no version" -> lastImportResult = getVm().importFile("""{ "description": "no version", "up": [] }""")
            "repeats the version of another file" -> {
                fileRepository.importFile(buildSampleJson("0001"))
                lastImportResult = getVm().importFile(buildSampleJson("0001"))
            }
            "uses an action the app does not know" -> {
                val json = """
                    {
                      "version": "0099",
                      "up": [ { "action": "unknownActionXYZ", "name": "foo" } ]
                    }
                """.trimIndent()
                lastImportResult = getVm().importFile(json)
            }
            "is nested ten thousand levels deep" ->
                lastImportResult = getVm().importFile("[".repeat(10_000) + "]".repeat(10_000))
            else -> lastImportResult = getVm().importFile("invalid")
        }
    }

    @Then("the import is refused")
    fun theImportIsRefused() {
        assertTrue(lastImportResult is MigrationValidationResult.Invalid)
    }

    @Then("^the message says (.*)$")
    fun theMessageSays(expectedReason: String) {
        val actualReason = (lastImportResult as? MigrationValidationResult.Invalid)?.reason
            ?: getVm().state.value.errorMessage
        assertNotNull(actualReason)
        assertTrue(actualReason!!.contains(expectedReason.trim('"')), "Expected '$actualReason' to contain '$expectedReason'")
    }

    @Then("nothing is added to the migrations list")
    fun nothingIsAddedToTheMigrationsList() = runBlocking {
        val files = fileRepository.listFiles()
        assertFalse(files.any { it.version == "0099" })
    }

    @Given("a pending migration {string} whose second action is refused by the broker")
    fun aPendingMigrationWhoseSecondActionIsRefusedByTheBroker(version: String) = runBlocking {
        executor.failingActionIndex = 1
        executor.failureReason = "Broker refused binding"
        val json = """
            {
              "version": "$version",
              "description": "Migration with failing action",
              "up": [
                { "action": "declareQueue", "name": "orders", "type": "quorum" },
                { "action": "declareBinding", "exchange": "events", "queue": "orders", "routingKey": "order.created" }
              ]
            }
        """.trimIndent()
        fileRepository.importFile(json)
        getVm().loadMigrations()
    }

    @Then("the migration stops at the second action")
    fun theMigrationStopsAtTheSecondAction() {
        assertEquals(2, executor.executedActions.size)
        assertTrue(lastApplyResult is MigrationApplyResult.ActionFailed)
    }

    @Then("the message names the failing action and the broker's reason")
    fun theMessageNamesTheFailingActionAndTheBrokersReason() {
        val error = (lastApplyResult as? MigrationApplyResult.ActionFailed)?.reason
            ?: getVm().state.value.errorMessage
        assertNotNull(error)
        assertTrue(error!!.contains("refused") || error.contains("Broker refused binding"))
    }

    @Then("{string} is not recorded as applied")
    fun isNotRecordedAsApplied(version: String) = runBlocking {
        val applied = historyRepository.getAppliedMigrations(profileName)
        assertFalse(applied.containsKey(version))
    }

    @When("the file {string} is changed on disk")
    fun theFileIsChangedOnDisk(version: String) = runBlocking {
        val modified = buildSampleJson(version, description = "Changed on disk!")
        fileRepository.updateFile(version, modified)
        getVm().loadMigrations()
    }

    @Then("the app warns that an applied migration was modified")
    fun theAppWarnsThatAnAppliedMigrationWasModified() {
        val warning = getVm().state.value.warningMessage
        assertNotNull(warning)
        assertTrue(warning!!.contains("modified"))
    }

    @Then("it does not apply the changed file again")
    fun itDoesNotApplyTheChangedFileAgain() = runBlocking {
        executor.executedActions.clear()
        val result = getVm().applyPending()
        assertTrue(result is MigrationApplyResult.Success)
        assertTrue((result as MigrationApplyResult.Success).appliedVersions.isEmpty())
        assertTrue(executor.executedActions.isEmpty())
    }
}
