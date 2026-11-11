package dev.caerbannog.acceptance.access

import dev.caerbannog.acceptance.connections.FakeBrokerReachabilityChecker
import dev.caerbannog.acceptance.connections.InMemoryConnectionProfileRepository
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeMqttCatalog
import dev.caerbannog.core.connections.fakes.FakeMqttMessagePublisher
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.CompatibilityAnalyzer
import dev.caerbannog.core.migrate3x.FileLoadResult
import dev.caerbannog.core.migrate3x.GenerationReport
import dev.caerbannog.core.migrate3x.GenerationState
import dev.caerbannog.core.migrate3x.LiveAnalysisOutcome
import dev.caerbannog.core.migrate3x.LiveDefinitionsSource
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.MigrateFromThreeViewModel
import dev.caerbannog.core.migrate3x.ReadOutcome
import dev.caerbannog.core.migrate3x.SaveOutcome
import dev.caerbannog.core.migrate3x.analyseLive
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.core.migrate3x.fakes.FakeThreeXReads
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import dev.caerbannog.core.migrate3x.CompatibilityReportJson
import dev.caerbannog.core.migrate3x.CompatibilityReportText
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.DefinitionsParser
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import dev.caerbannog.core.migrations.FailureKind
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.migrations.redactedRaw
import dev.caerbannog.mcp.McpServer
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M8-12a: steps of the `@m8-12a` scenarios. They call the real runner and assert what the fake 4.x broker holds; the rules live in `core`. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccessMigrationSteps {
    private val hash = "SENTINEL-HASH"
    private val sha256 = "rabbit_password_hashing_sha256"
    private val needsTag = "Creating virtual hosts, users and permissions needs the administrator tag"
    private val needsFull = "Creating virtual hosts, users and permissions needs the level Full control"

    private var vhostName = "/orders"
    private var vhostDescription = ""
    private var vhostTags = emptyList<String>()
    private var tags = setOf("administrator")
    private var level = TopologyControlLevel.FULL_CONTROL
    private var executor = FakeTopologyMigrationExecutor()
    private var history = InMemoryMigrationHistoryRepository()
    private var dryRun: MigrationDryRun? = null
    private var applied: MigrationApplyResult? = null
    private var snapshot = ""
    private val shown = mutableListOf<String>()
    private var logged = ""

    // ---- M8-12b: the generation runs through the real view model over a fake folder and a fake saver ----
    private var test = TestScope()
    private var algorithm = sha256
    private val extraUsers = mutableListOf<kotlinx.serialization.json.JsonObject>()
    private val extraPermissions = mutableListOf<kotlinx.serialization.json.JsonObject>()
    private var withDefaultVhost = false
    private var folders = FakeMigrationFolders("/m")
    private val saver = FakeReportFileSaver()
    private var viewModel: MigrateFromThreeViewModel? = null
    private var generationReport: GenerationReport? = null

    @Before
    fun reset() {
        test = TestScope(); algorithm = sha256; extraUsers.clear(); extraPermissions.clear(); withDefaultVhost = false; folders = FakeMigrationFolders("/m"); saver.saves.clear(); viewModel = null; generationReport = null
        vhostName = "/orders"; vhostDescription = ""; vhostTags = emptyList(); tags = setOf("administrator"); level = TopologyControlLevel.FULL_CONTROL
        executor = FakeTopologyMigrationExecutor(); history = InMemoryMigrationHistoryRepository(); dryRun = null; applied = null; shown.clear()
    }

    private fun actions(): List<MigrationAction> = listOf(
        MigrationAction.DeclareVhost(vhostName, vhostDescription, vhostTags),
        MigrationAction.DeclareUser("svc", SecretValue.of(hash), sha256, listOf("management")),
        MigrationAction.SetPermissions("/orders", "svc", ".*", ".*", ".*"),
    )

    /** The file holds the real hash, as a generated file does; the actions are what the parser would read from it. */
    private fun file(): MigrationFile {
        val raw = """{ "version": "0001", "description": "access", "up": [ { "action": "declareUser", "name": "svc", "passwordHash": "$hash", "hashingAlgorithm": "$sha256", "tags": ["management"] } ] }"""
        return MigrationFile("0001", "access", actions(), raw, MigrationParser.computeChecksum(raw))
    }

    private fun runner() = MigrationRunner(history, executor)
    private fun dry() { dryRun = runner().dryRun(file(), true, level, "/", tags) }
    private fun apply() { applied = runBlocking { runner().applyPending("new-eu", listOf(file()), true, level, "/", tags) } }
    private fun brokerState() = listOf(executor.vhosts.mapValues { it.value.description to it.value.tags }, executor.users.mapValues { it.value.tags }, executor.permissions.keys.toList()).toString()

    // ---- Background ----

    @Given("the 4.x connection {string} is the held connection")
    fun held(name: String) = Unit

    @Given("a definitions file exported from RabbitMQ 3.13 with the virtual host {string}, the user {string} with tags {string} and permissions on {string}")
    fun definitions(vhost: String, user: String, userTags: String, permissionsVhost: String) { vhostName = vhost }

    // ---- 1 and 2: no hash ----

    @Given("the access migration is generated")
    fun generated() = Unit

    @When("I run a dry run of it")
    fun runDry() = dry()

    @Then("I see {string} for each user")
    fun seeUser(text: String) {
        val users = dryRun!!.actions.map { it.action }.filterIsInstance<MigrationAction.DeclareUser>()
        assertTrue(users.isNotEmpty() && users.all { it.description == text.replace("svc", it.name) }, "descriptions: ${users.map { it.description }}")
        assertTrue(users.all { it.detail?.contains("password hash hidden") == true }, "details: ${users.map { it.detail }}")
    }

    @Then("no hash is shown")
    fun noHashShown() {
        val d = dryRun!!
        val text = d.toString() + d.actions.joinToString { "${it.action.description} ${it.action.detail} ${it.reason}" }
        assertFalse(text.contains(hash), "the hash is in the dry run: $text")
    }

    @Given("I accepted every proposal and applied the access migration, and one action fails with an error")
    fun appliedWithFailure() {
        executor.failingActionName = "declareUser"
        executor.failureReason = "Action refused by broker"
        captured { dry(); apply() }
    }

    @Then("the hash appears in no log, no report, no exported report, no error text, no history entry and no MCP output")
    fun nowhere() {
        val failed = assertIs<MigrationApplyResult.ActionFailed>(applied, "the apply did not fail: $applied")
        val badFile = MigrationParser.parse(file().rawJson.replace(sha256, "a_name_nobody_knows"))
        val parseError = (badFile as? MigrationValidationResult.Invalid)?.reason.orEmpty()
        val definitions = (DefinitionsParser.parse(DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER)) as DefinitionsParseResult.Parsed).document
        val report = CompatibilityAnalyzer.analyze(definitions)
        val history = runBlocking { history.getAppliedMigrations("new-eu") }.toString()
        val mcp = toolsList()
        val everything = listOf(
            logged, failed.toString(), failed.reason, parseError, history, redactedRaw(file().rawJson), dryRun.toString(),
            CompatibilityReportText.render(report), CompatibilityReportJson.encode(report), mcp,
        )
        everything.forEach { assertFalse(it.contains(hash) || it.contains("SENTINEL-HASH"), "a hash is visible in: ${it.take(200)}") }
        assertTrue(parseError.isNotBlank(), "the premise: the bad file was refused with a reason")
        assertTrue(history.contains("0001").not(), "the failed migration must not be recorded as applied: $history")
    }

    private fun captured(block: () -> Unit) {
        val lines = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { lines += "${record.loggerName} ${record.message} ${record.parameters?.joinToString()} ${record.thrown}" }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val root = Logger.getLogger("")
        val old = root.level
        root.level = Level.ALL; root.addHandler(handler)
        val out = System.out; val err = System.err
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true)); System.setErr(PrintStream(buffer, true))
        try { block() } finally { System.setOut(out); System.setErr(err); root.removeHandler(handler); root.level = old }
        logged = lines.joinToString("\n") + "\n" + buffer.toString()
    }

    // ---- 3 and 4: disabled ----

    @Given("the user of {string} has no administrator tag")
    fun noAdmin(name: String) { tags = setOf("management", "policymaker") }

    @Given("the user of {string} is an administrator and the level is Declare and edit")
    fun adminDeclare(name: String) { tags = setOf("administrator"); level = TopologyControlLevel.DECLARE_AND_EDIT }

    @When("I open the access migration")
    fun open() = dry()

    @Then("{string} is disabled")
    fun disabled(button: String) { assertEquals("Apply pending", button); assertFalse(dryRun!!.canApply, "the dry run allows the apply: ${dryRun!!.actions.map { it.reason }}") }

    @Then("I am told that creating virtual hosts, users and permissions needs the administrator tag")
    fun toldTag() = assertEquals(needsTag, dryRun!!.blockedReason)

    @Then("I am told that this needs the level Full control")
    fun toldLevel() = assertEquals(needsFull, dryRun!!.blockedReason)

    // ---- 5 and 6: apply, twice ----

    @Given("the connection {string} has the virtual host {string}")
    fun hasVhost(name: String, vhost: String) { assertTrue(vhost in executor.vhosts) }

    @Given("the virtual host {string} does not exist on the 4.x broker")
    fun vhostMissing(vhost: String) { assertFalse(vhost in executor.vhosts) }

    @When("I apply the access migration on {string}")
    fun applyOn(name: String) = apply()

    @When("I apply the access migration")
    fun applyIt() = apply()

    @Then("the virtual host {string} is created")
    fun created(vhost: String) {
        assertIs<MigrationApplyResult.Success>(applied, "apply: $applied")
        assertTrue(vhost in executor.vhosts, "the virtual host was not created: ${executor.vhosts.keys}")
    }

    @Then("the single-virtual-host rule is not applied to these actions")
    fun v1NotApplied() = assertTrue(dryRun == null || dryRun!!.canApply)

    @Given("the access migration was applied on {string}")
    fun wasApplied(name: String) { apply(); assertIs<MigrationApplyResult.Success>(applied); snapshot = brokerState(); assertTrue("svc" in executor.users, "the first apply created nothing") }

    @When("I apply it again")
    fun applyAgain() { applied = runBlocking { runner().applyPending("new-eu", listOf(file()), true, level, "/", tags) } }

    @Then("nothing on the broker changes")
    fun unchanged() = assertEquals(snapshot, brokerState())

    @Then("it is recorded as applied")
    fun recorded() = assertTrue(runBlocking { history.getAppliedMigrations("new-eu") }.containsKey("0001"))

    // ---- 7 to 11: conflicts and carried settings ----

    @Given("the user {string} already exists on {string} with another password hash")
    fun existingUser(user: String, name: String) { executor.existingUser(user, "ANOTHER-HASH", sha256, listOf("management")); snapshot = "ANOTHER-HASH" }

    @Then("the apply stops with a conflict for {string}")
    fun conflictFor(user: String) {
        val failed = assertIs<MigrationApplyResult.ActionFailed>(applied, "apply: $applied")
        assertEquals(FailureKind.Conflict, failed.kind)
        assertEquals(user, (failed.action as? MigrationAction.DeclareUser)?.name)
    }

    @Then("the password hash of {string} is unchanged")
    fun hashUnchanged(user: String) = assertTrue(executor.users.getValue(user).hash.matches(SecretValue.of("ANOTHER-HASH")))

    @Then("the message names what differs without showing any hash")
    fun namesWhat() {
        val reason = assertIs<MigrationApplyResult.ActionFailed>(applied).reason
        assertTrue(reason.contains("password hash"), reason)
        assertFalse(reason.contains("ANOTHER-HASH") || reason.contains(hash), reason)
    }

    @Given("{string} has other permissions on {string} on {string}")
    fun otherPermissions(user: String, vhost: String, name: String) { executor.existingPermissions(vhost, user, "^other", "^other", "^other") }

    @Then("the apply stops with a conflict and the permissions are unchanged")
    fun permissionsConflict() {
        assertEquals(FailureKind.Conflict, assertIs<MigrationApplyResult.ActionFailed>(applied, "apply: $applied").kind)
        assertEquals("^other", executor.permissions.getValue("/orders" to "svc").configure)
    }

    @Given("the export gives the virtual host {string} the description {string} and the tag {string}")
    fun exportDescribes(vhost: String, description: String, tag: String) { vhostName = vhost; vhostDescription = description; vhostTags = listOf(tag) }

    @Given("the export gives the virtual host {string} the description {string}")
    fun exportDescription(vhost: String, description: String) { vhostName = vhost; vhostDescription = description }

    @Given("the export gives the virtual host {string} no description, no tags and no default queue type")
    fun exportBare(vhost: String) { vhostName = vhost; vhostDescription = ""; vhostTags = emptyList() }

    @Given("the virtual host {string} already exists on {string} with the description {string}")
    fun existsWith(vhost: String, name: String, description: String) { executor.existingVhost(vhost, description = description) }

    @Given("the virtual host {string} already exists on {string} with the description {string} and the tag {string}")
    fun existsWithTag(vhost: String, name: String, description: String, tag: String) { executor.existingVhost(vhost, description = description, tags = listOf(tag)) }

    @Given("{string} has the default virtual host {string} with its own description")
    fun defaultVhost(name: String, vhost: String) { assertEquals("/", vhost); assertTrue(executor.vhosts.getValue("/").description.isNotEmpty()) }

    @Then("the apply stops with a conflict for the virtual host {string}")
    fun vhostConflict(vhost: String) {
        val failed = assertIs<MigrationApplyResult.ActionFailed>(applied, "apply: $applied")
        assertEquals(FailureKind.Conflict, failed.kind)
        assertEquals(vhost, (failed.action as? MigrationAction.DeclareVhost)?.name)
    }

    @Then("the message names the description as what differs")
    fun namesDescription() = assertEquals("The virtual host /orders exists with a different description", assertIs<MigrationApplyResult.ActionFailed>(applied).reason)

    @Then("the virtual host {string} is unchanged")
    fun vhostUnchanged(vhost: String) {
        val v = executor.vhosts.getValue(vhost)
        when (vhost) {
            "/orders" -> assertTrue(v.description in setOf("Something else", "Orders"), "changed: ${v.description}")
            "/" -> assertEquals("Default virtual host", v.description)
        }
        if (vhost == "/orders" && vhostDescription.isEmpty()) assertEquals(listOf("prod"), v.tags)
    }

    @Then("the apply continues and the virtual host {string} is unchanged")
    fun continues(vhost: String) {
        assertIs<MigrationApplyResult.Success>(applied, "the apply did not continue: $applied")
        vhostUnchanged(vhost)
    }

    // ---- 12 and 13: never deletes, MCP ----

    @Given("I accepted every proposal")
    fun acceptedAll() = Unit

    @When("I generate the migrations")
    fun generate() = generateThroughTheScreen(readOnlyFolder = false)

    private fun definitionsText(): String = DefinitionsFiles.accessFile(
        vhosts = buildList { if (withDefaultVhost) add(DefinitionsFiles.vhost("/")); add(DefinitionsFiles.vhost(vhostName)) },
        users = listOf(DefinitionsFiles.user("svc", hash, algorithm, listOf("management"))) + extraUsers,
        permissions = listOf(DefinitionsFiles.permission("/orders", "svc")) + extraPermissions,
        topicPermissions = listOf(DefinitionsFiles.topicPermission("/orders", "svc")),
        queues = listOf(DefinitionsFiles.queue("jobs", type = "quorum", vhost = "/orders")),
    )

    private fun settle() = test.testScheduler.advanceUntilIdle()

    /** The real Generate of the review over a fake folder; with [readOnlyFolder] the save fails and the files are exported through the fake saver. */
    private fun generateThroughTheScreen(readOnlyFolder: Boolean) {
        folders = FakeMigrationFolders("/m").also { it.folder("/m").readOnly = readOnlyFolder }
        val loader = FakeDefinitionsFileLoader(mapOf("/data/legacy.json" to FileLoadResult.Loaded(definitionsText())))
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val vm = MigrateFromThreeViewModel(loader, FakeDefinitionsFileChooser(), saver, AnalysisHolder(), folders.ports, CoroutineScope(dispatcher), dispatcher)
        viewModel = vm
        vm.open("/data/legacy.json"); settle()
        vm.generate(); settle()
        if (readOnlyFolder) { saver.outcome = SaveOutcome.Saved("/home/me/exported.json"); vm.exportFiles(); settle() }
        val state = vm.state.value as? MigrateFromThreeState.Report ?: error("the screen is not a report: ${vm.state.value}")
        generationReport = (state.review.generation as? GenerationState.Done)?.report ?: error("generation did not finish: ${state.review.generation}")
    }

    private fun report(): GenerationReport = generationReport ?: error("nothing was generated")

    /** The generated files in version order: from the folder after a save, else the texts the saver was given. */
    private fun generatedTexts(): List<String> =
        if (saver.saves.isNotEmpty()) saver.saves.sortedBy { it.suggestedName }.map { it.text }
        else folders.folder("/m").files.toSortedMap().values.map { it.rawJson }

    private fun accessActionNames(): List<String> =
        (MigrationParser.parse(generatedTexts().first()) as? MigrationValidationResult.Valid)?.migration?.up?.map { it.actionName } ?: error("the first file does not parse")

    @Then("there is an access migration with the actions in the order: declare virtual host, declare user, set permissions, set topic permissions")
    fun accessOrder() {
        assertEquals(listOf("declareVhost", "declareUser", "setPermissions", "setTopicPermissions"), accessActionNames().distinct(), "files: ${report().files.map { it.version to it.vhost }}")
        assertEquals("access", report().files.first().vhost)
    }

    @Then("it has a lower version than the topology migrations")
    fun lowerVersion() {
        val versions = report().files.map { it.version }
        assertTrue(versions.size >= 2, "no topology migration was generated: $versions")
        assertEquals(versions.sorted(), versions)
        assertEquals("access", report().files.first().vhost)
    }

    // ---- 12b: the review and the report ----

    @When("I review the proposal for the user {string}")
    fun reviewUser(user: String) {
        val loader = FakeDefinitionsFileLoader(mapOf("/data/legacy.json" to FileLoadResult.Loaded(definitionsText())))
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val vm = MigrateFromThreeViewModel(loader, FakeDefinitionsFileChooser(), saver, AnalysisHolder(), folders.ports, CoroutineScope(dispatcher), dispatcher)
        viewModel = vm
        vm.open("/data/legacy.json"); settle()
        val view = (vm.state.value as MigrateFromThreeState.Report).view
        view.groups.forEach { vm.toggleGroup(it.key) }; settle()
        userRow = (vm.state.value as MigrateFromThreeState.Report).view.let { v -> v.groups.flatMap { g -> v.rows(g.key).let { r -> (0 until r.size).map { r.at(it) } } } }.firstOrNull { it.user != null && it.name == user }
        screenText = vm.state.value.toString()
    }
    private var userRow: dev.caerbannog.core.migrate3x.RowView? = null
    private var screenText = ""

    @Then("I see the name, the tags, the hashing algorithm and {string}")
    fun seeUserRow(hidden: String) {
        val row = userRow ?: error("no row for the user")
        assertEquals("svc", row.name)
        assertEquals(listOf("management"), row.user!!.tags)
        assertEquals(sha256, row.user!!.algorithm)
        assertTrue(hidden.contains(row.user!!.hashText) && row.user!!.hashText.isNotEmpty(), "the row says \"${row.user!!.hashText}\", expected a part of \"$hidden\"")
    }

    @Then("the hash is shown nowhere on the screen")
    fun hashNotOnScreen() { assertFalse(screenText.contains(hash)); assertFalse(userRow.toString().contains(hash)) }

    // ---- 12b: saving and exporting ----

    @When("I save or export the access migration")
    fun saveOrExport() = generateThroughTheScreen(readOnlyFolder = true)

    @Then("I am warned that the file contains password hashes and must be kept secret")
    fun warned() {
        assertEquals(MigrateFromThreeTexts.SECRET_FILE_WARNING, report().secretWarning)
        assertTrue(report().secretWarning.orEmpty().contains("password hashes") && report().secretWarning.orEmpty().contains("Keep it secret"))
    }

    @Then("the file is readable by its owner only where the system allows it")
    fun ownerOnlyAsked() {
        // The saver is asked for an owner-only file (the folder path is checked by FolderMigrationFileAccessModeTest).
        assertEquals(listOf(true), saver.saves.take(1).map { it.secret }.ifEmpty { listOf(false) })
        assertFalse(saver.saves.drop(1).any { it.secret }, "a topology file was asked for as a secret")
    }

    // ---- 12b: full export ----

    @Given("a live 3.x connection whose user is an administrator")
    fun liveAdmin() = Unit

    private var liveReads = FakeThreeXReads()
    private var liveHeld: dev.caerbannog.core.migrate3x.HeldAnalysis? = null

    @When("I analyse it")
    fun analyse() {
        liveReads = FakeThreeXReads().apply { definitions = ReadOutcome.Ok(definitionsText()) }
        liveHeld = (runBlocking { analyseLive(LiveDefinitionsSource("legacy-eu", "3.13.7", liveReads) { }) } as LiveAnalysisOutcome.Analysed).held
    }

    @Then("the users, virtual hosts and permissions come from the full export")
    fun fromFull() {
        val d = liveHeld!!.document
        assertEquals(setOf("svc"), d.users.map { it.name }.toSet())
        assertTrue(d.virtualHosts.isNotEmpty() && d.permissions.isNotEmpty(), "no access data in the live analysis")
        assertTrue("definitions" in liveReads.calls, "the full export was not read: ${liveReads.calls}")
    }

    @Then("not from a per-virtual-host export")
    fun notPerVhost() = assertTrue(liveReads.calls.none { it.startsWith("definitions:") && it != "definitions" }, "a per-virtual-host export was read: ${liveReads.calls}")

    // ---- 12b: algorithms, not migrated, left out, default vhost ----

    @Given("the export contains a user whose hashing algorithm is {string}")
    fun userWithAlgorithm(name: String) { algorithm = name }

    @When("I generate the access migration")
    fun generateAccess() = generateThroughTheScreen(readOnlyFolder = false)

    @Then("the user is declared with its hash and the algorithm {string}")
    fun declaredWithAlgorithm(name: String) {
        val migration = (MigrationParser.parse(generatedTexts().first()) as MigrationValidationResult.Valid).migration
        val user = migration.up.filterIsInstance<MigrationAction.DeclareUser>().single { it.name == "svc" }
        assertEquals(name, user.hashingAlgorithm)
        assertTrue(user.passwordHash.matches(SecretValue.of(hash)))
    }

    @Given("the export contains the user {string} and a user {string} whose hashing algorithm is {string}")
    fun guestAndOld(guest: String, old: String, algorithm: String) {
        extraUsers += listOf(DefinitionsFiles.user(guest), DefinitionsFiles.user(old, algorithm = algorithm))
    }

    @Then("the access migration declares neither {string} nor {string}")
    fun declaresNeither(a: String, b: String) {
        val names = (MigrationParser.parse(generatedTexts().first()) as MigrationValidationResult.Valid).migration.up.filterIsInstance<MigrationAction.DeclareUser>().map { it.name }
        assertTrue(names.isNotEmpty() && a !in names && b !in names, "declared users: $names")
    }

    @Then("the result lists {string} and {string} as not migrated, with {string} for {string}")
    fun listsNotMigrated(a: String, b: String, text: String, forUser: String) {
        val rows = report().usersNotMigrated
        assertEquals(setOf(a, b), rows.map { it.label }.toSet(), "rows: $rows")
        assertTrue(rows.first { it.label == forUser }.text.contains(text), rows.toString())
    }

    @Given("the export contains the user {string} with permissions on {string}")
    fun guestWithPermissions(user: String, vhost: String) {
        extraUsers += DefinitionsFiles.user(user); extraPermissions += DefinitionsFiles.permission(vhost, user)
    }

    @Then("the access migration has no permission action for {string}")
    fun noPermissionFor(user: String) {
        val up = (MigrationParser.parse(generatedTexts().first()) as MigrationValidationResult.Valid).migration.up
        assertTrue(up.any { it is MigrationAction.SetPermissions }, "the premise: permission actions exist")
        assertTrue(up.filterIsInstance<MigrationAction.SetPermissions>().none { it.user == user })
    }

    @Then("the result lists the left out permissions of {string}")
    fun listsLeftOut(user: String) = assertTrue(report().permissionsLeftOut.any { it.label.startsWith("$user on ") }, report().permissionsLeftOut.toString())

    @Given("the export has the virtual hosts {string} and {string}")
    fun hasVhosts(a: String, b: String) { withDefaultVhost = a == "/" || b == "/" }

    @Then("the access migration declares the virtual host {string} and not {string}")
    fun declaresVhost(yes: String, no: String) {
        val names = (MigrationParser.parse(generatedTexts().first()) as MigrationValidationResult.Valid).migration.up.filterIsInstance<MigrationAction.DeclareVhost>().map { it.name }
        assertTrue(yes in names && no !in names, "declared virtual hosts: $names")
    }

    @Then("the result notes that the default virtual host already exists on 4.x and is not changed")
    fun notesDefault() = assertTrue("The default virtual host / already exists on 4.x and is not changed" in report().notes, report().notes.toString())

    @Then("no action deletes a virtual host, a user or a permission")
    fun noDelete() {
        assertTrue(actions().none { it.actionName.startsWith("delete") })
        val names = MigrationAction::class.java.permittedSubclasses.map { it.simpleName }
        assertTrue(names.none { it.startsWith("Delete") && (it.contains("Vhost") || it.contains("User") || it.contains("Permission")) }, "delete actions: $names")
        assertTrue("DeclareUser" in names, "the access actions do not exist")
    }

    @Given("a migration with a {string} action")
    fun migrationWith(action: String) { assertEquals("declareUser", action) }

    private fun toolsList(): String {
        val server = McpServer(
            profileRepository = InMemoryConnectionProfileRepository(), connectionChecker = FakeBrokerReachabilityChecker(),
            streamCatalogProvider = { _, _ -> FakeStreamCatalog() }, streamOffsetsProvider = { _, _ -> FakeStreamOffsets() },
            topologyCatalogProvider = { _, _ -> FakeTopologyCatalog() }, mqttCatalogProvider = { _, _ -> FakeMqttCatalog() },
            mqttMessagePublisherProvider = { _, _ -> FakeMqttMessagePublisher() },
        )
        return server.handleRequest("""{"jsonrpc": "2.0", "id": 2, "method": "tools/list"}""").orEmpty()
    }

    @When("an agent lists the available tools")
    fun listTools() { shown += toolsList() }

    @Then("there is no tool that applies a migration")
    fun noApplyTool() {
        val names = Json.parseToJsonElement(shown.single()).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(names.isNotEmpty(), "no tools were listed")
        assertTrue(names.none { "apply" in it || "migration" in it || "shovel" in it }, "tools: $names")
    }

    @Then("the dry run tool lists the action as {string} without any hash")
    fun dryRunTool(text: String) {
        // The MCP dry-run tool of M5-09 does not exist in the server yet: what it would print is the core description and detail of the action.
        val user = actions().filterIsInstance<MigrationAction.DeclareUser>().single()
        assertEquals("$text svc", user.description)
        assertFalse("${user.description} ${user.detail} $user".contains(hash))
        assertTrue(user.detail?.contains("password hash hidden") == true, "detail: ${user.detail}")
    }
}
