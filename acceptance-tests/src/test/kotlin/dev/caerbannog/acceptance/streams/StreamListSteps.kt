package dev.caerbannog.acceptance.streams

import dev.caerbannog.acceptance.connections.FakeSecretStore
import dev.caerbannog.acceptance.permissions.FakePermissionChecker
import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.fakes.CallGate
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.CatalogMessages
import dev.caerbannog.core.streams.DEFAULT_REFRESH
import dev.caerbannog.core.streams.PublisherRow
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.StreamKindFilter
import dev.caerbannog.core.streams.StreamListScreen
import dev.caerbannog.core.streams.StreamListState
import dev.caerbannog.core.streams.StreamListViewModel
import dev.caerbannog.core.streams.StreamListTexts
import dev.caerbannog.core.streams.StreamRow
import dev.caerbannog.core.streams.StreamRowKind
import dev.caerbannog.core.streams.StreamSort
import dev.caerbannog.core.streams.Visibility
import dev.caerbannog.datarabbit.JsonFileAppSettings
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
import java.io.File
import java.nio.file.Files
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
    private lateinit var settings: AppSettings
    private lateinit var secrets: FakeSecretStore
    private var heldList: CallGate? = null
    private var listCallsAtDisconnect: Int = -1
    private var settingsDir: File? = null
    private lateinit var test: TestScope
    private lateinit var vm: StreamListViewModel
    private val catalog: FakeStreamCatalog get() = gateway.catalogOf(NAME)
    private var pageSize = 5

    private val state: StreamListState get() = vm.state.value
    private fun row(name: String): StreamRow = state.rows.firstOrNull { it.name == name }
        ?: error("expected a row for \"$name\", the list shows ${state.rows.map { it.name }} (screen ${state.screen})")

    @Before
    fun reset() {
        settingsDir = null
        buildApp(FakeAppSettings())
        pageSize = 5
    }

    // A fresh broker, connection and view model on the given settings: "the app was started". Nothing is connected yet.
    private fun buildApp(appSettings: AppSettings) {
        gateway = FakeConnectionGateway()
        secrets = FakeSecretStore().also { it.store(NAME, PASSWORD) }
        heldList = null
        listCallsAtDisconnect = -1
        active = ActiveConnection(ConnectionSession(FakePermissionChecker(), secrets), gateway)
        settings = appSettings
        test = TestScope()
        vm = newViewModel()
    }

    private fun newViewModel() = StreamListViewModel(
        active = active,
        settings = settings,
        scope = test.backgroundScope,
        clock = RefreshClock { test.testScheduler.currentTime },
        reconnect = { active.connect(profile()) },
        pageSize = pageSize,
    )

    @After
    fun closeEverything() {
        vm.close()
        active.close()
        settingsDir?.deleteRecursively()
    }

    private fun settingsFile(): File = File(checkNotNull(settingsDir) { "no settings file in this scenario" }, "settings.json")

    private fun useSettingsDir() {
        if (settingsDir == null) settingsDir = Files.createTempDirectory("caerbannog-settings-").toFile()
    }

    // The same connection, a new view model: the app restarts its screen (the view model reads its settings once, at start).
    private fun startViewModelOn(appSettings: AppSettings) {
        vm.close()
        settings = appSettings
        vm = newViewModel()
    }

    private fun profile(name: String = NAME) = ConnectionProfile(name = name, host = "$name.example.com", virtualHost = "/", username = "svc")

    private fun listCalls(): Int = synchronized(catalog.calls) { catalog.calls.count { it == "listStreams" } }

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

    @Given("a stream {string} with {int} publishers and {int} consumers")
    fun aStreamWithPublishersAndConsumers(name: String, publishers: Int, consumers: Int) =
        addStream(name, 0, 10, publishers = publishers, consumers = consumers)

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

    @Given("the app keeps its settings in a settings file")
    fun theAppKeepsItsSettingsInAFile() {
        useSettingsDir()
        startViewModelOn(JsonFileAppSettings(settingsFile()))
    }

    @Given("the settings file contains {string}")
    fun theSettingsFileContains(text: String) {
        useSettingsDir()
        settingsFile().writeText(text)
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

    @When("I set the refresh interval to {int} seconds")
    fun iSetTheRefreshIntervalToSeconds(seconds: Int) = theRefreshIntervalIsSeconds(seconds)

    @When("I set the refresh interval to off")
    fun iSetTheRefreshIntervalToOff() = theRefreshIntervalIsOff()

    @When("I start the app")
    fun iStartTheApp() = startViewModelOn(JsonFileAppSettings(settingsFile()))

    // The whole composition goes: view model, held connection, broker fake. Only the settings file is left.
    @When("I restart the app")
    fun iRestartTheApp() {
        vm.close()
        active.close()
        buildApp(JsonFileAppSettings(settingsFile()))
        runBlocking { active.connect(profile()) }
        settle()
    }

    // D3 and M2-05b: a load that the broker answers only later, after the connection it belongs to is gone.
    @Given("the broker answers the next stream list call only when released")
    fun theBrokerAnswersTheNextCallOnlyWhenReleased() {
        // ignoreCancellation: the fake keeps waiting although the view model cancelled the load, so the test proves the late
        // result is dropped by the view model itself and not only by cancellation.
        heldList = catalog.holdNext("listStreams", ignoreCancellation = true)
    }

    @Given("the connection {string} has a stream {string} with last offset {long}")
    fun theConnectionHasAStream(name: String, stream: String, last: Long) {
        secrets.store(name, PASSWORD)
        gateway.catalogOf(name).addStream(stream(stream, messages = last + 1))
        gateway.offsetsOf(name).setFirst("/", stream, 0)
    }

    @When("I disconnect from {string}")
    fun iDisconnectFrom(name: String) {
        assertEquals(name, active.state.value.name, "precondition: $name is the connection held")
        active.disconnect()
        settle()
        listCallsAtDisconnect = listCalls()
    }

    @When("I connect to {string}")
    fun iConnectTo(name: String) {
        secrets.store(name, PASSWORD)
        runBlocking { active.connect(profile(name)) }
        settle()
    }

    @When("the broker answers the held stream list call")
    fun theBrokerAnswersTheHeldCall() {
        checkNotNull(heldList) { "no stream list call was held in this scenario" }.release()
        settle()
    }

    @When("I look at the refresh interval")
    fun iLookAtTheRefreshInterval() {
        // Nothing to do: the interval is state, read by the Then.
    }

    @Then("the refresh interval shown is {int} seconds")
    fun theRefreshIntervalShownIsSeconds(seconds: Int) {
        assertEquals(seconds, state.refreshInterval.seconds, "refresh interval shown: ${state.refreshInterval}")
    }

    // --- Then ---

    @Then("the list says {string} and shows no streams")
    fun theListSaysAndShowsNoStreams(text: String) {
        assertEquals(text, state.message, "message; screen ${state.screen}")
        assertEquals(emptyList<StreamRow>(), state.rows, "rows must be gone")
    }

    @Then("the broker was not asked for the stream list again after the disconnect")
    fun theBrokerWasNotAskedAgain() {
        assertTrue(listCallsAtDisconnect >= 0, "the disconnect step did not run")
        assertEquals(listCallsAtDisconnect, listCalls(), "stream list calls to the broker after the disconnect")
    }

    @Then("the list stays that way when {int} minutes pass")
    fun theListStaysThatWay(minutes: Int) {
        val before = state.rows
        pass(minutes * 60_000L)
        assertEquals(before, state.rows, "rows changed while time passed")
    }

    @Then("the refresh interval shown is off")
    fun theRefreshIntervalShownIsOff() {
        assertEquals(RefreshInterval.Off, state.refreshInterval, "refresh interval shown: ${state.refreshInterval}")
    }

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

    @Then("the stream {string} shows {string} publishers and {string} consumers")
    fun theStreamShowsPublishersAndConsumers(name: String, publishers: String, consumers: String) {
        assertEquals(publishers, row(name).publishers, "publishers cell of $name")
        assertEquals(consumers, row(name).consumers, "consumers cell of $name")
    }

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

    // --- M2-07 super streams (features 06 and the "Filter by kind" scenario of 03; no DI in this module, so they share this class) ---

    private val note = "Messages are ordered only inside a partition. Choose a partition to browse it."
    private val superPartitions = mutableMapOf<String, List<String>>() // what each step declared, in the broker's order

    // The partition streams exist (first offset 0, 10 messages unless a step sets them) and the broker's layout names them in this order.
    private fun addSuperStream(name: String, partitions: List<String>, messages: List<Long?> = partitions.map { 10L }) {
        partitions.forEachIndexed { i, p -> addStream(p, first = 0, messages = messages[i]) }
        catalog.superStream(name, partitions)
        superPartitions[name] = partitions
    }

    private fun partitionsOf(name: String): List<String> = checkNotNull(superPartitions[name]) { "no super stream $name declared" }

    @Given("a super stream {string} with partitions {string}, {string} and {string}")
    fun aSuperStreamWithThreePartitions(name: String, a: String, b: String, c: String) = addSuperStream(name, listOf(a, b, c))

    // The streams are added in alphabetical order on purpose: the order that counts is the one the layout (the broker) gives.
    @Given("the super stream {string} with partitions {string} and {string}, ordered us first")
    fun aSuperStreamOrderedUsFirst(name: String, us: String, eu: String) {
        assertTrue(us.endsWith("-us") && eu.endsWith("-eu"), "this step is written for an us and an eu partition")
        addStream(eu, 0, 10)
        addStream(us, 0, 10)
        catalog.superStream(name, listOf(us, eu))
        superPartitions[name] = listOf(us, eu)
    }

    @Given("a stream {string} and a super stream {string} with {int} partitions exist")
    fun aStreamAndASuperStreamExist(stream: String, name: String, count: Int) {
        addStream(stream, 0, 10)
        addSuperStream(name, List(count) { "$name-$it" })
    }

    @Given("the partitions hold {long}, {long} and {long} messages")
    fun thePartitionsHold(a: Long, b: Long, c: Long) {
        val partitions = partitionsOf("orders")
        partitions.zip(listOf(a, b, c)).forEach { (p, messages) -> addStream(p, first = 0, messages = messages) }
    }

    @Given("the partition {string} has no statistics yet")
    fun thePartitionHasNoStatistics(name: String) = addStream(name, first = 0, messages = null)

    @Given("{int} plain streams exist besides {string}")
    fun plainStreamsExistBesides(count: Int, @Suppress("UNUSED_PARAMETER") name: String) {
        repeat(count) { addStream("plain-%02d".format(it), 0, 10) }
    }

    @Given("I have the stream list open and {string} is expanded")
    fun iHaveTheListOpenAndExpanded(name: String) {
        open()
        vm.toggleExpanded(name)
        settle()
    }

    @Given("the broker refuses to list the exchanges of the virtual host")
    fun theBrokerRefusesToListTheExchanges() = catalog.failSuperStreams()

    @When("I open the stream list with a page size of {int}")
    fun iOpenTheListWithAPageSizeOf(size: Int) {
        vm.setPageSize(size)
        open()
    }

    @When("I expand {string}")
    fun iExpand(name: String) {
        vm.toggleExpanded(name)
        settle()
    }

    @When("the list refreshes")
    fun theListRefreshes() {
        vm.refreshNow()
        settle()
    }

    @When("I choose the {string} filter")
    fun iChooseTheFilter(label: String) {
        open()
        val kind = when (label) {
            "Super streams" -> StreamKindFilter.SuperStreams
            "Streams" -> StreamKindFilter.Streams
            "All" -> StreamKindFilter.All
            else -> error("no kind filter called \"$label\"")
        }
        vm.setKindFilter(kind)
        settle()
    }

    @Then("{string} is listed once, as a {string} with {int} partitions")
    fun isListedOnceAs(name: String, type: String, partitions: Int) {
        assertEquals(1, state.rows.count { it.name == name }, "rows named $name in ${state.rows.map { it.name }}")
        val r = row(name)
        assertEquals(type, r.type, "type of $name")
        assertEquals(StreamRowKind.SuperStream, r.kind)
        assertEquals(partitions, r.partitionCount, "partition count of $name")
    }

    @Then("^(\"[^\"]*\"(?:, \"[^\"]*\")*) and (\"[^\"]*\") are not listed as separate top-level entries$")
    fun partitionsAreNotTopLevel(list: String, last: String) {
        val names = (list.split(", ") + last).map { it.trim('"') }
        val topLevel = state.rows.filter { it.kind != StreamRowKind.Partition }.map { it.name }
        assertTrue(names.none { it in topLevel }, "top-level entries are $topLevel, partitions $names must not be among them")
    }

    @Then("{string} is not listed as a separate top-level entry")
    fun partitionIsNotTopLevel(name: String) {
        val topLevel = state.rows.filter { it.kind != StreamRowKind.Partition }.map { it.name }
        assertFalse(name in topLevel, "top-level entries are $topLevel")
    }

    @Then("{string} is listed")
    fun isListed(name: String) = assertTrue(state.rows.any { it.name == name }, "rows ${state.rows.map { it.name }}")

    @Then("I see each partition with its own messages, offsets, publishers and consumers, and a blank Size cell")
    fun iSeeEachPartition() {
        val partitions = state.rows.filter { it.kind == StreamRowKind.Partition }
        assertEquals(partitionsOf("orders"), partitions.map { it.name }, "partition rows of orders")
        val messages = listOf(31_200L, 32_900L, 32_000L)
        partitions.zip(messages).forEach { (p, count) ->
            assertEquals(grouped(count), p.messages, "messages of ${p.name}")
            assertEquals("0", p.firstOffset, "first offset of ${p.name}")
            assertEquals((count - 1).toString(), p.lastOffset, "last offset of ${p.name}")
            assertTrue(p.publishers.isNotBlank() && p.consumers.isNotBlank(), "publishers and consumers cells of ${p.name}: <${p.publishers}> <${p.consumers}>")
            assertEquals("orders", p.partitionOf)
        }
        val members = StreamRow::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(members.none { "size" in it || "bytes" in it }, "N1: the row model carries no size, members: $members")
    }

    @Then("the super stream row shows {long} messages in total")
    fun theSuperStreamRowShowsTotal(total: Long) = assertEquals(grouped(total), row("orders").messages)

    @Then("the super stream row shows {string} messages, not a partial total")
    fun theSuperStreamRowShowsNa(text: String) = assertEquals(text, row("orders").messages, "total with a partition without a count")

    // The row model has no browse or open member (M3 adds browsing per partition); the super stream is one entry that is not a stream.
    @Then("the super stream {string} has no option to open or browse it")
    fun theSuperStreamHasNoOption(name: String) {
        assertEquals(StreamRowKind.SuperStream, row(name).kind)
        assertEquals(null, row(name).partitionOf)
        val members = StreamRow::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(members.none { "browse" in it || "open" in it }, "row members $members")
    }

    @Then("I am told that order only exists inside a partition")
    fun iAmToldThatOrderOnlyExistsInsideAPartition() {
        assertTrue(row("orders").expanded, "the note is shown under an expanded super stream")
        assertEquals(note, StreamListTexts.PARTITION_ORDER_NOTE)
    }

    @Then("the partitions of {string} are listed as {string}, {string}")
    fun thePartitionsAreListedAs(name: String, a: String, b: String) {
        val names = state.rows.map { it.name }
        assertEquals(listOf(name, a, b), names.dropWhile { it != name }.take(3), "rows $names")
    }

    @Then("the list says {string}")
    fun theListSays(range: String) = assertEquals(range, state.rangeText)

    @Then("{string} is still expanded")
    fun isStillExpanded(name: String) {
        assertTrue(row(name).expanded, "rows ${state.rows}")
        assertEquals(3, state.rows.count { it.partitionOf == name }, "partition rows of $name")
    }

    @Then("^(\"[^\"]*\"(?:, \"[^\"]*\")*) and (\"[^\"]*\") are listed as separate streams$")
    fun partitionsAreListedAsSeparateStreams(list: String, last: String) {
        val names = (list.split(", ") + last).map { it.trim('"') }
        assertEquals(names, state.rows.map { it.name }, "rows")
        assertTrue(state.rows.all { it.kind == StreamRowKind.Stream }, "kinds ${state.rows.map { it.kind }}")
    }

    @Then("the list says that super stream grouping is unavailable")
    fun theListSaysGroupingIsUnavailable() {
        assertEquals("Super stream grouping is unavailable. Partitions are listed as separate streams.", state.groupingNotice)
        assertInstanceOf(StreamListScreen.Rows::class.java, state.screen, "the list still loads: ${state.screen}")
    }

    @Then("only the super stream {string} is listed")
    fun onlyTheSuperStreamIsListed(name: String) {
        assertEquals(listOf(name), state.rows.map { it.name })
        assertEquals(StreamRowKind.SuperStream, state.rows.single().kind)
    }
}
