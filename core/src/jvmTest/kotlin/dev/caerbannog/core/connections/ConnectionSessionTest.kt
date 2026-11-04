package dev.caerbannog.core.connections

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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

    // --- M1-09: no exception leaves open, except CancellationException ---

    private class ThrowingChecker(private val failure: Throwable) : PermissionChecker {
        override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult = throw failure
    }

    private val unexpected = PermissionCheckResult.Unreadable(UnreadableCause.UNEXPECTED, "IllegalStateException")

    @Test
    fun `a throwing checker leaves the connection usable and Unreadable UNEXPECTED`() = runBlocking {
        val session = ConnectionSession(ThrowingChecker(IllegalStateException("boom")), secrets)

        assertEquals(unexpected, session.open(profile))

        assertTrue(session.isUsable("prod-eu"))
        assertEquals(unexpected, session.permissions("prod-eu"))
        assertFalse(session.permissions("prod-eu")!!.topologyUnlocked)
        assertFalse(session.permissions("prod-eu")!!.publishingEnabled)
    }

    @Test
    fun `a throwing checker with a declaration gives DeclaredByUser carrying the UNEXPECTED reason`() = runBlocking {
        val session = ConnectionSession(ThrowingChecker(IllegalStateException("boom")), secrets)

        val expected = PermissionCheckResult.DeclaredByUser(true, false, unexpected)
        assertEquals(expected, session.open(declaring))
        assertEquals(expected, session.permissions("prod-eu"))
        assertTrue(session.isUsable("prod-eu"))
    }

    @Test
    fun `the exception message is not kept in the result`() = runBlocking {
        val password = "s3cr3t-pw-Zq9"
        val store = MapSecretStore(mapOf("prod-eu" to password))
        val session = ConnectionSession(ThrowingChecker(IllegalStateException("auth failed for svc:$password")), store)

        val result = session.open(profile) as PermissionCheckResult.Unreadable

        assertEquals("IllegalStateException", result.detail, "detail is the class simple name only")
        assertFalse(result.detail!!.contains(password), "password in detail: ${result.detail}")
        assertFalse(result.userMessage.contains(password), "password in userMessage: ${result.userMessage}")
        assertFalse(result.userMessage.contains("auth failed"), "exception text in userMessage: ${result.userMessage}")
    }

    @Test
    fun `a cancelled check propagates the cancellation and leaves the connection not usable`() = runBlocking {
        val session = ConnectionSession(ThrowingChecker(CancellationException("cancelled")), secrets)

        assertFailsWith<CancellationException> { session.open(profile) }

        assertFalse(session.isUsable("prod-eu"))
        assertNull(session.permissions("prod-eu"))
    }

    // Pending senior developer confirmation (brief Open points): if rejected, delete this test and say so in the Status.
    @Test
    fun `a failing secret store is Unreadable UNEXPECTED even with a declaration`() = runBlocking {
        val failingStore = object : SecretStore {
            override fun store(profileName: String, password: String) = error("not used")
            override fun retrieve(profileName: String): String? = throw IllegalStateException("keychain locked")
            override fun delete(profileName: String) = error("not used")
        }
        val checker = RecordingChecker(unlocked)
        val session = ConnectionSession(checker, failingStore)

        val result = session.open(declaring)

        assertEquals(unexpected, result, "the declaration must not be applied: without a password nothing can connect")
        assertTrue(session.isUsable("prod-eu"))
        assertEquals(unexpected, session.permissions("prod-eu"))
        assertTrue(checker.calls.isEmpty(), "must not call the broker without a password")
    }

    @Test
    fun `overlapping opens of different profiles all end usable with their own result`() = runBlocking {
        val names = (1..64).map { "profile-$it" }
        val perName = names.associateWith { name ->
            PermissionCheckResult.Checked(topologyUnlocked = name.hashCode() % 2 == 0, publishingEnabled = name.hashCode() % 3 == 0)
        }
        val checker = object : PermissionChecker {
            override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
                kotlinx.coroutines.yield()
                return perName.getValue(profile.name)
            }
        }
        val session = ConnectionSession(checker, MapSecretStore(names.associateWith { "pw" }))

        val returned = names.map { name ->
            async(Dispatchers.Default) { session.open(profile.copy(name = name)) }
        }.awaitAll()

        names.forEachIndexed { i, name ->
            assertTrue(session.isUsable(name), "$name not usable")
            assertEquals(perName.getValue(name), session.permissions(name), "$name has the wrong result")
            assertEquals(perName.getValue(name), returned[i])
        }
    }

    @Test
    fun `for the same profile the check that finishes last wins`() = runBlocking {
        val first = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
        val second = PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = false)
        val started = listOf(CompletableDeferred<Unit>(), CompletableDeferred())
        val gates = listOf(CompletableDeferred<Unit>(), CompletableDeferred())
        val results = listOf(first, second)
        val calls = AtomicInteger()
        val checker = object : PermissionChecker {
            override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
                val n = calls.getAndIncrement()
                started[n].complete(Unit)
                gates[n].await()
                return results[n]
            }
        }
        val session = ConnectionSession(checker, secrets)

        withTimeout(10_000) {
            val a = async(Dispatchers.Default) { session.open(profile) }
            started[0].await()
            val b = async(Dispatchers.Default) { session.open(profile) }
            started[1].await()

            gates[1].complete(Unit) // the later start finishes first
            assertEquals(second, b.await())
            gates[0].complete(Unit) // the earlier start finishes last: it wins
            assertEquals(first, a.await())
        }

        assertEquals(first, session.permissions("prod-eu"), "the check that finished last must win")
        assertTrue(session.isUsable("prod-eu"))
    }
}
