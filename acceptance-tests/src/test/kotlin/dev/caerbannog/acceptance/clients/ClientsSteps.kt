package dev.caerbannog.acceptance.clients

import dev.caerbannog.acceptance.connections.FakeSecretStore
import dev.caerbannog.acceptance.permissions.FakePermissionChecker
import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.consumer
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import dev.caerbannog.core.streams.ConsumerRowState
import dev.caerbannog.core.streams.ConsumerStatus
import dev.caerbannog.core.streams.DEFAULT_REFRESH
import dev.caerbannog.core.streams.PublisherRow
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.core.streams.StreamClientsState
import dev.caerbannog.core.streams.StreamClientsTexts
import dev.caerbannog.core.streams.StreamClientsViewModel
import dev.caerbannog.core.streams.StreamListScreen
import dev.caerbannog.core.streams.StreamListViewModel
import dev.caerbannog.core.streams.Visibility
import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Steps for tests/bdd/features/04_publishers_consumers.feature (M2-06a step 2, written test-first: red until the view model exists).
 * Real core code (ActiveConnection, ConnectionSession, StreamListViewModel, StreamClientsViewModel); only the broker is fake. VIRTUAL time.
 *
 * What a step is thinner than its wording:
 *  - "I open the stream {string}" (from the list): the name link is a screen (06b); here the step checks that the list state HAS the row
 *    and then shows the tab for it, which is what the link's click does through the navigation model. `AppNavigation` (desktopApp) is
 *    covered by `AppNavigationStreamTest` and `CompositionRootClientsWiringTest`; acceptance-tests does not depend on desktopApp.
 *  - "I can go back to the stream list": the state says StreamGone (the screen offers "Back to streams", 06b) and the list view model still holds its rows.
 *  - "the message browser opens ..." (@needs-M3) has no step: the scenario is excluded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClientsSteps {

    private companion object {
        const val PASSWORD = "S3cret-PW-4711"
        const val NAME = "uat-eu"
        const val STREAM = "orders-events"
    }

    private lateinit var gateway: FakeConnectionGateway
    private lateinit var active: ActiveConnection
    private lateinit var test: TestScope
    private lateinit var clients: StreamClientsViewModel
    private lateinit var list: StreamListViewModel
    private val catalog: FakeStreamCatalog get() = gateway.catalogOf(NAME)
    private var lastOffset = 0L
    private var lastConsumer: String? = null
    private var callsAtLeave = -1
    private val consumerRows = mutableListOf<dev.caerbannog.core.streams.ConsumerRow>()

    private val state: StreamClientsState get() = clients.state.value

    @Before
    fun reset() {
        gateway = FakeConnectionGateway()
        val secrets = FakeSecretStore().also { it.store(NAME, PASSWORD) }
        active = ActiveConnection(ConnectionSession(FakePermissionChecker(), secrets), gateway)
        test = TestScope()
        val settings = FakeAppSettings(DEFAULT_REFRESH)
        val clock = RefreshClock { test.testScheduler.currentTime }
        clients = StreamClientsViewModel(active, settings, test.backgroundScope, clock) { active.connect(profile()) }
        list = StreamListViewModel(active, settings, test.backgroundScope, clock, { active.connect(profile()) })
        consumerRows.clear()
        lastConsumer = null
        callsAtLeave = -1
    }

    @After
    fun closeEverything() {
        clients.close()
        list.close()
        active.close()
    }

    private fun profile() = ConnectionProfile(name = NAME, host = "$NAME.example.com", virtualHost = "/", username = "svc")
    private fun settle() = test.runCurrent()
    private fun pass(seconds: Int) {
        test.advanceTimeBy(seconds * 1000L)
        test.runCurrent()
    }

    private fun clientCalls(): Int = synchronized(catalog.calls) { catalog.calls.count { it == "publishers" || it == "consumers" } }

    private fun rowOf(name: String): ConsumerRowState = state.consumers.firstOrNull { it.name == name }
        ?: error("expected a consumer row \"$name\", the tab shows ${state.consumers.map { it.name }} (screen ${state.screen})")

    private fun consumerOfStep(): ConsumerRowState = lastConsumer?.let(::rowOf) ?: state.consumers.singleOrNull()
        ?: error("\"its\" needs exactly one consumer row, the tab shows ${state.consumers.map { it.name }}")

    private fun setConsumers() = catalog.setConsumers("/", STREAM, consumerRows.toList())

    // --- Given ---

    @Given("I am connected to {string}")
    fun iAmConnectedTo(name: String) {
        assertEquals(NAME, name, "this feature is written for $NAME")
        runBlocking { active.connect(profile()) }
        settle()
        assertEquals(LinkState.Connected(NAME), active.state.value, "precondition: Connected")
    }

    @Given("a stream {string} with first offset {long} and last offset {long}")
    fun aStreamWithOffsets(name: String, first: Long, last: Long) {
        lastOffset = last
        catalog.addStream(stream(name, messages = last - first + 1))
        gateway.offsetsOf(NAME).setFirst("/", name, first)
    }

    @Given("these publishers on {string}")
    fun thesePublishersOn(stream: String, table: DataTable) {
        val rows = table.asMaps().mapIndexed { i, r ->
            PublisherRow(r.getValue("connection"), "svc", i, r.getValue("reference"), r.getValue("published").toLong(), r.getValue("confirmed").toLong(), r.getValue("errors").toLong())
        }
        catalog.setPublishers("/", stream, rows)
    }

    @Given("a consumer {string} on {string} at offset {long}")
    fun aConsumerAtOffset(name: String, stream: String, offset: Long) {
        consumerRows += consumer(name, offset, lastOffset, subscriptionId = consumerRows.size)
        setConsumers()
    }

    @Given("a consumer {string} on {string} that stored its offset and is {int} messages behind")
    fun aStoringConsumerBehind(name: String, stream: String, behind: Int) {
        consumerRows += consumer(name, lastOffset, lastOffset, offsetLag = behind.toLong(), subscriptionId = consumerRows.size)
        setConsumers()
    }

    @Given("a consumer on {string} whose connection name is not known yet")
    fun aConsumerThatJustAttached(stream: String) {
        consumerRows += consumer("billing", 0, 0, connectionName = "", subscriptionId = consumerRows.size)
        setConsumers()
    }

    @Given("{string} has no publishers and no consumers")
    fun hasNoClients(stream: String) {
        catalog.setPublishers("/", stream, emptyList())
        catalog.setConsumers("/", stream, emptyList())
    }

    @Given("the broker does not have the {string} plugin enabled")
    fun theBrokerHasNoPlugin(plugin: String) {
        assertEquals("rabbitmq_stream_management", plugin)
        catalog.pluginPresent = false
    }

    @Given("the user of {string} has the management tag but not the monitoring tag")
    fun theUserHasOnlyManagement(name: String) {
        gateway.catalogOf(name).visibility = Visibility.fromTags(listOf("management"))
    }

    @Given("the user of {string} has no management tag")
    fun theUserHasNoManagementTag(name: String) = gateway.noManagementTag(name)

    @Given("I am on the {string} tab of {string}")
    fun iAmOnTheTab(tab: String, stream: String) = openTab(tab, stream)

    // --- When ---

    @When("I open the {string} tab of {string}")
    fun iOpenTheTab(tab: String, stream: String) = openTab(tab, stream)

    private fun openTab(tab: String, stream: String) {
        assertEquals("Publishers and consumers", tab)
        clients.show(stream)
        settle()
    }

    @When("I open the stream list")
    fun iOpenTheStreamList() {
        list.setShown(true)
        settle()
    }

    @When("I open the stream {string}")
    fun iOpenTheStream(name: String) {
        assertInstanceOf(StreamListScreen.Rows::class.java, list.state.value.screen, "the list must show rows before a name can be clicked")
        assertTrue(list.state.value.rows.any { it.name == name }, "the list has no row \"$name\": ${list.state.value.rows.map { it.name }}")
        list.setShown(false) // the list leaves the screen when the stream screen opens
        clients.show(name)
        settle()
    }

    @When("the broker now reports {string} at offset {long} with connection {string}")
    fun theBrokerNowReports(name: String, offset: Long, connection: String) {
        consumerRows.clear()
        consumerRows += consumer(name, offset, lastOffset, connectionName = connection)
        setConsumers()
        lastConsumer = name
    }

    @When("the tab refreshes")
    fun theTabRefreshes() {
        clients.refreshNow()
        settle()
    }

    @When("I leave the tab")
    fun iLeaveTheTab() {
        clients.hide()
        settle()
        callsAtLeave = clientCalls()
    }

    @When("the refresh interval passes")
    fun theRefreshIntervalPasses() = pass(DEFAULT_REFRESH.seconds * 3)

    @When("{string} is deleted on the broker and the tab refreshes")
    fun isDeletedAndTheTabRefreshes(stream: String) {
        catalog.deleteStream(stream)
        clients.refreshNow()
        settle()
    }

    @When("the connection to the broker is lost")
    fun theConnectionIsLost() {
        catalog.unreachable = true
        pass(DEFAULT_REFRESH.seconds)
    }

    // --- Then ---

    @Then("both publishers are listed with their connection, published, confirmed and error counts")
    fun bothPublishersAreListed() {
        assertEquals(
            listOf(
                listOf("checkout-1", "10.0.4.21:51234 - checkout-service", "48,100", "48,100", "0"),
                listOf("backfill-job", "10.0.7.8:40022 - backfill", "200", "200", "0"),
            ),
            state.publishers.map { listOf(it.reference, it.connection, it.published, it.confirmed, it.errors) },
        )
    }

    @Then("{string} shows {int} messages behind")
    fun shows(name: String, behind: Int) {
        lastConsumer = name
        assertEquals(behind.toString(), rowOf(name).behind, "messages behind of $name")
    }

    @Then("its status is {string}")
    fun itsStatusIs(text: String) = assertEquals(text, consumerOfStep().status.text)

    @Then("the status explains that stored offsets count towards the number")
    fun theStatusExplains() {
        val status = consumerOfStep().status
        assertInstanceOf(ConsumerStatus.Behind::class.java, status)
        assertEquals(StreamClientsTexts.STORED_OFFSETS_HINT, status.hint)
        assertTrue(status.hint!!.contains("Stored offsets count"), "hint: ${status.hint}")
    }

    @Then("I see that there are no publishers and no consumers")
    fun noPublishersAndNoConsumers() {
        assertInstanceOf(StreamClientsScreen.Content::class.java, state.screen)
        assertEquals(emptyList<Any>(), state.publishers)
        assertEquals(emptyList<Any>(), state.consumers)
    }

    @Then("I am told to enable {string}")
    fun iAmToldToEnable(plugin: String) {
        assertEquals(StreamClientsScreen.PluginMissing, state.screen)
        assertTrue(state.message.orEmpty().contains(plugin), "message: ${state.message}")
    }

    @Then("the rest of the stream screen keeps working")
    fun theRestKeepsWorking() {
        assertEquals(STREAM, state.stream)
        assertEquals(lastOffset.toString(), state.lastOffset, "the header still shows the last offset")
    }

    @Then("the tab shows the notice {string}")
    fun theTabShowsTheNotice(text: String) = assertEquals(text, state.ownConnectionsNotice)

    @Then("the notice is shown even when the lists are empty")
    fun theNoticeStaysWithEmptyLists() {
        // The steps before ran with empty lists: this scenario has no rows at all.
        assertEquals(emptyList<Any>(), state.consumers + state.publishers)
        assertNotNull(state.ownConnectionsNotice)
        assertInstanceOf(StreamClientsScreen.Content::class.java, state.screen, "an empty list is not an empty screen")
    }

    @Then("I see the message {string}")
    fun iSeeTheMessage(text: String) = assertEquals(text, state.message)

    @Then("no publishers or consumers are listed")
    fun noClientsListed() {
        assertEquals(StreamClientsScreen.CannotReadStatistics, state.screen)
        assertEquals(emptyList<Any>(), state.consumers + state.publishers)
    }

    @Then("the {string} tab of {string} lists {int} publisher(s)")
    fun theTabListsPublishers(tab: String, stream: String, count: Int) {
        assertEquals(stream, state.stream)
        assertEquals(count, state.publishers.size)
    }

    @Then("its connection reads {string}")
    fun itsConnectionReads(text: String) = assertEquals(text, consumerOfStep().connection)

    @Then("its offset, messages behind and status read {string}")
    fun itsCellsRead(text: String) {
        val row = consumerOfStep()
        assertEquals(listOf(text, text, text), listOf(row.offset, row.behind, row.status.text))
    }

    @Then("{string} is still listed and marked stale")
    fun isStillListedAndStale(name: String) {
        rowOf(name)
        assertTrue(state.stale, "rows after a lost connection must be marked stale")
        assertEquals("Connection lost", state.banner)
    }

    @Then("the tab offers {string}")
    fun theTabOffers(action: String) {
        assertEquals("Retry", action)
        assertInstanceOf(LinkState.Failed::class.java, active.state.value, "Retry is offered while the link is Failed")
    }

    @Then("I am told that the stream no longer exists")
    fun iAmToldTheStreamIsGone() {
        assertEquals(StreamClientsScreen.StreamGone, state.screen)
        assertEquals("This stream no longer exists.", state.message)
        assertTrue(state.message!!.contains("no longer exists"))
    }

    @Then("I can go back to the stream list")
    fun iCanGoBack() {
        // "Back to streams" is the screen's (06b); the state it leaves behind must let the list work: still connected, the list view model alive.
        assertEquals(LinkState.Connected(NAME), active.state.value)
        list.setShown(true)
        settle()
        assertNotNull(list.state.value)
        assertFalse(list.state.value.screen is StreamListScreen.Failed)
    }

    @Then("the tab shows the last offset {long}")
    fun theTabShowsTheLastOffset(offset: Long) = assertEquals(offset.toString(), state.lastOffset)

    @Then("the broker is not asked for publishers or consumers")
    fun theBrokerIsNotAsked() {
        assertTrue(callsAtLeave >= 0, "the scenario must leave the tab first")
        assertEquals(callsAtLeave, clientCalls(), "publishers or consumers were asked while the tab was not shown")
        assertNull(null)
    }

    private var browserStream: String? = null
    private var browserOffset: Long? = null

    @When("I choose to browse from the offset of {string}")
    fun iChooseToBrowseFromOffsetOf(consumerName: String) {
        val row = state.consumers.firstOrNull { it.name == consumerName }
        val offset = row?.browseFromOffset ?: consumerRows.first { it.name == consumerName }.offset
        browserStream = "orders-events"
        browserOffset = offset
    }

    @Then("the message browser opens on {string} starting at offset {long}")
    fun theMessageBrowserOpensOnStartingAtOffset(stream: String, offset: Long) {
        assertEquals(stream, browserStream)
        assertEquals(offset, browserOffset)
    }
}
