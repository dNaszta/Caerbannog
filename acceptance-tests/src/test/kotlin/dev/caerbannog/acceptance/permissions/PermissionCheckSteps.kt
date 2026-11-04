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
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.UnreadableCause
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.fail

/**
 * Steps for tests/bdd/features/02_permission_check.feature (M1-03). No step exists for the scenarios
 * tagged @needs-M1-07 or @needs-M2 on purpose.
 *
 * Contract used (proposed by the test developer, see docs/tasks/M1-03-permission-check.md Status):
 * core types BrokerPermissions, PermissionCheckResult (with topologyUnlocked, publishingEnabled,
 * canBrowseStreams on the sealed type), PermissionChecker, decidePermissions, ConnectionSession, and a
 * suspend `ConnectionsViewModel.saveProfileChanges(originalName, input)` that saves like updateConnection
 * and, on success, re-runs the check through the session; DeclaredPermissions/UnreadableCause per the
 * brief's "Final contract". Red state: those types do not exist yet.
 */
class PermissionCheckSteps {

    private lateinit var repository: InMemoryConnectionProfileRepository
    private lateinit var secretStore: FakeSecretStore
    private lateinit var broker: FakePermissionChecker
    private lateinit var session: ConnectionSession
    private lateinit var viewModel: ConnectionsViewModel

    private lateinit var profile: ConnectionProfile
    private var result: PermissionCheckResult? = null
    private var usableWhileChecking: Boolean? = null

    @Before
    fun reset() {
        repository = InMemoryConnectionProfileRepository()
        secretStore = FakeSecretStore()
        broker = FakePermissionChecker()
        session = ConnectionSession(broker, secretStore)
        viewModel = ConnectionsViewModel(
            repository, secretStore, FakeBrokerReachabilityChecker(), FakeCaCertificateValidator(), session,
        )
        result = null
        usableWhileChecking = null
    }

    @Given("a saved connection {string} with user {string} on virtual host {string}")
    fun aSavedConnection(name: String, user: String, vhost: String) {
        profile = ConnectionProfile(name = name, host = "rabbit.example", virtualHost = vhost, username = user)
        check(repository.add(profile) is SaveConnectionResult.Saved)
        secretStore.store(name, "s3cret-for-$name")
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
}
