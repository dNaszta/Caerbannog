package dev.caerbannog.acceptance.connections

import dev.caerbannog.acceptance.permissions.FakePermissionChecker
import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionControlModel
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.StreamListViewModel
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * M2-05c step 2: "Switch between connections" in `01_connections.feature`, un-skipped (it was `@needs-M2`, deferred by M1-02 until
 * the stream list existed). Switching means Connect on the other profile (the chip popup's action); the app side is the real core
 * code in the same wiring as the app (one `ActiveConnection`, `ConnectionsViewModel`, `StreamListViewModel` shown, and the
 * `ConnectionControlModel` behind the app bar chip); only the broker is a fake (`FakeConnectionGateway`), virtual time, no sleeps.
 *
 * Thinner than its wording: the Background step "saved connections ..." lives in [ConnectionsSteps], which has its own world (no
 * connection, no stream list); this class builds its own world and saves the profile it needs the first time a step names it
 * (the same pattern as `PermissionCheckSteps`). Each profile's broker holds two streams named "<profile>-orders" and
 * "<profile>-audit", so "the streams of prod-eu" can be told from the streams of uat-eu. That the chip and the list are DRAWN is
 * pinned by the Compose tests in `ui`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchBetweenConnectionsSteps {

    private lateinit var repository: InMemoryConnectionProfileRepository
    private lateinit var secrets: FakeSecretStore
    private lateinit var gateway: FakeConnectionGateway
    private lateinit var active: ActiveConnection
    private lateinit var viewModel: ConnectionsViewModel
    private lateinit var test: TestScope
    private lateinit var streamList: StreamListViewModel
    private lateinit var control: ConnectionControlModel

    @Before
    fun buildTheApp() {
        repository = InMemoryConnectionProfileRepository()
        secrets = FakeSecretStore()
        gateway = FakeConnectionGateway()
        val session = ConnectionSession(FakePermissionChecker(), secrets)
        active = ActiveConnection(session, gateway)
        viewModel = ConnectionsViewModel(repository, secrets, FakeBrokerReachabilityChecker(), FakeCaCertificateValidator(), session, active)
        test = TestScope()
        val clock = RefreshClock { test.testScheduler.currentTime }
        streamList = StreamListViewModel(
            active = active, settings = FakeAppSettings(), scope = test.backgroundScope, clock = clock, reconnect = viewModel::connect,
        )
        control = ConnectionControlModel(viewModel, active, streamList, test.backgroundScope, clock, onOpenConnections = {})
        streamList.setShown(true)
    }

    @After
    fun closeEverything() {
        control.close()
        streamList.close()
        active.close()
    }

    private fun ensureSaved(name: String) {
        if (repository.list().any { it.name == name }) return
        val profile = ConnectionProfile(name = name, host = "$name.example.com", virtualHost = "/", username = "svc-$name")
        check(repository.add(profile) is SaveConnectionResult.Saved) { "test setup: could not save $name" }
        secrets.store(name, "not-a-real-secret")
        gateway.catalogOf(name).addStream(stream("$name-orders")).addStream(stream("$name-audit"))
    }

    private fun connect(name: String) {
        ensureSaved(name)
        runBlocking { viewModel.connect(name) }
        test.runCurrent()
    }

    @Given("I am connected to {string}")
    fun iAmConnectedTo(name: String) {
        connect(name)
        assertEquals(LinkState.Connected(name), active.state.value, "precondition: connected to $name")
    }

    @When("I switch to {string}")
    fun iSwitchTo(name: String) {
        connect(name)
    }

    @Then("the app bar shows {string} as the current connection")
    fun theAppBarShowsAsTheCurrentConnection(name: String) {
        val chip = control.state.value.chip
        assertEquals(name, chip.text, "connection shown in the app bar, chip: $chip")
        assertEquals(ChipState.Connected, chip.kind, "the app bar says it is connected, chip: $chip")
    }

    @Then("the stream list shows the streams of {string}")
    fun theStreamListShowsTheStreamsOf(name: String) {
        test.runCurrent()
        val shown = streamList.state.value.rows.map { it.name }
        assertEquals(listOf("$name-audit", "$name-orders"), shown, "streams in the list after the switch (screen ${streamList.state.value.screen})")
        assertTrue(shown.none { !it.startsWith("$name-") }, "a stream of another connection is still listed: $shown")
    }
}
