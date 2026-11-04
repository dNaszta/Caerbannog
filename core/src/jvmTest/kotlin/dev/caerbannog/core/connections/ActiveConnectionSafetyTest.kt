package dev.caerbannog.core.connections

import dev.caerbannog.core.connections.fakes.FakeConnectionGateway.Failure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2-03 part 1: D6 (the password never leaves the store) and atomicity of the link state under concurrent
 * connect and disconnect calls. No sleeps: the concurrent test checks invariants after all calls joined.
 */
class ActiveConnectionSafetyTest {

    private val h = Harness()

    @Test
    fun `the password never appears in the link state, the session state, the log or the held connections`() = runBlocking {
        // Control: the checker received the password, so the test is not vacuous.
        h.active.connect(h.uat)
        assertEquals(PASSWORD, h.checker.calls.single().second, "precondition: the password was read from the store")
        var text = h.exposedText()

        for (cause in Failure.values()) {
            h.gateway.failWith("prod-eu", cause)
            h.active.connect(h.prod)
            text += "\n" + h.exposedText()
        }
        h.gateway.succeed("prod-eu")
        h.checker.result = PermissionCheckResult.Unreadable(UnreadableCause.UNEXPECTED, "SomeException")
        h.active.connect(h.prod)
        h.active.disconnect()
        text += "\n" + h.exposedText()

        assertFalse(text.contains(PASSWORD), "the password must not be in any exposed value:\n$text")
    }

    @Test
    fun `the password is not in the state of the view model either`() = runBlocking {
        val vm = ConnectionsViewModel(
            repository = object : ConnectionProfileRepository {
                override fun list() = listOf(h.uat)
                override fun add(profile: ConnectionProfile) = SaveConnectionResult.Saved(profile)
                override fun update(profile: ConnectionProfile) = SaveConnectionResult.Saved(profile)
                override fun delete(name: String) = Unit
            },
            secretStore = h.secrets,
            reachabilityChecker = object : BrokerReachabilityChecker {
                override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
            },
            caCertificateValidator = object : CaCertificateValidator {
                override fun validate(filePath: String) = CaCertificateValidationResult.Valid
            },
            session = h.session,
            active = h.active,
        )
        h.gateway.failWith("uat-eu", Failure.CREDENTIALS_REFUSED)

        vm.connect("uat-eu")

        assertFalse(vm.state.value.toString().contains(PASSWORD))
        assertFalse(h.active.state.value.toString().contains(PASSWORD))
    }

    @Test
    fun `concurrent connect and disconnect calls leave one consistent state and no leaked connection`() = runBlocking {
        val profiles = listOf(h.uat, h.prod)
        val random = Random(20261104)
        val operations = List(400) { random.nextInt(3) }
        val scope = CoroutineScope(Dispatchers.Default)

        withTimeout(30_000) {
            operations.mapIndexed { index, op ->
                scope.async {
                    when (op) {
                        0 -> h.active.connect(profiles[index % 2])
                        1 -> h.active.connect(profiles[(index + 1) % 2])
                        else -> h.active.disconnect()
                    }
                }
            }.awaitAll()
        }

        val state = h.active.state.value
        val held = h.gateway.heldNow
        assertTrue(held.size <= 1, "at most one connection is held, got ${held.map { it.profileName }}")
        for (opened in h.gateway.everOpened) {
            assertTrue(opened.closeCalls <= 1, "${opened.profileName}#${opened.number} closed ${opened.closeCalls} times")
        }
        when (state) {
            is LinkState.Connected -> {
                assertEquals(listOf(state.name), held.map { it.profileName }, "Connected must mean exactly that connection is held")
                assertEquals(state.name, h.session.state.value.current?.name)
            }
            else -> assertTrue(held.isEmpty(), "state $state but still held: ${held.map { it.profileName }}")
        }

        h.active.close()
        assertTrue(h.gateway.heldNow.isEmpty())
        assertTrue(h.gateway.everOpened.all { it.closeCalls == 1 }, "after close every opened connection was closed exactly once")
    }
}
