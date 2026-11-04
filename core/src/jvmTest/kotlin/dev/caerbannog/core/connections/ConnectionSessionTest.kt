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
 * Final contract and rules: docs/tasks/M1-03-permission-check.md. Red state: types do not exist yet.
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
        assertEquals(PermissionCheckResult.Unreadable(UnreadableCause.NO_PASSWORD), result)
        assertFalse(result.topologyUnlocked)
        assertTrue(checker.calls.isEmpty(), "must not call the broker without a password")
    }

    @Test
    fun `an unreadable check still leaves the connection usable but in safest mode`() = runBlocking {
        val session = ConnectionSession(
            RecordingChecker(PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED)),
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

    // --- declared permissions override (decision D) ---

    private val refused = PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED)
    private val declaring = profile.copy(declaredPermissions = DeclaredPermissions(configure = true, write = false))
    private val secrets = MapSecretStore(mapOf("prod-eu" to "pw"))

    @Test
    fun `a declaration is applied when the check is unreadable`() = runBlocking {
        val session = ConnectionSession(RecordingChecker(refused), secrets)
        assertEquals(PermissionCheckResult.DeclaredByUser(true, false, refused), session.open(declaring))
        assertEquals(PermissionCheckResult.DeclaredByUser(true, false, refused), session.permissions("prod-eu"))
    }

    @Test
    fun `a declaration never replaces what the broker said`() = runBlocking {
        val broker = PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = false)
        val session = ConnectionSession(RecordingChecker(broker), secrets)
        assertEquals(broker, session.open(declaring.copy(declaredPermissions = DeclaredPermissions(true, true))))
    }

    @Test
    fun `a declaration is not applied when no password is stored`() = runBlocking {
        val checker = RecordingChecker(unlocked)
        val result = ConnectionSession(checker, MapSecretStore(emptyMap())).open(declaring)
        assertEquals(PermissionCheckResult.Unreadable(UnreadableCause.NO_PASSWORD), result)
        assertTrue(checker.calls.isEmpty())
    }

    @Test
    fun `without a declaration an unreadable result stays unreadable`() = runBlocking {
        assertEquals(refused, ConnectionSession(RecordingChecker(refused), secrets).open(profile))
    }

    @Test
    fun `the declaration of one profile does not leak to another`() = runBlocking {
        val other = profile.copy(name = "dev")
        val session = ConnectionSession(RecordingChecker(refused), MapSecretStore(mapOf("prod-eu" to "a", "dev" to "b")))
        session.open(declaring)
        assertEquals(refused, session.open(other))
        assertTrue(session.permissions("prod-eu") is PermissionCheckResult.DeclaredByUser)
    }

    @Test
    fun `the connection is not usable while the check runs`() = runBlocking {
        lateinit var session: ConnectionSession
        var usableDuring: Boolean? = null
        var permissionsDuring: PermissionCheckResult? = null
        val checker = object : PermissionChecker {
            override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
                usableDuring = session.isUsable("prod-eu")
                permissionsDuring = session.permissions("prod-eu")
                return unlocked
            }
        }
        session = ConnectionSession(checker, secrets)
        session.open(profile)
        assertEquals(false, usableDuring)
        assertNull(permissionsDuring)
        // and again on a re-check: the earlier result is cleared first
        session.reopen(profile)
        assertEquals(false, usableDuring, "reopen must clear the stored result before asking the broker")
        assertNull(permissionsDuring)
    }
}
