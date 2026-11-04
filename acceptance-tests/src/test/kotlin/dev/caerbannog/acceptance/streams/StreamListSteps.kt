package dev.caerbannog.acceptance.streams

import dev.caerbannog.acceptance.connections.FakeSecretStore
import dev.caerbannog.acceptance.permissions.FakePermissionChecker
import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import dev.caerbannog.core.streams.CatalogMessages
import dev.caerbannog.core.streams.DEFAULT_REFRESH
import dev.caerbannog.core.streams.PublisherRow
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.StreamListScreen
import dev.caerbannog.core.streams.StreamListState
import dev.caerbannog.core.streams.StreamListViewModel
import dev.caerbannog.core.streams.StreamRow
import dev.caerbannog.core.streams.StreamSort
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
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.Locale

/**
 * Steps for tests/bdd/features/03_stream_list.feature (M2-04 step 2, written test-first: red until the view model exists).
 * The app side is real core code (ActiveConnection, ConnectionSession, StreamListViewModel); only the broker is fake
 * (FakeConnectionGateway with its FakeStreamCatalog and FakeStreamOffsets). Time is VIRTUAL: the view model runs in a test
 * scope, "30 seconds pass" advances the scheduler, no sleeps. What the contract of the view model is: see
 * core jvmTest `StreamListHarness`.
 *
 * What a step is thinner than its wording:
 *  - "the Size cell of each stream is blank": the row model has no size member at all (N1), checked by name; the blank cell is
 *    drawn by M2-05.
 *  - "I can move to the next page" and "I see streams 1 to 5 of 12" read the state (`hasNextPage`, `rangeText`), not a screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamListSteps {

    private companion object {
        const val PASSWORD = "S3cret-PW-4711"
        const val NAME = "uat-eu"
        const val OTHER_VHOST = "other-vhost"
    }

    private lateinit var gateway: FakeConnectionGateway
    private lateinit var active: ActiveConnection
    private lateinit var settings: FakeAppSettings
    private lateinit var test: TestScope
    private lateinit var vm: StreamListViewModel
    private val catalog: FakeStreamCatalog get() = gateway.catalogOf(NAME)
    private var pageSize = 5

    private val state: StreamListState get() = vm.state.value
    private fun row(name: String): StreamRow = state.rows.firstOrNull { it.name == name }
        ?: error("expected a row for \"$name\", the list shows ${state.rows.map { it.name }} (screen ${state.screen})")

    @Before
    fun reset() {
        gateway = FakeConnectionGateway()
        val secrets = FakeSecretStore().also { it.store(NAME, PASSWORD) }
        active = ActiveConnection(ConnectionSession(FakePermissionChecker(), secrets), gateway)
        settings = FakeAppSettings()
        test = TestScope()
        pageSize = 5
        vm = StreamListViewModel(
            active = active,
            settings = settings,
            scope = test.backgroundScope,
            clock = RefreshClock { test.testScheduler.currentTime },
            reconnect = { active.connect(profile()) },
            pageSize = pageSize,
        )
    }

    @After
    fun closeEverything() {
        vm.close()
        active.close()
    }

    private fun profile() = ConnectionProfile(name = NAME, host = "$NAME.example.com", virtualHost = "/", username = "svc")

    private fun settle() = test.runCurrent()

    private fun pass(millis: Long) {
        test.advanceTimeBy(millis)
        test.runCurrent()
    }

    private fun open() {
        vm.setShown(true)
        settle()
    }

    private fun addStream(name: String, first: Long?, messages: Long?, publishers: Int = 0, consumers: Int = 0) {
        catalog.addStream(stream(name, messages = messages, consumers = consumers))
        if (first != null) gateway.offsetsOf(NAME).setFirst("/", name, first)
        if (publishers > 0) catalog.setPublishers("/", name, List(publishers) { PublisherRow("c$it", "svc", it, null, 1, 1, 0) })
    }

    private fun grouped(n: Long): String = String.format(Locale.ROOT, "%,d", n)

    // --- Given ---

    @Given("I am connected to {string}")
    fun iAmConnectedTo(name: String) {
        assertEquals(NAME, name, "this feature is written for $NAME")
        runBlocking { active.connect(profile()) }
        settle()
        assertEquals(LinkState.Connected(NAME), active.state.value, "precondition: Connected")
    }

    @Given("these streams exist")
    fun theseStreamsExist(table: DataTable) {
        for (r in table.asMaps()) {
            val first = r.getValue("first offset").toLong()
            val last = r.getValue("last offset").toLong()
            addStream(r.getValue("name"), first, last - first + 1, r.getValue("publishers").toInt(), r.getValue("consumers").toInt())
        }
    }

    @Given("a stream {string} with first offset {long} and last offset {long}")
    fun aStreamWithOffsets(name: String, first: Long, last: Long) = addStream(name, first, last - first + 1)

    @Given("a stream {string} with last offset {long}")
    fun aStreamWithLastOffset(name: String, last: Long) = addStream(name, 0, last + 1)

    @Given("a stream {string} whose statistics call is denied")
    fun aStreamWhoseStatisticsCallIsDenied(name: String) {
        addStream(name, first = null, messages = 100, publishers = 1, consumers = 1)
        gateway.offsetsOf(NAME).deny("/", name)
    }

    @Given("a stream {string} with {int} publishers")
    fun aStreamWithPublishers(name: String, publishers: Int) = addStream(name, 0, 10, publishers = publishers)

    @Given("a stream {string} exists")
    fun aStreamExists(name: String) = addStream(name, 0, 10)

    @Given("^streams (\"[^\"]*\"(?:, \"[^\"]*\")*) and (\"[^\"]*\") exist$")
    fun streamsExist(list: String, last: String) {
        for (quoted in list.split(", ") + last) addStream(quoted.trim('"'), 0, 10)
    }

    @Given("the user of {string} has no management tag")
    fun theUserHasNoManagementTag(name: String) = gateway.noManagementTag(name)

    @Given("the user of {string} has the tag {string}")
    fun theUserHasTheTag(name: String, tag: String) {
        gateway.catalogOf(name).visibility = Visibility.fromTags(listOf(tag))
    }

    @Given("the broker has no stream management plugin")
    fun theBrokerHasNoPlugin() {
        catalog.pluginPresent = false
    }

    @Given("the broker has no streams")
    fun theBrokerHasNoStreams() = Unit

    @Given("the broker has {int} streams")
    fun theBrokerHasStreams(count: Int) {
        repeat(count) { addStream("stream-%02d".format(it), 0, 10) }
    }

    @Given("the broker has {int} streams and {int} quorum queues")
    fun theBrokerHasStreamsAndQueues(streams: Int, queues: Int) {
        repeat(queues) { catalog.addQueue("quorum-$it") }
        repeat(streams) { addStream("stream-%02d".format(it), 0, 10) }
    }

    @Given("the broker has {int} streams in the connection's virtual host")
    fun theBrokerHasStreamsInTheConnectionsVhost(count: Int) {
        repeat(count) { addStream("here-%02d".format(it), 0, 10) }
    }

    @Given("the broker has {int} streams in another virtual host")
    fun theBrokerHasStreamsInAnotherVhost(count: Int) {
        repeat(count) { catalog.addStream(stream("elsewhere-%02d".format(it), messages = 10, vhost = OTHER_VHOST)) }
    }

    @Given("the app has no saved settings")
    fun theAppHasNoSavedSettings() {
        assertEquals(DEFAULT_REFRESH, settings.streamListRefresh, "a fresh FakeAppSettings is a fresh install")
    }

    @Given("I have the stream list open")
    fun iHaveTheStreamListOpen() = open()

    @Given("the refresh interval is off")
    fun theRefreshIntervalIsOff() {
        vm.setRefreshInterval(RefreshInterval.Off)
        settle()
    }

    @Given("the refresh interval is {int} seconds")
    fun theRefreshIntervalIsSeconds(seconds: Int) {
        vm.setRefreshInterval(checkNotNull(RefreshInterval.fromSeconds(seconds)) { "$seconds is not an allowed interval" })
        settle()
    }

    // --- When ---

    @When("I open the stream list")
    fun iOpenTheStreamList() = open()

    @When("I open the stream list with {int} streams per page")
    fun iOpenTheStreamListWithPageSize(size: Int) {
        vm.setPageSize(size)
        open()
    }

    @When("I search the list for {string}")
    fun iSearchTheListFor(text: String) {
        open()
        vm.setFilter(text)
        settle()
    }

    @When("I sort the list by name")
    fun iSortTheListByName() {
        open()
        vm.setSort(StreamSort.NameAscending)
        settle()
    }

    @When("messages are published so the last offset becomes {long}")
    fun messagesArePublished(last: Long) {
        // The stream in this scenario is "orders-events" (first offset 0).
        addStream("orders-events", 0, last + 1)
    }

    @When("I refresh the list")
    fun iRefreshTheList() {
        vm.refreshNow()
        settle()
    }

    @When("{int} seconds pass")
    fun secondsPass(seconds: Int) = pass(seconds * 1000L)

    @When("{int} minutes pass")
    fun minutesPass(minutes: Int) = pass(minutes * 60_000L)

    @When("I look at the refresh interval")
    fun iLookAtTheRefreshInterval() {
        // Nothing to do: the interval is state, read by the Then.
    }

    @Then("the refresh interval shown is {int} seconds")
    fun theRefreshIntervalShownIsSeconds(seconds: Int) {
        assertEquals(seconds, state.refreshInterval.seconds, "refresh interval shown: ${state.refreshInterval}")
    }

    // --- Then ---

    @Then("each stream is shown with its type, approximate messages, first offset, last offset, publishers and consumers")
    fun eachStreamIsShown() {
        assertInstanceOf(StreamListScreen.Rows::class.java, state.screen, "screen ${state.screen}")
        val expected = mapOf(
            "orders-events" to listOf("48,300", "0", "48299", "2", "3"),
            "audit-log" to listOf("1,204,000", "880000", "2083999", "1", "1"),
            "payments-stream" to listOf("7,850", "0", "7849", "1", "0"),
        )
        assertEquals(expected.keys, state.rows.map { it.name }.toSet())
        for ((name, cells) in expected) {
            val r = row(name)
            assertEquals("stream", r.type, "type of $name")
            assertEquals(cells, listOf(r.messages, r.firstOffset, r.lastOffset, r.publishers, r.consumers), "cells of $name")
        }
    }

    @Then("the Size cell of each stream is blank")
    fun theSizeCellIsBlank() {
        val members = StreamRow::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(members.none { "size" in it || "bytes" in it }, "N1: the row model carries no size, members: $members")
    }

    @Then("^the stream \"([^\"]*)\" shows about (.+) messages$")
    fun theStreamShowsAboutMessages(name: String, count: String) = assertEquals(count, row(name).messages)

    @Then("{string} is listed with its message count, publishers and consumers")
    fun isListedWithCounts(name: String) {
        val r = row(name)
        assertEquals("100", r.messages)
        assertEquals("1", r.publishers)
        assertEquals("1", r.consumers)
    }

    @Then("the first offset and last offset cells of {string} read {string}")
    fun theOffsetCellsRead(name: String, text: String) {
        assertEquals(text, row(name).firstOffset)
        assertEquals(text, row(name).lastOffset)
    }

    @Then("{string} shows first offset {long} and last offset {long}")
    fun showsOffsets(name: String, first: Long, last: Long) {
        assertEquals(first.toString(), row(name).firstOffset)
        assertEquals(last.toString(), row(name).lastOffset)
    }

    @Then("I see the message {string}")
    fun iSeeTheMessage(text: String) {
        assertEquals(text, state.message)
        assertEquals(CatalogMessages.NOT_MANAGEMENT_USER, state.message)
    }

    @Then("no stream list is shown")
    fun noStreamListIsShown() {
        assertEquals(StreamListScreen.CannotReadStatistics, state.screen)
        assertEquals(emptyList<StreamRow>(), state.rows)
    }

    @Then("the publishers of {string} read {string}")
    fun thePublishersRead(name: String, text: String) = assertEquals(text, row(name).publishers)

    @Then("the list says that message counts are approximate")
    fun theListSaysApproximate() =
        assertTrue(state.approximateNote.contains("approximate", ignoreCase = true), "note: <${state.approximateNote}>")

    @Then("only {string} is listed")
    fun onlyIsListed(name: String) = assertEquals(listOf(name), state.rows.map { it.name })

    @Then("^the streams are listed as (.+)$")
    fun theStreamsAreListedAs(list: String) =
        assertEquals(list.split(", ").map { it.trim('"') }, state.rows.map { it.name })

    @Then("{string} shows the new last offset {long}")
    fun showsTheNewLastOffset(name: String, last: Long) = assertEquals(last.toString(), row(name).lastOffset)

    @Then("{string} still shows the last offset {long}")
    fun stillShowsTheLastOffset(name: String, last: Long) = assertEquals(last.toString(), row(name).lastOffset)

    @Then("the list says it was updated a moment ago")
    fun theListSaysUpdatedAMomentAgo() {
        val ago = vm.updatedSecondsAgo()
        assertNotNull(ago, "no successful load recorded")
        assertTrue(ago!! in 0..2, "updated $ago s ago")
    }

    @Then("I see a message that there are no streams yet")
    fun iSeeNoStreams() {
        assertInstanceOf(StreamListScreen.Empty::class.java, state.screen, "screen ${state.screen}")
        assertTrue(state.message!!.contains("no streams", ignoreCase = true), "message: <${state.message}>")
    }

    @Then("I am offered the {string} action")
    fun iAmOffered(action: String) = assertEquals(action, state.action)

    @Then("I see streams {int} to {int} of {int}")
    fun iSeeStreamsRange(from: Int, to: Int, total: Int) {
        assertEquals("$from to $to of $total", state.rangeText)
        assertEquals(total, state.totalStreams)
        assertEquals(to - from + 1, state.rows.size)
    }

    @Then("I can move to the next page")
    fun iCanMoveToTheNextPage() = assertTrue(state.hasNextPage, "no next page, range <${state.rangeText}>")

    @Then("no stream of the other virtual host is listed")
    fun noStreamOfTheOtherVhostIsListed() =
        assertFalse(state.rows.any { it.name.startsWith("elsewhere-") }, "rows: ${state.rows.map { it.name }}")

    @Then("no quorum queue is listed")
    fun noQuorumQueueIsListed() = assertFalse(state.rows.any { it.name.startsWith("quorum-") }, "rows: ${state.rows.map { it.name }}")
}
