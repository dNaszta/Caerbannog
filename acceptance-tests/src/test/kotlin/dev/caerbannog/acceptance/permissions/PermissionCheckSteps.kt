package dev.caerbannog.acceptance.permissions

import dev.caerbannog.acceptance.connections.FakeBrokerReachabilityChecker
import dev.caerbannog.acceptance.connections.FakeCaCertificateValidator
import dev.caerbannog.acceptance.connections.FakeSecretStore
import dev.caerbannog.acceptance.connections.InMemoryConnectionProfileRepository
import dev.caerbannog.core.connections.BrokerPermissions
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.DeclaredPermissions
import dev.caerbannog.core.connections.DeclaredPermissionsEdit
import dev.caerbannog.core.connections.PermissionBanner
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionCheckStatus
import dev.caerbannog.core.connections.PillTone
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.UnreadableCause
import dev.caerbannog.core.connections.permissionBanner
import dev.caerbannog.core.connections.permissionPill
import dev.caerbannog.datarabbit.JsonFileConnectionProfileRepository
import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlModel
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.StreamListViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.fail
import java.io.File
import java.nio.file.Files

/**
 * Steps for tests/bdd/features/02_permission_check.feature (M1-03, declared-permission persistence M1-06).
 * No step exists for the scenarios tagged @needs-M2 on purpose. M1-07: the sidebar and edit panel steps run in
 * core (no Compose): they feed what the session found into permissionPill / permissionBanner and compare the
 * strings from docs/tasks/M1-07-permission-state-ui.md. Fakes only: no real broker, keychain or ~/.caerbannog.
 *
 * M1-06: "Declared permissions are saved with the profile" runs in memory through saveProfileChanges with a
 * DeclaredPermissionsEdit.Set. "A profile saved before declarations existed has none" needs a real file: the
 * step writes the M1-02 format fixture into a temp dir (never ~/.caerbannog) and loads it with the real
 * JsonFileConnectionProfileRepository from data-rabbit (project-internal dependency).
 *
 * Contract used (proposed by the test developer, see docs/tasks/M1-03-permission-check.md Status):
 * core types BrokerPermissions, PermissionCheckResult (with topologyUnlocked, publishingEnabled,
 * canBrowseStreams on the sealed type), PermissionChecker, decidePermissions, ConnectionSession, and a
 * suspend `ConnectionsViewModel.saveProfileChanges(originalName, input)` that saves like updateConnection
 * and, on success, re-runs the check through the session; DeclaredPermissions/UnreadableCause per the
 * brief's "Final contract". Red state: those types do not exist yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionCheckSteps {

    private lateinit var repository: InMemoryConnectionProfileRepository
    private lateinit var secretStore: FakeSecretStore
    private lateinit var broker: FakePermissionChecker
    private lateinit var session: ConnectionSession
    private lateinit var viewModel: ConnectionsViewModel
    private lateinit var gateway: FakeConnectionGateway
    private lateinit var active: ActiveConnection
    private lateinit var test: TestScope
    private var control: ConnectionControlModel? = null

    private lateinit var profile: ConnectionProfile
    private var result: PermissionCheckResult? = null
    private var usableWhileChecking: Boolean? = null

    // M1-06
    private var editedProfileName: String? = null
    private var pendingDeclaration: DeclaredPermissionsEdit = DeclaredPermissionsEdit.Keep
    private var reopenedProfile: ConnectionProfile? = null
    private var loadedProfiles: List<ConnectionProfile> = emptyList()
    private var tempDir: File? = null

    // M2-01: a check left running (held by the fake) and the scope it runs in
    private var heldCheck: Job? = null

    @Before
    fun reset() {
        repository = InMemoryConnectionProfileRepository()
        secretStore = FakeSecretStore()
        broker = FakePermissionChecker()
        session = ConnectionSession(broker, secretStore)
        // M2-05b: the same wiring as the app (one ActiveConnection), so "connect" and "switch" are the real thing and the app bar
        // (ConnectionControlModel) can be read. The broker behind the gateway is a fake that lets every connection open.
        gateway = FakeConnectionGateway()
        active = ActiveConnection(session, gateway)
        viewModel = ConnectionsViewModel(
            repository, secretStore, FakeBrokerReachabilityChecker(), FakeCaCertificateValidator(), session, active,
        )
        test = TestScope()
        control = null
        result = null
        usableWhileChecking = null
        editedProfileName = null
        pendingDeclaration = DeclaredPermissionsEdit.Keep
        reopenedProfile = null
        loadedProfiles = emptyList()
        heldCheck = null
    }

    @After
    fun deleteTempFiles() {
        control?.close()
        active.close()
        tempDir?.deleteRecursively()
        tempDir = null
    }

    @Given("a saved connection {string} with user {string} on virtual host {string}")
    fun aSavedConnection(name: String, user: String, vhost: String) {
        profile = ConnectionProfile(name = name, host = "rabbit.example", virtualHost = vhost, username = user)
        check(repository.add(profile) is SaveConnectionResult.Saved)
        secretStore.store(name, "s3cret-for-$name")
    }

    // M2-14: the profile's TLS switch. The fake broker answers either way; what the scenario checks is that the app asked in the profile's mode.
    @Given("the connection {string} has TLS {word}")
    fun theConnectionHasTls(name: String, word: String) {
        val tls = when (word) { "on" -> true; "off" -> false; else -> error("TLS is 'on' or 'off' in a scenario, got '$word'") }
        val ports = dev.caerbannog.core.connections.BrokerEndpoints.defaultsFor(tls)
        assertEquals(profile.name, name)
        profile = profile.copy(tls = tls, streamPort = ports.stream, managementPort = ports.management)
        repository.update(profile)
    }

    @Then("^the permission check asked the broker (with|without) TLS$")
    fun thePermissionCheckAskedTheBrokerInMode(mode: String) {
        val asked = broker.calls.lastOrNull()?.first
        assertTrue(asked != null, "the permission check never asked the broker")
        assertEquals(mode == "with", asked!!.tls, "the check asked with tls=${asked.tls}")
        assertEquals(profile.managementPort, asked.managementPort, "the check used the management port of the profile")
    }

    @Given("the user has these permissions on {string}: configure {string}, write {string}, read {string}")
    fun theUserHasPermissions(vhost: String, configure: String, write: String, read: String) {
        assertEquals(profile.virtualHost, vhost, "the permissions must be for the connection's virtual host")
        broker.permissions = BrokerPermissions(configure = configure, write = write, read = read)
    }

    @Given("the management API refuses to return the user's permissions")
    fun theManagementApiRefuses() {
        broker.refusal = UnreadableCause.NOT_AUTHORIZED
    }

    @Given("I am connected to {string} and the user has configure permission")
    fun connectedWithConfigure(name: String) {
        broker.permissions = BrokerPermissions(configure = ".*", write = ".*", read = ".*")
        result = runBlocking { session.open(profile) }
        assertTrue(result!!.topologyUnlocked, "precondition: topology unlocked, got $result")
    }

    @When("the permission check runs")
    fun thePermissionCheckRuns() {
        result = runBlocking { session.open(profile) }
    }

    @When("I connect to {string} for the first time")
    fun iConnectForTheFirstTime(name: String) {
        assertFalse(session.isUsable(name), "precondition: a never-opened connection must not be usable")
        broker.onCheck = { usableWhileChecking = session.isUsable(name) }
        result = runBlocking { session.open(profile) }
    }

    @When("the user's configure permission is removed on the broker")
    fun configureIsRemoved() {
        broker.permissions = BrokerPermissions(configure = "", write = ".*", read = ".*")
    }

    @When("I save a change to the profile {string}")
    fun iSaveAChange(name: String) {
        val input = ConnectionProfileInput(
            name = name, host = "rabbit.example", virtualHost = profile.virtualHost,
            username = profile.username, password = null,
        )
        val saved = runBlocking { viewModel.saveProfileChanges(name, input) }
        assertTrue(saved is SaveConnectionResult.Saved, "the edit itself must save, got $saved")
        result = session.permissions(name)
    }

    @Then("the permission check runs before any stream or topology screen opens")
    fun checkRanBeforeUse() {
        assertEquals(1, broker.calls.size, "expected exactly one permission check, got ${broker.calls.size}")
        assertEquals(false, usableWhileChecking, "the connection was already usable while the check was still running")
        assertTrue(session.isUsable(profile.name), "the connection must be usable once the check is done")
    }

    @Then("the permission check runs again")
    fun checkRunsAgain() {
        assertEquals(2, broker.calls.size, "expected the check to run on open and again after the save")
    }

    @Then("topology changes are {word}")
    fun topologyChangesAre(state: String) {
        val r = checkNotNull(result) { "no permission check result" }
        assertEquals(state == "unlocked", r.topologyUnlocked, "topology: expected $state for $r")
        assertTrue(state == "unlocked" || state == "locked", "unknown state '$state'")
    }

    @Then("test publishing is {word}")
    fun testPublishingIs(state: String) {
        val r = checkNotNull(result) { "no permission check result" }
        assertEquals(state == "enabled", r.publishingEnabled, "publishing: expected $state for $r")
        assertTrue(state == "enabled" || state == "disabled", "unknown state '$state'")
    }

    @Then("the app tells me that permissions could not be read")
    fun appTellsUnreadable() {
        val unreadable = when (val r = result) {
            is PermissionCheckResult.Unreadable -> r
            is PermissionCheckResult.DeclaredByUser -> r.unreadable
            else -> fail("expected permissions that could not be read, got $r")
        }
        assertTrue(
            unreadable.userMessage.startsWith("Permissions could not be read"),
            "the message shown to the user must start with 'Permissions could not be read', got '${unreadable.userMessage}'",
        )
    }

    @Given("the profile {string} declares configure {string} and write {string}")
    fun profileDeclares(name: String, configure: String, write: String) {
        assertEquals(profile.name, name)
        profile = profile.copy(
            declaredPermissions = DeclaredPermissions(configure = configure == "yes", write = write == "yes"),
        )
        // the session is handed the profile as the repository holds it
        check(repository.update(profile) is SaveConnectionResult.Saved)
    }

    @Given("no password is stored for {string}")
    fun noPasswordStored(name: String) {
        secretStore.delete(name)
        assertEquals(null, secretStore.retrieve(name), "precondition: no password stored")
    }

    @Then("the app says these permissions are declared by me, not confirmed by the broker")
    fun declaredByUser() {
        assertTrue(result is PermissionCheckResult.DeclaredByUser, "expected DeclaredByUser, got $result")
    }

    @Then("the app says these permissions were read from the broker")
    fun readFromBroker() {
        assertTrue(result is PermissionCheckResult.Checked, "expected Checked, got $result")
    }

    // Capability flag only: no stream list exists yet (M2), so this asserts that the check result does not
    // block browsing, nothing more.
    @Then("streams can still be listed and browsed")
    fun streamsCanStillBeBrowsed() {
        assertTrue(checkNotNull(result).canBrowseStreams, "reading streams must never depend on the permission check")
    }

    // M2-08b: "Listing streams does not need the configure permission" (the browse half of the old scenario is @needs-M3 and has no step).
    // The step runs the REAL stream list view model on the connection the scenario opened with the locked permission result: reading streams
    // must not depend on the check (`canBrowseStreams` is true in every case) and the list must come out all the same.
    @Then("I can list streams")
    fun iCanListStreams() {
        val checked = checkNotNull(result) { "no permission check result: the scenario must connect first" }
        assertFalse(checked.topologyUnlocked, "precondition: the user has no configure permission, got $checked")
        assertTrue(checked.canBrowseStreams, "reading streams must never depend on the permission check")
        gateway.catalogOf(profile.name).addStream(dev.caerbannog.core.connections.fakes.FakeStreamCatalog.stream("orders"))
        val list = StreamListViewModel(
            active = active, settings = FakeAppSettings(), scope = test.backgroundScope,
            clock = RefreshClock { test.testScheduler.currentTime }, reconnect = viewModel::connect,
        )
        list.setShown(true)
        test.runCurrent()
        assertEquals(listOf("orders"), list.state.value.rows.map { it.name }, "the stream list with a locked topology, screen ${list.state.value.screen}")
        list.close()
    }

    // M2-08c: the topology screen's reasons, core half: the REAL `TopologyViewModel` on the connection the scenario opened, with the context the
    // composition root builds (profile and the session's permission result). The drawing of the banner is a Compose test of 08c. NOTE (owner decision
    // O1): at the default Read-only level the level banner comes FIRST in the list and is the one drawn; these steps check the reason is in the list.
    private fun topologyBanners(): List<dev.caerbannog.core.topology.TopologyBanner> {
        val vm = dev.caerbannog.core.topology.TopologyViewModel(
            active = active, settings = FakeAppSettings(), scope = test.backgroundScope, clock = RefreshClock { test.testScheduler.currentTime },
            reconnect = viewModel::connect,
            context = kotlinx.coroutines.flow.MutableStateFlow(dev.caerbannog.core.topology.TopologyContext(profile, session.permissions(profile.name))),
        )
        test.runCurrent()
        return vm.state.value.banners.also { vm.close() }
    }

    // The default level of a profile is Read-only (T5), and the level banner is drawn first (owner decision O1), so the scenarios that name the
    // broker's reason put the connection at a level above Read-only first. Saved through the view model, so the connect below uses it.
    @Given("the connection {string} is set to the {string} topology level")
    fun theConnectionIsSetToLevel(name: String, level: String) {
        assertEquals(profile.name, name, "the level must be set on the saved connection")
        val wanted = when (level) {
            "Read-only" -> dev.caerbannog.core.connections.TopologyControlLevel.READ_ONLY
            "Declare and edit" -> dev.caerbannog.core.connections.TopologyControlLevel.DECLARE_AND_EDIT
            "Full control" -> dev.caerbannog.core.connections.TopologyControlLevel.FULL_CONTROL
            else -> error("unknown level \"$level\"")
        }
        val saved = viewModel.setTopologyControlLevel(name, wanted)
        assertTrue(saved is SaveConnectionResult.Saved, "the level was not saved: $saved")
        profile = profile.copy(topologyControlLevel = wanted)
    }

    @Then("the topology screen explains that the user has no configure permission on virtual host {string}")
    fun topologyScreenExplainsLocked(vhost: String) {
        val banners = topologyBanners()
        val reason = banners.firstOrNull()
        assertEquals(dev.caerbannog.core.topology.TopologyBannerKind.NoConfigure, reason?.kind, "the FIRST banner is the one drawn, banners: $banners")
        assertEquals(dev.caerbannog.core.topology.TopologyTexts.noConfigure(profile.username, vhost), reason?.headline, "banners: $banners")
    }

    @Then("the topology screen says the broker could not confirm the permissions and still enforces its own")
    fun topologyScreenSaysDeclared() {
        val banners = topologyBanners()
        val first = banners.firstOrNull()
        assertEquals(dev.caerbannog.core.topology.TopologyBannerKind.DeclaredNotConfirmed, first?.kind, "the FIRST banner is the one drawn, banners: $banners")
        val text = first?.let { it.headline + " " + it.detail.orEmpty() }.orEmpty()
        assertTrue(text.contains("could not confirm") && text.contains("still enforces its own"), "banners: $banners")
    }

    // --- M1-07: what the sidebar pill and the edit panel banner say (core level) --------------------

    @Given("the user has no configure permission on {string}")
    fun theUserHasNoConfigurePermission(vhost: String) {
        assertEquals(profile.virtualHost, vhost, "the permissions must be for the connection's virtual host")
        broker.permissions = BrokerPermissions(configure = "", write = ".*", read = ".*")
    }

    @When("I connect to {string}")
    fun iConnectTo(name: String) {
        assertEquals(profile.name, name, "the connection being opened must be the saved one")
        runBlocking { viewModel.connect(name) }
        result = session.permissions(name)
    }

    // M2-01: once a connection is current (connect / switch steps), the app bar shows the current connection and
    // its status. Only the scenario "The latest check wins ..." never connects (it opens two checks on the session directly),
    // so nothing is current there and the status is read per profile name from the session; that step is thinner than its wording.
    private fun statusOf(name: String): PermissionCheckStatus {
        val current = viewModel.state.value.currentConnection
        if (current != null) {
            assertEquals(name, current.name, "the sidebar shows '${current.name}', not '$name'")
            return checkNotNull(current.status) { "the current connection '$name' has not been checked" }
        }
        assertEquals(profile.name, name, "the profile shown must be the saved one")
        return PermissionCheckStatus.Done(checkNotNull(session.permissions(name)) { "no permission check result for '$name'" })
    }

    private fun bannerOf(): PermissionBanner = permissionBanner(profile, statusOf(profile.name))

    // --- M2-01: overlapping checks, current connection ------------------------------------------

    private fun ensureSaved(name: String) {
        if (repository.list().none { it.name == name }) {
            val saved = ConnectionProfile(name = name, host = "rabbit.example", virtualHost = "/", username = "svc-explorer")
            check(repository.add(saved) is SaveConnectionResult.Saved)
            secretStore.store(name, "s3cret-for-$name")
        }
    }

    @Given("the first check of {string} is still running and would say the user can change topology")
    fun firstCheckStillRunning(name: String) {
        assertEquals(profile.name, name, "the connection must be the saved one")
        broker.permissions = BrokerPermissions(configure = ".*", write = ".*", read = ".*")
        broker.holdNextCheck()
        // Unconfined: the check runs up to the broker call and stays there until releaseHeld.
        heldCheck = CoroutineScope(Dispatchers.Unconfined).launch { session.open(profile) }
        assertEquals(1, broker.calls.size, "precondition: the first check has asked the broker")
        assertFalse(session.isUsable(name), "precondition: the connection is not usable while its first check runs")
    }

    @Given("the connection {string} is checked again and that check says the user has no configure permission")
    fun checkedAgainNoConfigure(name: String) {
        assertEquals(profile.name, name, "the connection must be the saved one")
        broker.permissions = BrokerPermissions(configure = "", write = ".*", read = ".*")
        result = runBlocking { session.open(profile) }
        assertEquals(2, broker.calls.size, "precondition: the second check ran")
        assertFalse(result!!.topologyUnlocked, "precondition: the second check says locked, got $result")
    }

    @When("the first check finishes")
    fun theFirstCheckFinishes() {
        broker.releaseHeld()
        runBlocking { withTimeout(5_000) { checkNotNull(heldCheck) { "no check was started" }.join() } }
    }

    @Given("saved connections {string} and {string}")
    fun savedConnections(first: String, second: String) {
        ensureSaved(first)
        ensureSaved(second)
    }

    @Given("the user has configure permission on {string} and no configure permission on {string}")
    fun configurePerConnection(withConfigure: String, withoutConfigure: String) {
        broker.permissionsByName[withConfigure] = BrokerPermissions(configure = ".*", write = ".*", read = ".*")
        broker.permissionsByName[withoutConfigure] = BrokerPermissions(configure = "", write = ".*", read = ".*")
    }

    @Given("I am connected to {string}")
    fun iAmConnectedTo(name: String) {
        runBlocking { viewModel.connect(name) }
        assertEquals(name, viewModel.state.value.currentConnection?.name, "precondition: '$name' is the current connection")
    }

    @When("I switch to {string}")
    fun iSwitchTo(name: String) {
        runBlocking { viewModel.connect(name) }
    }

    private fun appBar(): ConnectionChipState {
        val model = control ?: ConnectionControlModel(
            viewModel, active,
            StreamListViewModel(
                active = active, settings = FakeAppSettings(), scope = test.backgroundScope,
                clock = RefreshClock { test.testScheduler.currentTime }, reconnect = viewModel::connect,
            ),
            test.backgroundScope, RefreshClock { test.testScheduler.currentTime }, onOpenConnections = {},
        ).also { control = it }
        test.runCurrent()
        return model.state.value.chip
    }

    @Then("the app bar shows {string} as the current connection")
    fun appBarShowsCurrent(name: String) {
        val chip = appBar()
        assertEquals(name, chip.text, "connection shown in the app bar, chip: $chip")
        assertTrue(chip.kind != ChipState.NoConnection, "the app bar says there is no connection: $chip")
    }

    @Then("the app bar shows {string} next to {string}")
    fun theAppBarShows(text: String, name: String) {
        if (viewModel.state.value.currentConnection == null) {
            assertEquals(text, permissionPill(statusOf(name)).text, "pill text next to '$name' (read from the session, nothing is connected)")
            return
        }
        val chip = appBar()
        assertEquals(name, chip.text, "connection shown in the app bar, chip: $chip")
        assertEquals(text, chip.pill?.text, "pill text next to '$name', chip: $chip")
    }

    @Then("the edit panel says the broker confirmed that {string} can change topology on virtual host {string}")
    fun theEditPanelSaysConfirmed(user: String, vhost: String) {
        val banner = bannerOf()
        assertEquals(PillTone.OK, banner.tone, "a broker-confirmed result is the ok tone, got $banner")
        assertEquals("$user can change topology on virtual host $vhost.", banner.headline)
        assertTrue(
            banner.detail.orEmpty().startsWith("The broker confirmed it"),
            "the detail must say the broker confirmed it, got '${banner.detail}'",
        )
    }

    @Then("the edit panel says the broker could not confirm the permissions and still enforces its own")
    fun theEditPanelSaysNotConfirmed() {
        val banner = bannerOf()
        assertEquals(PillTone.WARNING, banner.tone, "a declared result is the warning tone, never ok, got $banner")
        assertTrue(
            banner.detail.orEmpty().contains("The broker could not confirm it and still enforces its own permissions."),
            "the detail must say the broker could not confirm it, got '${banner.detail}'",
        )
    }

    @Then("the edit panel says {string}")
    fun theEditPanelSays(text: String) {
        val banner = bannerOf()
        assertEquals(text, banner.dimNote, "the dim note of $banner")
    }

    @Then("the edit panel tells me that permissions could not be read")
    fun theEditPanelTellsUnreadable() {
        val unreadable = checkNotNull(result as? PermissionCheckResult.Unreadable) { "expected Unreadable, got $result" }
        val banner = bannerOf()
        assertEquals(PillTone.LOCKED, banner.tone, "an unreadable result is locked, got $banner")
        assertEquals(unreadable.userMessage, banner.headline, "the banner must show the reason the broker gave")
        assertTrue(banner.headline.startsWith("Permissions could not be read"), "got '${banner.headline}'")
    }

    // --- M1-06 ---------------------------------------------------------------------------------

    @Given("I edit the profile {string}")
    fun iEditTheProfile(name: String) {
        assertEquals(profile.name, name, "the profile being edited must be the saved one")
        editedProfileName = name
    }

    @When("I declare that the user can change topology and can publish")
    fun iDeclareConfigureAndWrite() {
        pendingDeclaration = DeclaredPermissionsEdit.Set(DeclaredPermissions(configure = true, write = true))
    }

    @When("I save the profile")
    fun iSaveTheProfile() {
        val name = checkNotNull(editedProfileName) { "no profile is being edited" }
        val input = ConnectionProfileInput(
            name = name, host = profile.host, virtualHost = profile.virtualHost,
            username = profile.username, password = null, declaredPermissions = pendingDeclaration,
        )
        val saved = runBlocking { viewModel.saveProfileChanges(name, input) }
        assertTrue(saved is SaveConnectionResult.Saved, "the edit itself must save, got $saved")
    }

    @When("I reopen the saved profile {string}")
    fun iReopenTheSavedProfile(name: String) {
        // Read back from the repository (the source of truth), not from what the save returned.
        reopenedProfile = repository.list().firstOrNull { it.name == name }
        assertTrue(reopenedProfile != null, "profile '$name' is not in the repository after saving")
    }

    @Then("the profile declares configure {string} and write {string}")
    fun theProfileDeclares(configure: String, write: String) {
        val declared = checkNotNull(reopenedProfile) { "no profile was reopened" }.declaredPermissions
        assertEquals(
            DeclaredPermissions(configure = configure == "yes", write = write == "yes"), declared,
            "declared permissions of the saved profile",
        )
    }

    @Given("a saved profile file from before declared permissions existed")
    fun aSavedProfileFileFromBefore() {
        val dir = Files.createTempDirectory("caerbannog-m1-06-").toFile()
        tempDir = dir
        val fixture = checkNotNull(javaClass.getResource("/fixtures/connections-m1-02.json")) {
            "fixture /fixtures/connections-m1-02.json missing"
        }.readText()
        File(dir, "connections.json").writeText(fixture)
    }

    @When("the profiles are loaded")
    fun theProfilesAreLoaded() {
        val dir = checkNotNull(tempDir) { "no profile file was prepared" }
        loadedProfiles = JsonFileConnectionProfileRepository(File(dir, "connections.json")).list()
    }

    @Then("{string} declares nothing")
    fun declaresNothing(name: String) {
        val loaded = checkNotNull(loadedProfiles.firstOrNull { it.name == name }) {
            "profile '$name' was not loaded from the old-format file, got ${loadedProfiles.map { it.name }}"
        }
        assertEquals(null, loaded.declaredPermissions, "an old-format profile must declare nothing")
    }

    @Then("the permission check, if it cannot read permissions, uses the safest mode")
    fun safestModeWhenUnreadable() {
        val loaded = checkNotNull(loadedProfiles.firstOrNull()) { "no profile was loaded" }
        broker.refusal = UnreadableCause.NOT_AUTHORIZED
        val outcome = runBlocking { session.open(loaded) }
        assertTrue(outcome is PermissionCheckResult.Unreadable, "expected safest mode (Unreadable), got $outcome")
        assertFalse(outcome.topologyUnlocked, "topology must be locked in safest mode")
        assertFalse(outcome.publishingEnabled, "publishing must be disabled in safest mode")
    }
}
