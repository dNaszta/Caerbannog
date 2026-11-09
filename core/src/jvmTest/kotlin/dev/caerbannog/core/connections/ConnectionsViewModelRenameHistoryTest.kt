package dev.caerbannog.core.connections

import dev.caerbannog.core.migrations.ProfileHistoryKeys
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M5-08b scope 6: a renamed profile keeps its applied-migrations history (the history key moves with the name). */
class ConnectionsViewModelRenameHistoryTest {

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

    private val repo = Repo()
    private val renames = mutableListOf<Pair<String, String>>()
    private val deletes = mutableListOf<String>()
    private val keys = object : ProfileHistoryKeys {
        override fun rename(oldName: String, newName: String) { renames += oldName to newName }
        override fun delete(profileName: String) { deletes += profileName }
    }
    private val vm = ConnectionsViewModel(repo, Secrets(), Reach(), Ca(), historyKeys = keys)

    private fun input(name: String, host: String = "h") =
        ConnectionProfileInput(name = name, host = host, virtualHost = "/", username = "svc", password = null)

    init {
        repo.add(ConnectionProfile(name = "uat-eu", host = "h", virtualHost = "/", username = "svc"))
        repo.add(ConnectionProfile(name = "prod-eu", host = "h", virtualHost = "/", username = "svc"))
    }

    @Test
    fun `renaming a profile moves its history key once`() {
        val result = vm.updateConnection("uat-eu", input("uat-eu-2"))

        assertTrue(result is SaveConnectionResult.Saved)
        assertEquals(listOf("uat-eu" to "uat-eu-2"), renames)
    }

    @Test
    fun `a rename that collides with another profile leaves the history alone`() {
        val result = vm.updateConnection("uat-eu", input("prod-eu"))

        assertTrue(result is SaveConnectionResult.NameAlreadyExists)
        assertTrue(renames.isEmpty(), "history moved although the rename failed: $renames")
    }

    @Test
    fun `an edit that keeps the name does not touch the history`() {
        vm.updateConnection("uat-eu", input("uat-eu", host = "other-host"))

        assertTrue(renames.isEmpty(), "history key touched on a plain edit: $renames")
    }

    @Test
    fun `saveProfileChanges with a new name moves the history key as well`() = kotlinx.coroutines.runBlocking {
        vm.saveProfileChanges("uat-eu", input("uat-eu-3"))

        assertEquals(listOf("uat-eu" to "uat-eu-3"), renames)
    }

    @Test
    fun `confirming a delete removes that profile's history entries once and no other profile's`() {
        vm.requestDelete("uat-eu")
        vm.confirmDelete()

        assertEquals(listOf("uat-eu"), deletes)
        assertTrue(renames.isEmpty())
        assertEquals(listOf("prod-eu"), repo.profiles.keys.toList())
    }

    @Test
    fun `a delete that is cancelled or never confirmed leaves the history alone`() {
        vm.requestDelete("uat-eu")
        vm.cancelDelete()
        vm.confirmDelete()

        assertTrue(deletes.isEmpty(), "history removed without a confirmed delete: $deletes")
    }

    @Test
    fun `a rename makes no delete call and a delete makes no rename call`() {
        vm.updateConnection("uat-eu", input("uat-eu-2"))
        assertTrue(deletes.isEmpty(), "a rename must not delete history: $deletes")
    }
}
