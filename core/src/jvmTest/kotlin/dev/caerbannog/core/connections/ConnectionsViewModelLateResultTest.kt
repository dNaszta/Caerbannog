package dev.caerbannog.core.connections

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1-12a: a late `checkConnection` result belongs to one profile (the banner carries no profile name), so it
 * must not be written when that profile was deleted, or the selection moved on, while the check was running.
 * No sleeps: a gated reachability checker (CompletableDeferred) holds the check open; the coroutine starts
 * Unconfined so it runs up to the gate synchronously.
 */
class ConnectionsViewModelLateResultTest {

    private class Repo : ConnectionProfileRepository {
        val profiles = linkedMapOf<String, ConnectionProfile>()
        override fun list() = profiles.values.toList()
        override fun add(profile: ConnectionProfile): SaveConnectionResult {
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

    private class Ca : CaCertificateValidator {
        override fun validate(filePath: String) = CaCertificateValidationResult.Valid
    }

    /** Every check waits for [gate]; [started] completes when a check has reached the gate. */
    private class GatedReach : BrokerReachabilityChecker {
        var gate = CompletableDeferred<Unit>()
        var started = CompletableDeferred<Unit>()
        var result: ConnectionCheckResult = ConnectionCheckResult.BothReachable
        override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult {
            started.complete(Unit)
            gate.await()
            return result
        }
    }

    private val repo = Repo()
    private val reach = GatedReach()
    private val vm = ConnectionsViewModel(repo, Secrets(), reach, Ca())
    private val a = ConnectionProfile(name = "profile-a", host = "a", virtualHost = "/", username = "u")
    private val b = ConnectionProfile(name = "profile-b", host = "b", virtualHost = "/", username = "u")

    init {
        repo.add(a)
        repo.add(b)
        vm.selectConnection("profile-a")
    }

    private fun runLate(duringCheck: () -> Unit): ConnectionCheckResult = runBlocking {
        val result = CompletableDeferred<ConnectionCheckResult>()
        launch(Dispatchers.Unconfined, start = CoroutineStart.DEFAULT) { result.complete(vm.checkConnection("profile-a")) }
        withTimeout(5_000) { reach.started.await() }
        assertNull(vm.state.value.lastCheckResult, "precondition: nothing is written while the check is gated")
        duringCheck()
        reach.gate.complete(Unit)
        withTimeout(5_000) { result.await() }
    }

    @Test
    fun `control - a check that nothing disturbs sets lastCheckResult`() {
        val returned = runLate { }

        assertEquals(ConnectionCheckResult.BothReachable, returned)
        assertEquals(ConnectionCheckResult.BothReachable, vm.state.value.lastCheckResult)
    }

    @Test
    fun `a late result after the profile was deleted leaves lastCheckResult null`() {
        runLate {
            vm.requestDelete("profile-a")
            vm.confirmDelete()
        }

        assertTrue(vm.state.value.profiles.none { it.name == "profile-a" }, "precondition: profile-a is deleted")
        assertNull(vm.state.value.lastCheckResult, "a late result for a deleted profile must not show as a banner")
    }

    @Test
    fun `a late result after the selection moved to another profile leaves lastCheckResult null`() {
        runLate { vm.selectConnection("profile-b") }

        assertEquals("profile-b", vm.state.value.selectedProfileName, "precondition")
        assertNull(vm.state.value.lastCheckResult, "profile-a's result must not show as profile-b's banner")
    }

    @Test
    fun `a late result after New connection was started leaves lastCheckResult null`() {
        runLate { vm.startNewConnection() }

        assertNull(vm.state.value.selectedProfileName, "precondition")
        assertNull(vm.state.value.lastCheckResult, "the New connection panel must not show a stale banner")
    }

    @Test
    fun `a late result after the profile was deleted and another one selected leaves lastCheckResult null`() {
        runLate {
            vm.requestDelete("profile-a")
            vm.confirmDelete()
            vm.selectConnection("profile-b")
        }

        assertNull(vm.state.value.lastCheckResult)
    }
}
