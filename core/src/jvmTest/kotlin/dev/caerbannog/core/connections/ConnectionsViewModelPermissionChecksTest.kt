package dev.caerbannog.core.connections

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1-07: `ConnectionsState.permissionChecks` and how the view model fills it. No sleeps: a gated checker
 * (CompletableDeferred) holds a check open so the Checking state can be observed, and the coroutine is
 * started Unconfined so it runs up to the gate synchronously. Red state: `permissionChecks`,
 * `PermissionCheckStatus` and `checkConnectionAndPermissions` do not exist yet.
 */
class ConnectionsViewModelPermissionChecksTest {

    private class Repo : ConnectionProfileRepository {
        val profiles = linkedMapOf<String, ConnectionProfile>()
        override fun list() = profiles.values.toList()
        override fun add(profile: ConnectionProfile): SaveConnectionResult {
            if (profiles.containsKey(profile.name)) return SaveConnectionResult.NameAlreadyExists(profile.name)
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }
        override fun update(profile: ConnectionProfile): SaveConnectionResult {
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }
        override fun delete(name: String) { profiles.remove(name) }
    }

    private class Secrets : SecretStore {
        val map = mutableMapOf<String, String>()
        override fun store(profileName: String, password: String) { map[profileName] = password }
        override fun retrieve(profileName: String) = map[profileName]
        override fun delete(profileName: String) { map.remove(profileName) }
    }

    private class Reach : BrokerReachabilityChecker {
        var result: ConnectionCheckResult = ConnectionCheckResult.BothReachable
        var calls = 0
        override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult {
            calls++
            return result
        }
    }

    private class Ca : CaCertificateValidator {
        override fun validate(filePath: String) = CaCertificateValidationResult.Valid
    }

    /** Answers immediately unless [gate] is set; then every check waits for it. */
    private class GatedChecker : PermissionChecker {
        var result: PermissionCheckResult = PermissionCheckResult.Checked(true, true)
        var gate: CompletableDeferred<Unit>? = null
        /** Holds only the next call (then cleared), so a later call in the same test is not blocked. */
        var firstCallGate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<ConnectionProfile>()
        override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
            calls += profile
            val once = firstCallGate
            firstCallGate = null
            once?.await()
            gate?.await()
            return result
        }
    }

    private val repo = Repo()
    private val secrets = Secrets()
    private val reach = Reach()
    private val checker = GatedChecker()
    private val session = ConnectionSession(checker, secrets)
    private val vm = ConnectionsViewModel(repo, secrets, reach, Ca(), session)
    private val profile = ConnectionProfile(name = "prod-eu", host = "h", virtualHost = "/", username = "svc")
    private val other = ConnectionProfile(name = "dev", host = "h2", virtualHost = "/", username = "svc")

    init {
        repo.add(profile)
        repo.add(other)
        secrets.store("prod-eu", "pw")
        secrets.store("dev", "pw")
    }

    private fun input(name: String = "prod-eu", host: String = "h") = ConnectionProfileInput(
        name = name, host = host, virtualHost = "/", username = "svc", password = null,
    )

    private fun checks(v: ConnectionsViewModel = vm) = v.state.value.permissionChecks

    // --- construction and selection ------------------------------------------------------------

    @Test
    fun `nothing is checked at construction`() {
        assertEquals(emptyMap(), checks())
        assertTrue(checker.calls.isEmpty(), "the view model must never run a check on its own")
        assertEquals(0, reach.calls)
    }

    @Test
    fun `selecting and starting a new connection leave the permission checks untouched`() = runBlocking {
        vm.checkConnectionAndPermissions("prod-eu")
        val before = checks()
        assertEquals(setOf("prod-eu"), before.keys, "precondition")

        vm.selectConnection("dev")
        assertEquals(before, checks())
        vm.startNewConnection()
        assertEquals(before, checks())
        assertEquals(1, checker.calls.size, "selecting must not run a check")
    }

    // --- the button flow -----------------------------------------------------------------------

    @Test
    fun `the check goes Checking then Done`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        checker.gate = gate
        checker.result = PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = true)

        val job = launch(Dispatchers.Unconfined) { vm.checkConnectionAndPermissions("prod-eu") }

        assertEquals(PermissionCheckStatus.Checking, checks()["prod-eu"], "while the broker is being asked")
        assertNull(checks()["dev"], "other profiles are not touched")
        gate.complete(Unit)
        withTimeout(10_000) { job.join() }

        assertEquals(PermissionCheckStatus.Done(checker.result), checks()["prod-eu"])
    }

    @Test
    fun `the button runs reachability and the permission check and sets both results`() = runBlocking {
        reach.result = ConnectionCheckResult.BothReachable
        checker.result = PermissionCheckResult.Checked(true, false)

        vm.checkConnectionAndPermissions("prod-eu")

        assertEquals(ConnectionCheckResult.BothReachable, vm.state.value.lastCheckResult)
        assertEquals(PermissionCheckStatus.Done(PermissionCheckResult.Checked(true, false)), checks()["prod-eu"])
        assertEquals(1, reach.calls)
        assertEquals(1, checker.calls.size)
    }

    @Test
    fun `an unreachable broker still runs the permission check and shows Unreadable UNREACHABLE`() = runBlocking {
        reach.result = ConnectionCheckResult.EndpointUnreachable(Endpoint.MANAGEMENT, "connection refused")
        checker.result = PermissionCheckResult.Unreadable(UnreadableCause.UNREACHABLE)

        vm.checkConnectionAndPermissions("prod-eu")

        assertIs<ConnectionCheckResult.EndpointUnreachable>(vm.state.value.lastCheckResult)
        assertEquals(1, checker.calls.size, "the permission check must not wait for a reachability success")
        val status = assertIs<PermissionCheckStatus.Done>(checks()["prod-eu"])
        assertEquals(PermissionCheckResult.Unreadable(UnreadableCause.UNREACHABLE), status.result)
    }

    @Test
    fun `a missing password shows Unreadable NO_PASSWORD`() = runBlocking {
        secrets.delete("prod-eu")

        vm.checkConnectionAndPermissions("prod-eu")

        val status = assertIs<PermissionCheckStatus.Done>(checks()["prod-eu"])
        assertEquals(UnreadableCause.NO_PASSWORD, assertIs<PermissionCheckResult.Unreadable>(status.result).cause)
    }

    @Test
    fun `a second check replaces the first result`() = runBlocking {
        vm.checkConnectionAndPermissions("prod-eu")
        checker.result = PermissionCheckResult.Checked(false, false)

        vm.checkConnectionAndPermissions("prod-eu")

        assertEquals(PermissionCheckStatus.Done(PermissionCheckResult.Checked(false, false)), checks()["prod-eu"])
    }

    @Test
    fun `without a session the button still checks reachability and records no permission status`() = runBlocking {
        val plain = ConnectionsViewModel(repo, secrets, reach, Ca())

        plain.checkConnectionAndPermissions("prod-eu")

        assertEquals(ConnectionCheckResult.BothReachable, plain.state.value.lastCheckResult)
        assertEquals(emptyMap(), checks(plain))
        assertTrue(checker.calls.isEmpty())
    }

    // --- save ----------------------------------------------------------------------------------

    @Test
    fun `a successful save goes Checking then Done`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        checker.gate = gate

        val job = launch(Dispatchers.Unconfined) { vm.saveProfileChanges("prod-eu", input(host = "new-host")) }

        assertEquals(PermissionCheckStatus.Checking, checks()["prod-eu"])
        gate.complete(Unit)
        withTimeout(10_000) { job.join() }
        assertEquals(PermissionCheckStatus.Done(checker.result), checks()["prod-eu"])
        assertEquals("new-host", checker.calls.single().host)
    }

    @Test
    fun `a rename saves under the new key and drops the old one`() = runBlocking {
        vm.checkConnectionAndPermissions("prod-eu")
        assertEquals(setOf("prod-eu"), checks().keys, "precondition")

        val result = vm.saveProfileChanges("prod-eu", input(name = "prod-eu-2"))

        assertIs<SaveConnectionResult.Saved>(result)
        assertEquals(setOf("prod-eu-2"), checks().keys, "the old name must not linger")
        assertIs<PermissionCheckStatus.Done>(checks()["prod-eu-2"])
        assertFalse(session.isUsable("prod-eu"))
    }

    @Test
    fun `a rename shows Checking under the new name while the check is open`() = runBlocking {
        vm.checkConnectionAndPermissions("prod-eu")
        val gate = CompletableDeferred<Unit>()
        checker.gate = gate

        val job = launch(Dispatchers.Unconfined) { vm.saveProfileChanges("prod-eu", input(name = "prod-eu-2")) }

        assertEquals(PermissionCheckStatus.Checking, checks()["prod-eu-2"])
        assertNull(checks()["prod-eu"])
        gate.complete(Unit)
        withTimeout(10_000) { job.join() }
    }

    @Test
    fun `a failed save changes nothing`() = runBlocking {
        vm.checkConnectionAndPermissions("prod-eu")
        val before = checks()
        checker.calls.clear()

        val result = vm.saveProfileChanges("prod-eu", input(name = "dev"))

        assertIs<SaveConnectionResult.NameAlreadyExists>(result)
        assertEquals(before, checks(), "no Checking, no new Done, no removed key")
        assertTrue(checker.calls.isEmpty())
    }

    @Test
    fun `saving does not touch the status of other profiles`() = runBlocking {
        vm.checkConnectionAndPermissions("dev")
        val devBefore = checks()["dev"]

        vm.saveProfileChanges("prod-eu", input(host = "x"))

        assertEquals(devBefore, checks()["dev"])
    }

    // --- delete --------------------------------------------------------------------------------

    @Test
    fun `confirming a delete drops the entry and forgets the session result`() = runBlocking {
        vm.checkConnectionAndPermissions("prod-eu")
        vm.checkConnectionAndPermissions("dev")
        assertTrue(session.isUsable("prod-eu"), "precondition")

        vm.requestDelete("prod-eu")
        assertEquals(setOf("prod-eu", "dev"), checks().keys, "requesting is not deleting")
        vm.confirmDelete()

        assertEquals(setOf("dev"), checks().keys)
        assertFalse(session.isUsable("prod-eu"), "session.forget must be called")
        assertNull(session.permissions("prod-eu"))
        assertTrue(session.isUsable("dev"))
    }

    @Test
    fun `cancelling a delete keeps the entry and the session result`() = runBlocking {
        vm.checkConnectionAndPermissions("prod-eu")

        vm.requestDelete("prod-eu")
        vm.cancelDelete()

        assertEquals(setOf("prod-eu"), checks().keys)
        assertTrue(session.isUsable("prod-eu"))
    }

    // --- stale writes: a late result must not resurrect a deleted or renamed profile (round 2) ------

    @Test
    fun `a profile deleted while its check is in flight leaves no entry when the check completes`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        checker.firstCallGate = gate
        val job = launch(Dispatchers.Unconfined) { vm.checkConnectionAndPermissions("prod-eu") }
        assertEquals(PermissionCheckStatus.Checking, checks()["prod-eu"], "precondition: check in flight")

        vm.requestDelete("prod-eu")
        vm.confirmDelete()
        gate.complete(Unit)
        withTimeout(10_000) { job.join() }

        assertEquals(emptyMap(), checks(), "the late result must not come back for a deleted profile")
    }

    @Test
    fun `a profile renamed while its check is in flight leaves no entry under the old name`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        checker.firstCallGate = gate
        val job = launch(Dispatchers.Unconfined) { vm.checkConnectionAndPermissions("prod-eu") }
        assertEquals(PermissionCheckStatus.Checking, checks()["prod-eu"], "precondition: check in flight")

        val renamed = vm.saveProfileChanges("prod-eu", input(name = "prod-eu-2"))
        assertIs<SaveConnectionResult.Saved>(renamed)
        gate.complete(Unit)
        withTimeout(10_000) { job.join() }

        assertEquals(setOf("prod-eu-2"), checks().keys, "the old name must stay gone after the late result")
        assertNull(checks()["prod-eu"])
    }

    @Test
    fun `a new profile that reuses the old name reads Not checked after the late result`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        checker.firstCallGate = gate
        val job = launch(Dispatchers.Unconfined) { vm.checkConnectionAndPermissions("prod-eu") }
        vm.saveProfileChanges("prod-eu", input(name = "prod-eu-2"))
        gate.complete(Unit)
        withTimeout(10_000) { job.join() }

        assertIs<SaveConnectionResult.Saved>(vm.addConnection(input(name = "prod-eu")))

        assertNull(checks()["prod-eu"], "no entry means 'Not checked' for the new profile")
    }

    // Strictest case: the name is reused while the old check is still in flight. A guard that only asks "does
    // a profile with this name exist" would let the old result through; this test asks for more (see report).
    @Test
    fun `the late result of the old profile does not appear on a profile that reused its name`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        checker.firstCallGate = gate
        checker.result = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
        val job = launch(Dispatchers.Unconfined) { vm.checkConnectionAndPermissions("prod-eu") }
        vm.saveProfileChanges("prod-eu", input(name = "prod-eu-2"))
        assertIs<SaveConnectionResult.Saved>(vm.addConnection(input(name = "prod-eu")))
        gate.complete(Unit)
        withTimeout(10_000) { job.join() }

        assertNull(checks()["prod-eu"], "the new profile was never checked")
    }
}
