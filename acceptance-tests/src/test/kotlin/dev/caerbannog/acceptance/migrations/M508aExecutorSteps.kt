package dev.caerbannog.acceptance.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.PagedTopologyCatalog
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.datarabbit.ManagementTopologyMigrationExecutor
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * M5-08a step definitions: the `@m5-08a` scenarios of tests/bdd/features/10_topology_migrations.feature and the one of 16 ("Applying a generated
 * migration reads back every declared queue"). The REAL runner and the REAL management executor (data-rabbit) run over a Ktor mock engine in front
 * of [FakeManagementBroker], an honest in-memory model of the management API, with in-memory repositories. The shared scenario state (view model,
 * repositories, last result) is that of [TopologyMigrationsSteps], reached through `TopologyMigrationsSteps.current`: Cucumber has no DI here.
 * Nothing sleeps: the read-back delay is a no-op lambda. The scenarios that never touch the broker's resources still use the real executor.
 */
class M508aExecutorSteps {

    private val s: TopologyMigrationsSteps get() = checkNotNull(TopologyMigrationsSteps.current) { "no scenario state" }

    private var broker = FakeManagementBroker()
    private var executor: TopologyMigrationExecutor? = null
    private var installed = false
    private val sentinel = "SENTINEL-broker-text-31c7"

    /** What the broker held for a queue when the scenario said "unchanged" would matter. */
    private val queueBaseline = mutableMapOf<String, String?>()
    private var snapshotBefore = ""
    private var requestMark = 0
    private var firstRunQueues = emptyMap<String, String?>()

    @Before
    fun reset() {
        broker = FakeManagementBroker()
        executor = null
        installed = false
        queueBaseline.clear()
        snapshotBefore = ""
        requestMark = 0
    }

    /** Puts the real runner and executor in front of the scripted broker. Must run before the view model is built. */
    private fun real(): TopologyMigrationExecutor {
        executor?.let { return it }
        val engine = MockEngine { request ->
            val body = String(request.body.toByteArray())
            val reply = broker.handle(request.method.value, request.url.encodedPath, body.ifEmpty { null })
            respond(reply.body, HttpStatusCode.fromValue(reply.status), headersOf("Content-Type", "application/json"))
        }
        val created = ManagementTopologyMigrationExecutor.overClient(
            client = HttpClient(engine), baseUrl = "http://broker.test:15672", username = "svc", password = "pw",
            streams = broker.streamManager(), readBackDelay = { },
        )
        executor = created
        s.runner = MigrationRunner(s.historyRepository, created)
        s.viewModel = null
        installed = true
        return created
    }

    private fun queueJson(version: String, vararg queues: String, vhost: String? = null, arguments: String? = null): String {
        val actions = queues.joinToString(", ") { name ->
            """{ "action": "declareQueue", "name": "$name", "type": "quorum"${vhost?.let { ", \"vhost\": \"$it\"" } ?: ""}${arguments?.let { ", \"arguments\": $it" } ?: ""} }"""
        }
        return """{ "version": "$version", "description": "m5-08a", "up": [ $actions ] }"""
    }

    private fun import(json: String) = runBlocking {
        val result = s.fileRepository.importFile(json)
        assertTrue(result is MigrationValidationResult.Valid, "the scenario's own file was refused: $result")
        s.getVm().loadMigrations()
    }

    private fun failed(): MigrationApplyResult.ActionFailed =
        s.lastApplyResult as? MigrationApplyResult.ActionFailed ?: error("expected the apply to fail at an action but it gave ${s.lastApplyResult}")

    private fun requestsSince(mark: Int) = broker.requests.drop(mark)

    // ---- A failing action stops the migration; a rerun finishes it ------------------------------------------------------------

    @Given("a pending migration {string} with three actions whose second action is refused by the broker")
    fun pendingWithSecondRefused(version: String) {
        real()
        broker.answerContaining("PUT", "/api/queues/%2F/q-$version-2", 403, sentinel)
        import(queueJson(version, "q-$version-1", "q-$version-2", "q-$version-3"))
    }

    /** M5-08c1: the scenario of the screen. A 400 on a queue that does not exist is the executor's fixed "the broker refused the request". */
    @Given("a pending migration {string} whose second action is refused by the broker")
    fun pendingWithSecondRefusedOnScreen(version: String) {
        real()
        broker.answerContaining("PUT", "/api/queues/%2F/q-$version-2", 400, sentinel)
        import(queueJson(version, "q-$version-1", "q-$version-2", "q-$version-3"))
    }

    @Then("the first action was applied and the third action was never sent")
    fun firstAppliedThirdNeverSent() {
        assertTrue(broker.hasQueue("/", "q-0003-1"), "the first action was not applied")
        assertFalse(broker.hasQueue("/", "q-0003-3"), "the third action was applied")
        assertTrue(broker.requests.none { it.path.contains("q-0003-3") }, "the third action was sent: ${broker.requests}")
    }

    @Then("the message names the second action and a fixed reason, not the broker's text")
    fun namesSecondActionFixedReason() {
        val result = failed()
        assertEquals(2, result.index, "position of the failed action")
        assertEquals(3, result.total)
        assertEquals("q-0003-2", (result.action as MigrationAction.DeclareQueue).name)
        assertTrue(result.reason.isNotBlank())
        assertFalse(result.reason.contains(sentinel), "the broker's text is in the reason: ${result.reason}")
    }

    @Given("a pending migration {string} with three actions whose second action was refused by the broker in the first run")
    fun pendingSecondRefusedInFirstRun(version: String) = runBlocking {
        pendingWithSecondRefused(version)
        s.lastApplyResult = s.getVm().applySingle(version)
        assertEquals(2, failed().index, "the premise: the first run fails at action 2")
        firstRunQueues = mapOf("q-$version-1" to broker.queueArguments("/", "q-$version-1")?.toString())
    }

    @Given("the broker no longer refuses it")
    fun brokerNoLongerRefuses() {
        broker.clearRules()
        requestMark = broker.requests.size
    }

    @When("I apply {string} again")
    fun applyAgain(version: String) = runBlocking {
        s.lastApplyResult = s.getVm().applySingle(version)
    }

    @Then("the first action changes nothing on the broker")
    fun firstActionChangesNothing() {
        assertEquals(firstRunQueues["q-0003-1"], broker.queueArguments("/", "q-0003-1")?.toString())
        assertTrue(broker.deletes.isEmpty(), "a rerun deleted something: ${broker.deletes}")
    }

    @Then("the second and third actions are applied")
    fun secondAndThirdApplied() {
        assertTrue(s.lastApplyResult is MigrationApplyResult.Success, "the rerun gave ${s.lastApplyResult}")
        assertTrue(broker.hasQueue("/", "q-0003-2") && broker.hasQueue("/", "q-0003-3"))
    }

    // ---- A rerun after a success changes nothing -----------------------------------------------------------------------------------

    private var appliedActions: List<MigrationAction> = emptyList()

    @Given("a migration that declares a queue, an exchange, a binding and a policy was applied")
    fun migrationWasApplied() = runBlocking {
        real()
        import(
            """{ "version": "0001", "description": "m5-08a", "up": [
                { "action": "declareQueue", "name": "orders", "type": "quorum", "arguments": { "x-delivery-limit": 5 } },
                { "action": "declareExchange", "name": "events", "type": "topic" },
                { "action": "declareBinding", "exchange": "events", "queue": "orders", "routingKey": "order.created" },
                { "action": "declarePolicy", "name": "limits", "pattern": "^orders", "applyTo": "queues", "definition": { "max-length": 500 } }
            ] }""",
        )
        s.lastApplyResult = s.getVm().applySingle("0001")
        assertTrue(s.lastApplyResult is MigrationApplyResult.Success, "the premise: the first apply works, got ${s.lastApplyResult}")
        appliedActions = s.fileRepository.listFiles().single().up
        snapshotBefore = broker.snapshot()
        s.topologyUnchangedCheck = { assertEquals(snapshotBefore, broker.snapshot(), "the broker topology changed") }
    }

    @When("its actions run again")
    fun actionsRunAgain() = runBlocking {
        requestMark = broker.requests.size
        for (action in appliedActions) assertEquals(ActionResult.Success, real().execute(action), action.actionName)
    }

    @Then("no policy request changed anything and no request deleted anything")
    fun noPolicyWriteNoDelete() {
        val after = requestsSince(requestMark)
        assertTrue(after.none { it.path.startsWith("/api/policies") && it.isWrite }, "a policy was written: ${after.filter { it.isWrite }}")
        assertTrue(after.none { it.method == "DELETE" }, "something was deleted: ${after.filter { it.method == "DELETE" }}")
    }

    // ---- A rerun when the broker changed in between ------------------------------------------------------------------------------

    @Given("a pending migration {string} that declares the quorum queues {string} and {string}")
    fun pendingTwoQueues(version: String, first: String, second: String) {
        real()
        import(queueJson(version, first, second))
    }

    @Given("the first run failed at {string}")
    fun firstRunFailedAt(queue: String) = runBlocking {
        broker.answerContaining("PUT", "/api/queues/%2F/$queue", 500, sentinel)
        s.lastApplyResult = s.getVm().applySingle(s.fileRepository.listFiles().single().version)
        assertEquals(2, failed().index, "the premise: the first run fails at $queue")
        broker.clearRules()
    }

    @Given("someone declared {string} with other arguments in the meantime")
    fun someoneDeclaredWithOtherArguments(queue: String) {
        broker.addQueue("/", queue, arguments = mapOf("x-delivery-limit" to JsonPrimitive(99)))
        queueBaseline[queue] = broker.queueArguments("/", queue)?.toString()
    }

    @Then("the apply stops at {string} and says it exists with different settings")
    fun stopsAtAndSaysConflict(queue: String) {
        val result = failed()
        assertEquals(queue, (result.action as MigrationAction.DeclareQueue).name)
        assertTrue(result.reason.contains("exists with different settings"), result.reason)
        assertTrue(result.reason.contains(queue), "the plain name should be in the reason: ${result.reason}")
        assertFalse(result.reason.contains(sentinel))
    }

    @Then("{string} on the broker is unchanged")
    fun queueUnchanged(queue: String) {
        assertTrue(queueBaseline.containsKey(queue), "the scenario recorded no baseline for $queue")
        assertEquals(queueBaseline[queue], broker.queueArguments("/", queue)?.toString())
    }

    // ---- A queue or a policy with other settings --------------------------------------------------------------------------------------

    @Given("the broker has the quorum queue {string} with {string} {int}")
    fun brokerHasQueueWith(queue: String, key: String, value: Int) {
        real()
        broker.addQueue("/", queue, arguments = mapOf(key to JsonPrimitive(value)))
        queueBaseline[queue] = broker.queueArguments("/", queue)?.toString()
    }

    @Given("a pending migration declares the quorum queue {string} with {string} {int}")
    fun pendingDeclaresQueueWith(queue: String, key: String, value: Int) {
        real()
        import(queueJson("0001", queue, arguments = """{ "$key": $value }"""))
    }

    @Then("the apply stops and says {string} exists with different settings")
    fun applyStopsAndSaysConflict(name: String) {
        val result = failed()
        assertTrue(result.reason.contains("exists with different settings"), result.reason)
        assertTrue(result.reason.contains(name), "the plain name should be in the reason: ${result.reason}")
        assertFalse(result.reason.contains(sentinel))
        assertFalse(result.reason.contains("PRECONDITION"), "the broker's text is in the reason: ${result.reason}")
    }

    @Then("no request deleted or recreated anything")
    fun nothingDeletedOrRecreated() {
        assertTrue(broker.deletes.isEmpty(), "something was deleted: ${broker.deletes}")
        val puts = broker.requests.filter { it.method == "PUT" }.groupBy { it.path }
        assertTrue(puts.values.all { it.size == 1 }, "a resource was sent more than once: ${puts.filterValues { it.size > 1 }.keys}")
    }

    @Then("the migration is not recorded as applied")
    fun migrationNotRecorded() = runBlocking {
        assertTrue(s.historyRepository.getAppliedMigrations(s.profileName).isEmpty(), "a migration was recorded: ${s.historyRepository.getAppliedMigrations(s.profileName)}")
    }

    @Given("the broker has the policy {string} on {string} with {string} {int}")
    fun brokerHasPolicy(name: String, pattern: String, key: String, value: Int) {
        real()
        broker.addPolicy("/", name, pattern, mapOf(key to JsonPrimitive(value)))
    }

    @Given("a pending migration declares the policy {string} on {string} with {string} {int}")
    fun pendingDeclaresPolicy(name: String, pattern: String, key: String, value: Int) {
        real()
        import(
            """{ "version": "0001", "description": "m5-08a", "up": [
                { "action": "declarePolicy", "name": "$name", "pattern": "$pattern", "definition": { "$key": $value } } ] }""",
        )
    }

    @Then("the policy {string} on the broker still has {string} {int}")
    fun policyStillHas(name: String, key: String, value: Int) {
        assertEquals(JsonPrimitive(value), broker.policyDefinition("/", name)!![key])
    }

    @Then("no policy was written")
    fun noPolicyWritten() {
        assertEquals(0, broker.policyWrites, "a policy PUT reached the broker")
    }

    @Then("the migration is recorded as applied and no policy request changed anything")
    fun recordedAndNoPolicyWrite() = runBlocking {
        assertTrue(s.lastApplyResult is MigrationApplyResult.Success, "the apply gave ${s.lastApplyResult}")
        assertEquals(setOf("0001"), s.historyRepository.getAppliedMigrations(s.profileName).keys)
        assertEquals(0, broker.policyWrites, "an equal policy was written again")
        assertTrue(broker.writes.none { it.path.startsWith("/api/policies") }, broker.writes.toString())
    }

    // ---- Deleting something that is already gone ---------------------------------------------------------------------------------------

    @Given("a pending migration that deletes the queue {string} and the connection has the level {string}")
    fun pendingDeletesQueue(queue: String, level: String) {
        real()
        s.controlLevel = when (level) {
            "Full control" -> TopologyControlLevel.FULL_CONTROL
            "Declare and edit" -> TopologyControlLevel.DECLARE_AND_EDIT
            "Read-only" -> TopologyControlLevel.READ_ONLY
            else -> error("unknown level $level")
        }
        s.viewModel = null
        import("""{ "version": "0001", "description": "m5-08a", "up": [ { "action": "deleteQueue", "name": "$queue" } ] }""")
    }

    @Given("the broker has no queue {string}")
    fun brokerHasNoQueue(queue: String) {
        assertFalse(broker.hasQueue("/", queue))
    }

    @Then("the migration is recorded as applied")
    fun migrationRecorded() = runBlocking {
        assertTrue(s.lastApplyResult is MigrationApplyResult.Success, "the apply gave ${s.lastApplyResult}")
        assertEquals(setOf("0001"), s.historyRepository.getAppliedMigrations(s.profileName).keys)
    }

    // ---- Read-back (feature 10 and feature 16) -----------------------------------------------------------------------------------------

    @Given("a pending migration that declares the quorum queues {string} and {string}")
    fun pendingTwoQueuesNoVersion(first: String, second: String) {
        real()
        import(queueJson("0001", first, second))
    }

    @Given("a generated migration that declares the quorum queues {string} and {string}")
    fun generatedTwoQueues(first: String, second: String) {
        real()
        import(queueJson("0001", first, second))
    }

    @Given("the broker silently does not create {string}")
    fun brokerSilentlyDoesNotCreate(queue: String) {
        broker.silentlyDoNotCreateQueue("/", queue)
    }

    @When("I apply it")
    fun applyIt() = runBlocking {
        s.lastApplyResult = s.getVm().applyPending()
    }

    @Then("the apply stops and says {string} was not found after it was declared")
    fun stopsAndSaysNotFound(queue: String) {
        val result = failed()
        assertEquals("$queue was not found after it was declared", result.reason)
        assertEquals(queue, (result.action as MigrationAction.DeclareQueue).name)
    }

    // ---- Another virtual host ------------------------------------------------------------------------------------------------------------

    @Given("a pending migration whose actions belong to the virtual host {string}")
    fun pendingForVhost(vhost: String) {
        real()
        import(queueJson("0001", "orders", "jobs", vhost = vhost))
    }

    @Given("the connection {string} is for the virtual host {string}")
    fun connectionIsForVhost(profile: String, vhost: String) {
        s.profileName = profile
        s.vhost = vhost
        s.getVm().setConnectionContext(profile, vhost, s.configurePermission, s.controlLevel)
    }

    @Then("the dry run says the actions belong to another virtual host and {string} is disabled")
    fun dryRunSaysAnotherVhost(button: String) {
        val state = s.getVm().state.value
        val dry = assertNotNull(state.dryRun, "no dry run shown")
        assertFalse(dry.canApply, "the dry run says it can be applied")
        assertTrue(dry.actions.isNotEmpty() && dry.actions.all { !it.allowed }, "an action of another virtual host is allowed: ${dry.actions}")
        assertTrue(dry.actions.all { it.reason.orEmpty().contains("virtual host") }, "reasons: ${dry.actions.map { it.reason }}")
        assertEquals("Apply pending", button)
        assertFalse(state.isApplyPendingEnabled, "\"Apply pending\" is enabled")
        assertTrue(broker.requests.isEmpty(), "the screen sent something to the broker: ${broker.requests}")
    }

    // ---- A migration that declares and deletes the same thing ------------------------------------------------------------------------------

    @Given("a migration file that declares the queue {string} and also deletes the queue {string}")
    fun fileDeclaresAndDeletes(declared: String, deleted: String) {
        real()
        s.pendingImportJson = """{ "version": "0002", "description": "m5-08a", "up": [
            { "action": "declareQueue", "name": "$declared", "type": "quorum" },
            { "action": "deleteQueue", "name": "$deleted" } ] }"""
    }

    @Then("the import is refused and the message says the file declares and deletes {string} and must be split into two migrations")
    fun importRefusedDeclaresAndDeletes(name: String) = runBlocking {
        val result = s.lastImportResult
        assertTrue(result is MigrationValidationResult.Invalid, "the import gave $result")
        assertEquals("the file declares and deletes \"$name\"; split it into two migrations", (result as MigrationValidationResult.Invalid).reason)
        assertTrue(s.fileRepository.listFiles().isEmpty(), "the refused file was added")
    }

    // ---- Export ------------------------------------------------------------------------------------------------------------------------------

    @Given("the broker refuses to list the queues of {string} for this user")
    fun brokerRefusesToListQueues(vhost: String) {
        assertEquals("/", vhost)
        s.fakeTopologyCatalog.failCall("queues", CatalogResult.NotAuthorised)
    }

    @Then("I am told the topology could not be read and no migration file was written")
    fun toldTopologyNotReadNoFile() = runBlocking {
        assertEquals(null, s.exportedFile, "the export returned a file although the broker could not be read")
        assertEquals("The topology could not be read", s.getVm().state.value.errorMessage)
        assertTrue(s.fileRepository.listFiles().isEmpty(), "a migration file was written: ${s.fileRepository.listFiles().map { it.version }}")
    }

    private var pagedCatalog: PagedTopologyCatalog? = null

    @Given("the broker has {int} queues in {string}")
    fun brokerHasQueues(count: Int, vhost: String) {
        assertEquals("/", vhost)
        for (i in 1..count) {
            s.fakeTopologyCatalog.addQueue(
                dev.caerbannog.core.topology.QueueRow("queue-%03d".format(i), "/", "quorum", 0, 0, 0, "rabbit@n", listOf("rabbit@n"), listOf("rabbit@n"), null, "running"),
            )
        }
        // the real broker pages at 500; a smaller page here makes 150 queues several pages (the reader must not assume a page size)
        pagedCatalog = PagedTopologyCatalog(s.fakeTopologyCatalog, pageSize = 40).also { s.topologyCatalogOverride = it }
        // The Background may already have built the view model with the plain catalog; the next getVm() builds it with the paged one.
        s.viewModel = null
    }

    @Then("the new migration declares all {int} queues")
    fun newMigrationDeclaresAll(count: Int) {
        assertNotNull(s.exportedFile, "no migration was exported; error: ${s.getVm().state.value.errorMessage}")
        val queues = s.exportedFile!!.up.filterIsInstance<MigrationAction.DeclareQueue>().map { it.name }
        assertEquals(count, queues.size, "queues in the exported file")
        assertEquals(count, queues.toSet().size, "a queue was exported twice")
        assertEquals(listOf(1, 2, 3, 4), pagedCatalog!!.pagesRequested.toList(), "the pages the export asked for")
    }

    // ---- A 3.x source connection cannot apply migrations (F3, fail closed) ------------------------------------------------------

    private var versionServer: com.sun.net.httpserver.HttpServer? = null
    private val versionRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var versionProfile: dev.caerbannog.core.connections.ConnectionProfile? = null
    private var heldConnection: dev.caerbannog.core.connections.HeldConnection? = null

    @After
    fun stopVersionServer() {
        runCatching { heldConnection?.close() }
        versionServer?.stop(0)
        versionServer = null
        heldConnection = null
    }

    @Given("a connection {string} to a broker whose overview reports RabbitMQ {word}")
    fun connectionToBrokerReporting(name: String, version: String) {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 10)
        server.createContext("/") { ex ->
            versionRequests += "${ex.requestMethod} ${ex.requestURI.rawPath}"
            val body = when (ex.requestURI.rawPath) {
                "/api/whoami" -> """{"name":"svc","tags":["administrator"]}"""
                "/api/overview" -> """{"rabbitmq_version":"$version","erlang_version":"26"}"""
                else -> "{}"
            }.toByteArray()
            ex.sendResponseHeaders(if (ex.requestURI.rawPath == "/api/whoami" || ex.requestURI.rawPath == "/api/overview") 200 else 404, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        versionServer = server
        versionProfile = dev.caerbannog.core.connections.ConnectionProfile(
            name = name, host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = 1, managementPort = server.address.port, tls = false,
        )
    }

    @When("I connect to {string}")
    fun iConnectTo(name: String) = runBlocking {
        val profile = checkNotNull(versionProfile) { "no connection was described" }
        assertEquals(name, profile.name)
        val secrets = object : dev.caerbannog.core.connections.SecretStore {
            override fun store(profileName: String, password: String) = Unit
            override fun retrieve(profileName: String): String = "pw"
            override fun delete(profileName: String) = Unit
        }
        val result = dev.caerbannog.datarabbit.RabbitMqConnectionGateway(secrets).open(profile)
        heldConnection = (result as? dev.caerbannog.core.connections.OpenResult.Opened)?.connection
        assertNotNull(heldConnection, "the connection did not open: $result")
    }

    @Then("the connection has no migration executor and says migrations are not offered because it is a migration source only")
    fun noExecutorSourceOnly() {
        val connection = checkNotNull(heldConnection)
        assertEquals(null, connection.migrationExecutor, "a 3.x connection must have no migration executor")
        assertEquals("This broker runs RabbitMQ 3.x: it is a migration source only", connection.migrationUnavailableReason)
    }

    @Then("no request that changes anything was sent to {string}")
    fun noWriteSent(name: String) {
        assertTrue(versionRequests.isNotEmpty(), "the scenario never reached the broker")
        assertTrue(versionRequests.all { it.startsWith("GET ") }, "a request that changes something was sent: $versionRequests")
    }
}
