package dev.caerbannog.core.connections

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1-03: `ConnectionSession` is the gate that runs the permission check before a connection is usable.
 * Proposed contract (test developer, to be confirmed with the senior developer):
 *   ConnectionSession(checker: PermissionChecker, secretStore: SecretStore)
 *   suspend fun open(profile): PermissionCheckResult   // check first, usable only afterwards
 *   suspend fun reopen(profile): PermissionCheckResult // drops the stored result, checks again
 *   fun isUsable(profileName): Boolean
 *   fun permissions(profileName): PermissionCheckResult?
 * Results are kept per profile name; nothing global. Red state: types do not exist yet.
 */
class ConnectionSessionTest {

    private val profile = ConnectionProfile(name = "prod-eu", host = "h", virtualHost = "/", username = "svc")

    private class RecordingChecker(var result: PermissionCheckResult) : PermissionChecker {
        val calls = mutableListOf<Pair<ConnectionProfile, String>>()
        override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
            calls += profile to password
            return result
        }
    }

    private class MapSecretStore(private val passwords: Map<String, String>) : SecretStore {
        override fun store(profileName: String, password: String) = error("not used")
        override fun retrieve(profileName: String): String? = passwords[profileName]
        override fun delete(profileName: String) = error("not used")
    }

    private val unlocked = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)

    @Test
    fun `a connection is not usable before open and is usable after a check`() = runBlocking {
        val session = ConnectionSession(RecordingChecker(unlocked), MapSecretStore(mapOf("prod-eu" to "pw")))
        assertFalse(session.isUsable("prod-eu"))
        assertNull(session.permissions("prod-eu"))

        assertEquals(unlocked, session.open(profile))

        assertTrue(session.isUsable("prod-eu"))
        assertEquals(unlocked, session.permissions("prod-eu"))
    }

    @Test
    fun `the stored password is handed to the checker`() = runBlocking {
        val checker = RecordingChecker(unlocked)
        ConnectionSession(checker, MapSecretStore(mapOf("prod-eu" to "pw"))).open(profile)
        assertEquals(listOf(profile to "pw"), checker.calls)
    }

    @Test
    fun `a missing stored password is Unreadable, not an exception and not unlocked`() = runBlocking {
        val checker = RecordingChecker(unlocked)
        val result = ConnectionSession(checker, MapSecretStore(emptyMap())).open(profile)
        assertTrue(result is PermissionCheckResult.Unreadable, "got $result")
        assertFalse(result.topologyUnlocked)
        assertTrue(checker.calls.isEmpty(), "must not call the broker without a password")
    }

    @Test
    fun `an unreadable check still leaves the connection usable but in safest mode`() = runBlocking {
        val session = ConnectionSession(
            RecordingChecker(PermissionCheckResult.Unreadable("permissions could not be read")),
            MapSecretStore(mapOf("prod-eu" to "pw")),
        )
        session.open(profile)
        assertTrue(session.isUsable("prod-eu"))
        assertFalse(session.permissions("prod-eu")!!.topologyUnlocked)
        assertFalse(session.permissions("prod-eu")!!.publishingEnabled)
    }

    @Test
    fun `reopen checks again and replaces the previous result`() = runBlocking {
        val checker = RecordingChecker(unlocked)
        val session = ConnectionSession(checker, MapSecretStore(mapOf("prod-eu" to "pw")))
        session.open(profile)
        checker.result = PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = true)

        session.reopen(profile)

        assertEquals(2, checker.calls.size)
        assertFalse(session.permissions("prod-eu")!!.topologyUnlocked)
    }

    @Test
    fun `results are kept per profile`() = runBlocking {
        val checker = RecordingChecker(unlocked)
        val session = ConnectionSession(checker, MapSecretStore(mapOf("prod-eu" to "pw", "dev" to "pw2")))
        session.open(profile)
        assertFalse(session.isUsable("dev"))
        assertNull(session.permissions("dev"))
    }
}
