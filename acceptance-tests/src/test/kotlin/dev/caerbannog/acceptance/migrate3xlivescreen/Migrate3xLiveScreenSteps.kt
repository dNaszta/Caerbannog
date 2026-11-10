package dev.caerbannog.acceptance.migrate3xlivescreen

import dev.caerbannog.acceptance.connections.FakeBrokerReachabilityChecker
import dev.caerbannog.acceptance.connections.FakeCaCertificateValidator
import dev.caerbannog.acceptance.connections.FakeSecretStore
import dev.caerbannog.acceptance.connections.InMemoryConnectionProfileRepository
import dev.caerbannog.acceptance.permissions.FakePermissionChecker
import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionControlModel
import dev.caerbannog.core.connections.ConnectionControlTexts
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.HeldLiveSourceProvider
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.cardPill
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.LiveSourceTexts
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.MigrateFromThreeViewModel
import dev.caerbannog.core.migrate3x.ReadOutcome
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.migrate3x.SourceOption
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.StreamListViewModel
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

/**
 * M8-06c step definitions for the `@m8-06c @fake-broker` scenarios. One class, so the scenario state lives in its fields (Cucumber makes one instance per
 * scenario). The app side is real: `ActiveConnection` over `FakeConnectionGateway`, `ConnectionSession`, `HeldLiveSourceProvider`, `ConnectionControlModel`,
 * `cardPill` and `MigrateFromThreeViewModel`; only the broker is faked (`FakeThreeXReads` behind the real `LiveDefinitionsSource`). Virtual time, no sleeps.
 * No decision logic in the steps. That the words are DRAWN (picker, lock icon, the button "Choose definitions file", the popup row) is pinned by the Compose
 * tests in `ui`; here "the button is offered" means the state in which the screen shows it, and that the file event still works from it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Migrate3xLiveScreenSteps {
    private val password = "S3cret-PW-livescreen-4711"
    private val test = TestScope()
    private val repository = InMemoryConnectionProfileRepository()
    private val secrets = FakeSecretStore()
    private val gateway = FakeConnectionGateway()
    private val session = ConnectionSession(FakePermissionChecker(), secrets)
    private val active = ActiveConnection(session, gateway) { }
    private val connections = ConnectionsViewModel(repository, secrets, FakeBrokerReachabilityChecker(), FakeCaCertificateValidator(), session, active)
    private val provider = HeldLiveSourceProvider(active, session, test.backgroundScope)
    private val holder = AnalysisHolder()
    private val chooser = FakeDefinitionsFileChooser()
    private var screen: MigrateFromThreeViewModel? = null
    private var opened: MutableList<SourceKind> = mutableListOf()

    private val clock = RefreshClock { test.testScheduler.currentTime }
    private val streamList = StreamListViewModel(active, FakeAppSettings(), test.backgroundScope, clock, connections::connect)
    private val control = ConnectionControlModel(
        connections, active, streamList, test.backgroundScope, clock, onOpenConnections = {}, onOpenMigrateFromThree = { opened += SourceKind.Live },
    )

    init {
        streamList.setShown(true)
    }

    @After
    fun closeEverything() {
        control.close()
        streamList.close()
        active.close()
    }

    // advanceUntilIdle ignores tasks of the background scope (the provider and the chip model run there), so run the current ones too.
    private fun settle() {
        test.testScheduler.advanceUntilIdle()
        test.testScheduler.runCurrent()
        test.testScheduler.advanceUntilIdle()
    }

    private fun screen(): MigrateFromThreeViewModel = screen ?: newScreen()

    /** A window: a new view model over the same holder and the same live provider (the window rebuilds it, the analysis must survive). */
    private fun newScreen(): MigrateFromThreeViewModel {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        return MigrateFromThreeViewModel(
            FakeDefinitionsFileLoader(mapOf(FILE to dev.caerbannog.core.migrate3x.FileLoadResult.Loaded(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("audit")))))),
            chooser, FakeReportFileSaver(), holder, FakeMigrationFolders().ports,
            scope = CoroutineScope(dispatcher), analysisDispatcher = dispatcher, liveSource = provider,
        ).also { screen = it }.also { settle() }
    }

    private fun state(): MigrateFromThreeState = screen().state.value

    // ---- Given -----------------------------------------------------------------------------------------------------------------------

    private fun save(name: String, version: String) {
        // The view model saves the profile and the password, like the Connections screen; its state then lists the profile (the popup rows).
        val input = ConnectionProfileInput(name = name, host = "$name.example.com", virtualHost = "/", username = "svc", password = password)
        check(connections.addConnection(input) is SaveConnectionResult.Saved) { "test setup: could not save $name" }
        gateway.brokerRuns(name, version)
        gateway.threeXReadsOf(name).definitions = ReadOutcome.Ok(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("audit", "quorum"))))
        gateway.threeXReadsOf(name).storageVersionAppearsAtRead("/", "orders", 1, version = 2)
    }

    private fun connect(name: String) {
        runBlocking { connections.connect(name) }
        settle()
    }

    @Given("a connection {string} to a broker running RabbitMQ 3.13")
    fun a313Connection(name: String) = save(name, "3.13.7")

    @Given("a connection {string} to a broker running RabbitMQ 4")
    fun a4Connection(name: String) = save(name, "4.1.0")

    @Given("a connection {string} to a broker running RabbitMQ 3.13 is the held connection")
    fun a313HeldConnection(name: String) {
        a313Connection(name)
        connect(name)
        assertEquals(LinkState.Connected(name), active.state.value, "precondition: $name is held")
    }

    @Given("a connection {string} to a broker running RabbitMQ 4 is the held connection")
    fun a4HeldConnection(name: String) {
        a4Connection(name)
        connect(name)
        assertEquals(LinkState.Connected(name), active.state.value, "precondition: $name is held")
    }

    @Given("I analysed {string} and the report is shown")
    fun iAnalysedAndTheReportIsShown(name: String) {
        analyseLive()
        assertTrue(state() is MigrateFromThreeState.Report, "precondition: a report for $name, the screen shows ${state()}")
        assertEquals(name, (state() as MigrateFromThreeState.Report).view.fileLabel)
    }

    @Given("the broker refuses the definitions export for this user because it is not an administrator")
    fun theExportIsRefused() {
        gateway.threeXReadsOf(only()).definitions = ReadOutcome.NotPermitted
    }

    @Given("the broker reports index version {int} for the classic queue {string} and version {int} for the classic queue {string}")
    fun theBrokerReportsVersions(v1: Int, first: String, v2: Int, second: String) {
        val reads = gateway.threeXReadsOf(only())
        reads.definitions = ReadOutcome.Ok(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue(first), DefinitionsFiles.queue(second))))
        reads.storageVersionAppearsAtRead("/", first, 1, version = v1)
        reads.storageVersionAppearsAtRead("/", second, 1, version = v2)
    }

    @Given("the broker stops answering during the export")
    fun theBrokerStopsAnswering() {
        gateway.threeXReadsOf(only()).definitions = ReadOutcome.Unreachable
    }

    // ---- When ------------------------------------------------------------------------------------------------------------------------

    @When("I open the Migrate from 3.x screen")
    fun iOpenTheScreen() {
        screen = null
        newScreen()
    }

    @When("I analyse the live connection")
    fun iAnalyseTheLiveConnection() = analyseLive()

    private fun analyseLive() {
        val vm = screen()
        vm.chooseSource(SourceKind.Live)
        vm.analyseLive()
        settle()
    }

    @When("I open the connection popup")
    fun iOpenThePopup() {
        settle()
        assertNotNull(control.state.value, "the chip model has a state")
    }

    @When("I connect to {string}")
    fun iConnectTo(name: String) = connect(name)

    @When("I disconnect {string}")
    fun iDisconnect(name: String) {
        assertEquals(name, active.state.value.name, "precondition: $name is the held connection")
        active.disconnect()
        settle()
    }

    // ---- Then: the picker ----------------------------------------------------------------------------------------------------------

    @Then("the source picker offers {string} and {string}")
    fun pickerOffers(file: String, live: String) {
        assertEquals(listOf(file, live), screen().picker.value.options.map { it.label }, "picker: ${screen().picker.value}")
        assertTrue(screen().picker.value.options.none { it is SourceOption.LiveDisabled }, "the live option must be enabled")
    }

    @Then("the source picker offers {string} only")
    fun pickerOffersOnly(file: String) {
        val enabled = screen().picker.value.options.filter { it !is SourceOption.LiveDisabled }
        assertEquals(listOf(file), enabled.map { it.label }, "picker: ${screen().picker.value}")
    }

    @Then("the live option is shown disabled with the reason {string}")
    fun liveDisabledWithReason(reason: String) {
        val picker = screen().picker.value
        assertEquals(SourceOption.LiveDisabled(reason), picker.options.last(), "picker: $picker")
        assertEquals(SourceKind.File, picker.selected, "a disabled live option is never selected")
    }

    // ---- Then: the report and the card ---------------------------------------------------------------------------------------------

    @Then("the source card shows {string} with the lock and the words {string}")
    fun cardShowsLocked(name: String, words: String) {
        val card = (state() as? MigrateFromThreeState.Report ?: fail("no report is shown: ${state()}")).view.source
        assertEquals(SourceKind.Live, card.kind)
        assertEquals(name, card.label)
        assertTrue(card.locked, "a live source is locked: $card")
        assertEquals(words, card.lockText)
    }

    @Then("the source card says {string}")
    fun cardSays(text: String) {
        val card = (screen().state.value as? MigrateFromThreeState.Report ?: fail("no report is shown: ${state()}")).view.source
        assertEquals(text, card.label)
        assertFalse(card.canAnalyseAgain, "a disconnected source cannot be analysed again: $card")
    }

    @Then("the report is still available")
    fun reportStillAvailable() {
        assertNotNull(holder.held.value, "the analysis was cleared")
        // The window rebuilds its view model when the connection changes; the report must still be there.
        screen = null
        assertTrue(newScreen().state.value is MigrateFromThreeState.Report, "a new window over the holder shows ${state()}")
    }

    @Then("only {string} is the held connection")
    fun onlyHeld(name: String) {
        assertEquals(LinkState.Connected(name), active.state.value)
        assertEquals(listOf(name), gateway.heldNow.map { it.profileName }, "connections held: ${gateway.heldNow.map { it.profileName }}")
    }

    // ---- Then: the other outcomes --------------------------------------------------------------------------------------------------

    @Then("the screen says that this user cannot export definitions")
    fun saysCannotExport() {
        assertEquals(MigrateFromThreeState.CannotExport(only(), LiveSourceTexts.CANNOT_EXPORT), state())
    }

    @Then("the button {string} is offered")
    fun buttonOffered(label: String) {
        // The state is the contract: it carries the text and offers chooseFile(). The button itself (label, tag migrate3x-choose-file, keyboard) is asserted by
        // MigrateFromThreeLiveSourceUiTest, so this step checks the state only.
        check(label.isNotBlank())
        assertTrue(state() is MigrateFromThreeState.CannotExport, "the screen is in ${state()}")
    }

    @Then("the screen says how to export the definitions")
    fun saysHowToExport() {
        // The hint is drawn from MigrateFromThreeTexts.FILE_EXPORT_HINT under the state's text (Compose test: migrate3x-state-cannot-export-hint); here the state is the one that shows it.
        assertTrue(state() is MigrateFromThreeState.CannotExport, "the screen is in ${state()}")
        check(MigrateFromThreeTexts.FILE_EXPORT_HINT.isNotBlank())
    }

    private fun lossBlock() = (state() as? MigrateFromThreeState.Report ?: fail("no report is shown: ${state()}")).view.lossBlock ?: fail("no message-loss block")

    @Then("the message-loss block lists {string} only")
    fun lossBlockListsOnly(name: String) {
        val loss = lossBlock()
        assertEquals(listOf(name), (0 until loss.queueCount).map { loss.queueAt(it) })
    }

    @Then("the message-loss block says the list comes from the broker and not from a definitions file")
    fun lossBlockSaysFromBroker() {
        val text = lossBlock().detection
        assertEquals(MigrateFromThreeTexts.LOSS_DETECTION_LIVE, text)
        assertFalse(text.contains("A definitions file cannot tell"), text)
    }

    @Then("the screen says that {string} could not be reached")
    fun saysNotReached(name: String) {
        assertEquals(MigrateFromThreeState.LiveFailed(name, LiveSourceTexts.unreachable(name)), state())
    }

    @Then("no report is shown")
    fun noReport() {
        assertFalse(state() is MigrateFromThreeState.Report, "a report is shown: ${state()}")
        assertNull(holder.held.value, "an analysis is held: ${holder.held.value}")
    }

    @Then("the screen shows no text that the broker returned")
    fun noBrokerText() {
        val shown = state().toString() + screen().picker.value.toString()
        assertFalse(shown.contains(password), "the password is on screen")
        assertFalse(shown.contains("http", ignoreCase = true), "a URL is on screen: $shown")
        assertEquals(MigrateFromThreeState.LiveFailed(only(), LiveSourceTexts.unreachable(only())), state(), "the only text is the fixed one of the source")
    }

    @Then("nothing on {string} has changed")
    fun nothingChanged(name: String) {
        val reads = gateway.threeXReadsOf(name).calls
        assertTrue(reads.isNotEmpty(), "the analysis made no read at all, so \"nothing changed\" proves nothing")
        assertTrue(reads.all { it == "definitions" || it == "deprecated-features" || it.startsWith("storage-versions:") }, "calls: $reads")
        assertEquals(emptyList<Any>(), gateway.managerOf(name).createdStreams + gateway.managerOf(name).deletedStreams, "streams were created or deleted")
        assertEquals(emptyList<Any>(), gateway.publisherOf(name).publishedMessages, "a message was published")
        assertEquals(emptyList<Any>(), gateway.mqttPublisherOf(name).publishedMessages, "an MQTT message was published")
        assertEquals(emptyList<Any>(), gateway.queueInspectorOf(name).requests, "queue messages were peeked at")
        assertEquals(emptyList<String>(), gateway.topologyOf(name).calls.filter { it.startsWith("create") || it.startsWith("delete") || it.startsWith("purge") }, "topology calls")
    }

    // ---- Then: the popup and the card pill -----------------------------------------------------------------------------------------

    @Then("the popup offers {string}")
    fun popupOffers(text: String) {
        assertEquals(text, ConnectionControlTexts.MIGRATE_FROM_3X)
        val rows = control.state.value.rows
        val row = rows.firstOrNull { it.current } ?: fail("no current row: $rows; chip ${control.state.value.chip}; link ${active.state.value}; profiles ${connections.state.value.profiles.map { it.name }}")
        assertTrue(row.offersMigration, "the row of the held 3.x connection does not offer it: $row")
        assertEquals(setOf(row.name), rows.filter { it.offersMigration }.map { it.name }.toSet())
    }

    @Then("choosing it opens the Migrate from 3.x screen with the live source selected")
    fun choosingItSelectsLive() {
        control.openMigrateFromThree()
        settle()
        val requested = opened.singleOrNull() ?: fail("the popup entry asked to open the screen $opened times")
        // What the window does with the request (MigrateFromThreeWindowContent): the view model selects the requested kind.
        screen().chooseSource(requested)
        settle()
        assertEquals(SourceKind.Live, screen().picker.value.selected)
    }

    private fun pillOf(name: String) = run {
        settle()
        val chip = control.state.value.chip
        val connected = chip.kind == ChipState.Connected && chip.text == name
        cardPill(session.state.value.statuses[name], connected)
    }

    @Then("the card of {string} shows the pill {string}")
    fun cardShowsPill(name: String, text: String) = assertEquals(text, pillOf(name)?.text)

    @Then("the card of {string} shows no pill")
    fun cardShowsNoPill(name: String) = assertNull(pillOf(name), "the card shows a pill")

    // ---- helpers -----------------------------------------------------------------------------------------------------------------------

    private fun only(): String = repository.list().singleOrNull()?.name ?: fail("this step needs exactly one connection, found ${repository.list().map { it.name }}")

    private companion object {
        const val FILE = "/data/legacy.json"
        val unused = MigrateFromThreeTexts.INFO_LINE
    }
}
