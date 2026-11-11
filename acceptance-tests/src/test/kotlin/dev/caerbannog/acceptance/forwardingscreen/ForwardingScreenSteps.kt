package dev.caerbannog.acceptance.forwardingscreen

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.forwarding.DestinationConnection
import dev.caerbannog.core.forwarding.DestinationQueueType
import dev.caerbannog.core.forwarding.ForwardingBlock
import dev.caerbannog.core.forwarding.ForwardingContext
import dev.caerbannog.core.forwarding.ForwardingScreenState
import dev.caerbannog.core.forwarding.ForwardingService
import dev.caerbannog.core.forwarding.ForwardingState
import dev.caerbannog.core.forwarding.ForwardingTexts
import dev.caerbannog.core.forwarding.ForwardingViewModel
import dev.caerbannog.core.forwarding.FORWARDING_POLL_MILLIS
import dev.caerbannog.core.forwarding.QueueRow
import dev.caerbannog.core.forwarding.ShovelRef
import dev.caerbannog.core.forwarding.ShovelRunState
import dev.caerbannog.core.forwarding.ShovelStatus
import dev.caerbannog.core.forwarding.StatusOutcome
import dev.caerbannog.core.forwarding.fakes.FakeShovelGateway
import dev.caerbannog.core.forwarding.fakes.ShovelCall
import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.CompatibilityAnalyzer
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.DefinitionsParser
import dev.caerbannog.core.migrate3x.HeldAnalysis
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** M8-09: the `@m8-09` scenarios of feature 17 at view-model level. Steps only call and assert; the rules live in `core`. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardingScreenSteps {
    private val ref = ShovelRef("/", "caerbannog-migrate-orders")
    private var test = TestScope()
    private var gateway = FakeShovelGateway()
    private var level = TopologyControlLevel.DECLARE_AND_EDIT
    private var sourceTls = true
    private lateinit var vm: ForwardingViewModel
    private val state get() = vm.state.value

    @Before
    fun reset() {
        test = TestScope(); gateway = FakeShovelGateway(); level = TopologyControlLevel.DECLARE_AND_EDIT; sourceTls = true
    }

    private fun newVm(): ForwardingViewModel {
        val text = DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("jobs")))
        val doc = (DefinitionsParser.parse(text) as DefinitionsParseResult.Parsed).document
        val holder = AnalysisHolder().also { it.hold(HeldAnalysis("legacy-eu", doc, CompatibilityAnalyzer.analyze(doc), SourceKind.Live)) }
        val source = ConnectionProfile("legacy-eu", "legacy.example", "/", "svc-reader", tls = sourceTls)
        val destination = DestinationConnection(ConnectionProfile("new-eu", "new.example", "/", "svc", tls = true))
        return ForwardingViewModel(
            ForwardingService(gateway) { ForwardingContext(level, true) }, holder, { destination }, { if (it == "legacy-eu") source else null },
            { ForwardingContext(level, true) }, test.backgroundScope,
        ).also { vm = it }
    }

    private fun settle() = test.testScheduler.runCurrent()
    private fun pollOnce() { test.testScheduler.advanceTimeBy(FORWARDING_POLL_MILLIS); test.testScheduler.runCurrent(); settle() }
    private fun orders() = QueueRow("/", "orders")

    // ---- Background ----

    @Given("the 4.x connection {string} is the held connection")
    fun held(name: String) = Unit

    @Given("the user of {string} has the policymaker and monitoring tags and configure permission on {string}")
    fun tags(name: String, vhost: String) = gateway.userTags("policymaker", "monitoring")

    @Given("the topology level of {string} is Declare and edit")
    fun declare(name: String) { level = TopologyControlLevel.DECLARE_AND_EDIT }

    @Given("{string} is a RabbitMQ 3.13 broker with the classic queue {string} holding {int} messages")
    fun source(connection: String, queue: String, n: Int) = gateway.sourceQueue(connection, "/", queue, n.toLong())

    @Given("{string} has the quorum queue {string} with no messages")
    fun quorum(connection: String, queue: String) = gateway.destinationQueue("/", queue, DestinationQueueType.QUORUM)

    // ---- Given ----

    @Given("the confirmation for forwarding {string} is open")
    fun confirmationOpen(queue: String) {
        newVm(); vm.open(); settle(); vm.pick(orders()); settle()
        assertIs<ForwardingScreenState.Confirm>(state, "the confirmation is not open, state is $state")
    }

    @Given("I chose to forward {string} from {string} to {string}")
    fun chose(queue: String, from: String, to: String) = confirmationOpen(queue)

    @Given("the forwarding of {string} is running and {int} of {int} messages were moved")
    fun runningAt(queue: String, moved: Int, total: Int) {
        gateway.sourceQueue("legacy-eu", "/", queue, total.toLong())
        confirmationOpen(queue); vm.setTyped(queue); settle(); vm.confirm(); settle()
        gateway.statusScript(StatusOutcome.Listed(ShovelStatus(ref, ShovelRunState.RUNNING, null, moved.toLong(), (total - moved).toLong())))
    }

    @Given("the forwarding of {string} moved the messages that were present when it started")
    fun moved(queue: String) {
        gateway.sourceQueue("legacy-eu", "/", queue, 120)
        confirmationOpen(queue); vm.setTyped(queue); settle(); vm.confirm(); settle()
    }

    @Given("{int} more messages were published to the source meanwhile")
    fun published(n: Int) = gateway.sourceQueue("legacy-eu", "/", "orders", n.toLong())

    @Given("the shovel plugins are not enabled on {string}")
    fun pluginsOff(name: String) = gateway.plugins(false)

    // ---- When ----

    @When("I cancel")
    fun cancel() { vm.cancel(); settle(); assertIs<ForwardingScreenState.Pick>(state, "cancel must return to the list, state is $state") }

    @When("the screen refreshes")
    fun refresh() = pollOnce()

    @When("I open the forwarding of {string}")
    fun open(queue: String) { newVm(); vm.open(); settle(); vm.pick(orders()); settle() }

    @When("the shovel finishes and removes itself")
    fun finishes() {
        gateway.statusScript(StatusOutcome.Listed(ShovelStatus(ref, ShovelRunState.RUNNING, null, 120, 0)), StatusOutcome.Absent)
        repeat(3) { pollOnce() }
    }

    @When("I choose {string} and type a user name and a password")
    fun anotherUser(label: String) {
        assertEquals(ForwardingTexts.LABEL_ANOTHER_USER, label)
        vm.setAnotherUser(true, "mover", "s3cret-SENTINEL"); settle()
        vm.setTyped("orders"); settle(); vm.confirm(); settle()
    }

    // ---- Then ----

    @Then("no shovel is created")
    fun noShovel() { assertTrue(gateway.calls.none { it is ShovelCall.Start }, "calls: ${gateway.calls}"); assertTrue(gateway.shovels.isEmpty()) }

    @Then("I see the shovel state and about {int} of {int} messages moved")
    fun progress(moved: Int, total: Int) {
        val p = assertIs<ForwardingScreenState.Progress>(state, "state is $state")
        assertEquals(ForwardingState.Running(moved.toLong(), (total - moved).toLong()), p.state)
        assertEquals(listOf(ForwardingTexts.moved(moved.toLong(), total.toLong())), p.texts.filter { it.startsWith("Moved") })
    }

    @Then("I can forward only that queue")
    fun onlyThat() { assertEquals("orders", assertIs<ForwardingScreenState.Confirm>(state, "state is $state").queue) }

    @Then("there is no action that forwards every queue")
    fun noAll() = assertTrue(ForwardingViewModel::class.java.methods.none { it.name.lowercase().let { n -> "all" in n && n != "getstate" && n != "notifyall" } })

    @Then("I see that about {int} messages stayed on the source")
    fun stayed(n: Int) {
        val p = assertIs<ForwardingScreenState.Progress>(state, "state is $state")
        assertEquals(ForwardingState.Finished, p.state)
        assertEquals(listOf(ForwardingTexts.finishedStayed(n.toLong())), p.texts)
    }

    @Then("no shovel and no stored credentials remain on {string}")
    fun gone(name: String) {
        // the broker removed it by itself (queue-length, S-J): the last status read was Absent and the app had nothing to remove
        assertEquals(ForwardingState.Finished, assertIs<ForwardingScreenState.Progress>(state, "state is $state").state)
        assertTrue(gateway.calls.none { it is ShovelCall.Remove }, "the app removed a shovel that was already gone")
    }

    @Then("the shovel source address uses that user instead of the profile user")
    fun userUsed() {
        val request = gateway.calls.filterIsInstance<ShovelCall.Start>().singleOrNull()?.request ?: fail("no start was sent: ${gateway.calls}")
        assertEquals("mover", request.source.address.username)
        assertEquals("mover", request.source.credentials?.username)
    }

    @Then("the typed password is masked on screen and is not saved in the profile, the keychain, the history or the logs")
    fun notShown() {
        // masking is drawn by the screen (Compose test); here: the state, the calls and the texts never hold it
        val shown = listOf(state.toString(), gateway.calls.filter { it !is ShovelCall.Start }.toString())
        shown.forEach { assertFalse(it.contains("s3cret-SENTINEL"), "the password is in: $it") }
    }

    @Then("the password is cleared when the dialog closes")
    fun cleared() {
        vm.close(); settle()
        assertEquals(ForwardingScreenState.Closed, state)
        assertFalse(state.toString().contains("s3cret-SENTINEL"))
        gateway.shovels.clear() // the fake keeps the started shovel; the real one would show it as existing
        vm.open(); settle(); vm.pick(orders()); settle()
        val form = assertIs<ForwardingScreenState.Confirm>(state, "state is $state").form
        assertEquals(listOf(false, "", null), listOf(form.anotherUser, form.user, form.password))
    }

    @Then("I am told which plugins to enable")
    fun plugins() {
        val b = assertIs<ForwardingScreenState.Blocked>(state, "state is $state")
        assertTrue(ForwardingBlock.ShovelPluginsOff in b.blocks)
        assertTrue(ForwardingTexts.block(ForwardingBlock.ShovelPluginsOff, "new-eu", "orders").let { "rabbitmq_shovel" in it && "rabbitmq_shovel_management" in it })
    }

    @Then("with the level Read-only I am told that topology changes are locked on {string}")
    fun readOnly(name: String) {
        level = TopologyControlLevel.READ_ONLY
        newVm(); vm.open(); settle()
        assertEquals(ForwardingTexts.block(ForwardingBlock.TopologyLocked, name, "orders"), assertIs<ForwardingScreenState.Pick>(state).forwardDisabledReason)
        level = TopologyControlLevel.DECLARE_AND_EDIT
    }

    @Then("with {string} turned off on {string} the progress view says {string}")
    fun notEncrypted(switch: String, connection: String, text: String) {
        assertEquals("Not encrypted", text)
        sourceTls = false; gateway.plugins(true)
        confirmationOpen("orders"); vm.setTyped("orders"); settle(); vm.confirm(); settle()
        assertTrue(ForwardingTexts.NOT_ENCRYPTED in assertIs<ForwardingScreenState.Progress>(state, "state is $state").texts)
    }
}
