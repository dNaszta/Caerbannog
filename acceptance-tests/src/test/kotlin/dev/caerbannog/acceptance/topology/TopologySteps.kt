package dev.caerbannog.acceptance.topology

import dev.caerbannog.acceptance.connections.FakeSecretStore
import dev.caerbannog.acceptance.permissions.FakePermissionChecker
import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.binding
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.classic
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.exchange
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.quorum
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.QueueRowView
import dev.caerbannog.core.topology.QueueTypeFilter
import dev.caerbannog.core.topology.TopologyBannerKind
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.core.topology.TopologyContext
import dev.caerbannog.core.topology.TopologyScreen
import dev.caerbannog.core.topology.TopologyState
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.core.topology.TopologyViewModel
import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Steps for the `@M2 @fake-broker` scenarios of tests/bdd/features/09_topology_queues.feature that belong to the view model (M2-08b step 2,
 * written test-first: red until `TopologyViewModel` exists). The app side is real core code; only the broker is fake (`FakeConnectionGateway`
 * with its `FakeTopologyCatalog`). Time is VIRTUAL: "30 seconds pass" advances a test scheduler, no sleeps. The contract of the view model:
 * core jvmTest `TopologyHarness`.
 *
 * What a step is thinner than its wording:
 *  - "the bindings view shows ...": the 08b data is the table rows (`TopologyState.bindings`); the graph is 08d.
 *  - "I see the message ..." reads `state.message`, not a drawn screen; "no queue is shown" reads the rows.
 *  - the profile of "uat-eu" has the topology level DECLARE_AND_EDIT unless a scenario says otherwise, so the level (T5) is not a banner by accident.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopologySteps {

    private companion object {
        const val PASSWORD = "S3cret-PW-4711"
        const val NAME = "uat-eu"
        val READ_ONLY_CALLS = setOf("vhosts", "queues", "exchanges", "bindings", "queueDetail")
    }

    private lateinit var gateway: FakeConnectionGateway
    private lateinit var active: ActiveConnection
    private lateinit var session: ConnectionSession
    private lateinit var settings: FakeAppSettings
    private lateinit var test: TestScope
    private lateinit var vm: TopologyViewModel
    private lateinit var profile: ConnectionProfile
    private val context = MutableStateFlow<TopologyContext?>(null)
    private val topology: FakeTopologyCatalog get() = gateway.topologyOf(NAME)
    private val state: TopologyState get() = vm.state.value
    private var pageSize = 5
    private var expectedRows: List<QueueRowView> = emptyList()
    private var callsAtHide = -1
    private val visible = mutableListOf<String>()
    private val declared = mutableListOf<String>()

    @Before
    fun reset() {
        gateway = FakeConnectionGateway()
        val secrets = FakeSecretStore().also { it.store(NAME, PASSWORD) }
        session = ConnectionSession(FakePermissionChecker(), secrets)
        active = ActiveConnection(session, gateway)
        settings = FakeAppSettings()
        test = TestScope()
        profile = ConnectionProfile(
            name = NAME, host = "$NAME.example.com", virtualHost = "/", username = "svc", topologyControlLevel = TopologyControlLevel.DECLARE_AND_EDIT,
        )
        context.value = null
        pageSize = 5
        callsAtHide = -1
        expectedRows = emptyList()
        visible.clear()
        declared.clear()
        vm = newViewModel()
    }

    private fun newViewModel() = TopologyViewModel(
        active = active, settings = settings, scope = test.backgroundScope, clock = RefreshClock { test.testScheduler.currentTime },
        reconnect = { active.connect(profile) }, context = context, pageSize = pageSize,
    )

    @After
    fun closeEverything() {
        vm.close()
        active.close()
    }

    private fun settle() = test.runCurrent()

    private fun pass(seconds: Int) {
        test.advanceTimeBy(seconds * 1000L)
        test.runCurrent()
    }

    private fun publishContext() {
        context.value = TopologyContext(profile, session.permissions(NAME))
        settle()
    }

    private fun names(): List<String> = state.queues.map { it.name }

    private fun open(tab: TopologyTab = TopologyTab.Queues, filter: QueueTypeFilter? = null) {
        vm.setTab(tab)
        filter?.let(vm::setQueueFilter)
        vm.setShown(true)
        settle()
    }

    private fun tabOf(text: String): TopologyTab = TopologyTab.entries.firstOrNull { it.name == text } ?: error("unknown tab \"$text\"")

    private fun filterOf(text: String): QueueTypeFilter = when (text) {
        "Quorum" -> QueueTypeFilter.Quorum
        "Other types" -> QueueTypeFilter.OtherTypes
        else -> error("unknown filter \"$text\"")
    }

    // --- background and connection ---

    @Given("I am connected to {string}")
    fun iAmConnectedTo(name: String) {
        assertEquals(NAME, name, "the scenarios use the one connection $NAME")
        runBlocking { active.connect(profile) }
        publishContext()
    }

    @Given("the user has configure permission on {string}")
    fun theUserHasConfigurePermission(vhost: String) {
        assertEquals(profile.virtualHost, vhost)
        assertTrue(session.permissions(NAME)?.topologyUnlocked == true, "precondition: the fake broker lets the user configure, got ${session.permissions(NAME)}")
    }

    @Given("the connection {string} is set to the {string} topology level")
    fun theConnectionIsSetToLevel(name: String, level: String) {
        assertEquals(NAME, name)
        profile = profile.copy(topologyControlLevel = when (level) {
            "Read-only" -> TopologyControlLevel.READ_ONLY
            "Declare and edit" -> TopologyControlLevel.DECLARE_AND_EDIT
            "Full control" -> TopologyControlLevel.FULL_CONTROL
            else -> error("unknown level \"$level\"")
        })
        publishContext()
    }

    @Given("the user of {string} has no management tag")
    fun theUserHasNoManagementTag(name: String) {
        gateway.noManagementTag(name)
    }

    // --- data ---

    @Given("these quorum queues")
    fun theseQuorumQueues(table: DataTable) {
        val rows = table.asMaps()
        expectedRows = rows.map { r ->
            val (online, total) = r.getValue("members online").split(" of ").map { it.toInt() }
            val members = (1..total).map { "rabbit@node-$it" }
            topology.addQueue(
                quorum(
                    r.getValue("name"), ready = r.getValue("ready").toLong(), unacked = r.getValue("unacked").toLong(), consumers = r.getValue("consumers").toInt(),
                    members = members, online = members.take(online), leader = r.getValue("leader"),
                ),
            )
            QueueRowView(
                r.getValue("name"), "quorum", "%,d".format(java.util.Locale.ROOT, r.getValue("ready").toLong()), r.getValue("unacked"), r.getValue("consumers"),
                r.getValue("leader"), r.getValue("members online"), memberOffline = online < total,
            )
        }
    }

    @Given("the quorum queue {string} has {int} of its {int} members online")
    fun quorumQueueWithMembersOnline(name: String, online: Int, total: Int) {
        val members = (1..total).map { "rabbit@node-$it" }
        topology.addQueue(quorum(name, members = members, online = members.take(online)))
    }

    @Given("a quorum queue {string} bound to the exchange {string} with routing key {string}")
    fun quorumQueueBoundToExchange(queue: String, exchange: String, key: String) {
        topology.addQueue(quorum(queue, policy = "quorum-default", members = listOf("rabbit@node-1", "rabbit@node-2", "rabbit@node-3"), ready = 5, unacked = 1))
        topology.addExchange(exchange(exchange, "topic"))
        topology.addBinding(binding(exchange, queue, routingKey = key))
    }

    @Given("a quorum queue {string} and a classic queue {string}")
    fun quorumAndClassic(quorumName: String, classicName: String) {
        topology.addQueue(quorum(quorumName))
        topology.addQueue(classic(classicName))
    }

    @Given("a quorum queue {string}, a classic queue {string} and a stream {string}")
    fun quorumClassicAndStream(quorumName: String, classicName: String, stream: String) {
        quorumAndClassic(quorumName, classicName)
        topology.addStream(stream)
    }

    @Given("quorum queues {string}, {string} and {string}")
    fun threeQuorumQueues(a: String, b: String, c: String) {
        listOf(a, b, c).forEach { topology.addQueue(quorum(it)) }
    }

    @Given("a quorum queue {string} with {int} ready messages")
    fun quorumQueueWithReady(name: String, ready: Int) {
        topology.addQueue(quorum(name, ready = ready.toLong()))
    }

    @Given("a quorum queue {string}")
    fun aQuorumQueue(name: String) {
        topology.addQueue(quorum(name))
    }

    @Given("the connection's virtual host has {int} quorum queues")
    fun connectionVhostHasQueues(count: Int) {
        repeat(count) { topology.addQueue(quorum("queue-%02d".format(it))) }
    }

    @Given("another virtual host has {int} quorum queues")
    fun anotherVhostHasQueues(count: Int) {
        repeat(count) { topology.addQueue(quorum("other-%02d".format(it), vhost = "other-vhost")) }
    }

    @Given("the connection's virtual host has no queues")
    fun connectionVhostHasNoQueues() = Unit

    @Given("the connection's virtual host is {string}")
    fun connectionVhostIs(vhost: String) {
        assertEquals(vhost, profile.virtualHost)
    }

    @Given("the user can see the virtual hosts {string} and {string}")
    fun userCanSeeVhosts(a: String, b: String) {
        listOf(a, b).forEach { topology.declareVhost(it) }
        topology.permittedVhosts = setOf(a, b)
    }

    @Given("the broker has the virtual hosts {string}, {string} and {string}")
    fun brokerHasVhosts(a: String, b: String, c: String) {
        listOf(a, b, c).forEach { topology.declareVhost(it) }
    }

    @Given("the virtual host {string} has the quorum queue {string}")
    fun vhostHasQueue(vhost: String, queue: String) {
        topology.addQueue(quorum(queue, vhost = vhost))
    }

    @Given("the virtual host {string} is offered but the broker answers that it is not available")
    fun vhostOfferedButUnavailable(vhost: String) {
        topology.declareVhost(vhost)
        topology.permittedVhosts = setOf(profile.virtualHost)
        topology.listsEveryVhost = true // a monitoring user is listed every vhost and gets 404 on the ones without a permission (spike S-E)
    }

    @Given("the broker has the default exchange, the exchange {string} and a queue {string} bound to {string}")
    fun brokerHasDefaultExchangeAndBinding(exchange: String, queue: String, source: String) {
        assertEquals(exchange, source, "the scenario binds the queue to the exchange it names")
        topology.addExchange(exchange(exchange, "topic"))
        topology.addQueue(quorum(queue))
        topology.addBinding(binding(exchange, queue, routingKey = "order.created"))
    }

    @Given("the exchange {string} is bound to the exchange {string} with routing key {string}")
    fun exchangeBoundToExchange(source: String, destination: String, key: String) {
        topology.addExchange(exchange(source, "topic"))
        topology.addExchange(exchange(destination, "fanout"))
        topology.addBinding(binding(source, destination, routingKey = key, destinationType = BindingDestinationType.EXCHANGE))
    }

    @Given("a super stream {string} with {int} partitions")
    fun superStream(name: String, partitions: Int) {
        topology.addExchange(exchange(name, "direct", arguments = mapOf("x-super-stream" to "true")))
        repeat(partitions) {
            topology.addStream("$name-$it")
            topology.addBinding(binding(name, "$name-$it", routingKey = "$it", arguments = mapOf("x-stream-partition-order" to "$it")))
        }
    }

    // --- opening and acting ---

    @When("I open the topology screen on the {string} tab")
    fun openOnTab(tab: String) = open(tabOf(tab))

    @When("I open the topology screen on the {string} tab with the {string} filter")
    fun openOnTabWithFilter(tab: String, filter: String) = open(tabOf(tab), filterOf(filter))

    @When("I open the topology screen on the {string} tab with {int} queues per page")
    fun openOnTabWithPageSize(tab: String, size: Int) {
        pageSize = size
        vm.setPageSize(size)
        open(tabOf(tab))
    }

    @When("I open the {string} tab")
    fun openTab(tab: String) {
        vm.setTab(tabOf(tab))
        settle()
    }

    @When("I search the queue list for {string}")
    fun searchQueues(text: String) {
        if (!state.screen.let { it is TopologyScreen.Ready || it is TopologyScreen.Empty }) open()
        vm.setSearch(text)
        settle()
    }

    @When("I choose the virtual host {string}")
    fun chooseVhost(vhost: String) {
        vm.selectVhost(vhost)
        settle()
    }

    @When("I select the queue {string}")
    fun selectQueue(name: String) {
        if (!state.screen.let { it is TopologyScreen.Ready }) open()
        vm.selectQueue(name)
        settle()
    }

    @When("I choose to show the default exchange")
    fun showDefaultExchange() {
        vm.setShowDefaultExchange(true)
        settle()
    }

    @Given("I have the topology screen open")
    fun haveTheScreenOpen() = open()

    @Given("the quorum queue {string} is listed")
    fun theQueueIsListed(name: String) {
        topology.addQueue(quorum(name, ready = 3))
        open()
        assertTrue(name in names(), "precondition: $name is listed, the screen shows ${names()} (${state.screen})")
    }

    @Given("the refresh interval is {int} seconds")
    fun refreshIntervalIs(seconds: Int) {
        settings.streamListRefresh = checkNotNull(RefreshInterval.fromSeconds(seconds)) { "$seconds s is not an interval of the app" }
    }

    @When("the queue gets {int} more ready messages")
    fun queueGetsMore(more: Int) {
        val current = checkNotNull(topology.queueRow("orders")) { "no queue \"orders\" in the scenario" }
        topology.addQueue(current.copy(readyMessages = (current.readyMessages ?: 0) + more))
    }

    @When("{int} seconds pass")
    fun secondsPass(seconds: Int) = pass(seconds)

    @When("I open the stream list")
    fun openTheStreamList() {
        vm.setShown(false)
        settle()
        callsAtHide = topology.calls.size
    }

    @When("the broker stops answering")
    fun brokerStopsAnswering() {
        topology.unreachable = true
        pass(settings.streamListRefresh.seconds.coerceAtLeast(1))
    }

    @When("I open the topology screen on every tab and select the queue {string}")
    fun everyTabAndSelect(name: String) {
        vm.setShown(true)
        for (tab in TopologyTab.entries) {
            vm.setTab(tab)
            vm.selectQueue(name)
            settle()
        }
        pass(10)
    }

    // --- outcomes ---

    @Then("each queue is shown with its ready and unacked counts, consumers, leader and members online")
    fun eachQueueShown() {
        assertEquals(expectedRows.sortedBy { it.name }, state.queues, "the queue rows, screen ${state.screen}")
    }

    @Then("{string} is marked as having a member offline")
    fun markedOffline(name: String) {
        assertTrue(state.queues.single { it.name == name }.memberOffline, "$name must be flagged, rows ${state.queues}")
    }

    @Then("I see its leader, members, ready and unacked counts, consumers, policy and bindings")
    fun seeTheDetail() {
        val detail = checkNotNull(state.selectedQueue) { "a queue is selected, the screen shows ${state.screen}" }
        assertTrue(detail.leader.isNotBlank() && detail.members.isNotBlank() && detail.readyUnacked.contains(" / ") && detail.consumers.isNotBlank(), "detail: $detail")
        assertEquals("quorum-default", detail.policy)
        assertEquals(listOf("events" to "order.created"), detail.bindings.map { it.source to it.routingKey })
    }

    @Then("I see the exchanges of the virtual host")
    fun seeExchanges() {
        assertEquals(TopologyTab.Exchanges, state.tab)
        assertTrue(state.exchanges.any { it.isDefault } && state.exchanges.any { it.name == "events" }, "exchanges: ${state.exchanges}")
    }

    @Then("I see each binding with its source, destination and routing key")
    fun seeBindings() {
        assertEquals(TopologyTab.Bindings, state.tab)
        assertTrue(state.bindings.isNotEmpty(), "the Bindings tab shows the binding of the scenario, screen ${state.screen}")
        state.bindings.forEach { assertTrue(it.source.isNotBlank() && it.destination.isNotBlank() && it.destinationType.isNotBlank(), "binding row: $it") }
        assertEquals(listOf(Triple("events", "orders", "order.created")), state.bindings.map { Triple(it.source, it.destination, it.routingKey) })
    }

    @Then("only {string} is listed")
    fun onlyListed(name: String) {
        assertEquals(listOf(name), names(), "screen ${state.screen}")
    }

    @Then("{string} and {string} are listed")
    fun twoListed(a: String, b: String) {
        assertEquals(listOf(a, b).sorted(), names())
    }

    @Then("I see queues {int} to {int} of {int}")
    fun seeRange(from: Int, to: Int, total: Int) {
        assertEquals("$from to $to of $total", state.rangeText)
    }

    @Then("no queue of the other virtual host is listed")
    fun noOtherVhostQueues() {
        assertTrue(names().none { it.startsWith("other-") }, "rows: ${names()}")
    }

    @Then("the virtual host {string} is the chosen one")
    fun vhostIsChosen(vhost: String) {
        assertEquals(vhost, state.vhost)
    }

    @Then("no queue of the virtual host {string} is shown")
    fun noQueueOfVhost(vhost: String) {
        assertEquals(listOf("invoices"), names(), "only the chosen vhost's rows, not the rows of $vhost")
    }

    @Then("I can choose between {string} and {string} and no other")
    fun canChooseBetween(a: String, b: String) {
        assertEquals(listOf(a, b), state.vhosts)
    }

    @Then("I see the message {string}")
    fun seeMessage(text: String) {
        assertEquals(text, state.message, "screen ${state.screen}")
    }

    @Then("no queue is shown")
    fun noQueueShown() {
        assertEquals(emptyList<QueueRowView>(), state.queues)
    }

    @Then("no queue list is shown")
    fun noQueueListShown() {
        assertEquals(emptyList<QueueRowView>(), state.queues)
        assertTrue(state.screen is TopologyScreen.CannotRead, "screen ${state.screen}")
        assertFalse(state.showVhostSwitch, "no switch for a user who cannot read topology")
    }

    @Then("I can still choose another virtual host")
    fun canStillChoose() {
        assertTrue(state.showVhostSwitch)
        val other = state.vhosts.first { it != state.vhost }
        vm.selectVhost(other)
        settle()
        assertEquals(other, state.vhost)
        assertTrue(state.screen is TopologyScreen.Ready || state.screen is TopologyScreen.Empty, "screen ${state.screen}")
    }

    @Then("the topology screen says permissions were checked on virtual host {string} only and this virtual host is shown read-only")
    fun saysOtherVhost(vhost: String) {
        val banner = state.banners.firstOrNull { it.kind == TopologyBannerKind.OtherVhost }
        assertEquals(
            "Permissions were checked on virtual host $vhost only. This virtual host is shown read-only.",
            banner?.headline,
            "banners: ${state.banners}",
        )
    }

    @Then("I see a message that there are no queues yet")
    fun seeNoQueuesMessage() {
        assertTrue(state.screen is TopologyScreen.Empty, "screen ${state.screen}")
        assertTrue(state.message.orEmpty().contains("no queues"), "message: ${state.message}")
    }

    @Then("I can move to the next page")
    fun canMoveNext() {
        assertTrue(state.hasNextPage)
        vm.nextPage()
        settle()
        assertEquals(2, state.page)
    }

    @Then("the bindings view shows only the binding from {string} to {string}")
    fun bindingsShowOnly(source: String, destination: String) {
        assertEquals(listOf(source to destination), state.bindings.map { it.source to it.destination }, "default exchange hidden")
    }

    @Then("the bindings view also shows the implicit binding from the default exchange to {string}")
    fun bindingsAlsoShowImplicit(destination: String) {
        assertTrue(state.bindings.any { it.source == "(default exchange)" && it.destination == destination }, "bindings: ${state.bindings}")
        assertTrue(state.bindings.size == 2, "the declared binding stays: ${state.bindings}")
    }

    @Then("the binding from {string} to {string} says its destination is an exchange")
    fun bindingSaysExchange(source: String, destination: String) {
        assertEquals("exchange", state.bindings.single { it.source == source && it.destination == destination }.destinationType)
    }

    @Then("the exchange {string} is marked as a super stream")
    fun exchangeMarked(name: String) {
        assertTrue(state.exchanges.single { it.name == name }.superStream, "exchanges: ${state.exchanges}")
        assertEquals(listOf(name), state.exchanges.filter { it.superStream }.map { it.name })
    }

    @Then("{string} shows {int} ready messages")
    fun showsReady(name: String, ready: Int) {
        assertEquals("%,d".format(java.util.Locale.ROOT, ready), state.queues.single { it.name == name }.ready)
    }

    @Then("the topology screen does not ask the broker again")
    fun doesNotAskAgain() {
        assertEquals(callsAtHide, topology.calls.size, "calls after the screen was left: ${topology.calls}")
    }

    @Then("{string} stays listed and the screen is marked stale")
    fun staysListedAndStale(name: String) {
        assertTrue(name in names(), "rows: ${names()}")
        assertTrue(state.stale)
    }

    @Then("the app does not reconnect by itself")
    fun noReconnect() {
        assertEquals(1, gateway.openCalls.size, "opens: ${gateway.openCalls}")
    }

    @Then("the screen says the counts are approximate")
    fun saysApproximate() {
        assertTrue(state.approximateNote.contains("approximate"), state.approximateNote)
    }

    @Then("every request the app made to the broker was a read of topology or statistics")
    fun everyRequestWasARead() {
        val made = topology.calls.toSet()
        assertTrue(made.isNotEmpty(), "the scenario made no request at all")
        assertTrue(READ_ONLY_CALLS.containsAll(made), "calls outside the read-only set: ${made - READ_ONLY_CALLS}")
        // The type-level half of the rule: the gateway interface has no method that could ask for a message.
        val methods = TopologyCatalog::class.java.methods.map { it.name }.toSet()
        assertTrue(READ_ONLY_CALLS.containsAll(methods), "TopologyCatalog has methods outside the read-only set: ${methods - READ_ONLY_CALLS}")
    }

    @Then("none of them was a request for the messages of a queue")
    fun noneForMessages() {
        val words = listOf("get", "consume", "purge", "message", "contents", "ack")
        val offending = (topology.calls.toSet() + TopologyCatalog::class.java.methods.map { it.name }).filter { name -> words.any { name.lowercase().contains(it) && name != "queueDetail" } }
        assertEquals(emptyList<String>(), offending)
    }

    @Then("the topology screen explains that the connection is set to read-only")
    fun explainsReadOnly() {
        val banner = state.banners.firstOrNull { it.kind == TopologyBannerKind.ReadOnlyLevel }
        assertTrue(banner?.headline.orEmpty().contains("set to read-only"), "banners: ${state.banners}")
    }
}
