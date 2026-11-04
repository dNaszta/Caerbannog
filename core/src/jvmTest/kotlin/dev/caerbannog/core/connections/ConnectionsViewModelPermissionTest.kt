package dev.caerbannog.core.connections

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1-03 final contract: `ConnectionsViewModel.saveProfileChanges(originalName, input)` saves like
 * `updateConnection` and re-runs the permission check through the session only when the save succeeded.
 * Red state: `session` constructor argument, `saveProfileChanges` and the permission types do not exist yet.
 */
class ConnectionsViewModelPermissionTest {

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
        override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
    }

    private class Ca : CaCertificateValidator {
        override fun validate(filePath: String) = CaCertificateValidationResult.Valid
    }

    private class Checker : PermissionChecker {
        var result: PermissionCheckResult = PermissionCheckResult.Checked(true, true)
        val calls = mutableListOf<ConnectionProfile>()
        override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
            calls += profile
            return result
        }
    }

    private val repo = Repo()
    private val secrets = Secrets()
    private val checker = Checker()
    private val session = ConnectionSession(checker, secrets)
    private val vm = ConnectionsViewModel(repo, secrets, Reach(), Ca(), session)
    private val profile = ConnectionProfile(name = "prod-eu", host = "h", virtualHost = "/", username = "svc")

    private fun input(name: String = "prod-eu", host: String = "h") = ConnectionProfileInput(
        name = name, host = host, virtualHost = "/", username = "svc", password = null,
    )

    init {
        repo.add(profile)
        secrets.store("prod-eu", "pw")
        runBlocking { session.open(profile) }
        checker.calls.clear()
    }

    @Test
    fun `a successful save re-runs the check`() = runBlocking {
        checker.result = PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = true)
        val result = vm.saveProfileChanges("prod-eu", input(host = "other"))
        assertTrue(result is SaveConnectionResult.Saved)
        assertEquals(1, checker.calls.size)
        assertEquals("other", checker.calls.single().host, "the check must use the saved profile")
        assertFalse(session.permissions("prod-eu")!!.topologyUnlocked)
    }

    @Test
    fun `a failed save runs no check and keeps the earlier result`() = runBlocking {
        repo.add(profile.copy(name = "dev"))
        val before = session.permissions("prod-eu")
        val result = vm.saveProfileChanges("prod-eu", input(name = "dev"))
        assertTrue(result is SaveConnectionResult.NameAlreadyExists, "got $result")
        assertTrue(checker.calls.isEmpty())
        assertEquals(before, session.permissions("prod-eu"))
    }

    @Test
    fun `a rename checks under the new name and drops the old key`() = runBlocking {
        val result = vm.saveProfileChanges("prod-eu", input(name = "prod-eu-2"))
        assertTrue(result is SaveConnectionResult.Saved, "got $result")
        assertEquals("prod-eu-2", checker.calls.single().name)
        assertNull(session.permissions("prod-eu"))
        assertFalse(session.isUsable("prod-eu"))
    }

    @Test
    fun `a null session means no check and the save still works`() = runBlocking {
        val plain = ConnectionsViewModel(repo, secrets, Reach(), Ca())
        val result = plain.saveProfileChanges("prod-eu", input(host = "x"))
        assertTrue(result is SaveConnectionResult.Saved)
        assertTrue(checker.calls.isEmpty())
    }

    @Test
    fun `a throwing checker does not hide the Saved result`() = runBlocking {
        val throwing = object : PermissionChecker {
            override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult =
                throw IllegalStateException("boom")
        }
        val realSession = ConnectionSession(throwing, secrets)
        val throwingVm = ConnectionsViewModel(repo, secrets, Reach(), Ca(), realSession)

        val result = throwingVm.saveProfileChanges("prod-eu", input(host = "other"))

        assertTrue(result is SaveConnectionResult.Saved, "got $result")
        assertEquals(
            PermissionCheckResult.Unreadable(UnreadableCause.UNEXPECTED, "IllegalStateException"),
            realSession.permissions("prod-eu"),
        )
    }
}
