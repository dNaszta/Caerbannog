package dev.caerbannog.core.connections

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M2-01b: the session has ONE public state, so a reader never sees `current` and `statuses` out of step.
 * Contract the senior developer must follow (the brief leaves the shape open; test developer's pick):
 *   data class SessionState(
 *       val statuses: Map<String, PermissionCheckStatus> = emptyMap(),
 *       val current: CurrentConnection? = null,
 *   )
 *   val ConnectionSession.state: StateFlow<SessionState>
 *   ConnectionSession.statuses and .current (the two flows) are removed, not kept.
 * Invariant of every observable state: current != null implies current.status == statuses[current.name].
 * No sleeps: writers run on real threads and are joined; collectors are cancelled after the writers finish.
 */
class ConnectionSessionStateTest {

    private val names = listOf("a", "b", "c")
    private val profiles = names.map { ConnectionProfile(name = it, host = "h", virtualHost = "/", username = "svc") }
    private val unlocked = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)

    private class InstantChecker(private val result: PermissionCheckResult) : PermissionChecker {
        override suspend fun check(profile: ConnectionProfile, password: String) = result
    }

    private class Secrets(private val names: List<String>) : SecretStore {
        override fun store(profileName: String, password: String) = error("not used")
        override fun retrieve(profileName: String): String? = if (profileName in names) "pw" else null
        override fun delete(profileName: String) = error("not used")
    }

    private fun session() = ConnectionSession(InstantChecker(unlocked), Secrets(names))

    private fun SessionState.inconsistency(): String? {
        val c = current ?: return null
        return if (c.status == statuses[c.name]) null else "current=$c but statuses[${c.name}]=${statuses[c.name]}"
    }

    @Test
    fun `state starts empty`() {
        assertEquals(SessionState(), session().state.value)
        assertEquals(emptyMap(), session().state.value.statuses)
        assertNull(session().state.value.current)
    }

    @Test
    fun `the public state is a StateFlow of the session snapshot`() {
        val flow: StateFlow<SessionState> = session().state
        assertEquals(SessionState(emptyMap(), null), flow.value)
    }

    @Test
    fun `one read of state shows current and statuses together at every step of a check`() = runBlocking {
        val checker = GatedPermissionChecker()
        val session = ConnectionSession(checker, Secrets(names))
        session.select("a")
        assertEquals(SessionState(emptyMap(), CurrentConnection("a", null)), session.state.value)

        val job = launch(Dispatchers.Unconfined) { session.open(profiles[0]) }
        val pending = checker.nextStarted()
        assertEquals(
            SessionState(mapOf("a" to PermissionCheckStatus.Checking), CurrentConnection("a", PermissionCheckStatus.Checking)),
            session.state.value,
        )
        pending.finish(unlocked)
        job.join()
        val done = PermissionCheckStatus.Done(unlocked)
        assertEquals(SessionState(mapOf("a" to done), CurrentConnection("a", done)), session.state.value)

        session.forget("a")
        assertEquals(SessionState(), session.state.value)
    }

    @Test
    fun `concurrent readers never observe a current connection whose status is not in the statuses`() = runBlocking {
        val session = session()
        val violations = Collections.synchronizedList(mutableListOf<String>())
        val observed = java.util.concurrent.atomic.AtomicLong()

        val writersDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val readers = List(4) {
            async(Dispatchers.Default) {
                // yield() is what keeps this loop cooperative: Dispatchers.Default has one thread per core (2 on a
                // CI runner), so busy readers that never suspend would occupy every thread, the writers would never
                // be scheduled and this test would hang (it ran 15 minutes on CI) instead of failing.
                while (!writersDone.get()) {
                    session.state.value.inconsistency()?.let { violations += it }
                    observed.incrementAndGet()
                    yield()
                }
            }
        }
        // a collector sees every state it receives consistent too
        val collected = Collections.synchronizedList(mutableListOf<SessionState>())
        val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            session.state.collect { collected += it }
        }
        val writers = profiles.map { profile ->
            async(Dispatchers.Default) {
                repeat(WRITER_ROUNDS) {
                    session.select(profile.name)
                    session.open(profile)
                    session.forget(profile.name)
                }
            }
        }
        try {
            withTimeout(TIMEOUT_MS) { writers.awaitAll() }
        } finally {
            // Also on a timeout: otherwise runBlocking would wait for the readers for ever.
            writersDone.set(true)
        }
        withTimeout(TIMEOUT_MS) { readers.awaitAll() }
        collector.cancelAndJoin()

        collected.forEach { state -> state.inconsistency()?.let { violations += "collected: $it" } }
        assertTrue(violations.isEmpty(), "inconsistent snapshots observed (${violations.size}): ${violations.take(5)}")
        assertTrue(observed.get() > 0 && collected.isNotEmpty(), "readers and collector must have seen states")
    }

    @Test
    fun `a collector sees the select and the result as state changes`() = runBlocking {
        val session = session()
        val reached = launch(Dispatchers.Default) {
            withTimeout(TIMEOUT_MS) {
                session.state.first { it.current?.status is PermissionCheckStatus.Done }
            }
        }
        session.select("a")
        session.open(profiles[0])
        reached.join()
        if (reached.isCancelled) fail("collector never saw the Done state of the current connection")
    }

    @Test
    fun `the code base has no for-inheritance coroutines API opt-in left`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val root = dir ?: fail("settings.gradle.kts not found above ${File("").absoluteFile}")
        // the name is split so this file does not match itself
        val needle = "ExperimentalForInheritance" + "CoroutinesApi"
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" }
            .filter { needle in it.readText() }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue(offenders.isEmpty(), "remove the opt-in (M2-01b); still used in: $offenders")
    }

    private companion object {
        const val WRITER_ROUNDS = 2_000
        const val TIMEOUT_MS = 30_000L
    }
}
