package dev.caerbannog.acceptance.lifecycle

import dev.caerbannog.acceptance.connections.FakeBrokerReachabilityChecker
import dev.caerbannog.acceptance.connections.FakeCaCertificateValidator
import dev.caerbannog.acceptance.connections.FakeSecretStore
import dev.caerbannog.acceptance.connections.InMemoryConnectionProfileRepository
import dev.caerbannog.acceptance.permissions.FakePermissionChecker
import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlModel
import dev.caerbannog.core.connections.BrokerEndpoints
import dev.caerbannog.core.connections.ConnectionNotes
import dev.caerbannog.core.connections.editPanelNotes
import dev.caerbannog.core.connections.RowAction
import dev.caerbannog.core.connections.permissionPill
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.StreamListScreen
import dev.caerbannog.core.streams.StreamListViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.UnreadableCause
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.streams.CatalogMessages
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.streams.Visibility
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Steps for tests/bdd/features/13_connection_lifecycle.feature, M2-03 part 1 (core only, fakes only: no
 * Docker, no network, no keychain, no ~/.caerbannog). The whole app side is real core code
 * (ConnectionsViewModel, ConnectionSession, ActiveConnection); only the broker is fake.
 *
 * M2-05b: the lost-connection scenario (D2) runs here with a real `StreamListViewModel` on the same fakes (a test scheduler,
 * virtual time, no sleeps), and the three app bar scenarios read the chip state of the real `ConnectionControlModel`. That the
 * chip and the popup DRAW that state on every screen is pinned by the Compose tests in `ui` (a step cannot see a screen).
 * "When I open any screen" therefore only lets the model catch up.
 *
 * Part 2 (M2-03b): "A user without the management tag ..." asserts through the held connection's catalog
 * (`Capabilities.visibility` NONE, every list NotAuthorised, the message text from core); "first offsets will
 * read unavailable" asks the held connection's real `StreamOffsets` port (a `FirstOffset.Unavailable` result).
 * Both go through the fake gateway's catalog and offsets: that the REAL gateway answers the same way is pinned
 * by data-rabbit tests (ManagementErrorsTest, RabbitMqConnectionGatewayTest, RealBrokerCatalogTest).
 *
 * One step is still thinner than its wording until M2-05:
 *  - "I see the note ..." compares core's ConnectionNotes.HOST_NAME_NOT_VERIFIED with the scenario text; that
 *    the edit panel shows it is pinned by ui ConnectionsScreenUiTest ("the edit panel says the host name is not verified").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionLifecycleSteps {

    private companion object {
        const val PASSWORD = "S3cret-PW-4711"
        const val TIMEOUT_MS = 5_000L
    }

    private lateinit var repository: InMemoryConnectionProfileRepository
    private lateinit var secretStore: FakeSecretStore
    private lateinit var checker: FakePermissionChecker
    private lateinit var gateway: FakeConnectionGateway
    private lateinit var session: ConnectionSession
    private lateinit var active: ActiveConnection
    private lateinit var viewModel: ConnectionsViewModel
    private lateinit var scope: CoroutineScope
    private lateinit var test: TestScope
    private var streamList: StreamListViewModel? = null
    private var control: ConnectionControlModel? = null
    private var opensBeforeWaiting = 0

    private var lastName: String = ""
    private var permissionDoneWhenClientsOpened: Boolean? = null
    private var slowGate: FakeConnectionGateway.Gate? = null
    private var slowConnect: Job? = null
    private val seenText = mutableListOf<String>()

    @Before
    fun reset() {
        repository = InMemoryConnectionProfileRepository()
        secretStore = FakeSecretStore()
        checker = FakePermissionChecker()
        gateway = FakeConnectionGateway()
        session = ConnectionSession(checker, secretStore)
        active = ActiveConnection(session, gateway)
        viewModel = ConnectionsViewModel(
            repository, secretStore, FakeBrokerReachabilityChecker(), FakeCaCertificateValidator(), session, active,
        )
        scope = CoroutineScope(Dispatchers.Default)
        test = TestScope()
        streamList = null
        control = null
        permissionDoneWhenClientsOpened = null
        slowGate = null
        slowConnect = null
        lastName = ""
        seenText.clear()
    }

    @After
    fun closeEverything() {
        slowGate?.release()
        control?.close()
        streamList?.close()
        scope.cancel()
        active.close()
    }

    // --- Given ---

    @Given("saved connections {string} and {string}")
    fun savedConnections(first: String, second: String) {
        for (name in listOf(first, second)) {
            val profile = ConnectionProfile(name = name, host = "$name.example.com", virtualHost = "/", username = "svc-$name")
            check(repository.add(profile) is SaveConnectionResult.Saved)
            secretStore.store(name, PASSWORD)
        }
        rebuildApp()
    }

    // The profiles were added behind the view model's back; rebuild it so its list shows them.
    private fun rebuildApp() {
        viewModel = ConnectionsViewModel(
            repository, secretStore, FakeBrokerReachabilityChecker(), FakeCaCertificateValidator(), session, active,
        )
        buildStreamListAndControl()
    }

    // --- M2-14: connections with the TLS switch on or off ---

    private fun tlsFromWord(word: String): Boolean = when (word) {
        "on" -> true
        "off" -> false
        else -> error("TLS is 'on' or 'off' in a scenario, got '$word'")
    }

    @Given("a saved connection {string} with TLS {word}")
    fun aSavedConnectionWithTls(name: String, word: String) = theConnectionHasTls(name, word)

    @Given("the connection {string} has TLS {word}")
    fun theConnectionHasTls(name: String, word: String) {
        val tls = tlsFromWord(word)
        val ports = BrokerEndpoints.defaultsFor(tls)
        val existing = repository.list().firstOrNull { it.name == name }
        if (existing == null) {
            val profile = ConnectionProfile(
                name = name, host = "$name.example.com", virtualHost = "/", username = "svc-$name",
                streamPort = ports.stream, managementPort = ports.management, tls = tls,
            )
            check(repository.add(profile) is SaveConnectionResult.Saved)
            secretStore.store(name, PASSWORD)
        } else {
            repository.update(existing.copy(tls = tls, streamPort = ports.stream, managementPort = ports.management))
        }
        rebuildApp()
    }

    @Given("the connection {string} is in the state {string}")
    fun theConnectionIsInTheState(name: String, state: String) {
        when (state) {
            "Connected" -> connect(name)
            "Failed" -> {
                gateway.failWith(name, FakeConnectionGateway.Failure.BROKER_NOT_REACHABLE)
                connect(name)
            }
            "Disconnected" -> {
                connect(name)
                active.disconnect()
            }
            else -> error("Unknown state in a scenario: '$state'")
        }
        assertState(name, state)
    }

    // The app side of the app bar: the stream list view model and the connection control model, on the same fakes, in virtual time.
    private fun buildStreamListAndControl() {
        control?.close()
        streamList?.close()
        val clock = RefreshClock { test.testScheduler.currentTime }
        val list = StreamListViewModel(
            active = active, settings = FakeAppSettings(), scope = test.backgroundScope, clock = clock,
            reconnect = viewModel::connect,
        )
        streamList = list
        control = ConnectionControlModel(viewModel, active, list, test.backgroundScope, clock, onOpenConnections = {})
    }

    private fun model(): ConnectionControlModel = checkNotNull(control) { "the Background step 'saved connections' did not run" }

    private fun list(): StreamListViewModel = checkNotNull(streamList) { "the Background step 'saved connections' did not run" }

    private fun settle() = test.runCurrent()

    private fun pass(millis: Long) {
        test.advanceTimeBy(millis)
        test.runCurrent()
    }

    @Given("the broker of {string} answers slowly")
    fun theBrokerAnswersSlowly(name: String) {
        slowGate = gateway.holdOpens(name)
    }

    @Given("I am connected to {string}")
    fun iAmConnectedTo(name: String) {
        connect(name)
        assertState(name, "Connected")
        assertEquals(1, gateway.heldNow.size, "precondition: exactly one connection held")
    }

    @Given("^the connection \"([^\"]*)\" fails because (.+)$")
    fun theConnectionFailsBecause(name: String, cause: String) {
        val failure = when (cause) {
            "the broker is not reachable" -> FakeConnectionGateway.Failure.BROKER_NOT_REACHABLE
            "the certificate is not trusted" -> FakeConnectionGateway.Failure.CERTIFICATE_NOT_TRUSTED
            "no password is stored" -> FakeConnectionGateway.Failure.NO_PASSWORD_STORED
            "the credentials are refused" -> FakeConnectionGateway.Failure.CREDENTIALS_REFUSED
            "the broker does not answer the TLS handshake" -> FakeConnectionGateway.Failure.NO_TLS_ANSWER
            else -> error("Unknown failure cause in a scenario: '$cause'")
        }
        gateway.failWith(name, failure)
    }

    @Given("the broker does not let the user of {string} read permissions")
    fun theBrokerDoesNotLetTheUserReadPermissions(name: String) {
        checker.refusal = UnreadableCause.NOT_AUTHORIZED
    }

    @Given("no password is stored for {string}")
    fun noPasswordIsStoredFor(name: String) {
        secretStore.delete(name)
    }

    @Given("the broker answers {string} for the user of {string}")
    fun theBrokerAnswersForTheUserOf(answer: String, name: String) {
        assertEquals("Not management user", answer, "only the 401 reason of a user without the management tag is modelled")
        gateway.noManagementTag(name)
    }

    @Given("the stream port of {string} does not answer")
    fun theStreamPortDoesNotAnswer(name: String) {
        gateway.streamPortSilent(name)
    }

    // --- When ---

    @When("I connect to {string}")
    fun iConnectTo(name: String) {
        lastName = name
        gateway.onOpen = { permissionDoneWhenClientsOpened = session.permissions(it.name) != null }
        if (slowGate != null) {
            // The broker answers only when the scenario says so: start Connect in the background and wait
            // until the broker has been asked, so "Connecting" is observable without sleeping.
            slowConnect = scope.launch { viewModel.connect(name) }
            runBlocking { assertEquals(name, gateway.awaitOpenStarted()) }
        } else {
            connect(name)
        }
    }

    @When("I disconnect")
    fun iDisconnect() {
        active.disconnect()
    }

    @When("I save a change to {string}")
    fun iSaveAChangeTo(name: String) {
        lastName = name
        val original = repository.list().first { it.name == name }
        val input = ConnectionProfileInput(
            name = name, host = "changed-${original.host}", virtualHost = original.virtualHost,
            username = original.username, password = null,
        )
        val result = runBlocking { viewModel.saveProfileChanges(name, input) }
        check(result is SaveConnectionResult.Saved) { "the save must succeed, got $result" }
    }

    @When("I delete {string}")
    fun iDelete(name: String) {
        viewModel.requestDelete(name)
        viewModel.confirmDelete()
    }

    @When("the window is closed")
    fun theWindowIsClosed() {
        active.close()
    }

    @When("I open the edit panel of {string}")
    fun iOpenTheEditPanelOf(name: String) {
        viewModel.selectConnection(name)
        assertEquals(name, viewModel.state.value.selectedProfileName)
    }

    // --- Then ---

    @Then("{string} is the current connection")
    fun isTheCurrentConnection(name: String) {
        assertEquals(name, viewModel.state.value.currentConnection?.name, "current connection in the view model")
        assertEquals(name, session.state.value.current?.name, "current connection in the session")
    }

    @Then("there is no current connection")
    fun thereIsNoCurrentConnection() {
        assertNull(viewModel.state.value.currentConnection, "view model still has a current connection")
        assertNull(session.state.value.current, "session still has a current connection")
    }

    @Then("its state is {string}")
    fun itsStateIs(expected: String) {
        assertState(lastName, expected)
    }

    @Then("the state of {string} is {string}")
    fun theStateOfIs(name: String, expected: String) {
        assertState(name, expected)
    }

    // --- M2-14c: the TLS hint on a failed Connect ---

    // Shared with the Check's text on purpose: one sentence, the same words, asserted by its two halves.
    private fun failedReason(): String {
        val state = active.stateOf(lastName)
        return (state as? LinkState.Failed)?.reason ?: error("'$lastName' is not Failed, it is $state")
    }

    @Given("the broker only offers plain connections")
    fun theBrokerOnlyOffersPlainConnections() {
        gateway.brokerOnlyOffersPlain()
    }

    @Then("the reason says the broker may not use TLS")
    fun theReasonSaysTheBrokerMayNotUseTls() {
        assertTrue(failedReason().contains("may not use TLS")) { "reason: ${failedReason()}" }
    }

    @Then("the reason says the switch can be turned off in the connection settings")
    fun theReasonSaysTheSwitchCanBeTurnedOff() {
        assertTrue(failedReason().contains("turn off 'Use TLS' in this connection's settings")) { "reason: ${failedReason()}" }
    }

    @Then("the reason does not mention turning TLS off")
    fun theReasonDoesNotMentionTurningTlsOff() {
        val reason = failedReason()
        // A wrong CA has its own words ("certificate") and must not point at the switch.
        assertFalse(reason.contains("TLS", ignoreCase = true) || reason.contains("turn off", ignoreCase = true)) { "reason: $reason" }
    }

    @Then("the reason does not mention TLS")
    fun theReasonDoesNotMentionTls() {
        val reason = failedReason()
        // Spec "Connection lifecycle": with TLS off no text names TLS or a certificate.
        assertFalse(reason.contains("TLS", ignoreCase = true) || reason.contains("certificate", ignoreCase = true)) { "reason: $reason" }
    }

    @Then("the saved connection {string} still has TLS on")
    fun theSavedConnectionStillHasTlsOn(name: String) {
        val saved = repository.list().firstOrNull { it.name == name } ?: error("no saved connection '$name'")
        assertTrue(saved.tls) { "'$name' was changed by the failed Connect, TLS is now off: $saved" }
    }

    @Then("no attempt without TLS was made")
    fun noAttemptWithoutTlsWasMade() {
        val asked = gateway.openedProfiles
        assertTrue(asked.isNotEmpty() && asked.all { it.tls }) { "every attempt must be with TLS (no fallback), attempts (true = TLS): ${asked.map { it.tls }}" }
    }

    @Then("no credential was sent")
    fun noCredentialWasSent() {
        assertEquals(0, gateway.credentialsSent, "the broker was asked for credentials: ${gateway.log}")
    }

    @Then("the state of {string} is {string} with the reason {string}")
    fun theStateOfIsWithTheReason(name: String, expected: String, reason: String) {
        val state = active.stateOf(name)
        assertEquals(expected, phase(state), "state of $name: $state")
        assertEquals(reason, (state as? LinkState.Failed)?.reason, "reason in $state")
    }

    @Then("its state is {string} until the broker answered")
    fun itsStateIsUntilTheBrokerAnswered(expected: String) {
        assertState(lastName, expected)
        assertTrue(gateway.heldNow.isEmpty(), "nothing may be held before the broker answered")
        slowGate!!.release()
        runBlocking { withTimeout(TIMEOUT_MS) { slowConnect!!.join() } }
        assertState(lastName, "Connected")
    }

    @Then("the permission check has run before the state became {string}")
    fun thePermissionCheckHasRunBefore(state: String) {
        assertTrue(checker.calls.isNotEmpty(), "the permission check was never asked")
        assertEquals(true, permissionDoneWhenClientsOpened, "the permission check had not finished when the clients were opened")
        assertState(lastName, state)
    }

    @Then("the app holds no connection to the broker")
    fun theAppHoldsNoConnection() {
        val held = gateway.heldNow
        assertTrue(held.isEmpty(), "still held: ${held.map { it.profileName }}")
        assertTrue(gateway.everOpened.all { it.closeCalls == 1 }, "every opened connection must be closed exactly once")
    }

    @Then("the clients were not opened")
    fun theClientsWereNotOpened() {
        assertTrue(gateway.log.none { it.startsWith("open:") }, "the gateway was called: ${gateway.log}")
    }

    @Then("I can retry")
    fun iCanRetry() {
        assertEquals(RowAction.Retry, model().state.value.rows.first { it.name == lastName }.action, "the app bar offers Retry")
        gateway.succeed(lastName)
        connect(lastName)
        // Against a broker that only offers plain, Retry asks again with TLS on and fails the same way: the app never switches TLS off.
        val tlsOn = repository.list().first { it.name == lastName }.tls
        assertState(lastName, if (gateway.onlyPlain && tlsOn) "Failed" else "Connected")
    }

    @Then("topology is locked \\(see 02, safest mode)")
    fun topologyIsLocked() {
        val permissions = session.permissions(lastName)
        assertTrue(permissions is PermissionCheckResult.Unreadable, "expected an unreadable permission result, got $permissions")
        assertFalse(permissions!!.topologyUnlocked, "topology must be locked")
        assertFalse(permissions.publishingEnabled, "publishing must be disabled")
    }

    @Then("first offsets will read {string}")
    fun firstOffsetsWillRead(text: String) {
        assertEquals("unavailable", text)
        val held = gateway.heldNow.singleOrNull()
        assertNotNull(held, "the connection must still be held")
        val result = runBlocking { withTimeout(TIMEOUT_MS) { held!!.offsets.firstOffset("/", "orders-events") } }
        assertEquals(FirstOffset.Unavailable, result, "first offset of a stream while the stream port is silent")
    }

    @Then("the stream and publisher lists are replaced by the message {string}")
    fun theListsAreReplacedByTheMessage(message: String) {
        assertEquals(message, CatalogMessages.NOT_MANAGEMENT_USER, "the message text owned by core")
        val held = gateway.heldNow.singleOrNull()
        assertNotNull(held, "the connection must be held")
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val capabilities = held!!.catalog.capabilities()
                assertInstanceOf(CatalogResult.Success::class.java, capabilities, "capabilities: $capabilities")
                assertEquals(Visibility.NONE, (capabilities as CatalogResult.Success).value.visibility)
                assertEquals(CatalogResult.NotAuthorised, held.catalog.listStreams(StreamListRequest(vhost = "/")), "stream list")
                assertEquals(CatalogResult.NotAuthorised, held.catalog.publishers("/", "orders-events"), "publishers")
                assertEquals(CatalogResult.NotAuthorised, held.catalog.consumers("/", "orders-events"), "consumers")
            }
        }
    }

    @Then("the app holds one connection")
    fun theAppHoldsOneConnection() {
        assertEquals(listOf(lastName), gateway.heldNow.map { it.profileName }, "connections held: ${gateway.log}")
    }

    @Then("I am told {string}")
    fun iAmTold(note: String) {
        val state = active.stateOf(lastName)
        assertEquals(note, (state as? LinkState.Disconnected)?.note, "note in $state")
        assertEquals(ConnectionNotes.SETTINGS_CHANGED, note)
    }

    @Then("the failure message does not contain the password")
    fun theFailureMessageDoesNotContainThePassword() {
        val state = active.stateOf(lastName)
        assertTrue(state is LinkState.Failed, "expected a failure, got $state")
        val exposed = listOf(
            state.toString(), session.state.value.toString(), viewModel.state.value.toString(),
            gateway.log.toList().toString(), gateway.everOpened.toString(),
        ).joinToString("\n")
        assertFalse(exposed.contains(PASSWORD), "the password is visible in: $exposed")
    }

    private fun selectedProfile(): ConnectionProfile =
        checkNotNull(viewModel.state.value.selectedProfile) { "no edit panel is open" }

    @Then("I see the note {string}")
    fun iSeeTheNote(note: String) {
        assertEquals(note, editPanelNotes(selectedProfile().tls).hostNameNotVerified, "host name note of the open panel")
    }

    @Then("I do not see the note {string}")
    fun iDoNotSeeTheNote(note: String) {
        assertEquals(ConnectionNotes.HOST_NAME_NOT_VERIFIED, note, "the scenario must name the real note")
        assertNull(editPanelNotes(selectedProfile().tls).hostNameNotVerified, "host name note of the open panel")
    }

    @Then("the edit panel shows the warning {string}")
    fun theEditPanelShowsTheWarning(text: String) {
        assertEquals(text, editPanelNotes(selectedProfile().tls).notEncrypted, "warning of the open panel")
        assertEquals(ConnectionNotes.NOT_ENCRYPTED, text)
    }

    @Then("the edit panel shows no warning")
    fun theEditPanelShowsNoWarning() {
        assertNull(editPanelNotes(selectedProfile().tls).notEncrypted, "warning of the open panel")
    }

    @Then("the app bar chip says {string}")
    fun theChipSays(text: String) {
        settle()
        val chip = model().state.value.chip
        assertEquals(ConnectionNotes.NOT_ENCRYPTED, text)
        assertTrue(chip.notEncrypted, "the chip must say $text, chip: $chip")
    }

    @Then("the app bar chip does not say {string}")
    fun theChipDoesNotSay(text: String) {
        settle()
        assertEquals(ConnectionNotes.NOT_ENCRYPTED, text)
        assertFalse(model().state.value.chip.notEncrypted, "chip: ${model().state.value.chip}")
    }

    @Then("the chip's accessible label says {string}")
    fun theChipLabelSays(text: String) {
        settle()
        val label = model().state.value.chip.accessibleLabel
        assertTrue(label.contains(text), "label: $label")
    }

    @Then("the chip's accessible label does not say {string}")
    fun theChipLabelDoesNotSay(text: String) {
        settle()
        val label = model().state.value.chip.accessibleLabel
        assertFalse(label.contains(text, ignoreCase = true), "label: $label")
    }

    @Then("the popup row of {string} says {string}")
    fun thePopupRowSays(name: String, text: String) {
        settle()
        assertEquals(ConnectionNotes.NOT_ENCRYPTED, text)
        assertTrue(model().state.value.rows.first { it.name == name }.notEncrypted, "rows: ${model().state.value.rows}")
    }

    @Then("the popup row of {string} does not say {string}")
    fun thePopupRowDoesNotSay(name: String, text: String) {
        settle()
        assertEquals(ConnectionNotes.NOT_ENCRYPTED, text)
        assertFalse(model().state.value.rows.first { it.name == name }.notEncrypted, "rows: ${model().state.value.rows}")
    }

    @Then("^the connection was opened (with|without) TLS$")
    fun theConnectionWasOpenedWithOrWithoutTls(mode: String) {
        val asked = gateway.openedProfiles.lastOrNull()
        assertNotNull(asked, "the gateway was never asked to open")
        assertEquals(mode == "with", asked!!.tls, "the gateway was asked to open with tls=${asked.tls}")
    }

    // --- M2-05b: the lost connection (D2) and the app bar ---

    @When("the broker stops answering")
    fun theBrokerStopsAnswering() {
        val catalog = gateway.catalogOf(lastName)
        catalog.addStream(stream("orders-events", messages = 10))
        gateway.offsetsOf(lastName).setFirst("/", "orders-events", 0)
        list().setShown(true)
        settle()
        assertInstanceOf(StreamListScreen.Rows::class.java, list().state.value.screen, "precondition: rows are shown before the broker goes silent")
        catalog.unreachable = true
        pass(5_000) // the next tick of the 5 second default asks the broker and finds it gone
    }

    @Then("the last data stays visible and is marked stale")
    fun theLastDataStaysVisibleAndStale() {
        val shown = list().state.value
        assertEquals(listOf("orders-events"), shown.rows.map { it.name }, "rows must stay; screen ${shown.screen}")
        assertTrue(shown.stale, "the rows must be marked stale")
        assertEquals("Connection lost", shown.banner, "banner")
        assertEquals(RowAction.Retry, model().state.value.rows.first { it.name == lastName }.action, "the app bar offers Retry")
    }

    @Then("the app does not reconnect by itself")
    fun theAppDoesNotReconnectByItself() {
        opensBeforeWaiting = gateway.openCalls.size
        pass(10 * 60_000L)
        assertEquals(opensBeforeWaiting, gateway.openCalls.size, "the gateway was asked to open again: ${gateway.log}")
        assertEquals("Failed", phase(active.stateOf(lastName)), "state after 10 minutes")
    }

    @When("the broker answers again")
    fun theBrokerAnswersAgain() {
        gateway.catalogOf(lastName).unreachable = false
    }

    @When("I retry")
    fun iRetry() {
        list().retry()
        settle()
    }

    @Then("the state of {string} is {string} once the broker answers")
    fun theStateIsOnceTheBrokerAnswers(name: String, expected: String) {
        assertState(name, expected)
        val shown = list().state.value
        assertFalse(shown.stale, "the rows must be fresh again")
        assertNull(shown.banner, "the banner must be gone")
    }

    @Given("no connection is current")
    fun noConnectionIsCurrent() {
        settle()
        assertEquals(ChipState.NoConnection, model().state.value.chip.kind, "precondition")
    }

    @When("I open any screen")
    fun iOpenAnyScreen() {
        settle()
    }

    @When("I open the connection control and press Connect on {string}")
    fun iPressConnectOn(name: String) {
        settle()
        val row = model().state.value.rows.firstOrNull { it.name == name }
        assertEquals(RowAction.Connect, row?.action, "the popup must offer Connect on $name, rows: ${model().state.value.rows}")
        lastName = name
        model().connect(name)
        settle()
    }

    @Then("the app bar shows {string}")
    fun theAppBarShows(text: String) {
        settle()
        val chip = model().state.value.chip
        assertEquals(text, chip.text, "chip text, chip: $chip")
        if (text == "No connection") assertEquals(ChipState.NoConnection, chip.kind, "chip kind, chip: $chip")
    }

    @Then("the app bar shows {string} with the state {string} and its permission pill")
    fun theAppBarShowsWithStateAndPill(name: String, state: String) {
        val chip = appBarShows(name, state)
        val expected = permissionPill(session.state.value.statuses[name]).text
        assertEquals(expected, chip.pill?.text, "permission pill in $chip")
    }

    @Then("the app bar shows {string} with the state {string}")
    fun theAppBarShowsWithState(name: String, state: String) {
        appBarShows(name, state)
    }

    @Then("opening it lists {string} and {string} with a Connect action each")
    fun openingItListsWithConnect(first: String, second: String) {
        settle()
        val rows = model().state.value.rows
        assertEquals(listOf(first, second), rows.map { it.name }, "rows")
        assertTrue(rows.all { it.action == RowAction.Connect }, "every row must offer Connect: $rows")
    }

    private fun appBarShows(name: String, state: String): ConnectionChipState {
        settle()
        val chip = model().state.value.chip
        assertEquals(name, chip.text, "chip text, chip: $chip")
        val kind = when (state) {
            "Connected" -> ChipState.Connected
            "Connecting" -> ChipState.Connecting
            "Disconnected" -> ChipState.Disconnected
            "Failed" -> ChipState.Failed
            else -> error("Unknown chip state in a scenario: '$state'")
        }
        assertEquals(kind, chip.kind, "chip state, chip: $chip")
        return chip
    }

    // --- helpers ---

    private fun connect(name: String) {
        lastName = name
        runBlocking { withTimeout(TIMEOUT_MS) { viewModel.connect(name) } }
        settle()
    }

    private fun assertState(name: String, expected: String) {
        val state = active.stateOf(name)
        assertEquals(expected, phase(state), "state of $name: $state")
    }

    private fun phase(state: LinkState): String = when (state) {
        is LinkState.Disconnected -> "Disconnected"
        is LinkState.Connecting -> "Connecting"
        is LinkState.Connected -> "Connected"
        is LinkState.Failed -> "Failed"
    }
}
