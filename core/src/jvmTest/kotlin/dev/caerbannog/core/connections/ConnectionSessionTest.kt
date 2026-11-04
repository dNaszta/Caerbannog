package dev.caerbannog.core.connections

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    // --- M2-01: the latest STARTED check per profile name wins ---------------------------------------
    // Replaces the M1-09 test "for the same profile the check that finishes last wins", which pinned the
    // behaviour the spec Risks entry calls a bug. Gated checker: no sleeps. What `open` returns for a
    // superseded check is not asserted (brief leaves it open); the session state is.

    private val allowed = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
    private val locked = PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = false)
    private val twoNames = MapSecretStore(mapOf("prod-eu" to "pw", "dev" to "pw2"))

    @Test
    fun `an older check finishing last does not overwrite the newer result`() = runBlocking {
        val checker = GatedPermissionChecker()
        val session = ConnectionSession(checker, secrets)
        withTimeout(10_000) {
            val a = async(Dispatchers.Default) { session.open(profile) }
            val first = checker.nextStarted()
            val b = async(Dispatchers.Default) { session.open(profile) }
            val second = checker.nextStarted()

            second.finish(locked)
            b.await()
            assertEquals(locked, session.permissions("prod-eu"))

            first.finish(allowed) // the older start finishes last: dropped
            a.await()
        }
        assertEquals(locked, session.permissions("prod-eu"), "the older check must not overwrite the newer one")
        assertTrue(session.isUsable("prod-eu"))
    }

    @Test
    fun `an older check finishing first is replaced by the newer result and does not make the connection usable meanwhile`() =
        runBlocking {
            val checker = GatedPermissionChecker()
            val session = ConnectionSession(checker, secrets)
            withTimeout(10_000) {
                val a = async(Dispatchers.Default) { session.open(profile) }
                val first = checker.nextStarted()
                val b = async(Dispatchers.Default) { session.open(profile) }
                val second = checker.nextStarted()

                first.finish(allowed)
                a.await()
                assertFalse(session.isUsable("prod-eu"), "a stale result must not make the connection usable")
                assertNull(session.permissions("prod-eu"), "a stale result must not be shown while the newer check runs")
                assertEquals(PermissionCheckStatus.Checking, session.state.value.statuses["prod-eu"])

                second.finish(locked)
                b.await()
            }
            assertEquals(locked, session.permissions("prod-eu"))
            assertTrue(session.isUsable("prod-eu"))
        }

    @Test
    fun `checks of different profile names are independent`() = runBlocking {
        val checker = GatedPermissionChecker()
        val session = ConnectionSession(checker, twoNames)
        val dev = profile.copy(name = "dev")
        withTimeout(10_000) {
            val a = async(Dispatchers.Default) { session.open(profile) }
            val prodCheck = checker.nextStarted()
            val b = async(Dispatchers.Default) { session.open(dev) }
            val devCheck = checker.nextStarted()

            prodCheck.finish(allowed) // the earlier start finishes first, for another name: both keep their result
            a.await()
            assertEquals(allowed, session.permissions("prod-eu"))
            assertFalse(session.isUsable("dev"))
            devCheck.finish(locked)
            b.await()
        }
        assertEquals(allowed, session.permissions("prod-eu"))
        assertEquals(locked, session.permissions("dev"))
    }

    @Test
    fun `a check that finishes after forget does not make the connection usable`() = runBlocking {
        val checker = GatedPermissionChecker()
        val session = ConnectionSession(checker, secrets)
        withTimeout(10_000) {
            val a = async(Dispatchers.Default) { session.open(profile) }
            val pending = checker.nextStarted()
            session.forget("prod-eu")
            pending.finish(allowed)
            a.await()
        }
        assertFalse(session.isUsable("prod-eu"), "forget during a running check must still win")
        assertNull(session.permissions("prod-eu"))
        assertNull(session.state.value.statuses["prod-eu"])
    }

    @Test
    fun `after forget a new open works and the forgotten check stays dropped`() = runBlocking {
        val checker = GatedPermissionChecker()
        val session = ConnectionSession(checker, secrets)
        withTimeout(10_000) {
            val a = async(Dispatchers.Default) { session.open(profile) }
            val old = checker.nextStarted()
            session.forget("prod-eu")
            val b = async(Dispatchers.Default) { session.open(profile) }
            val fresh = checker.nextStarted()
            old.finish(allowed)
            a.await()
            assertFalse(session.isUsable("prod-eu"))
            fresh.finish(locked)
            b.await()
        }
        assertEquals(locked, session.permissions("prod-eu"))
    }

    @Test
    fun `a rename during a check drops the old name and keeps the new one`() = runBlocking {
        val checker = GatedPermissionChecker()
        val session = ConnectionSession(checker, twoNames)
        val renamed = profile.copy(name = "dev")
        withTimeout(10_000) {
            val a = async(Dispatchers.Default) { session.open(profile) }
            val oldCheck = checker.nextStarted()
            session.forget("prod-eu") // what a rename does to the old name
            val b = async(Dispatchers.Default) { session.open(renamed) }
            val newCheck = checker.nextStarted()
            newCheck.finish(locked)
            b.await()
            oldCheck.finish(allowed)
            a.await()
        }
        assertFalse(session.isUsable("prod-eu"))
        assertEquals(locked, session.permissions("dev"))
    }

    // --- M2-01: the current connection ---------------------------------------------------------------
    // Contract proposed by the test developer (brief leaves names open):
    //   data class CurrentConnection(val name: String, val status: PermissionCheckStatus?) // null = not checked
    //   val state: StateFlow<SessionState>  // M2-01b: SessionState(statuses, current); statuses: Checking / Done per name, no entry = not checked
    //   fun select(name: String)                                      // makes `name` current; no check, no profile lookup

    @Test
    fun `there is no current connection at first`() {
        assertNull(ConnectionSession(RecordingChecker(unlocked), secrets).state.value.current)
    }

    @Test
    fun `select makes a never-checked connection current as not checked`() {
        val session = ConnectionSession(RecordingChecker(unlocked), secrets)
        session.select("prod-eu")
        assertEquals(CurrentConnection("prod-eu", null), session.state.value.current)
        assertFalse(session.isUsable("prod-eu"), "select must not run a check or make anything usable")
    }

    @Test
    fun `select of an already checked connection shows its stored result at once`() = runBlocking {
        val session = ConnectionSession(RecordingChecker(unlocked), twoNames)
        session.open(profile)
        session.select("prod-eu")
        assertEquals(CurrentConnection("prod-eu", PermissionCheckStatus.Done(unlocked)), session.state.value.current)
    }

    @Test
    fun `selecting another connection switches current and keeps both results`() = runBlocking {
        val checker = RecordingChecker(unlocked)
        val session = ConnectionSession(checker, twoNames)
        session.open(profile)
        checker.result = locked
        session.open(profile.copy(name = "dev"))

        session.select("prod-eu")
        assertEquals(PermissionCheckStatus.Done(unlocked), session.state.value.current?.status)
        session.select("dev")
        assertEquals(CurrentConnection("dev", PermissionCheckStatus.Done(locked)), session.state.value.current)
        assertEquals(unlocked, session.permissions("prod-eu"), "the previous connection keeps its own result")
    }

    @Test
    fun `current follows its check from checking to done and to a newer check`() = runBlocking {
        val checker = GatedPermissionChecker()
        val session = ConnectionSession(checker, secrets)
        session.select("prod-eu")
        withTimeout(10_000) {
            val a = async(Dispatchers.Default) { session.open(profile) }
            val first = checker.nextStarted()
            assertEquals(CurrentConnection("prod-eu", PermissionCheckStatus.Checking), session.state.value.current)
            first.finish(allowed)
            a.await()
            assertEquals(CurrentConnection("prod-eu", PermissionCheckStatus.Done(allowed)), session.state.value.current)

            val b = async(Dispatchers.Default) { session.open(profile) }
            val second = checker.nextStarted()
            assertEquals(PermissionCheckStatus.Checking, session.state.value.current?.status, "a re-check clears the old result")
            second.finish(locked)
            b.await()
        }
        assertEquals(PermissionCheckStatus.Done(locked), session.state.value.current?.status)
    }

    @Test
    fun `a check of another connection does not change current`() = runBlocking {
        val session = ConnectionSession(RecordingChecker(unlocked), twoNames)
        session.select("prod-eu")
        session.open(profile.copy(name = "dev"))
        assertEquals(CurrentConnection("prod-eu", null), session.state.value.current)
    }

    @Test
    fun `forget of the current connection clears current, forget of another one does not`() = runBlocking {
        val session = ConnectionSession(RecordingChecker(unlocked), twoNames)
        session.open(profile)
        session.select("prod-eu")

        session.forget("dev")
        assertEquals("prod-eu", session.state.value.current?.name)
        session.forget("prod-eu")
        assertNull(session.state.value.current)
        assertFalse(session.isUsable("prod-eu"))
    }

    @Test
    fun `a stale check finishing after forget of the current connection leaves current empty`() = runBlocking {
        val checker = GatedPermissionChecker()
        val session = ConnectionSession(checker, secrets)
        session.select("prod-eu")
        withTimeout(10_000) {
            val a = async(Dispatchers.Default) { session.open(profile) }
            val pending = checker.nextStarted()
            session.forget("prod-eu")
            pending.finish(allowed)
            a.await()
        }
        assertNull(session.state.value.current)
    }

    @Test
    fun `no state object holds the password`() = runBlocking {
        val session = ConnectionSession(RecordingChecker(unlocked), MapSecretStore(mapOf("prod-eu" to "pw-Zq9-secret")))
        session.open(profile)
        session.select("prod-eu")
        assertFalse(session.state.value.current.toString().contains("pw-Zq9-secret"))
        assertFalse(session.state.value.statuses.toString().contains("pw-Zq9-secret"))
    }
}
