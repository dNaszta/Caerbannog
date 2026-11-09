package dev.caerbannog.core.connections

import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway.Failure
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-03 part 1: the connection lifecycle of `ActiveConnection` against `FakeConnectionGateway`
 * (tests/bdd/features/13_connection_lifecycle.feature, the first scenarios; rules in docs/spec.md "Connection
 * lifecycle"). Written before the code: red because ActiveConnection, LinkState, ConnectionGateway,
 * HeldConnection, OpenResult and ConnectionFailureReason do not exist yet. No sleeps: slow brokers are gates.
 */
class ActiveConnectionTest {

    private val h = Harness()

    // --- state ---

    @Test
    fun `before any connect the state is Disconnected with no name and no note`() {
        assertEquals(LinkState.Disconnected(name = null, note = null), h.active.state.value)
        assertEquals(LinkState.Disconnected(name = "uat-eu"), h.active.stateOf("uat-eu"))
        assertTrue(h.gateway.heldNow.isEmpty())
    }

    @Test
    fun `connect ends Connected, holds one connection and makes the profile the current one`() = runBlocking<Unit> {
        h.active.connect(h.uat)

        assertEquals(LinkState.Connected("uat-eu"), h.active.state.value)
        assertEquals(LinkState.Connected("uat-eu"), h.active.stateOf("uat-eu"))
        assertEquals(LinkState.Disconnected("prod-eu"), h.active.stateOf("prod-eu"))
        assertEquals(listOf("uat-eu"), h.gateway.heldNow.map { it.profileName })
        assertEquals("uat-eu", h.session.state.value.current?.name)
    }

    @Test
    fun `the source says whether the profile has an MQTT port, so the UI offers MQTT publishing only then`() = runBlocking<Unit> {
        h.active.connect(h.uat)
        assertEquals(false, h.active.source?.hasMqtt, "a profile without an MQTT port")

        h.active.connect(h.prod.copy(mqttPort = 1883))
        assertEquals(true, h.active.source?.hasMqtt, "a profile with an MQTT port")
    }

    @Test
    fun `connect runs in the documented order - select, Connecting, permission check, open clients, Connected`() = runBlocking<Unit> {
        var linkAtCheck: LinkState? = null
        var currentAtCheck: String? = null
        var checkDoneAtOpen: Boolean? = null
        var linkAtOpen: LinkState? = null
        h.checker.onCheck = {
            linkAtCheck = h.active.state.value
            currentAtCheck = h.session.state.value.current?.name
        }
        h.gateway.onOpen = {
            linkAtOpen = h.active.state.value
            checkDoneAtOpen = h.session.permissions(it.name) != null
        }

        h.active.connect(h.uat)

        assertEquals(LinkState.Connecting("uat-eu"), linkAtCheck, "state while the permission check runs")
        assertEquals("uat-eu", currentAtCheck, "the session already selected the profile when the check ran")
        assertEquals(LinkState.Connecting("uat-eu"), linkAtOpen, "state while the clients are opened")
        assertEquals(true, checkDoneAtOpen, "the permission check must be finished before the clients are opened")
        assertEquals(listOf("check:uat-eu", "open:uat-eu", "opened:uat-eu#1"), h.gateway.log.toList())
        assertEquals(LinkState.Connected("uat-eu"), h.active.state.value)
    }

    @Test
    fun `a slow broker shows Connecting until it answered`() = runBlocking<Unit> {
        val gate = h.gateway.holdOpens("uat-eu")

        val connecting = launch { h.active.connect(h.uat) }
        assertEquals("uat-eu", h.gateway.awaitOpenStarted())

        assertEquals(LinkState.Connecting("uat-eu"), h.active.state.value)
        assertTrue(h.gateway.heldNow.isEmpty(), "nothing is held before the broker answered")

        gate.release()
        withTimeout(5_000) { connecting.join() }

        assertEquals(LinkState.Connected("uat-eu"), h.active.state.value)
        assertEquals(1, h.gateway.heldNow.size)
    }

    // --- Disconnect ---

    @Test
    fun `disconnect releases the connection exactly once and is idempotent`() = runBlocking<Unit> {
        h.active.connect(h.uat)
        val held = h.gateway.heldNow.single()

        h.active.disconnect()

        assertEquals(LinkState.Disconnected(name = "uat-eu"), h.active.state.value)
        assertEquals(1, held.closeCalls)
        assertTrue(h.gateway.heldNow.isEmpty())
        assertEquals("uat-eu", h.session.state.value.current?.name, "Disconnect keeps the session's current connection")

        h.active.disconnect()
        assertEquals(1, held.closeCalls, "a second disconnect must not close again")
        assertEquals(LinkState.Disconnected(name = "uat-eu"), h.active.state.value)
    }

    @Test
    fun `disconnect with nothing held does nothing`() {
        h.active.disconnect()
        h.active.close()

        assertEquals(LinkState.Disconnected(name = null), h.active.state.value)
        assertTrue(h.gateway.log.isEmpty())
    }

    @Test
    fun `close, the window close, releases the connection and is idempotent`() = runBlocking<Unit> {
        h.active.connect(h.uat)
        val held = h.gateway.heldNow.single()

        h.active.close()
        h.active.close()

        assertEquals(1, held.closeCalls)
        assertTrue(h.gateway.heldNow.isEmpty())
        assertIs<LinkState.Disconnected>(h.active.state.value)
    }

    @Test
    fun `disconnect while connecting closes what the broker opens afterwards and the state stays Disconnected`() = runBlocking<Unit> {
        val gate = h.gateway.holdOpens("uat-eu")
        val connecting = launch { h.active.connect(h.uat) }
        h.gateway.awaitOpenStarted()

        h.active.disconnect()
        assertIs<LinkState.Disconnected>(h.active.state.value)

        gate.release()
        withTimeout(5_000) { connecting.join() }

        assertIs<LinkState.Disconnected>(h.active.state.value, "a late answer must not bring the connection back")
        val late = h.gateway.everOpened.single()
        assertEquals(1, late.closeCalls, "what the superseded connect opened is closed")
        assertTrue(h.gateway.heldNow.isEmpty())
    }

    // --- one held connection at a time ---

    @Test
    fun `connect on another profile closes the first before it checks and opens the second`() = runBlocking<Unit> {
        h.active.connect(h.uat)
        h.gateway.log.clear()

        h.active.connect(h.prod)

        assertEquals(
            listOf("close:uat-eu#1", "check:prod-eu", "open:prod-eu", "opened:prod-eu#2"),
            h.gateway.log.toList(),
        )
        assertEquals(LinkState.Disconnected("uat-eu"), h.active.stateOf("uat-eu"))
        assertEquals(LinkState.Connected("prod-eu"), h.active.stateOf("prod-eu"))
        assertEquals(LinkState.Connected("prod-eu"), h.active.state.value)
        assertEquals("prod-eu", h.session.state.value.current?.name)
        assertEquals(listOf("prod-eu"), h.gateway.heldNow.map { it.profileName })
        assertEquals(1, h.gateway.everOpened.first().closeCalls)
    }

    @Test
    fun `connect on the held profile again closes the old connection first, one stays held`() = runBlocking<Unit> {
        h.active.connect(h.uat)
        h.gateway.log.clear()

        h.active.connect(h.uat)

        assertEquals(listOf("close:uat-eu#1", "check:uat-eu", "open:uat-eu", "opened:uat-eu#2"), h.gateway.log.toList())
        assertEquals(1, h.gateway.heldNow.size)
    }

    @Test
    fun `while the second profile connects the first is already released`() = runBlocking<Unit> {
        h.active.connect(h.uat)
        val gate = h.gateway.holdOpens("prod-eu")

        val connecting = launch { h.active.connect(h.prod) }
        h.gateway.awaitOpenStarted()

        assertEquals(LinkState.Connecting("prod-eu"), h.active.state.value)
        assertEquals(LinkState.Disconnected("uat-eu"), h.active.stateOf("uat-eu"))
        assertTrue(h.gateway.heldNow.isEmpty(), "at no time are two brokers held")

        gate.release()
        withTimeout(5_000) { connecting.join() }
        assertEquals(LinkState.Connected("prod-eu"), h.active.state.value)
    }

    // --- latest started wins ---

    @Test
    fun `two connects overlap and the second answers first - the second stays, the late first is closed`() = runBlocking<Unit> {
        val gateUat = h.gateway.holdOpens("uat-eu")
        val first = launch { h.active.connect(h.uat) }
        h.gateway.awaitOpenStarted()
        val second = launch { h.active.connect(h.prod) }
        withTimeout(5_000) { second.join() }
        assertEquals(LinkState.Connected("prod-eu"), h.active.state.value)

        gateUat.release()
        withTimeout(5_000) { first.join() }

        assertEquals(LinkState.Connected("prod-eu"), h.active.state.value, "the older connect must not overwrite the newer one")
        assertEquals(listOf("prod-eu"), h.gateway.heldNow.map { it.profileName })
        assertEquals(1, h.gateway.everOpened.single { it.profileName == "uat-eu" }.closeCalls)
        assertEquals("prod-eu", h.session.state.value.current?.name)
    }

    @Test
    fun `two connects overlap and the first answers first - its connection is closed, only the second ends Connected`() = runBlocking<Unit> {
        val gateUat = h.gateway.holdOpens("uat-eu")
        val gateProd = h.gateway.holdOpens("prod-eu")
        val first = launch { h.active.connect(h.uat) }
        h.gateway.awaitOpenStarted()
        val second = launch { h.active.connect(h.prod) }
        h.gateway.awaitOpenStarted()

        gateUat.release()
        withTimeout(5_000) { first.join() }
        assertEquals(LinkState.Connecting("prod-eu"), h.active.state.value, "an answer of the older connect changes nothing")
        assertTrue(h.gateway.heldNow.isEmpty())

        gateProd.release()
        withTimeout(5_000) { second.join() }

        assertEquals(LinkState.Connected("prod-eu"), h.active.state.value)
        assertEquals(listOf("prod-eu"), h.gateway.heldNow.map { it.profileName })
        assertEquals(1, h.gateway.everOpened.single { it.profileName == "uat-eu" }.closeCalls)
    }

    @Test
    fun `an outdated connect that fails writes nothing`() = runBlocking<Unit> {
        h.gateway.failWith("uat-eu", Failure.BROKER_NOT_REACHABLE)
        val gateUat = h.gateway.holdOpens("uat-eu")
        val first = launch { h.active.connect(h.uat) }
        h.gateway.awaitOpenStarted()
        h.active.connect(h.prod)

        gateUat.release()
        withTimeout(5_000) { first.join() }

        assertEquals(LinkState.Connected("prod-eu"), h.active.state.value, "a late failure of the older connect must not show")
        assertEquals(listOf("prod-eu"), h.gateway.heldNow.map { it.profileName })
    }

    @Test
    fun `an outdated connect that is cancelled writes nothing`() = runBlocking<Unit> {
        h.gateway.holdOpens("uat-eu")
        val first = launch { h.active.connect(h.uat) }
        h.gateway.awaitOpenStarted()
        h.active.connect(h.prod)

        first.cancel()
        withTimeout(5_000) { first.join() }

        assertEquals(LinkState.Connected("prod-eu"), h.active.state.value, "a cancel of the older connect must not end the newer one")
        assertEquals(listOf("prod-eu"), h.gateway.heldNow.map { it.profileName })
    }

    // --- failures (the reason texts are the contract, see the failure table in the brief and feature 13) ---

    private val failures = listOf(
        Failure.BROKER_NOT_REACHABLE to "The broker could not be reached",
        Failure.CERTIFICATE_NOT_TRUSTED to "The certificate was not trusted",
        Failure.NO_PASSWORD_STORED to "No password is stored",
        Failure.CREDENTIALS_REFUSED to "Credentials were refused",
    )

    @Test
    fun `each failure ends Failed with the exact reason, holds nothing and keeps the profile current`() {
        for ((cause, reason) in failures) {
            val fresh = Harness()
            fresh.gateway.failWith("uat-eu", cause)

            runBlocking { fresh.active.connect(fresh.uat) }

            assertEquals(LinkState.Failed("uat-eu", reason), fresh.active.state.value, "cause $cause")
            assertEquals(reason, cause.reason, "the fake must use the reason text core owns")
            assertTrue(fresh.gateway.heldNow.isEmpty(), "cause $cause: nothing may stay open")
            assertEquals("uat-eu", fresh.session.state.value.current?.name, "cause $cause")
        }
    }

    @Test
    fun `a missing password fails Connect without opening the clients`() = runBlocking<Unit> {
        h.secrets.delete("uat-eu")

        h.active.connect(h.uat)

        assertEquals(LinkState.Failed("uat-eu", "No password is stored"), h.active.state.value)
        assertTrue(h.gateway.log.none { it.startsWith("open:") }, "the gateway must not be called: ${h.gateway.log}")
        assertTrue(h.gateway.heldNow.isEmpty())
        assertEquals(PermissionCheckResult.Unreadable(UnreadableCause.NO_PASSWORD), h.session.permissions("uat-eu"))
        assertEquals("uat-eu", h.session.state.value.current?.name)
    }

    @Test
    fun `every other Unreadable cause still ends Connected in safest mode`() {
        for (cause in UnreadableCause.values().filter { it != UnreadableCause.NO_PASSWORD }) {
            val fresh = Harness()
            fresh.checker.result = PermissionCheckResult.Unreadable(cause)

            runBlocking { fresh.active.connect(fresh.uat) }

            assertEquals(LinkState.Connected("uat-eu"), fresh.active.state.value, "cause $cause")
            assertFalse(fresh.session.permissions("uat-eu")!!.topologyUnlocked, "cause $cause")
        }
    }

    @Test
    fun `a password that vanishes between the check and the open still fails through the gateway`() = runBlocking<Unit> {
        h.gateway.failWith("uat-eu", Failure.NO_PASSWORD_STORED)

        h.active.connect(h.uat)

        assertEquals(LinkState.Failed("uat-eu", "No password is stored"), h.active.state.value)
        assertTrue("open:uat-eu" in h.gateway.log)
        assertTrue(h.gateway.heldNow.isEmpty())
    }

    @Test
    fun `a failed connect can be retried and then ends Connected`() = runBlocking<Unit> {
        h.gateway.failWith("uat-eu", Failure.BROKER_NOT_REACHABLE)
        h.active.connect(h.uat)
        assertIs<LinkState.Failed>(h.active.state.value)

        h.gateway.succeed("uat-eu")
        h.active.connect(h.uat)

        assertEquals(LinkState.Connected("uat-eu"), h.active.state.value)
        assertEquals(1, h.gateway.heldNow.size)
    }

    @Test
    fun `a failed connect on another profile still released the first connection`() = runBlocking<Unit> {
        h.active.connect(h.uat)
        h.gateway.failWith("prod-eu", Failure.CERTIFICATE_NOT_TRUSTED)

        h.active.connect(h.prod)

        assertEquals(LinkState.Failed("prod-eu", "The certificate was not trusted"), h.active.state.value)
        assertEquals(LinkState.Disconnected("uat-eu"), h.active.stateOf("uat-eu"))
        assertTrue(h.gateway.heldNow.isEmpty())
    }

    @Test
    fun `a gateway that throws ends Failed without the exception text, holds nothing and never throws out of connect`() = runBlocking<Unit> {
        val secretInMessage = "boom-$PASSWORD"
        val throwing = ActiveConnection(h.session, object : ConnectionGateway {
            override suspend fun open(profile: ConnectionProfile): OpenResult = throw IllegalStateException(secretInMessage)
        })

        throwing.connect(h.uat)

        val state = assertIs<LinkState.Failed>(throwing.state.value)
        assertEquals("uat-eu", state.name)
        assertFalse(state.reason.contains(PASSWORD), "an exception message may carry a secret and must not become the reason")
        assertTrue(state.reason.isNotBlank())
    }

    @Test
    fun `Unreadable permissions still end Connected, in safest mode`() = runBlocking<Unit> {
        h.checker.result = PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED)

        h.active.connect(h.uat)

        assertEquals(LinkState.Connected("uat-eu"), h.active.state.value)
        val permissions = assertIs<PermissionCheckResult.Unreadable>(h.session.permissions("uat-eu"))
        assertFalse(permissions.topologyUnlocked)
        assertFalse(permissions.publishingEnabled)
        assertEquals(1, h.gateway.heldNow.size)
    }

    @Test
    fun `a silent stream port still ends Connected, the held connection says its stream port does not answer`() = runBlocking<Unit> {
        h.gateway.streamPortSilent("uat-eu")

        h.active.connect(h.uat)

        assertEquals(LinkState.Connected("uat-eu"), h.active.state.value)
        assertFalse(h.gateway.heldNow.single().streamPortAnswers)
    }

    // --- cancellation ---

    @Test
    fun `cancelling a connect while the broker is asked leaves nothing open and no Connecting behind`() = runBlocking<Unit> {
        h.gateway.holdOpens("uat-eu")
        val connecting = launch { h.active.connect(h.uat) }
        h.gateway.awaitOpenStarted()

        connecting.cancel()
        withTimeout(5_000) { connecting.join() }

        assertIs<LinkState.Disconnected>(h.active.state.value)
        assertTrue(h.gateway.heldNow.isEmpty())
        assertTrue("cancelled:uat-eu" in h.gateway.log)
    }

    @Test
    fun `cancelling a connect during the permission check never opens the clients`() = runBlocking<Unit> {
        val gated = GatedPermissionChecker()
        val active = ActiveConnection(ConnectionSession(gated, h.secrets), h.gateway)
        val connecting = launch { active.connect(h.uat) }
        gated.nextStarted()
        assertEquals(LinkState.Connecting("uat-eu"), active.state.value)

        connecting.cancel()
        withTimeout(5_000) { connecting.join() }

        assertIs<LinkState.Disconnected>(active.state.value)
        assertNull(h.gateway.log.firstOrNull { it.startsWith("open:") }, "the clients are opened only after the check")
        assertTrue(h.gateway.heldNow.isEmpty())
    }

    @Test
    fun `cancelling the connect of a second profile still left the first one released`() = runBlocking<Unit> {
        h.active.connect(h.uat)
        h.gateway.holdOpens("prod-eu")
        val connecting = launch { h.active.connect(h.prod) }
        h.gateway.awaitOpenStarted()

        connecting.cancel()
        withTimeout(5_000) { connecting.join() }

        assertTrue(h.gateway.heldNow.isEmpty(), "one held connection at a time, and the cancelled one holds nothing")
        assertIs<LinkState.Disconnected>(h.active.state.value)
    }

    @Test
    fun `window close while connecting closes what is opened afterwards`() = runBlocking<Unit> {
        val gate = h.gateway.holdOpens("uat-eu")
        val connecting = launch { h.active.connect(h.uat) }
        h.gateway.awaitOpenStarted()

        h.active.close()
        gate.release()
        withTimeout(5_000) { connecting.join() }

        assertTrue(h.gateway.heldNow.isEmpty())
        assertIs<LinkState.Disconnected>(h.active.state.value)
    }
}
