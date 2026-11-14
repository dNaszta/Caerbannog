package dev.caerbannog.core.connections

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-01b: the view model state is derived from the single session state without a custom StateFlow:
 * `state.value` stays synchronous (also when the session is changed directly, not through the view model),
 * it can be collected, and one read never shows the current connection and the permission checks out of step.
 * No constructor change is required for these tests (they build the view model exactly as M2-01 does).
 */
class ConnectionsViewModelSingleStateTest {

    private class Repo : ConnectionProfileRepository {
        val profiles = linkedMapOf<String, ConnectionProfile>()
        override fun list() = profiles.values.toList()
        override fun add(profile: ConnectionProfile): SaveConnectionResult {
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }
        override fun update(profile: ConnectionProfile): SaveConnectionResult = add(profile)
        override fun delete(name: String) { profiles.remove(name) }
    }

    private class Secrets : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String? = "pw"
        override fun delete(profileName: String) = Unit
    }

    private class Reach : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult = ConnectionCheckResult.BothReachable
    }

    private class Ca : CaCertificateValidator {
        override fun validate(filePath: String) = CaCertificateValidationResult.Valid
    }

    private class Instant(val result: PermissionCheckResult) : PermissionChecker {
        override suspend fun check(profile: ConnectionProfile, password: String) = result
    }

    private val unlocked = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
    private val profiles = listOf("a", "b", "c").map { ConnectionProfile(name = it, host = "h", virtualHost = "/", username = "svc") }

    private fun newSession() = ConnectionSession(Instant(unlocked), Secrets())
    private fun newVm(session: ConnectionSession?) =
        ConnectionsViewModel(Repo().also { r -> profiles.forEach { r.add(it) } }, Secrets(), Reach(), Ca(), session)

    private fun ConnectionsState.inconsistency(): String? {
        val c = currentConnection ?: return null
        return if (c.status == permissionChecks[c.name]) null else "currentConnection=$c but permissionChecks[${c.name}]=${permissionChecks[c.name]}"
    }

    @Test
    fun `state value follows a direct change of the session synchronously`() {
        val session = newSession()
        val vm = newVm(session)
        session.select("a")
        assertEquals(CurrentConnection("a", null), vm.state.value.currentConnection)
        session.forget("a")
        assertEquals(null, vm.state.value.currentConnection)
    }

    @Test
    fun `state value follows the session's check results synchronously`() = runBlocking {
        val session = newSession()
        val vm = newVm(session)
        session.open(profiles[0])
        assertEquals(PermissionCheckStatus.Done(unlocked), vm.state.value.permissionChecks["a"])
    }

    @Test
    fun `without a session the state is still readable and has no current connection`() {
        val vm = newVm(null)
        assertEquals(null, vm.state.value.currentConnection)
        assertEquals(emptyMap(), vm.state.value.permissionChecks)
    }

    @Test
    fun `the view model state can be collected and sees session changes`() = runBlocking {
        val session = newSession()
        val vm = newVm(session)
        val seen = launch(Dispatchers.Default) {
            withTimeout(TIMEOUT_MS) { vm.state.first { it.currentConnection?.status is PermissionCheckStatus.Done } }
        }
        session.select("b")
        session.open(profiles[1])
        seen.join()
        assertTrue(!seen.isCancelled, "collector never saw the Done state of the current connection")
    }

    @Test
    fun `concurrent readers never see a current connection out of step with the permission checks`() = runBlocking {
        val session = newSession()
        val vm = newVm(session)
        val violations = Collections.synchronizedList(mutableListOf<String>())
        val done = AtomicBoolean(false)
        val readers = List(4) {
            async(Dispatchers.Default) {
                // yield() keeps the loop cooperative: Dispatchers.Default has one thread per core (2 on a CI runner), so
                // readers that never suspend would starve the writers and hang the test until the task timeout.
                while (!done.get()) {
                    vm.state.value.inconsistency()?.let { violations += it }
                    yield()
                }
            }
        }
        val writers = profiles.map { profile ->
            async(Dispatchers.Default) {
                repeat(ROUNDS) {
                    session.select(profile.name)
                    session.open(profile)
                    session.forget(profile.name)
                }
            }
        }
        withTimeout(TIMEOUT_MS) { writers.awaitAll() }
        done.set(true)
        withTimeout(TIMEOUT_MS) { readers.awaitAll() }
        assertTrue(violations.isEmpty(), "inconsistent view model states (${violations.size}): ${violations.take(5)}")
    }

    @Test
    fun `after concurrent session changes the view model state equals the final session state`() = runBlocking {
        val session = newSession()
        val vm = newVm(session)
        repeat(ROUNDS) {
            val writers = profiles.map { profile ->
                async(Dispatchers.Default) {
                    session.select(profile.name)
                    session.open(profile)
                    if (profile.name != "a") session.forget(profile.name)
                }
            }
            withTimeout(TIMEOUT_MS) { writers.awaitAll() }
            val final = session.state.value
            assertEquals(final.statuses, vm.state.value.permissionChecks, "view model lost or kept a stale session change (round $it)")
            assertEquals(final.current, vm.state.value.currentConnection, "view model current differs from the session (round $it)")
            session.forget("a")
        }
    }

    @Test
    fun `after close the view model no longer follows the session`() {
        val session = newSession()
        val vm = newVm(session)
        session.select("a")
        assertEquals("a", vm.state.value.currentConnection?.name)
        vm.close()
        session.select("b")
        assertEquals("a", vm.state.value.currentConnection?.name, "a closed view model must not follow the session")
        vm.close() // closing twice is harmless
    }

    private companion object {
        const val ROUNDS = 2_000
        const val TIMEOUT_MS = 30_000L
    }
}
