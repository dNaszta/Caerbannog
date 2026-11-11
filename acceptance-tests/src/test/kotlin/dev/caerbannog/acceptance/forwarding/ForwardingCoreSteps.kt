package dev.caerbannog.acceptance.forwarding

import dev.caerbannog.acceptance.forwarding.ForwardingWorld as w
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.forwarding.AckMode
import dev.caerbannog.core.forwarding.DestinationQueue
import dev.caerbannog.core.forwarding.DestinationQueueType
import dev.caerbannog.core.forwarding.DryRunResult
import dev.caerbannog.core.forwarding.ForwardingBlock
import dev.caerbannog.core.forwarding.ForwardingConfirmation
import dev.caerbannog.core.forwarding.ForwardingState
import dev.caerbannog.core.forwarding.ForwardingWarning
import dev.caerbannog.core.forwarding.Prepared
import dev.caerbannog.core.forwarding.RemoveResult
import dev.caerbannog.core.forwarding.ShovelGateway
import dev.caerbannog.core.forwarding.ShovelRef
import dev.caerbannog.core.forwarding.ShovelRunState
import dev.caerbannog.core.forwarding.ShovelSourceCredentials
import dev.caerbannog.core.forwarding.ShovelStatus
import dev.caerbannog.core.forwarding.StartOutcome
import dev.caerbannog.core.forwarding.StartResult
import dev.caerbannog.core.forwarding.StatusOutcome
import dev.caerbannog.core.forwarding.fakes.ShovelCall
import dev.caerbannog.core.migrate3x.SecretValue
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** M8-07: the `@m8-07` scenarios of feature 17 against the real service and planner, with `FakeShovelGateway`. Steps only call and assert; the rules live in `core`. */
class ForwardingCoreSteps {
    private val ref = ShovelRef("/", "caerbannog-migrate-orders")

    @Before
    fun reset() = w.reset()

    // ---- Background and Given ----

    @Given("the 4.x connection {string} is the held connection")
    fun held(name: String) = Unit

    @Given("the user of {string} has the policymaker tag and configure permission on {string}")
    fun userTags(name: String, vhost: String) = w.gateway.userTags("policymaker", "monitoring") // monitoring too: without it forwarding is blocked (brief decision 2)

    @Given("the topology level of {string} is Declare and edit")
    fun levelDeclare(name: String) { w.level = TopologyControlLevel.DECLARE_AND_EDIT }

    @Given("the topology level of {string} is Full control")
    fun levelFull(name: String) { w.level = TopologyControlLevel.FULL_CONTROL }

    @Given("the topology level of {string} is Read-only")
    fun levelReadOnly(name: String) { w.level = TopologyControlLevel.READ_ONLY }

    @Given("{string} is a RabbitMQ 3.13 broker with the classic queue {string} holding {int} messages")
    fun sourceQueue(connection: String, queue: String, messages: Int) = w.gateway.sourceQueue(connection, "/", queue, messages.toLong())

    @Given("{string} has the quorum queue {string} with no messages")
    fun quorum(connection: String, queue: String) = w.gateway.destinationQueue("/", queue, DestinationQueueType.QUORUM)

    @Given("{string} has no queue {string}")
    fun noQueue(connection: String, queue: String) = w.gateway.noDestinationQueue("/", queue)

    @Given("the destination queue {string} on {string} is a classic queue")
    fun classic(queue: String, connection: String) = w.gateway.destinationQueue("/", queue, DestinationQueueType.CLASSIC)

    @Given("the user of {string} has no policymaker tag")
    fun noPolicymaker(name: String) = w.gateway.userTags("monitoring")

    @Given("the user of {string} has the policymaker tag but no monitoring or administrator tag")
    fun noMonitoring(name: String) = w.gateway.userTags("policymaker")

    @Given("the shovel plugins are not enabled on {string}")
    fun pluginsOff(name: String) = w.gateway.plugins(false)

    @Given("a shovel {string} already exists on {string}")
    fun existing(shovel: String, name: String) = w.gateway.existingShovel(status(ShovelRunState.RUNNING))

    @Given("{string} has {string} turned off")
    fun tlsOff(connection: String, switch: String) { w.tls = false }

    @Given("{string} and {string} use TLS")
    fun tlsOn(a: String, b: String) { w.tls = true }

    @Given("I confirmed forwarding {string} from {string} to {string}")
    fun confirmed(queue: String, from: String, to: String) = prepare(queue)

    @Given("the confirmation for forwarding {string} is open")
    fun confirmationOpen(queue: String) = prepare(queue)

    @Given("I chose to forward {string} from {string} to {string}")
    fun chose(queue: String, from: String, to: String) = Unit // the request is built when it is needed, so a typed user can still be added

    @Given("a shovel {string} was created on {string} after the dry run")
    fun appeared(shovel: String, name: String) = w.gateway.appearAfterDryRun(status(ShovelRunState.RUNNING))

    @Given("a forwarding of {string} is running")
    fun running(queue: String) {
        prepare(queue); start("orders")
        w.gateway.statusScript()
    }

    @Given("the broker accepts the shovel but its state becomes {string} with the reason {string}")
    fun terminated(state: String, reason: String) {
        assertEquals("terminated", state)
        w.gateway.statusScript(StatusOutcome.Listed(status(ShovelRunState.TERMINATED, reason = reason)))
    }

    @Given("the forwarding of {string} failed and the topology level of {string} is Declare and edit")
    fun failedAtDeclare(queue: String, name: String) {
        w.level = TopologyControlLevel.DECLARE_AND_EDIT
        prepare(queue); start("orders")
        w.gateway.statusScript(StatusOutcome.Listed(status(ShovelRunState.TERMINATED, reason = "failed to connect to source")))
    }

    // ---- When ----

    @When("I run a dry run of forwarding {string} from {string} to {string}")
    fun dryRun(queue: String, from: String, to: String) = prepare(queue)

    @When("I choose to forward {string} from {string} to {string}")
    fun choose(queue: String, from: String, to: String) = prepare(queue)

    @When("I choose to forward {string} from {string} to {string} and keep the default user")
    fun chooseDefault(queue: String, from: String, to: String) = prepare(queue)

    @When("I open the forwarding of {string}")
    fun open(queue: String) = prepare(queue)

    @When("the forwarding starts")
    fun starts() {
        if (w.prepared == null) prepare("orders")
        start("orders")
    }

    @When("I type {string} and confirm")
    fun typed(text: String) { wrongName = text != "orders"; start(text) }

    private var wrongName = false

    @When("the forwarding runs to the end")
    fun runsToTheEnd() {
        starts()
        w.gateway.statusScript(StatusOutcome.Listed(status(ShovelRunState.RUNNING, forwarded = 120, remaining = 0)), StatusOutcome.Absent)
        repeat(2) { w.state = runBlocking { w.service.poll(w.request(), w.state ?: ForwardingState.Started()) } }
    }

    @When("I remove the shovel")
    fun remove() = removeNow()

    @When("I try to remove the shovel at the level Declare and edit")
    fun tryRemove() { w.level = TopologyControlLevel.DECLARE_AND_EDIT; removeNow() }

    private var removed: RemoveResult? = null
    private fun removeNow() { w.callsBefore = w.gateway.calls.size; removed = runBlocking { w.service.remove(ref) } }

    @When("the screen polls the shovel status")
    fun poll() { w.state = runBlocking { w.service.poll(w.request(), w.state ?: ForwardingState.Started()) } }

    @When("the app removes the failed shovel")
    fun cleanup() = poll()

    @When("the shovel is listed as running with {int} forwarded")
    fun listedRunning(n: Int) {
        w.gateway.statusScript(StatusOutcome.Listed(status(ShovelRunState.RUNNING, forwarded = n.toLong(), remaining = 80)))
        poll()
    }

    @When("the shovel is no longer listed")
    fun absent() { w.gateway.statusScript(StatusOutcome.Absent); poll() }

    @When("I type the user {string} and the password {string} for this shovel")
    fun typedUser(user: String, password: String) { w.credentials = ShovelSourceCredentials(user, SecretValue.of(password)) }

    // ---- Then ----

    private fun facts(): DryRunResult = when (val p = w.prepared) {
        is Prepared.Ready -> p.facts
        is Prepared.Blocked -> p.facts ?: fail("the gate blocked, so there are no facts")
        else -> fail("no dry run result: ${w.prepared}")
    }

    private fun blocks(): List<ForwardingBlock> = when (val p = w.prepared) {
        is Prepared.Blocked -> p.plan.blocks
        is Prepared.Ready -> emptyList()
        else -> fail("no plan: ${w.prepared}")
    }

    private fun warnings(): List<ForwardingWarning> = when (val p = w.prepared) {
        is Prepared.Ready -> p.plan.warnings
        is Prepared.Blocked -> p.plan.warnings
        else -> fail("no plan: ${w.prepared}")
    }

    private fun confirmation(): ForwardingConfirmation = assertIs<Prepared.Ready>(w.prepared, "no confirmation, the result was ${w.prepared}").confirmation

    private fun said(vararg words: String) = words.forEach { assertTrue(confirmation().lines.joinToString("\n").contains(it, ignoreCase = true), "the confirmation does not say \"$it\":\n${confirmation().lines.joinToString("\n")}") }

    @Then("I see {int} messages in the source queue")
    fun seeCount(n: Int) = assertEquals(n.toLong(), facts().source.ready)

    @Then("I see that the destination queue {string} exists and is a quorum queue")
    fun seeDestination(queue: String) = assertEquals(DestinationQueue.Exists(DestinationQueueType.QUORUM), facts().destination)

    @Then("I see that the shovel plugins are enabled and the shovel {string} does not exist yet")
    fun seePlugins(shovel: String) { assertTrue(facts().shovelPluginsOn); assertEquals(null, facts().existingShovel) }

    @Then("no shovel is created and nothing on either broker changes")
    fun nothingChanged() { assertEquals(listOf(ShovelCall.DryRun), w.gateway.calls); assertTrue(w.gateway.shovels.isEmpty()) }

    @Then("forwarding is blocked")
    fun blocked() = assertTrue(blocks().isNotEmpty(), "nothing blocks forwarding: ${w.prepared}")

    @Then("I am told to apply the migration that declares {string} first")
    fun destinationMissing(queue: String) = assertTrue(ForwardingBlock.DestinationMissing in blocks(), "blocks: ${blocks()}")

    @Then("no destination queue is created by the shovel")
    fun noDestination() { assertTrue(w.gateway.calls.none { it is ShovelCall.Start }); assertTrue(w.gateway.shovels.isEmpty()) }

    @Then("I see a warning that the destination is not a quorum queue")
    fun warnNotQuorum() = assertTrue(ForwardingWarning.DestinationNotQuorumOrStream(DestinationQueueType.CLASSIC) in warnings(), "warnings: ${warnings()}")

    @Then("the confirmation names the source {string} {string} {string} and the destination {string} {string} {string}")
    fun names(sc: String, sv: String, sq: String, dc: String, dv: String, dq: String) = said(sc, sv, sq, dc, dv, dq)

    @Then("it says the messages are moved and leave the source queue when the destination confirms")
    fun movedText() = said("moved", "destination confirms")

    @Then("it says the source queue is not deleted")
    fun notDeleted() = said("not deleted")

    @Then("it says the shovel keeps credentials on {string} until it is removed")
    fun keepsCredentials(name: String) = said("keeps credentials", name, "removed")

    @Then("nothing is sent until I type the source queue name {string}")
    fun typeToConfirm(queue: String) {
        assertEquals(queue, confirmation().requiredText)
        assertTrue(w.gateway.calls.none { it is ShovelCall.Start })
    }

    @Then("one shovel {string} is created on {string} in {string}")
    fun oneShovel(shovel: String, name: String, vhost: String) {
        assertEquals(StartResult.Started, w.started)
        assertEquals(1, w.gateway.calls.count { it is ShovelCall.Start })
        assertTrue(ShovelRef(vhost, shovel) in w.gateway.shovels, "shovels: ${w.gateway.shovels.keys}")
    }

    @Then("its ack mode is {string}")
    fun ack(mode: String) {
        assertEquals(mode, AckMode.ON_CONFIRM.wireName)
        assertEquals(AckMode.ON_CONFIRM, (w.gateway.calls.filterIsInstance<ShovelCall.Start>().single()).request.ackMode)
    }

    @Then("its source queue is {string} and its destination queue is {string}")
    fun queues(source: String, destination: String) {
        val r = w.gateway.calls.filterIsInstance<ShovelCall.Start>().single().request
        assertEquals(source to destination, r.source.queue to r.destinationQueue)
    }

    @Then("nothing is written to {string}")
    fun nothingWritten(name: String) {
        // The gateway is bound to the held 4.x connection: the 3.x broker is only named inside the request, never called.
        assertTrue(w.gateway.calls.all { it is ShovelCall.DryRun || it is ShovelCall.Start || it is ShovelCall.Status || it is ShovelCall.Remove })
        assertEquals(1, w.gateway.calls.count { it is ShovelCall.Start })
    }

    @Then("no request that gets, consumes, acknowledges or purges messages was sent by the app to either broker")
    fun noMessageAccess() {
        assertEquals(setOf("dryRun", "start", "status", "remove"), ShovelGateway::class.java.methods.map { it.name }.toSet())
        assertTrue(w.gateway.calls.isNotEmpty() && w.gateway.calls.all { it is ShovelCall })
    }

    @Then("the shovel {string} is deleted on {string}")
    fun deleted(shovel: String, name: String) {
        assertTrue(ShovelCall.Remove(ShovelRef("/", shovel)) in w.gateway.calls, "calls: ${w.gateway.calls}")
        assertTrue(ShovelRef("/", shovel) !in w.gateway.shovels)
    }

    @Then("no queue is deleted on either broker")
    fun noQueueDeleted() = assertEquals(setOf("DryRun", "Start", "Status", "Remove"), ShovelCall::class.java.permittedSubclasses.map { it.simpleName }.toSet())

    @Then("I am told that removing needs Full control")
    fun needsFull() = assertEquals(RemoveResult.NeedsFullControl, removed)

    @Then("no request is sent to the broker")
    fun noRequest() = assertEquals(w.callsBefore, w.gateway.calls.size, "a request was sent: ${w.gateway.calls.drop(w.callsBefore)}")

    @Then("forwarding is disabled")
    fun disabled() = assertIs<Prepared.Blocked>(w.prepared)

    @Then("I am told the user needs the policymaker tag")
    fun needsPolicymaker() = assertTrue(ForwardingBlock.NoPolicymakerTag in blocks(), "blocks: ${blocks()}")

    @Then("I am told which plugins to enable")
    fun plugins() = assertTrue(ForwardingBlock.ShovelPluginsOff in blocks(), "blocks: ${blocks()}")

    @Then("I am told that topology changes are locked on {string}")
    fun locked(name: String) {
        assertEquals(listOf(ForwardingBlock.TopologyLocked), blocks())
        assertEquals(emptyList(), w.gateway.calls, "a locked connection sent a request")
    }

    @Then("I see its state instead of a new start")
    fun seeState() = assertTrue(blocks().any { it is ForwardingBlock.ShovelExists }, "blocks: ${blocks()}")

    @Then("no shovel is created or replaced")
    fun notReplaced() { assertTrue(w.gateway.calls.none { it is ShovelCall.Start }); assertEquals(emptyList(), w.gateway.replaced) }

    @Then("the shovel addresses use {string}")
    fun scheme(scheme: String) = assertEquals(scheme, w.gateway.calls.filterIsInstance<ShovelCall.Start>().single().request.source.address.scheme)

    @Then("if the secure endpoint cannot be used the start is refused with the reason")
    fun refused() {
        w.gateway = dev.caerbannog.core.forwarding.fakes.FakeShovelGateway().also {
            it.sourceQueue("legacy-eu", "/", "orders", 120)
            it.destinationQueue("/", "orders", DestinationQueueType.QUORUM)
            it.userTags("policymaker", "monitoring")
            it.startOutcome(StartOutcome.Refused("the AMQP TLS endpoint cannot be used"))
        }
        start("orders")
        assertEquals(StartResult.Refused("the AMQP TLS endpoint cannot be used"), w.started)
    }

    @Then("no plain {string} address is used")
    fun noPlain(scheme: String) {
        val starts = w.gateway.calls.filterIsInstance<ShovelCall.Start>()
        assertEquals(1, starts.size, "a second start was sent")
        assertTrue(starts.all { it.request.source.address.scheme == "amqps" })
    }

    @Then("the confirmation and the progress view say {string}")
    fun notEncrypted(text: String) {
        assertTrue(confirmation().notEncrypted)
        said(text) // the progress view is drawn by M8-09; the planner warning it reads is checked in ForwardingPlannerTest
        assertTrue(ForwardingWarning.NotEncrypted("legacy-eu") in warnings())
    }

    @Then("I am told the forwarding failed with that reason")
    fun failedReason() = assertEquals("failed to connect to source", assertIs<ForwardingState.Failed>(w.state).reason)

    @Then("the shovel is removed so no stored credentials remain")
    fun removedAfterFailure() {
        assertEquals(true, assertIs<ForwardingState.Failed>(w.state).cleanedUp)
        assertEquals(1, w.gateway.calls.count { it is ShovelCall.Remove })
        assertTrue(ref !in w.gateway.shovels)
    }

    @Then("the confirmation says the source password is stored on {string} in clear text")
    fun clearText(name: String) = said("clear text", name)

    @Then("it says any administrator of {string} can read it until the shovel finishes or is removed")
    fun administrators(name: String) = said("administrator", name, "finishes", "removed")

    @Then("I am told the user cannot read the shovel status and needs the monitoring tag")
    fun cannotRead() = assertTrue(ForwardingBlock.CannotReadStatus in blocks(), "blocks: ${blocks()}")

    @Then("the shovel source address uses the user of the profile {string}")
    fun profileUser(name: String) {
        val r = assertNotNull(w.request())
        assertEquals(w.legacy.username, r.source.address.username)
        assertEquals(null, r.source.credentials, "the default is the profile user: no typed credentials")
        assertIs<Prepared.Ready>(w.prepared)
    }

    @Then("no shovel is created")
    fun noShovel() {
        if (wrongName) assertEquals(StartResult.NotConfirmed, w.started, "a wrong typed name must be refused as not confirmed")
        assertTrue(w.gateway.calls.none { it is ShovelCall.Start }); assertTrue(w.gateway.shovels.isEmpty()) }

    @Then("the start is refused because the shovel exists")
    fun refusedExists() {
        val result = assertIs<StartResult.Blocked>(w.started)
        assertTrue(result.plan.blocks.any { it is ForwardingBlock.ShovelExists })
    }

    @Then("the state is started")
    fun stateStarted() = assertEquals(ForwardingState.Started(), initialState())

    @Then("the state is running with {int} forwarded")
    fun stateRunning(n: Int) = assertEquals(n.toLong(), assertIs<ForwardingState.Running>(w.state).forwarded)

    @Then("the state is finished")
    fun stateFinished() = assertEquals(ForwardingState.Finished, w.state)

    @Then("the text of the request, of the credentials and of the address does not contain {string}")
    fun noSecret(secret: String) {
        val r = w.request()
        listOf(r.toString(), r.copy(destinationQueue = "x").toString(), r.source.toString(), "${r.source.credentials}", r.source.address.toString()).forEach {
            assertFalse(it.contains(secret), "the password is in: $it")
        }
        assertTrue(r.toString().contains("***"))
    }

    @Then("the address reads {string}")
    fun addressReads(text: String) = assertEquals(text, w.request().source.address.toString())

    // ---- helpers ----

    private fun status(state: ShovelRunState, reason: String? = null, forwarded: Long? = null, remaining: Long? = null) = ShovelStatus(ref, state, reason, forwarded, remaining)

    private fun prepare(queue: String) { w.prepared = runBlocking { w.service.prepare(w.request(queue)) } }

    private fun start(typed: String) {
        w.started = runBlocking { w.service.start(w.request(), typed) }
        if (w.started == StartResult.Started) w.state = ForwardingState.Started()
    }

    private fun initialState(): ForwardingState? = w.state
}
