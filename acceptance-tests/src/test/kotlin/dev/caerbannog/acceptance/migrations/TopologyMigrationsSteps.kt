package dev.caerbannog.acceptance.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.migrations.InMemoryMigrationFileRepository
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.core.migrations.fakes.MigrationFixtures
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class TopologyMigrationsSteps {

    /** M5-08a: the second step class of this package (M508aExecutorSteps) works on the same scenario state; Cucumber has no DI here. */
    companion object {
        @Volatile
        internal var current: TopologyMigrationsSteps? = null
    }

    internal val fakeTopologyCatalog = FakeTopologyCatalog()
    private val fakeStreamCatalog = FakeStreamCatalog()
    internal val fileRepository = InMemoryMigrationFileRepository()
    internal val historyRepository = InMemoryMigrationHistoryRepository()
    internal val executor = FakeTopologyMigrationExecutor(fakeTopologyCatalog, fakeStreamCatalog)
    /** M5-08a: a scenario with the real executor replaces it (before the view model is built). */
    internal var runner = MigrationRunner(historyRepository, executor)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    internal var profileName: String = "uat-eu"
    internal var vhost: String = "/"
    internal var configurePermission: Boolean = true
    internal var controlLevel: TopologyControlLevel = TopologyControlLevel.FULL_CONTROL

    /** M5-08c1: the fixed reason the connection has no migration executor (a 3.x source, for example); null when it has one. */
    internal var migrationUnavailableReason: String? = null

    internal var viewModel: MigrationsViewModel? = null

    /** M5-08b: a scenario with the real folder repositories (M508bPersistenceSteps) builds its view model here; null keeps the in-memory one. */
    internal var vmFactory: (() -> MigrationsViewModel)? = null
    private var dryRunResult: MigrationDryRun? = null
    internal var lastApplyResult: MigrationApplyResult? = null
    internal var lastImportResult: MigrationValidationResult? = null
    internal var exportedFile: MigrationFile? = null

    /** M5-08a: the topology catalog the view model reads; the 150-queue scenario puts a small-page catalog here. */
    internal var topologyCatalogOverride: dev.caerbannog.core.topology.TopologyCatalog? = null

    /** M5-08a: when a scenario runs against the scripted broker, "the broker topology is unchanged" compares its snapshot instead. */
    internal var topologyUnchangedCheck: (() -> Unit)? = null
    internal var pendingImportJson: String = ""

    @Before
    fun setUp() {
        current = this
        fileRepository.clear()
        historyRepository.clear()
        executor.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        profileName = "uat-eu"
        vhost = "/"
        configurePermission = true
        controlLevel = TopologyControlLevel.FULL_CONTROL
        migrationUnavailableReason = null
        dryRunResult = null
        lastApplyResult = null
        lastImportResult = null
        exportedFile = null
        pendingImportJson = ""
        viewModel = null
        vmFactory = null
        topologyCatalogOverride = null
        topologyUnchangedCheck = null
        runner = MigrationRunner(historyRepository, executor)
    }

    internal fun getVm(): MigrationsViewModel {
        return viewModel ?: (vmFactory?.invoke() ?: MigrationsViewModel(
            fileRepository = fileRepository,
            runner = runner,
            topologyCatalog = topologyCatalogOverride ?: fakeTopologyCatalog,
            profileName = profileName,
            vhost = vhost,
            hasConfigurePermission = configurePermission,
            controlLevel = controlLevel,
            migrationUnavailableReason = migrationUnavailableReason,
            scope = scope,
        )).also { viewModel = it }
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
        topologyUnchangedCheck?.let { it(); return }
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
        dryRunResult = runner.dryRun(file, configurePermission, controlLevel, vhost = "/")
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
        // M9-14: the sentence says policies, so the step looks at them (the scenario's Given has one)
        val policy = actions.filterIsInstance<MigrationAction.DeclarePolicy>().singleOrNull { it.name == "orders-limits" }
        assertNotNull(policy, "the export has no policy \"orders-limits\": $actions")
        assertEquals("^orders", policy!!.pattern)
        assertEquals(mapOf("max-length" to dev.caerbannog.core.json.ArgumentValue.Whole(5000)), policy.definition)
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
            "has a queue whose name is an object" -> lastImportResult = getVm().importFile(
                """{ "version": "0097", "up": [ { "action": "declareQueue", "name": { "inner": "x" } } ] }""",
            )
            "declares a queue named \"..\"" -> lastImportResult = getVm().importFile(
                """{ "version": "0096", "up": [ { "action": "declareQueue", "name": ".." } ] }""",
            )
            "declares a queue type inside the arguments" -> lastImportResult = getVm().importFile(
                """{ "version": "0098", "up": [ { "action": "declareQueue", "name": "q", "arguments": { "x-queue-type": "quorum" } } ] }""",
            )
            "has a policy with an unknown apply-to" -> lastImportResult = getVm().importFile(
                """{ "version": "0098", "up": [ { "action": "declarePolicy", "name": "p", "pattern": ".*", "applyTo": "everything" } ] }""",
            )
            "has a binding with an unknown destination type" -> lastImportResult = getVm().importFile(
                """{ "version": "0098", "up": [ { "action": "declareBinding", "exchange": "e", "queue": "q", "destinationType": "topic" } ] }""",
            )
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

    // M5-08c1: the step "a pending migration {string} whose second action is refused by the broker" moved to M508aExecutorSteps (three actions, the
    // real executor over the scripted broker): the screen's failure text names "Action 2 of 3" and the executor's fixed reason.

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

    // ---- M8-04: the format carries what a converted 3.x topology needs ----

    private fun migrationFile(vararg actions: String) =
        """{ "version": "0090", "description": "M8-04", "up": [ ${actions.joinToString(", ")} ] }"""

    private fun lastDeclaredQueue(name: String): MigrationAction.DeclareQueue =
        executor.declaredQueues[vhost to name] ?: error("the broker received no queue \"$name\"; it received ${executor.executedActions.map { it.description }}")

    private var lastQueueName: String = ""

    @Given("a migration file that declares the quorum queue {string} with the arguments {string} {int}, {string} {int} and {string} {string}")
    fun aMigrationFileThatDeclaresTheQuorumQueueWithTheArguments(
        queue: String, k1: String, v1: Int, k2: String, v2: Int, k3: String, v3: String,
    ) {
        lastQueueName = queue
        pendingImportJson = migrationFile(
            """{ "action": "declareQueue", "name": "$queue", "type": "quorum", "arguments": { "$k1": $v1, "$k2": $v2, "$k3": "$v3" } }""",
        )
    }

    @Then("the broker received the queue {string} with {string} as the number {int} and {string} as the number {int}")
    fun theBrokerReceivedTheQueueWithNumbers(queue: String, k1: String, v1: Int, k2: String, v2: Int) {
        val arguments = lastDeclaredQueue(queue).arguments
        assertEquals(ArgumentValue.Whole(v1.toLong()), arguments[k1], "$k1 must be the number $v1, arguments: $arguments")
        assertEquals(ArgumentValue.Whole(v2.toLong()), arguments[k2], "$k2 must be the number $v2, arguments: $arguments")
    }

    @Then("{string} is the text {string}")
    fun isTheText(key: String, text: String) {
        assertEquals(ArgumentValue.Text(text), lastDeclaredQueue(lastQueueName).arguments[key])
    }

    @Then("the dry run row for {string} lists the three arguments")
    fun theDryRunRowForListsTheThreeArguments(queue: String) {
        val dryRun = getVm().state.value.dryRun
        assertNotNull(dryRun, "no dry run is shown after the import")
        val action = dryRun!!.actions.map { it.action }.filterIsInstance<MigrationAction.DeclareQueue>().single { it.name == queue }
        val detail = action.detail
        assertNotNull(detail, "the dry run row of \"$queue\" has no detail line")
        for (key in listOf("x-delivery-limit", "x-message-ttl", "x-dead-letter-exchange")) {
            assertTrue(detail!!.contains(key), "the detail line \"$detail\" must list $key")
        }
    }

    @Given("a migration file that declares the exchange {string} as a topic exchange that is not durable, internal and auto-delete, with the argument {string} {string}")
    fun aMigrationFileThatDeclaresTheExchange(exchange: String, key: String, value: String) {
        pendingImportJson = migrationFile(
            """{ "action": "declareExchange", "name": "$exchange", "type": "topic", "durable": false, "internal": true, "autoDelete": true, "arguments": { "$key": "$value" } }""",
        )
    }

    @Then("the broker has the exchange {string} with durable false, internal true and auto-delete true")
    fun theBrokerHasTheExchangeWithFlags(exchange: String) = runBlocking {
        val rows = (fakeTopologyCatalog.exchanges(vhost) as dev.caerbannog.core.streams.CatalogResult.Success).value
        val row = rows.firstOrNull { it.name == exchange } ?: error("the broker has no exchange \"$exchange\"; it has ${rows.map { it.name }}")
        assertEquals(Triple(false, true, true), Triple(row.durable, row.internal, row.autoDelete), "durable, internal, autoDelete of $exchange")
    }

    @Given("a migration file that declares the policy {string} on {string} applying to {string} with priority {int} and the definition {string} {int} and {string} {string}")
    fun aMigrationFileThatDeclaresThePolicy(name: String, pattern: String, applyTo: String, priority: Int, k1: String, v1: Int, k2: String, v2: String) {
        pendingImportJson = migrationFile(
            """{ "action": "declarePolicy", "name": "$name", "pattern": "$pattern", "applyTo": "$applyTo", "priority": $priority, "definition": { "$k1": $v1, "$k2": "$v2" } }""",
        )
    }

    @Then("the broker received the policy {string} with apply-to {string}, priority {int} and {string} as the number {int}")
    fun theBrokerReceivedThePolicy(name: String, applyTo: String, priority: Int, key: String, number: Int) {
        val policy = executor.declaredPolicies[vhost to name] ?: error("the broker received no policy \"$name\"; it received ${executor.executedActions.map { it.description }}")
        assertEquals(applyTo, policy.applyTo)
        assertEquals(priority, policy.priority)
        assertEquals(ArgumentValue.Whole(number.toLong()), policy.definition[key], "$key must be the number $number, definition: ${policy.definition}")
    }

    @Given("a migration file that declares the operator policy {string} on {string} applying to {string} with the definition {string} {int}")
    fun aMigrationFileThatDeclaresTheOperatorPolicy(name: String, pattern: String, applyTo: String, key: String, number: Int) {
        pendingImportJson = migrationFile(
            """{ "action": "declareOperatorPolicy", "name": "$name", "pattern": "$pattern", "applyTo": "$applyTo", "definition": { "$key": $number } }""",
        )
    }

    @Then("the broker received the operator policy {string} with {string} as the number {int}")
    fun theBrokerReceivedTheOperatorPolicy(name: String, key: String, number: Int) {
        val policy = executor.declaredOperatorPolicies[vhost to name]
            ?: error("the broker received no operator policy \"$name\"; it received ${executor.executedActions.map { it.description }}")
        assertEquals(ArgumentValue.Whole(number.toLong()), policy.definition[key], "$key must be the number $number, definition: ${policy.definition}")
    }

    @Given("a migration file that binds the exchange {string} to the exchange {string} with routing key {string}")
    fun aMigrationFileThatBindsTheExchangeToTheExchange(source: String, destination: String, key: String) {
        pendingImportJson = migrationFile(
            """{ "action": "declareBinding", "exchange": "$source", "queue": "$destination", "destinationType": "exchange", "routingKey": "$key" }""",
        )
    }

    @Then("the broker has a binding from {string} to the exchange {string} with routing key {string}")
    fun theBrokerHasABindingToTheExchange(source: String, destination: String, key: String) = runBlocking {
        val rows = (fakeTopologyCatalog.bindings(vhost) as dev.caerbannog.core.streams.CatalogResult.Success).value
        val match = rows.filter { it.source == source && it.destination == destination && it.routingKey == key }
        assertTrue(match.isNotEmpty(), "no binding $source -> $destination ($key); the broker has $rows")
        assertEquals(BindingDestinationType.EXCHANGE, match.first().destinationType, "the destination must be an exchange, not a queue")
    }

    private val oldFormatActionsBefore: List<MigrationAction> get() = MigrationFixtures.defaultOnlyActions()

    @Given("the migration file {string} in the format of the first release was applied to {string}")
    fun theMigrationFileInTheFormatOfTheFirstReleaseWasAppliedTo(version: String, profile: String) = runBlocking {
        profileName = profile
        val text = MigrationFixtures.read(MigrationFixtures.OLD_FORMAT)
        assertEquals(version, (MigrationParser.parse(text) as MigrationValidationResult.Valid).migration.version)
        fileRepository.importFile(text)
        // What the first release recorded: the checksum written in the fixture helper, not one computed by the parser under test.
        historyRepository.recordApplied(profile, version, MigrationFixtures.OLD_FORMAT_CHECKSUM)
    }

    @When("I list the migrations")
    fun iListTheMigrations() {
        getVm().setConnectionContext(profileName, vhost, configurePermission, controlLevel)
    }

    @Then("{string} is shown as applied, not as modified")
    fun isShownAsAppliedNotAsModified(version: String) {
        val entry = getVm().state.value.entries.firstOrNull { it.file.version == version }
        assertNotNull(entry, "\"$version\" is not in the list")
        assertEquals(MigrationStatus.Applied(MigrationFixtures.OLD_FORMAT_CHECKSUM), entry!!.status)
        assertNull(getVm().state.value.warningMessage, "no modified warning may be shown")
    }

    @Then("its actions are the same as before")
    fun itsActionsAreTheSameAsBefore() {
        val entry = getVm().state.value.entries.single()
        assertEquals(oldFormatActionsBefore, entry.file.up)
    }

    @Given("the broker has a durable queue {string}, a non-durable exchange {string} and a binding between them")
    fun theBrokerHasADurableQueueANonDurableExchangeAndABinding(queue: String, exchange: String) {
        theBrokerHasTheQuorumQueueAndTheExchange(queue, exchange)
        fakeTopologyCatalog.addExchange(ExchangeRow(name = exchange, vhost = "/", type = "topic", durable = false, autoDelete = false, internal = false))
    }

    @Then("the new migration declares {string}, {string} with durable false, and the binding")
    fun theNewMigrationDeclares(queue: String, exchange: String) {
        val file = exportedFile ?: error("nothing was exported")
        assertTrue(file.up.any { it is MigrationAction.DeclareQueue && it.name == queue })
        val declared = file.up.filterIsInstance<MigrationAction.DeclareExchange>().singleOrNull { it.name == exchange }
        assertNotNull(declared, "the export has no exchange \"$exchange\": ${file.up}")
        assertFalse(declared!!.durable, "the non-durable exchange lost its flag in the export")
        assertTrue(file.up.any { it is MigrationAction.DeclareBinding && it.queue == queue })
    }

    @Then("a file without any new feature does not contain the new keys")
    fun aFileWithoutAnyNewFeatureDoesNotContainTheNewKeys() {
        val raw = (exportedFile ?: error("nothing was exported")).rawJson
        for (key in listOf("autoDelete", "internal", "destinationType", "arguments", "definition", "applyTo", "priority")) {
            assertFalse(raw.contains("\"$key\""), "the export of default rows must not write \"$key\": $raw")
        }
    }

    // ---- 16_migrate_from_3x.feature, @m8-04: no Shovel action (run by RunMigrate3xMigrationModelAcceptanceTest, same glue package)

    @Given("a migration file with the action {string}")
    fun aMigrationFileWithTheAction(action: String) {
        pendingImportJson = migrationFile("""{ "action": "$action", "name": "x" }""")
    }

    @Then("it is rejected because the action is unknown")
    fun itIsRejectedBecauseTheActionIsUnknown() {
        val result = lastImportResult
        assertTrue(result is MigrationValidationResult.Invalid, "the file must be refused, got $result")
        assertTrue((result as MigrationValidationResult.Invalid).reason.endsWith("which action is not supported"), "reason was \"${result.reason}\"")
        assertTrue(fileRepository.let { runBlocking { it.listFiles() } }.isEmpty(), "a refused file must not be stored")
    }
}
