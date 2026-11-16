package dev.caerbannog.acceptance.planner

import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.DeclareCheck
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import dev.caerbannog.core.planner.PlanCheck
import dev.caerbannog.core.planner.PlanCheckResult
import dev.caerbannog.core.planner.PlannerContext
import dev.caerbannog.core.planner.StepCheck
import dev.caerbannog.core.planner.StepFinding
import dev.caerbannog.core.planner.TopologyPlan
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.datarabbit.ManagementDeclareCheck
import dev.caerbannog.datarabbit.ManagementTopologyMigrationExecutor
import io.cucumber.datatable.DataTable
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
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the check scenarios share (Cucumber has no dependency injection here): the honest in-memory management API the REAL `ManagementDeclareCheck`
 * (and, for "after applying", the REAL executor) of data-rabbit talk to through a Ktor mock engine, every request recorded, and the last result.
 * The 3.x scenario brings the stub server's facts through [held] and [stubRequests] (see PlannerScreenSteps).
 */
object CheckWorld {
    class Seen(val method: String, val path: String, val body: String)

    var broker = FakeManagementBroker()
    val seen = CopyOnWriteArrayList<Seen>()

    /** A plan the scenario built outside the wizard (a stream, a policy, a delete, another virtual host): the wizard cannot hold those yet. */
    var plan: TopologyPlan? = null
    var result: PlanCheckResult? = null
    var snapshotBeforeCheck: String? = null

    /** The 3.x scenario: the real held connection and what the stub server saw. */
    var held: HeldConnection? = null
    var stubRequests: List<String> = emptyList()
    var requestsAfterConnect = 0

    fun reset() {
        broker = FakeManagementBroker(); seen.clear(); plan = null; result = null; snapshotBeforeCheck = null
        held = null; stubRequests = emptyList(); requestsAfterConnect = 0
    }

    private fun engine() = MockEngine { request ->
        val body = String(request.body.toByteArray())
        seen += Seen(request.method.value, request.url.encodedPath, body)
        val reply = broker.handle(request.method.value, request.url.encodedPath, body.ifEmpty { null })
        respond(reply.body, HttpStatusCode.fromValue(reply.status), headersOf("Content-Type", "application/json"))
    }

    /** The real check over the honest broker. */
    fun realCheck(): DeclareCheck = ManagementDeclareCheck.overClient(HttpClient(engine()), "http://broker.test:15672", "svc", "pw")

    /** The real executor over the same broker, for "applied on the Migrations screen"; its requests are not the check's, they are not recorded. */
    fun realExecutor(): TopologyMigrationExecutor = ManagementTopologyMigrationExecutor.overClient(
        client = HttpClient(MockEngine { request ->
            val body = String(request.body.toByteArray())
            val reply = broker.handle(request.method.value, request.url.encodedPath, body.ifEmpty { null })
            respond(reply.body, HttpStatusCode.fromValue(reply.status), headersOf("Content-Type", "application/json"))
        }),
        baseUrl = "http://broker.test:15672", username = "svc", password = "pw", streams = broker.streamManager(), readBackDelay = { },
    )

    fun seedQueue(name: String, type: String = "quorum") = broker.addQueue("/", name, type = type)
    fun seedExchange(name: String, type: String = "direct") = broker.addExchange("/", name, type = type)

    /** The wording of the results is `M9-06` (Q14); the glue maps the types to the decided words so the scenarios can be read. */
    fun label(finding: StepFinding): String = when (finding) {
        StepFinding.WillBeCreated -> "Will be created"
        StepFinding.AlreadyThere -> "Already there"
        StepFinding.Different -> "Exists with different settings"
        is StepFinding.Missing -> "Needs something that is missing"
        is StepFinding.NotChecked -> "Could not be checked"
        StepFinding.WillBeDeleted -> "Will be deleted"
        StepFinding.AlreadyGone -> "Already gone"
    }

    fun steps(): List<StepCheck> = assertIs<PlanCheckResult.Checked>(result, "the check was refused or did not run: $result").steps

    /** The `it says "..."` of a check result: the sentence of the step that is missing something. */
    fun saysSentence(text: String) {
        val said = steps().mapNotNull { (it.finding as? StepFinding.Missing)?.what }
        assertTrue(text in said, "the check says $said, not \"$text\"")
    }
}

/** M9-05 steps for the `@m9-05` scenarios of `20_topology_planner_check.feature`. The rules are `core` (PlanCheck) and `data-rabbit` (ManagementDeclareCheck): nothing is decided here. */
class PlannerCheckSteps {
    private val d get() = PlannerWorld.driver

    @Before
    fun resetCheckWorld() = CheckWorld.reset()

    /**
     * The check scenarios say nothing about the migrations folder, but a plan can only be saved with one ("Save as migration" is disabled with "Choose a migrations
     * folder first" otherwise), and two of them save: so the folder `/work/migrations` with the latest file `0004` is the default state of every @m9-05 scenario.
     * Runs after the hook of PlannerSteps that builds the driver (`order`), so the driver exists.
     */
    @Before(value = "@m9-05", order = 20000)
    fun defaultFolder() { if (PlannerWorld.driver.migrationsFolder.isEmpty()) PlannerWorld.driver.chooseMigrationsFolder("/work/migrations", "0004") }

    private val declareQueueRow = Regex("^Declare (\\w+) queue (.+)$")
    private val declareExchangeRow = Regex("^Declare (\\w+) exchange (.+)$")
    private val declareStreamRow = Regex("^Declare stream (.+)$")
    private val declarePolicyRow = Regex("^Declare policy (.+)$")
    private val bindRow = Regex("^Bind (.+) to (.+) with (.+)$")
    private val deleteQueueRow = Regex("^Delete queue (.+)$")

    /** One row of a step table or one step text as the action it stands for. A policy row has no pattern or value of its own: the glue's default is `^orders` with `max-length` 1000. */
    private fun actionOf(row: String, vhost: String = d.vhost): MigrationAction {
        declareQueueRow.matchEntire(row)?.let { return MigrationAction.DeclareQueue(it.groupValues[2], it.groupValues[1], vhost = vhost) }
        declareExchangeRow.matchEntire(row)?.let { return MigrationAction.DeclareExchange(it.groupValues[2], it.groupValues[1], vhost = vhost) }
        declareStreamRow.matchEntire(row)?.let { return MigrationAction.DeclareStream(it.groupValues[1], vhost = vhost) }
        declarePolicyRow.matchEntire(row)?.let { return policy(it.groupValues[1], "^orders", "max-length", 1000, vhost) }
        bindRow.matchEntire(row)?.let { return MigrationAction.DeclareBinding(it.groupValues[1], it.groupValues[2], it.groupValues[3], vhost = vhost) }
        deleteQueueRow.matchEntire(row)?.let { return MigrationAction.DeleteQueue(it.groupValues[1], vhost = vhost) }
        error("the step \"$row\" is not one the check steps know")
    }

    private fun policy(name: String, pattern: String, key: String, value: Int, vhost: String = d.vhost) =
        MigrationAction.DeclarePolicy(name, pattern, mapOf(key to ArgumentValue.Whole(value.toLong())), vhost = vhost)

    private fun planOf(vararg actions: MigrationAction, vhost: String = d.vhost) { CheckWorld.plan = TopologyPlan.of("", actions.toList(), vhost) }

    private fun context() = PlannerContext(
        connectionName = d.profile.takeIf { d.connected }, vhost = d.vhost, controlLevel = d.controlLevel,
        hasConfigurePermission = d.configurePermission, unavailableReason = d.unavailableReason,
    )

    private fun currentPlan(): TopologyPlan = CheckWorld.plan ?: d.plan

    // ---- the broker ----

    @Given("the broker has the direct exchange {string} and the quorum queue {string}")
    fun exchangeAndQueue(exchange: String, queue: String) { CheckWorld.seedExchange(exchange); CheckWorld.seedQueue(queue) }

    @Given("the broker has the classic queue {string}")
    fun classicQueue(name: String) = CheckWorld.seedQueue(name, "classic")

    @Given("the broker has the fanout exchange {string}")
    fun fanout(name: String) = CheckWorld.seedExchange(name, "fanout")

    @Given("the broker has the stream {string}")
    fun stream(name: String) = CheckWorld.seedQueue(name, "stream")

    /** A binding cannot exist without its ends on a real broker: the fake gets both, unless the scenario gave them already. */
    @Given("the broker has the binding from {string} to {string} with routing key {string}")
    fun binding(exchange: String, queue: String, key: String) {
        if (!CheckWorld.broker.hasExchange("/", exchange)) CheckWorld.seedExchange(exchange)
        if (!CheckWorld.broker.hasQueue("/", queue)) CheckWorld.seedQueue(queue)
        CheckWorld.broker.addBinding("/", exchange, queue, routingKey = key)
    }

    @Given("the broker has the policy {string} on {string} with {string} {int}")
    fun brokerPolicy(name: String, pattern: String, key: String, value: Int) =
        CheckWorld.broker.addPolicy("/", name, pattern, mapOf(key to JsonPrimitive(value)))

    @Given("the broker has neither the exchange {string} nor the queue {string}")
    fun neither(exchange: String, queue: String) {
        assertTrue(!CheckWorld.broker.hasExchange("/", exchange) && !CheckWorld.broker.hasQueue("/", queue), "the fake broker already holds one of them")
    }

    @Given("the broker has the quorum queue {string} and no exchange {string}")
    fun queueNoExchange(queue: String, exchange: String) {
        CheckWorld.seedQueue(queue)
        assertTrue(!CheckWorld.broker.hasExchange("/", exchange), "the fake broker holds the exchange $exchange")
    }

    @Given("the broker has no queue {string}")
    fun noQueue(name: String) = assertTrue(!CheckWorld.broker.hasQueue("/", name), "the fake broker holds the queue $name")

    @Given("the broker does not answer when the queue {string} is read")
    fun silentQueue(name: String) { CheckWorld.broker.answer("GET", "/api/queues/%2F/$name", 500, "") }

    @Given("the broker refuses the read of the policy {string} for this user")
    fun refusedPolicy(name: String) { CheckWorld.broker.answer("GET", "/api/policies/%2F/$name", 403, """{"error":"not_authorised","reason":"refused"}""") }

    // ---- plans the wizard cannot hold yet ----

    @Given("a plan with the step {string}")
    fun planWithOneStep(row: String) = planOf(actionOf(row))

    @Given("a plan that declares the policy {string} on {string} with {string} {int}")
    fun planWithPolicy(name: String, pattern: String, key: String, value: Int) = planOf(policy(name, pattern, key, value))

    @Given("a plan for the virtual host {string} that declares the quorum queue {string}")
    fun planForVhost(vhost: String, name: String) = planOf(MigrationAction.DeclareQueue(name, vhost = vhost), vhost = vhost)

    /** A pending file opened in the planner is `M9-12`; here it is its actions as a plan, which is all the check sees. */
    @Given("the pending migration {string} that deletes the queue {string} is open in the planner")
    fun openDelete(@Suppress("UNUSED_PARAMETER") version: String, queue: String) = planOf(MigrationAction.DeleteQueue(queue))

    @Given("a plan with a queue named {string}, an exchange, a binding, a stream and a policy")
    fun safetyPlan(queue: String) = planOf(
        MigrationAction.DeclareExchange("events"), MigrationAction.DeclareQueue(queue),
        MigrationAction.DeclareBinding("events", queue, "k"), MigrationAction.DeclareStream("telemetry"), policy("limits", "^orders", "max-length", 1000),
    )

    @Given("a plan that declares the direct exchange {string}, the quorum queue {string} and binds them with routing key {string}")
    fun planExchangeQueueBinding(exchange: String, queue: String, key: String) {
        d.newPlan()
        d.addWithWizard(d.exchangeForm(exchange, "direct"))
        d.addWithWizard(d.queueForm(queue, "quorum"))
        d.addWithWizard(d.bindingForm(exchange, queue, key))
    }

    @Given("the plan was saved as {string} and {string} was applied on the Migrations screen")
    fun savedAndApplied(version: String, applied: String) {
        assertEquals(version, applied)
        if (d.migrationsFolder.isEmpty()) d.chooseMigrationsFolder("/work/migrations", "0004")
        d.vm.saveAsMigration()
        assertEquals(version, d.state.saved?.version, "the plan was saved as ${d.state.saved}; ${d.state.errorMessage}")
        d.migrationExecutor = CheckWorld.realExecutor()
        val screen = d.migrationsScreen()
        screen.selectVersion(version)
        val outcome = runBlocking { screen.applySingle(version) }
        assertTrue(outcome is MigrationApplyResult.Success, "the apply on the Migrations screen answered $outcome")
    }

    // ---- the check ----

    @When("I check the plan against {string}")
    fun check(profile: String) {
        assertEquals(d.profile, profile)
        CheckWorld.snapshotBeforeCheck = CheckWorld.broker.snapshot()
        CheckWorld.result = runBlocking { PlanCheck.run(currentPlan(), context(), CheckWorld.realCheck()) { _, _ -> } }
    }

    @When("I copy {string} into a new plan and check it against {string}")
    fun copyAndCheck(version: String, profile: String) {
        val file = d.migrationFolder.files.getValue(version)
        CheckWorld.plan = TopologyPlan.of(file.description, file.up, d.vhost)
        check(profile)
    }

    /** The 3.x scenario: the connection's own port (null for 3.x) is all the check gets, as in the app. */
    @When("I ask for a check")
    fun askForCheck() {
        val port = CheckWorld.held?.declareCheck
        assertNull(port, "a 3.x connection must not bring a check")
        CheckWorld.result = runBlocking { PlanCheck.run(currentPlan(), context(), port) { _, _ -> } }
    }

    // ---- what it says ----

    private fun stepOf(title: String): StepCheck {
        val plan = currentPlan()
        val index = plan.steps.indexOfFirst { it.action.description == title }
        assertTrue(index >= 0, "the plan has no step \"$title\": ${plan.steps.map { it.action.description }}")
        return CheckWorld.steps()[index]
    }

    private fun onlyStep(): StepCheck = CheckWorld.steps().single()

    private fun assertShows(step: StepCheck, label: String, what: String) {
        assertEquals(label, CheckWorld.label(step.finding), "$what shows (finding ${step.finding})")
    }

    @Then("the steps show these results")
    fun stepsShow(table: DataTable) = table.asLists().forEach { row -> assertShows(stepOf(row[0]), row[1], "\"${row[0]}\"") }

    @Then("the step {string} shows {string}")
    fun stepShows(title: String, label: String) = assertShows(stepOf(title), label, "\"$title\"")

    @Then("the step shows {string}")
    fun onlyStepShows(label: String) = assertShows(onlyStep(), label, "the step")

    @Then("every step shows {string}")
    fun everyStepShows(label: String) = CheckWorld.steps().forEachIndexed { i, step -> assertShows(step, label, "step ${i + 1}") }

    private fun assertWithReason(step: StepCheck, pill: String, reason: String) = when (pill) {
        "Blocked" -> assertEquals(reason, step.blocked, "the Blocked reason")
        "Could not be checked" -> {
            assertShows(step, pill, "the step")
            assertEquals(reason, assertIs<StepFinding.NotChecked>(step.finding).reason)
        }
        else -> error("no pill \"$pill\" with a reason is known to the steps")
    }

    @Then("the step shows {string} with the reason {string}")
    fun onlyStepWithReason(pill: String, reason: String) = assertWithReason(onlyStep(), pill, reason)

    @Then("the step shows {string} and {string} with the reason {string}")
    fun onlyStepShowsAndBlocked(label: String, pill: String, reason: String) {
        assertShows(onlyStep(), label, "the step")
        assertWithReason(onlyStep(), pill, reason)
    }

    @Then("the step {string} shows {string} and {string} with the reason {string}")
    fun stepShowsAndBlocked(title: String, label: String, pill: String, reason: String) {
        assertShows(stepOf(title), label, "\"$title\"")
        assertWithReason(stepOf(title), pill, reason)
    }

    @Then("both steps show {string} with the reason {string}")
    fun bothBlocked(pill: String, reason: String) {
        assertEquals(2, CheckWorld.steps().size)
        CheckWorld.steps().forEach { assertWithReason(it, pill, reason) }
    }

    @Then("it says applying would stop there and nothing would be overwritten")
    fun saysStop() {
        val notes = onlyStep().notes.joinToString(" ").lowercase()
        assertTrue("stop" in notes && "overwritten" in notes, "the notes say: ${onlyStep().notes}")
    }

    @Then("{string} on the broker is unchanged")
    fun unchanged(name: String) {
        assertEquals(CheckWorld.snapshotBeforeCheck, CheckWorld.broker.snapshot(), "the broker's state changed while \"$name\" was checked")
        assertTrue(CheckWorld.broker.writes.isEmpty(), "a write reached the broker: ${CheckWorld.broker.writes}")
    }

    @Then("{string} is still enabled")
    fun stillEnabled(button: String) {
        assertEquals("Save as migration", button)
        assertTrue(d.saveEnabled(), "Save as migration should still be enabled after a check; the gate says ${d.saveGate()}")
    }

    // ---- what was sent ----

    @Then("only read requests were sent to the broker")
    fun onlyReads() {
        assertTrue(CheckWorld.seen.isNotEmpty(), "the check sent nothing, so there is nothing to prove")
        assertEquals(emptyList<String>(), CheckWorld.seen.filter { it.method != "GET" }.map { "${it.method} ${it.path}" }, "a request that is not a read")
        assertEquals(emptyList<Any>(), CheckWorld.broker.writes, "the broker saw a write")
        assertEquals(emptyList<MigrationAction>(), d.executor.executedActions, "the check used the executor")
    }

    @Then("every request was a GET for a queue, an exchange, a binding list or a policy by its name")
    fun everyRequestAllowed() {
        assertTrue(CheckWorld.seen.isNotEmpty(), "the check sent nothing")
        val shape = Regex("^/api/(queues|exchanges|policies|operator-policies)/[^/]+/[^/]+$|^/api/bindings/[^/]+/e/[^/]+/(q|e)/[^/]+$")
        for (r in CheckWorld.seen) {
            assertEquals("GET", r.method, "${r.method} ${r.path}")
            assertTrue(shape.matches(r.path), "${r.path} is not a read of an item or a binding list by its name")
        }
    }

    @Then("no request path ended in {string}, {string} or {string} after a queue name")
    fun noPeekPath(a: String, b: String, c: String) {
        for (r in CheckWorld.seen.filter { it.path.startsWith("/api/queues/") }) {
            val segments = r.path.removePrefix("/api/").split('/')
            assertEquals(3, segments.size, "a path under a queue's name: ${r.path} (would end in $a, $b or $c)")
        }
    }

    @Then("no request created, changed or deleted anything")
    fun noWrites() {
        assertEquals(emptyList<String>(), CheckWorld.seen.filter { it.method != "GET" }.map { "${it.method} ${it.path}" })
        assertEquals(CheckWorld.snapshotBeforeCheck, CheckWorld.broker.snapshot(), "the broker's state changed")
        assertTrue(CheckWorld.broker.writes.isEmpty() && CheckWorld.broker.policyWrites == 0)
    }

    @Then("no request for the virtual host {string} was sent")
    fun noRequestForVhost(vhost: String) {
        val encoded = java.net.URLEncoder.encode(vhost, Charsets.UTF_8)
        assertEquals(emptyList<String>(), CheckWorld.seen.filter { it.path.contains(encoded) }.map { it.path }, "a request for $vhost was sent")
        assertEquals(emptyList<CheckWorld.Seen>().size, CheckWorld.seen.size, "the check sent a request though the only step is for another virtual host: ${CheckWorld.seen.map { it.path }}")
    }

    @Then("the check is refused with {string}")
    fun refused(reason: String) = assertEquals(PlanCheckResult.Refused(reason), CheckWorld.result)

    @Then("the check sent no request to {string}")
    fun sentNothing(name: String) {
        assertEquals(CheckWorld.requestsAfterConnect, CheckWorld.stubRequests.size, "the check sent a request to $name: ${CheckWorld.stubRequests.drop(CheckWorld.requestsAfterConnect)}")
        assertEquals(emptyList<MigrationAction>(), d.executor.executedActions)
    }
}
