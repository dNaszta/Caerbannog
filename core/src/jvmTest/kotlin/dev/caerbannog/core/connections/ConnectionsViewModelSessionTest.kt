package dev.caerbannog.core.connections

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-01: the view model reads permission state and the current connection from [ConnectionSession] (one source of
 * truth) and exposes them in [ConnectionsState]. No sleeps: a gated checker (see [GatedPermissionChecker]) holds a
 * check open; coroutines start Unconfined so they run up to the gate synchronously.
 * Contract proposed by the test developer (brief leaves names open):
 *   suspend fun ConnectionsViewModel.connect(name: String)   // known profile: session.select(name) then the check
 *                                                             // (session.open); unknown name: no-op
 *   ConnectionsState.currentConnection: CurrentConnection?   // mirrors session.current
 *   ConnectionsState.permissionChecks                        // mirrors session.statuses (read from the session)
 * The edit-panel selection (selectedProfileName) and the current connection are independent.
 */
class ConnectionsViewModelSessionTest {

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
        override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult = ConnectionCheckResult.BothReachable
    }

    private class Ca : CaCertificateValidator {
        override fun validate(filePath: String) = CaCertificateValidationResult.Valid
    }

    private val repo = Repo()
    private val secrets = Secrets()
    private val checker = GatedPermissionChecker()
    private val session = ConnectionSession(checker, secrets)
    private val vm = ConnectionsViewModel(repo, secrets, Reach(), Ca(), session)
    private val prod = ConnectionProfile(name = "prod-eu", host = "h", virtualHost = "/", username = "svc")
    private val dev = ConnectionProfile(name = "dev", host = "h2", virtualHost = "/", username = "svc")
    private val allowed = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
    private val locked = PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = false)

    init {
        repo.add(prod)
        repo.add(dev)
        secrets.store("prod-eu", "pw")
        secrets.store("dev", "pw")
    }

    private fun current() = vm.state.value.currentConnection
    private fun checks() = vm.state.value.permissionChecks


    private fun input(name: String) = ConnectionProfileInput(
        name = name, host = "h", virtualHost = "/", username = "svc", password = null,
    )

    @Test
    fun `permission state is read from the session, not from a parallel map`() = runBlocking {
        val job = launch(Dispatchers.Unconfined) { session.open(prod) } // the session is used directly, not the view model
        checker.nextStarted().let {
            assertEquals(PermissionCheckStatus.Checking, checks()["prod-eu"])
            it.finish(locked)
        }
        job.join()
        assertEquals(PermissionCheckStatus.Done(locked), checks()["prod-eu"])
    }

    @Test
    fun `nothing is current at construction`() {
        assertNull(current())
    }

    @Test
    fun `connect makes the profile current, checking first and then done`() = runBlocking {
        val job = launch(Dispatchers.Unconfined) { vm.connect("prod-eu") }
        val pending = checker.nextStarted()
        assertEquals(CurrentConnection("prod-eu", PermissionCheckStatus.Checking), current())
        pending.finish(locked)
        job.join()
        assertEquals(CurrentConnection("prod-eu", PermissionCheckStatus.Done(locked)), current())
        assertEquals(PermissionCheckStatus.Done(locked), checks()["prod-eu"])
    }

    @Test
    fun `connect to an unknown name changes nothing and runs no check`() = runBlocking {
        vm.connect("nope")
        assertNull(current())
        assertNull(session.current.value)
        assertEquals(emptyMap(), checks())
        // the checker was never asked: nothing is queued
        assertFalse(session.isUsable("nope"))
    }

    @Test
    fun `switching connection shows the new connection's own result and keeps the old one`() = runBlocking {
        val a = launch(Dispatchers.Unconfined) { vm.connect("prod-eu") }
        checker.nextStarted().finish(allowed)
        a.join()
        val b = launch(Dispatchers.Unconfined) { vm.connect("dev") }
        checker.nextStarted().finish(locked)
        b.join()

        assertEquals(CurrentConnection("dev", PermissionCheckStatus.Done(locked)), current())
        assertEquals(PermissionCheckStatus.Done(allowed), checks()["prod-eu"])
    }

    @Test
    fun `the edit-panel selection and the current connection are independent`() = runBlocking {
        vm.selectConnection("dev")
        assertNull(current(), "selecting in the edit panel must not make a connection current")

        val job = launch(Dispatchers.Unconfined) { vm.connect("prod-eu") }
        checker.nextStarted().finish(allowed)
        job.join()
        assertEquals("dev", vm.state.value.selectedProfileName, "connecting must not move the edit-panel selection")
        assertEquals("prod-eu", current()?.name)

        vm.selectConnection("prod-eu")
        vm.startNewConnection()
        assertEquals("prod-eu", current()?.name, "New connection / selecting does not change the current connection")
    }

    @Test
    fun `deleting the current profile clears current`() = runBlocking {
        val job = launch(Dispatchers.Unconfined) { vm.connect("prod-eu") }
        checker.nextStarted().finish(allowed)
        job.join()

        vm.requestDelete("prod-eu")
        vm.confirmDelete()

        assertNull(current())
        assertNull(session.current.value)
        assertNull(checks()["prod-eu"])
    }

    @Test
    fun `deleting another profile keeps current`() = runBlocking {
        val job = launch(Dispatchers.Unconfined) { vm.connect("prod-eu") }
        checker.nextStarted().finish(allowed)
        job.join()

        vm.requestDelete("dev")
        vm.confirmDelete()

        assertEquals(CurrentConnection("prod-eu", PermissionCheckStatus.Done(allowed)), current())
    }

    @Test
    fun `deleting the profile while its connect check runs leaves no current and no result`() = runBlocking {
        val job = launch(Dispatchers.Unconfined) { vm.connect("prod-eu") }
        val pending = checker.nextStarted()
        vm.requestDelete("prod-eu")
        vm.confirmDelete()
        pending.finish(allowed)
        job.join()

        assertNull(current())
        assertNull(checks()["prod-eu"])
        assertFalse(session.isUsable("prod-eu"))
    }

    // Brief gap: what a rename does to current. Proposed (smallest, follows "forget clears current"): a rename of
    // the current profile clears current; the user connects again. Architect to confirm.
    @Test
    fun `renaming the current profile clears current and checks the new name`() = runBlocking {
        val job = launch(Dispatchers.Unconfined) { vm.connect("prod-eu") }
        checker.nextStarted().finish(allowed)
        job.join()

        val save = launch(Dispatchers.Unconfined) { vm.saveProfileChanges("prod-eu", input("prod-eu-2")) }
        assertNull(current(), "the old name is gone")
        checker.nextStarted().finish(locked)
        save.join()

        assertNull(current())
        assertEquals(PermissionCheckStatus.Done(locked), checks()["prod-eu-2"])
        assertNull(checks()["prod-eu"])
    }

    @Test
    fun `saving the current profile under the same name re-checks it and current follows`() = runBlocking {
        val job = launch(Dispatchers.Unconfined) { vm.connect("prod-eu") }
        checker.nextStarted().finish(allowed)
        job.join()

        val save = launch(Dispatchers.Unconfined) { vm.saveProfileChanges("prod-eu", input("prod-eu")) }
        val pending = checker.nextStarted()
        assertEquals(CurrentConnection("prod-eu", PermissionCheckStatus.Checking), current())
        pending.finish(locked)
        save.join()

        assertEquals(CurrentConnection("prod-eu", PermissionCheckStatus.Done(locked)), current())
    }

    @Test
    fun `two overlapping checks from the screen end with the newer result in state and in current`() = runBlocking {
        val first = launch(Dispatchers.Unconfined) { vm.connect("prod-eu") }
        val older = checker.nextStarted()
        val second = launch(Dispatchers.Unconfined) { vm.checkConnectionAndPermissions("prod-eu") }
        val newer = checker.nextStarted()

        newer.finish(locked)
        second.join()
        older.finish(allowed) // older start finishes last
        first.join()

        assertEquals(PermissionCheckStatus.Done(locked), checks()["prod-eu"])
        assertEquals(CurrentConnection("prod-eu", PermissionCheckStatus.Done(locked)), current())
        assertTrue(session.isUsable("prod-eu"))
    }
}
