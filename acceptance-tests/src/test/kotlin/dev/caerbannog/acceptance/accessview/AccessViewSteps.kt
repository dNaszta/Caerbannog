package dev.caerbannog.acceptance.accessview

import dev.caerbannog.acceptance.connections.FakeSecretStore
import dev.caerbannog.acceptance.permissions.FakePermissionChecker
import dev.caerbannog.core.access.AccessContext
import dev.caerbannog.core.access.AccessGateway
import dev.caerbannog.core.access.AccessResult
import dev.caerbannog.core.access.AccessScreen
import dev.caerbannog.core.access.AccessState
import dev.caerbannog.core.access.AccessTexts
import dev.caerbannog.core.access.AccessViewModel
import dev.caerbannog.core.access.VhostsStatus
import dev.caerbannog.core.access.fakes.FakeAccessGateway
import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.ConnectionNotes
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.streams.RefreshClock
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.lang.reflect.Modifier

/**
 * Steps for the `@m10-02` scenarios of tests/bdd/features/22_access_who_am_i_and_permissions.feature (M10-02 step 2, written test-first: red until
 * `AccessViewModel` and the wiring of `StreamSource.access` exist). The app side is real core code (`ActiveConnection`, `ConnectionSession`,
 * `AccessViewModel`); only the broker is fake (`FakeConnectionGateway` with its `FakeAccessGateway`). Time is VIRTUAL: "60 seconds pass" advances a
 * test scheduler, no sleeps. Contract of the view model: core jvmTest `AccessHarness`.
 *
 * What a step is thinner than its wording (stated plainly, so nobody reads more into a green run than is there):
 *  - "I see ..." reads `AccessState` (what the screen is told to draw), not pixels. The Compose half (test tags per state) is the UI test of the
 *    screen step, written when the composable exists.
 *  - "there is no control ..." is structural here: the gateway interface has only the two reads and the view model has no method that changes
 *    anything. The drawn screen is checked by the Compose test (no button with those meanings).
 *  - "I am on the Connections screen" reads `AccessState.leaveNotice`: the app frame navigates when it is set.
 *  - "every request used the profile's own management port and scheme" checks the profile the gateway was opened with (TLS off, its own port); the
 *    HTTP scheme and port themselves are pinned by `ManagementAccessGatewayTest` in data-rabbit.
 *  - "the user ... has permissions on ... only" belongs to the Permissions slice (M10-03); it is only recorded here, because the feature's
 *    Background has it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccessViewSteps {

    private companion object {
        const val PASSWORD = "S3cret-PW-4711"
        const val HOME_VHOST = "/orders"
        val PROFILE_NAMES = listOf("prod-eu", "prod-plain", "legacy-3x")
    }

    private lateinit var gateway: FakeConnectionGateway
    private lateinit var active: ActiveConnection
    private lateinit var settings: FakeAppSettings
    private lateinit var test: TestScope
    private lateinit var vm: AccessViewModel
    private lateinit var profiles: Map<String, ConnectionProfile>
    private val context = MutableStateFlow<AccessContext?>(null)
    private var held: String? = null
    private var gate: dev.caerbannog.core.connections.fakes.CallGate? = null
    private var requestsNoted = -1
    private var reconnectCalls = 0
    private val permissionsRecorded = mutableListOf<String>()

    private val state: AccessState get() = vm.state.value

    @Before
    fun reset() {
        gateway = FakeConnectionGateway()
        val secrets = FakeSecretStore().also { store -> PROFILE_NAMES.forEach { store.store(it, PASSWORD) } }
        val session = ConnectionSession(FakePermissionChecker(), secrets)
        active = ActiveConnection(session, gateway)
        settings = FakeAppSettings()
        test = TestScope()
        profiles = mapOf(
            "prod-eu" to profile("prod-eu", tls = true),
            "prod-plain" to profile("prod-plain", tls = false),
            "legacy-3x" to profile("legacy-3x", tls = true),
        )
        context.value = null
        held = null
        gate = null
        requestsNoted = -1
        reconnectCalls = 0
        permissionsRecorded.clear()
        vm = AccessViewModel(
            active = active,
            settings = settings,
            scope = test.backgroundScope,
            clock = RefreshClock { test.testScheduler.currentTime },
            reconnect = { name ->
                reconnectCalls++
                active.connect(profiles.getValue(name))
                publishContext(name)
            },
            context = context,
        )
    }

    @After
    fun closeEverything() {
        vm.close()
        active.close()
    }

    private fun profile(name: String, tls: Boolean) = ConnectionProfile(
        name = name, host = "$name.example.com", virtualHost = HOME_VHOST, username = "svc", managementPort = if (tls) 15671 else 15672, tls = tls,
    )

    private fun settle() = test.runCurrent()

    private fun pass(seconds: Int) {
        test.advanceTimeBy(seconds * 1000L)
        test.runCurrent()
    }

    private fun publishContext(name: String) {
        context.value = AccessContext(profiles.getValue(name))
    }

    private fun accessOf(name: String): FakeAccessGateway = gateway.accessOf(name)

    private val current: FakeAccessGateway get() = accessOf(checkNotNull(held) { "no connection is held in this scenario step" })

    private fun allRequests(): Int = PROFILE_NAMES.sumOf { accessOf(it).requests.size }

    private fun connect(name: String) {
        runBlocking { active.connect(profiles.getValue(name)) }
        held = name
        publishContext(name)
        settle()
    }

    // --- background ---

    @Given("the connection {string} is the held connection")
    fun theConnectionIsHeld(name: String) = connect(name)

    @Given("the connection user is {string} with the tag {string}")
    fun theConnectionUserIs(user: String, tag: String) {
        current.user = user
        current.tags = listOf(tag)
        current.internalUser = true
    }

    @Given("the broker has the virtual hosts {string}, {string} and {string}")
    fun theBrokerHasTheVirtualHosts(a: String, b: String, c: String) {
        current.listedVhosts = listOf(a, b, c)
    }

    @Given("the user {string} has permissions on {string} only")
    fun theUserHasPermissionsOnOnly(user: String, vhost: String) {
        permissionsRecorded += "$user:$vhost" // M10-03 data; nothing of the Who am I tab reads it
    }

    // --- opening and reading the view ---

    @When("I open the Access view")
    fun iOpenTheAccessView() {
        vm.setShown(true)
        settle()
    }

    @When("I leave the Access view")
    fun iLeaveTheAccessView() {
        vm.setShown(false)
        settle()
    }

    @Then("I see the connection user {string}")
    fun iSeeTheConnectionUser(user: String) {
        assertEquals(user, state.user, "connection user on screen; screen is ${state.screen}, message ${state.message}")
    }

    @Then("I do not see the connection user {string}")
    fun iDoNotSeeTheConnectionUser(user: String) {
        assertTrue(state.user != user, "the user \"$user\" is still on screen")
    }

    @Then("I see the tag {string}")
    fun iSeeTheTag(tag: String) {
        assertTrue(tag in state.tags, "tags on screen: ${state.tags}")
    }

    @Then("I see the virtual hosts the broker lists for this user")
    fun iSeeTheVirtualHosts() {
        assertEquals(VhostsStatus.Listed, state.vhostsStatus)
        assertEquals(current.listedVhosts.toSet(), state.vhosts.map { it.name }.toSet(), "the rows are exactly what the broker listed")
    }

    @Then("I see which virtual host the connection itself uses")
    fun iSeeWhichVhostTheConnectionUses() {
        val own = state.vhosts.filter { it.note == AccessTexts.CONNECTION_VHOST_NOTE }
        assertEquals(listOf(HOME_VHOST), own.map { it.name }, "the one row marked as the connection's own")
        assertEquals(HOME_VHOST, state.vhosts.first().name, "the connection's own virtual host comes first")
    }

    // --- no management tag ---

    @Given("the connection user has no management tag")
    fun theConnectionUserHasNoManagementTag() {
        current.hasManagementTag = false
    }

    @Then("I see a message that the management tag is needed")
    fun iSeeTheLockMessage() {
        assertTrue(state.screen is AccessScreen.Locked, "screen is ${state.screen}")
        assertEquals(AccessTexts.LOCKED_TITLE, state.message)
        assertEquals(AccessTexts.LOCKED_DETAIL, state.detail)
    }

    @Then("no virtual host, permission or client is listed")
    fun noDataIsListed() {
        assertNull(state.user)
        assertEquals(emptyList<String>(), state.tags)
        assertEquals(emptyList<Any>(), state.vhosts)
    }

    // --- read only ---

    @Then("there is no control to edit or delete a user, a tag, a permission or a virtual host")
    fun noControlToEdit() = assertOnlyReads()

    @Then("there is no control to close a connection or a channel")
    fun noControlToClose() = assertOnlyReads()

    @Then("there is no control that gets, consumes or purges queue messages")
    fun noControlForQueueMessages() = assertOnlyReads()

    private fun assertOnlyReads() {
        val gatewayMethods = AccessGateway::class.java.methods.map { it.name }.toSet()
        assertEquals(setOf("whoAmI", "virtualHosts"), gatewayMethods, "the access gateway has reads only; a new method needs a decision")
        val allowed = setOf("getState", "getUpdatedAtMillis", "setShown", "refreshNow", "retry", "noticeShown", "close")
        val own = AccessViewModel::class.java.methods.filter { !Modifier.isStatic(it.modifiers) && it.declaringClass == AccessViewModel::class.java }.map { it.name }
        assertTrue(own.all { it in allowed }, "unexpected public methods on the Access view model: ${own.filter { it !in allowed }}")
        assertTrue(state.readOnlyNote == null || state.readOnlyNote == AccessTexts.READ_ONLY_NOTE)
    }

    // --- nothing before the connection is chosen ---

    @Given("no connection is held")
    fun noConnectionIsHeld() {
        runBlocking { active.disconnect() }
        held = null
        settle()
    }

    @Then("I see that no connection is held")
    fun iSeeThatNoConnectionIsHeld() {
        assertTrue(state.screen is AccessScreen.NotConnected, "screen is ${state.screen}")
        assertEquals(AccessTexts.NOT_CONNECTED, state.message)
    }

    @Then("no request was sent to any broker")
    fun noRequestWasSent() {
        assertEquals(0, allRequests(), "requests: ${PROFILE_NAMES.associateWith { accessOf(it).requests.toList() }}")
    }

    // --- TLS off ---

    @Given("the held connection {string} has {string} switched off")
    fun theHeldConnectionHasTlsOff(name: String, switch: String) {
        assertEquals("Use TLS", switch)
        assertFalse(profiles.getValue(name).tls, "the scenario's profile must have TLS off")
        connect(name)
    }

    @Then("I see {string} on the view")
    fun iSeeOnTheView(text: String) {
        assertEquals(ConnectionNotes.NOT_ENCRYPTED, text)
        assertEquals(text, state.notEncrypted)
    }

    @Then("every request used the profile's own management port and scheme")
    fun everyRequestUsedTheProfilesOwnPortAndScheme() {
        val asked = gateway.openedProfiles.last()
        val own = profiles.getValue(asked.name)
        assertEquals(own.tls, asked.tls, "the connection was opened with the profile's own TLS switch")
        assertEquals(own.managementPort, asked.managementPort, "the profile's own management port")
        assertTrue(current.requests.isNotEmpty(), "the view must have read something for this to mean anything")
    }

    // --- lost connection ---

    @Given("the Access view showed the virtual hosts")
    fun theAccessViewShowedTheVirtualHosts() {
        iOpenTheAccessView()
        assertEquals(VhostsStatus.Listed, state.vhostsStatus, "precondition: the virtual hosts were shown")
    }

    @When("the connection to the broker is lost")
    fun theConnectionIsLost() {
        current.unreachable = true
        pass(5)
    }

    @Then("the last data stays on screen marked as stale")
    fun theLastDataStaysStale() {
        assertTrue(state.stale, "stale")
        assertNotNull(state.user, "the last data must stay")
        assertTrue(state.vhosts.isNotEmpty(), "the last virtual hosts must stay")
    }

    @Then("I see the {string} banner")
    fun iSeeTheBanner(text: String) {
        assertEquals(ConnectionFailureReason.CONNECTION_LOST, text)
        assertEquals(text, state.connectionBanner)
    }

    @When("{int} minutes pass")
    fun minutesPass(minutes: Int) = pass(minutes * 60)

    @When("{int} seconds pass")
    fun secondsPass(seconds: Int) = pass(seconds)

    @Then("the app did not connect again by itself")
    fun theAppDidNotConnectAgain() {
        assertEquals(0, reconnectCalls)
        assertEquals(1, gateway.openCalls.size, "opens: ${gateway.openCalls}")
        assertTrue(active.state.value is LinkState.Failed, "link is ${active.state.value}")
    }

    @When("the broker is back and I choose Retry")
    fun theBrokerIsBackAndIChooseRetry() {
        current.unreachable = false
        vm.retry()
        settle()
    }

    @Then("the connection is held again")
    fun theConnectionIsHeldAgain() {
        assertEquals(LinkState.Connected(checkNotNull(held)), active.state.value)
        assertEquals(1, reconnectCalls)
    }

    @Then("the data is no longer marked as stale")
    fun theDataIsNoLongerStale() {
        assertFalse(state.stale)
        assertNull(state.connectionBanner)
    }

    // --- virtual host list errors ---

    @Given("the broker answers the virtual host list with an error")
    fun theBrokerAnswersVhostsWithAnError() {
        current.vhostsFailure = AccessResult.Unexpected("HTTP 500")
    }

    // --- M10-02b: unreadable user while data is shown ---

    @When("the broker answers the user with an error")
    fun theBrokerAnswersTheUserWithAnError() {
        current.whoamiFailure = AccessResult.Unexpected("HTTP 500")
    }

    @Then("I see that the user could not be read, with the broker's reason, and a Retry")
    fun iSeeTheUserCouldNotBeRead() {
        val banner = state.readErrorBanner
        assertTrue(
            banner != null && AccessTexts.USER_ERROR in banner && AccessTexts.brokerText("HTTP 500") in banner,
            "expected the read-error banner with the broker's reason, state was: readErrorBanner=$banner",
        )
        assertEquals(AccessTexts.RETRY, state.action)
    }

    @When("the broker answers the user again and I choose Retry")
    fun theBrokerAnswersTheUserAgainAndIChooseRetry() {
        current.whoamiFailure = null
        vm.retry()
        settle()
    }

    @Then("I no longer see that the user could not be read")
    fun iNoLongerSeeTheReadError() {
        assertNull(state.readErrorBanner)
    }

    @Then("I still see the connection user and the tags")
    fun iStillSeeTheUserAndTags() {
        assertEquals(current.user, state.user)
        assertEquals(current.tags, state.tags)
    }

    @Then("I see that the virtual host list could not be read")
    fun iSeeTheVhostListCouldNotBeRead() {
        assertEquals(VhostsStatus.Unreadable, state.vhostsStatus)
        assertEquals(AccessTexts.VHOSTS_ERROR, state.vhostsMessage)
    }

    // --- 3.x (AQ6) ---

    @Given("the connection {string} is a RabbitMQ 3.x broker and is the held connection")
    fun theConnectionIsA3xBrokerAndHeld(name: String) {
        gateway.brokerRuns(name, "3.13.7")
        connect(name)
    }

    @Then("the drawer has no Access item")
    fun theDrawerHasNoAccessItem() {
        assertFalse(state.offered, "the Access item must be absent on a 3.x connection")
    }

    @Then("no request was sent to the 3.x broker")
    fun noRequestWasSentToThe3xBroker() {
        // Even if the screen were shown (a stale deep link, a tab restored): the view must not ask.
        vm.setShown(true)
        pass(60)
        assertEquals(emptyList<String>(), current.requests.toList(), "a request was sent to the 3.x broker")
        assertEquals(0, allRequests())
    }

    @Given("the Access view is open on the connection {string}")
    fun theAccessViewIsOpenOn(name: String) {
        if (held != name) connect(name)
        iOpenTheAccessView()
        assertEquals(current.user, state.user, "precondition: the view showed the user")
        requestsNoted = allRequests()
    }

    @When("the held connection becomes the RabbitMQ 3.x connection {string}")
    fun theHeldConnectionBecomes3x(name: String) {
        gateway.brokerRuns(name, "3.13.7")
        connect(name)
        pass(60)
    }

    @Then("I am on the Connections screen")
    fun iAmOnTheConnectionsScreen() {
        assertNotNull(state.leaveNotice, "the view must hand the app a reason to return to Connections")
    }

    @Then("I see the one-line notice {string}")
    fun iSeeTheNotice(text: String) {
        assertEquals(AccessTexts.NEEDS_4X, text, "the scenario quotes the approved notice")
        assertEquals(text, state.leaveNotice)
    }

    @Then("no request was sent to the 3.x broker by the Access view")
    fun noRequestWasSentToThe3xBrokerByTheView() {
        assertEquals(emptyList<String>(), accessOf("legacy-3x").requests.toList(), "a request was sent to the 3.x broker")
    }

    // --- new scenarios of this step ---

    @Given("the broker has not answered yet")
    fun theBrokerHasNotAnsweredYet() {
        gate = current.holdNext("whoami")
    }

    @Then("I see the loading text and loading rows, not a blank page")
    fun iSeeLoading() {
        assertTrue(state.screen is AccessScreen.Loading, "screen is ${state.screen}")
        assertEquals(AccessTexts.LOADING, state.message)
        assertEquals(VhostsStatus.Loading, state.vhostsStatus)
    }

    @Then("I see the note that this screen only reads")
    fun iSeeTheReadOnlyNote() {
        assertEquals(AccessTexts.READ_ONLY_NOTE, state.readOnlyNote)
    }

    @When("the broker answers")
    fun theBrokerAnswers() {
        checkNotNull(gate) { "no call was held" }.release()
        settle()
    }

    @Given("the broker lists no virtual host for this user")
    fun theBrokerListsNoVirtualHost() {
        current.listedVhosts = emptyList()
    }

    @Then("I see that the broker lists no virtual host for this user")
    fun iSeeNoVirtualHostText() {
        assertEquals(VhostsStatus.Empty, state.vhostsStatus)
        assertEquals(AccessTexts.NO_VHOSTS, state.vhostsMessage)
    }

    @Given("the connection user is the token user {string} with the tag {string}")
    fun theConnectionUserIsATokenUser(user: String, tag: String) {
        current.user = user
        current.tags = listOf(tag)
        current.internalUser = false
    }

    @Then("I see that the name is as the broker reports it")
    fun iSeeTheNameIsAsTheBrokerReportsIt() {
        assertTrue(state.tokenUser, "a token user is flagged from is_internal_user = false")
        assertEquals("as the broker reports it", AccessTexts.TOKEN_USER_NOTE)
    }

    @Given("the broker refuses the sign-in")
    fun theBrokerRefusesTheSignIn() {
        current.refusesSignIn = true
    }

    @Then("I see that the broker refused the sign-in")
    fun iSeeTheSignInWasRefused() {
        assertEquals(AccessScreen.Failed(AccessTexts.SIGN_IN_REFUSED), state.screen)
        assertEquals(AccessTexts.RETRY, state.action)
    }

    @Then("no password and no token text is shown")
    fun noSecretIsShown() {
        val everything = listOf(state.toString(), active.state.value.toString(), gateway.log.toList().toString()).joinToString("\n")
        assertFalse(everything.contains(PASSWORD), "the password is visible somewhere the user or a log could see it")
        assertFalse(everything.contains("eyJ"), "token-shaped text is visible")
    }

    @Then("the connection is not reported as lost")
    fun theConnectionIsNotReportedLost() {
        assertEquals(LinkState.Connected(checkNotNull(held)), active.state.value)
        assertNull(state.connectionBanner)
    }

    @Given("the Access view is not open")
    fun theAccessViewIsNotOpen() {
        // The Background held the connection; the view was never shown.
        assertEquals(0, allRequests(), "precondition: nothing was read yet")
    }

    @Then("the broker was asked for the user and for the virtual hosts")
    fun theBrokerWasAsked() {
        assertEquals(setOf("whoami", "vhosts"), current.requests.toSet())
    }

    @When("the number of requests is noted")
    fun theNumberOfRequestsIsNoted() {
        requestsNoted = allRequests()
    }

    @Then("no further request was sent")
    fun noFurtherRequest() {
        assertEquals(requestsNoted, allRequests(), "the hidden view kept reading")
    }

    @Given("the connection {string} has the connection user {string} with the tag {string}")
    fun theOtherConnectionHasTheUser(name: String, user: String, tag: String) {
        val other = accessOf(name)
        other.user = user
        other.tags = listOf(tag)
        other.listedVhosts = listOf(HOME_VHOST)
    }

    @When("I choose the connection {string}")
    fun iChooseTheConnection(name: String) {
        connect(name)
        settle()
    }
}
