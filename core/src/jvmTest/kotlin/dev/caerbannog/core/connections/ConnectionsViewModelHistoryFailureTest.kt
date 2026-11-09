package dev.caerbannog.core.connections

import dev.caerbannog.core.migrations.HistoryKeyResult
import dev.caerbannog.core.migrations.ProfileHistoryKeys
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M5-08b round 1 (F1): the history operation comes FIRST and must succeed, or the profile is not changed. A failed history delete or rename
 * must never leave entries that a later profile with the same name would inherit as "applied". An unreadable history file counts as done.
 */
class ConnectionsViewModelHistoryFailureTest {

    private val log = mutableListOf<String>()

    private inner class Repo : ConnectionProfileRepository {
        val profiles = linkedMapOf<String, ConnectionProfile>()
        override fun list() = profiles.values.toList()
        override fun add(profile: ConnectionProfile): SaveConnectionResult {
            log += "repo.add ${profile.name}"
            if (profiles.containsKey(profile.name)) return SaveConnectionResult.NameAlreadyExists(profile.name)
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }
        override fun update(profile: ConnectionProfile): SaveConnectionResult {
            log += "repo.update ${profile.name}"
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }
        override fun delete(name: String) { log += "repo.delete $name"; profiles.remove(name) }
    }

    private inner class Secrets : SecretStore {
        val map = mutableMapOf<String, String>()
        override fun store(profileName: String, password: String) { log += "secret.store $profileName"; map[profileName] = password }
        override fun retrieve(profileName: String) = map[profileName]
        override fun delete(profileName: String) { log += "secret.delete $profileName"; map.remove(profileName) }
    }

    private class Reach : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
    }

    private class Ca : CaCertificateValidator {
        override fun validate(filePath: String) = CaCertificateValidationResult.Valid
    }

    /** A history with real semantics (replace on rename, remove on delete), a switch to fail, and one that is "unreadable" (always Done, nothing changes). */
    private inner class History : ProfileHistoryKeys {
        val applied = mutableMapOf(
            "uat-eu" to mutableMapOf("0001" to "a"),
            "prod-eu" to mutableMapOf("0001" to "p", "0002" to "q"),
        )
        var failing = false
        var unreadable = false
        override fun rename(oldName: String, newName: String): HistoryKeyResult {
            log += "history.rename $oldName $newName"
            if (failing) return HistoryKeyResult.Failed("The migration history could not be written")
            if (unreadable) return HistoryKeyResult.Done
            applied.remove(newName); applied.remove(oldName)?.let { applied[newName] = it }
            return HistoryKeyResult.Done
        }
        override fun delete(profileName: String): HistoryKeyResult {
            log += "history.delete $profileName"
            if (failing) return HistoryKeyResult.Failed("The migration history could not be written")
            if (!unreadable) applied.remove(profileName)
            return HistoryKeyResult.Done
        }
    }

    private fun appliedOf(name: String): Map<String, String>? = history.applied[name]

    private val repo = Repo()
    private val secrets = Secrets()
    private val history = History()
    private val vm = ConnectionsViewModel(repo, secrets, Reach(), Ca(), historyKeys = history)

    private val message = "The migration history of this connection could not be updated, so the connection was left as it is"

    private fun input(name: String) = ConnectionProfileInput(name = name, host = "h", virtualHost = "/", username = "svc", password = null)

    init {
        repo.add(ConnectionProfile(name = "uat-eu", host = "h", virtualHost = "/", username = "svc"))
        repo.add(ConnectionProfile(name = "prod-eu", host = "h", virtualHost = "/", username = "svc"))
        secrets.store("uat-eu", "pw")
        log.clear()
    }

    private fun delete(name: String) { vm.requestDelete(name); vm.confirmDelete() }

    @Test
    fun `a failing history delete leaves the profile, its password and the history, and shows the fixed error`() {
        history.failing = true

        delete("uat-eu")

        assertTrue("uat-eu" in repo.profiles, "the profile was deleted although its history could not be removed")
        assertTrue(vm.state.value.profiles.any { it.name == "uat-eu" })
        assertEquals("pw", secrets.map["uat-eu"], "the password must stay with a connection that was left as it is")
        assertEquals(mapOf("0001" to "a"), appliedOf("uat-eu"))
        assertEquals(message, vm.state.value.errorMessage)
    }

    @Test
    fun `a failing history rename leaves the profile under its old name with the same error`() {
        history.failing = true

        val result = vm.updateConnection("uat-eu", input("uat-eu-2"))

        assertEquals(SaveConnectionResult.HistoryNotUpdated(message), result)
        assertEquals(listOf("uat-eu", "prod-eu"), repo.profiles.keys.toList())
        assertEquals("pw", secrets.map["uat-eu"])
        assertFalse("uat-eu-2" in secrets.map)
        assertEquals(mapOf("0001" to "a"), appliedOf("uat-eu"))
        assertEquals(message, vm.state.value.errorMessage)
    }

    @Test
    fun `an unreadable history lets delete and rename proceed`() {
        history.unreadable = true

        val renamed = vm.updateConnection("uat-eu", input("uat-eu-2"))
        delete("prod-eu")

        assertTrue(renamed is SaveConnectionResult.Saved)
        assertEquals(listOf("uat-eu-2"), repo.profiles.keys.toList())
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun `the history call happens before the profile is changed, for rename and for delete`() {
        vm.updateConnection("uat-eu", input("uat-eu-2"))
        val renameLog = log.toList(); log.clear()
        assertTrue(renameLog.first() == "history.rename uat-eu uat-eu-2", "history must be the first call, was: $renameLog")

        delete("uat-eu-2")
        assertTrue(log.first() == "history.delete uat-eu-2", "history must be the first call, was: $log")
        assertTrue(log.indexOf("history.delete uat-eu-2") < log.indexOf("repo.delete uat-eu-2"))
        assertTrue(log.indexOf("history.delete uat-eu-2") < log.indexOf("secret.delete uat-eu-2"))
    }

    @Test
    fun `a rename onto an existing profile name never touches any history`() {
        val result = vm.updateConnection("uat-eu", input("prod-eu"))

        assertTrue(result is SaveConnectionResult.NameAlreadyExists)
        assertTrue(log.none { it.startsWith("history.") }, "a refused rename must not replace prod-eu's history: $log")
        assertEquals(mapOf("0001" to "p", "0002" to "q"), appliedOf("prod-eu"))
    }

    @Test
    fun `success paths still change the profile and move or remove only that history`() {
        val renamed = vm.updateConnection("uat-eu", input("uat-eu-2"))
        assertTrue(renamed is SaveConnectionResult.Saved)
        assertEquals(mapOf("0001" to "a"), appliedOf("uat-eu-2"))
        assertNull(appliedOf("uat-eu"))
        assertEquals("pw", secrets.map["uat-eu-2"])
        assertNull(vm.state.value.errorMessage)

        delete("uat-eu-2")
        assertEquals(listOf("prod-eu"), repo.profiles.keys.toList())
        assertNull(appliedOf("uat-eu-2"))
        assertEquals(mapOf("0001" to "p", "0002" to "q"), appliedOf("prod-eu"), "the other profile's history is untouched")
    }

    @Test
    fun `a later successful action clears the error`() {
        history.failing = true
        delete("uat-eu")
        assertEquals(message, vm.state.value.errorMessage)

        history.failing = false
        delete("uat-eu")

        assertNull(vm.state.value.errorMessage)
        assertFalse("uat-eu" in repo.profiles)
    }
}
