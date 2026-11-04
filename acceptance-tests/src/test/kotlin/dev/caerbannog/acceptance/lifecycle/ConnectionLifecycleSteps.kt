package dev.caerbannog.acceptance.lifecycle

import dev.caerbannog.acceptance.connections.FakeBrokerReachabilityChecker
import dev.caerbannog.acceptance.connections.FakeCaCertificateValidator
import dev.caerbannog.acceptance.connections.FakeSecretStore
import dev.caerbannog.acceptance.connections.InMemoryConnectionProfileRepository
import dev.caerbannog.acceptance.permissions.FakePermissionChecker
import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionNotes
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.UnreadableCause
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Steps for tests/bdd/features/13_connection_lifecycle.feature, M2-03 part 1 (core only, fakes only: no
 * Docker, no network, no keychain, no ~/.caerbannog). The whole app side is real core code
 * (ConnectionsViewModel, ConnectionSession, ActiveConnection); only the broker is fake.
 *
 * No steps on purpose: the lost-connection scenario (@needs-decision, D2), the three app bar scenarios
 * (@needs-M2, M2-05), and "A user without the management tag ..." (@needs-M2-03b, part 2).
 *
 * Two steps are deliberately thinner than their wording until part 2 / M2-05, stated here so nobody reads them as full:
 *  - "first offsets will read unavailable" can only check that the connection is held and its stream port does
 *    not answer (StreamOffsets does not exist before part 2); part 2 replaces it with the real StreamOffsets result.
 *  - "I see the note ..." compares core's ConnectionNotes.HOST_NAME_NOT_VERIFIED with the scenario text; that
 *    the edit panel shows it is pinned by ui ConnectionsScreenUiTest ("the edit panel says the host name is not verified").
 */
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
        permissionDoneWhenClientsOpened = null
        slowGate = null
        slowConnect = null
        lastName = ""
        seenText.clear()
    }

    @After
    fun closeEverything() {
        slowGate?.release()
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
        // The profiles were added behind the view model's back; rebuild it so its list shows them.
        viewModel = ConnectionsViewModel(
            repository, secretStore, FakeBrokerReachabilityChecker(), FakeCaCertificateValidator(), session, active,
        )
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
            else -> error("Unknown failure cause in a scenario: '$cause'")
        }
        gateway.failWith(name, failure)
    }

    @Given("the broker does not let the user of {string} read permissions")
    fun theBrokerDoesNotLetTheUserReadPermissions(name: String) {
        checker.refusal = UnreadableCause.NOT_AUTHORIZED
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

    @Then("I can retry")
    fun iCanRetry() {
        gateway.succeed(lastName)
        connect(lastName)
        assertState(lastName, "Connected")
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
        assertFalse(held!!.streamPortAnswers, "the stream port of ${held.profileName} was set not to answer")
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

    @Then("I see the note {string}")
    fun iSeeTheNote(note: String) {
        assertEquals(note, ConnectionNotes.HOST_NAME_NOT_VERIFIED)
    }

    // --- helpers ---

    private fun connect(name: String) {
        lastName = name
        runBlocking { withTimeout(TIMEOUT_MS) { viewModel.connect(name) } }
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
