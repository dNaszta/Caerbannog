package dev.caerbannog.core.connections

import dev.caerbannog.core.connections.fakes.FakeConnectionGateway.Failure
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-03 part 1: what Edit, Rename, Delete and the interim Connect button do to the held connection
 * (spec "Connection lifecycle": "Closed when"), through `ConnectionsViewModel`, which gets the new optional
 * constructor parameter `active: ActiveConnection? = null` (contract proposed here, a brief gap).
 *
 * ViewModel contract used:
 *   connect(name): with `active` set, calls `active.connect(profile)` (select + check + open clients)
 *   updateConnection / saveProfileChanges / setCaCertificate on a saved profile: on a successful save,
 *     calls `active.profileChanged(originalName)`; confirmDelete: calls `active.profileDeleted(name)`
 *   `ActiveConnection.profileChanged(name)` and `.profileDeleted(name)` do nothing when `name` is not the held one.
 */
class ConnectionsViewModelActiveConnectionTest {

    private val h = Harness()

    private class InMemoryRepository(vararg seed: ConnectionProfile) : ConnectionProfileRepository {
        private val profiles = linkedMapOf<String, ConnectionProfile>().also { m -> seed.forEach { m[it.name] = it } }
        override fun list(): List<ConnectionProfile> = profiles.values.toList()
        override fun add(profile: ConnectionProfile): SaveConnectionResult {
            if (profiles.containsKey(profile.name)) return SaveConnectionResult.NameAlreadyExists(profile.name)
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }
        override fun update(profile: ConnectionProfile): SaveConnectionResult {
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }
        override fun delete(name: String) {
            profiles.remove(name)
        }
    }

    private class NeverUsedReachability : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult = ConnectionCheckResult.BothReachable
    }

    private class ValidCa : CaCertificateValidator {
        override fun validate(filePath: String): CaCertificateValidationResult = CaCertificateValidationResult.Valid
    }

    private val viewModel = ConnectionsViewModel(
        InMemoryRepository(h.uat, h.prod), h.secrets, NeverUsedReachability(), ValidCa(), h.session, h.active,
    )

    private fun input(profile: ConnectionProfile, name: String = profile.name, host: String = profile.host) =
        ConnectionProfileInput(name = name, host = host, virtualHost = profile.virtualHost, username = profile.username, password = null)

    private val note = "Connection settings changed. Connect again."

    @Test
    fun `the interim Connect opens the clients and mirrors the session`() = runBlocking<Unit> {
        viewModel.connect("uat-eu")

        assertEquals(LinkState.Connected("uat-eu"), h.active.state.value)
        assertEquals("uat-eu", viewModel.state.value.currentConnection?.name)
        assertEquals(1, h.gateway.heldNow.size)
    }

    @Test
    fun `Connect on an unknown name does nothing`() = runBlocking<Unit> {
        viewModel.connect("nope")

        assertIs<LinkState.Disconnected>(h.active.state.value)
        assertTrue(h.gateway.log.isEmpty())
    }

    @Test
    fun `saving a change to the held profile disconnects it with the note`() = runBlocking<Unit> {
        viewModel.connect("uat-eu")
        val held = h.gateway.heldNow.single()

        val result = viewModel.updateConnection("uat-eu", input(h.uat, host = "other-host.example.com"))

        assertIs<SaveConnectionResult.Saved>(result)
        assertEquals(LinkState.Disconnected(name = "uat-eu", note = note), h.active.stateOf("uat-eu"))
        assertEquals(1, held.closeCalls)
        assertTrue(h.gateway.heldNow.isEmpty())
    }

    @Test
    fun `saveProfileChanges disconnects, runs the permission check again and does not reconnect by itself`() = runBlocking<Unit> {
        viewModel.connect("uat-eu")
        val checksBefore = h.checker.calls.size

        viewModel.saveProfileChanges("uat-eu", input(h.uat, host = "other-host.example.com"))

        assertEquals(LinkState.Disconnected(name = "uat-eu", note = note), h.active.state.value)
        assertEquals(checksBefore + 1, h.checker.calls.size, "the check runs again after a save, as before")
        assertEquals(1, h.gateway.everOpened.size, "no second open: the user connects again")
        assertTrue(h.gateway.heldNow.isEmpty())
    }

    @Test
    fun `renaming the held profile disconnects it with the note`() = runBlocking<Unit> {
        viewModel.connect("uat-eu")

        val result = viewModel.updateConnection("uat-eu", input(h.uat, name = "uat-eu-2"))

        assertIs<SaveConnectionResult.Saved>(result)
        assertEquals(LinkState.Disconnected(name = "uat-eu", note = note), h.active.stateOf("uat-eu"))
        assertTrue(h.gateway.heldNow.isEmpty())
        assertEquals(LinkState.Disconnected("uat-eu-2"), h.active.stateOf("uat-eu-2"))
    }

    @Test
    fun `a rename that fails because the name exists keeps the connection`() = runBlocking<Unit> {
        viewModel.connect("uat-eu")

        val result = viewModel.updateConnection("uat-eu", input(h.uat, name = "prod-eu"))

        assertIs<SaveConnectionResult.NameAlreadyExists>(result)
        assertEquals(LinkState.Connected("uat-eu"), h.active.state.value)
        assertEquals(1, h.gateway.heldNow.size)
    }

    @Test
    fun `editing a profile that is not held leaves the held connection alone`() = runBlocking<Unit> {
        viewModel.connect("uat-eu")

        viewModel.updateConnection("prod-eu", input(h.prod, host = "other-host.example.com"))

        assertEquals(LinkState.Connected("uat-eu"), h.active.state.value)
        assertEquals(listOf("uat-eu"), h.gateway.heldNow.map { it.profileName })
    }

    @Test
    fun `changing the CA certificate of the held profile disconnects it with the note`() = runBlocking<Unit> {
        viewModel.connect("uat-eu")

        viewModel.setCaCertificate("uat-eu", "/some/ca.pem")

        assertEquals(LinkState.Disconnected(name = "uat-eu", note = note), h.active.state.value)
        assertTrue(h.gateway.heldNow.isEmpty())
    }

    @Test
    fun `changing the topology control level does not disconnect`() = runBlocking<Unit> {
        viewModel.connect("uat-eu")

        viewModel.setTopologyControlLevel("uat-eu", TopologyControlLevel.values().first { it != h.uat.topologyControlLevel })

        assertEquals(LinkState.Connected("uat-eu"), h.active.state.value)
        assertEquals(1, h.gateway.heldNow.size)
    }

    @Test
    fun `editing the profile while it connects closes the connection the broker opens afterwards`() = runBlocking<Unit> {
        val gate = h.gateway.holdOpens("uat-eu")
        val connecting = launch { viewModel.connect("uat-eu") }
        h.gateway.awaitOpenStarted()

        viewModel.updateConnection("uat-eu", input(h.uat, host = "other-host.example.com"))
        gate.release()
        withTimeout(5_000) { connecting.join() }

        assertEquals(LinkState.Disconnected(name = "uat-eu", note = note), h.active.state.value)
        assertTrue(h.gateway.heldNow.isEmpty(), "a connection made with the old settings must not stay")
    }

    @Test
    fun `deleting the held profile releases the connection and clears the current connection`() = runBlocking<Unit> {
        viewModel.connect("uat-eu")
        val held = h.gateway.heldNow.single()

        viewModel.requestDelete("uat-eu")
        viewModel.confirmDelete()

        assertEquals(1, held.closeCalls)
        assertTrue(h.gateway.heldNow.isEmpty())
        assertIs<LinkState.Disconnected>(h.active.stateOf("uat-eu"))
        assertNull(h.active.stateOf("uat-eu").let { (it as LinkState.Disconnected).note }, "a delete is not a settings change")
        assertNull(viewModel.state.value.currentConnection)
        assertNull(h.session.state.value.current)
    }

    @Test
    fun `deleting a profile that is not held leaves the held connection alone`() = runBlocking<Unit> {
        viewModel.connect("uat-eu")

        viewModel.requestDelete("prod-eu")
        viewModel.confirmDelete()

        assertEquals(LinkState.Connected("uat-eu"), h.active.state.value)
        assertEquals(1, h.gateway.heldNow.size)
    }

    @Test
    fun `deleting the profile while it connects closes the connection the broker opens afterwards`() = runBlocking<Unit> {
        val gate = h.gateway.holdOpens("uat-eu")
        val connecting = launch { viewModel.connect("uat-eu") }
        h.gateway.awaitOpenStarted()

        viewModel.requestDelete("uat-eu")
        viewModel.confirmDelete()
        gate.release()
        withTimeout(5_000) { connecting.join() }

        assertTrue(h.gateway.heldNow.isEmpty())
        assertIs<LinkState.Disconnected>(h.active.state.value)
    }

    @Test
    fun `the edit panel check stays a check - it opens no clients and holds nothing`() = runBlocking<Unit> {
        viewModel.checkConnectionAndPermissions("uat-eu")

        assertTrue(h.gateway.log.none { it.startsWith("open:") })
        assertTrue(h.gateway.heldNow.isEmpty())
        assertIs<LinkState.Disconnected>(h.active.state.value)
    }

    @Test
    fun `the connection state and the permission pill of the session stay consistent`() = runBlocking<Unit> {
        h.checker.result = PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED)

        viewModel.connect("uat-eu")
        assertEquals(LinkState.Connected("uat-eu"), h.active.state.value)
        assertIs<PermissionCheckStatus.Done>(viewModel.state.value.currentConnection?.status)

        h.gateway.failWith("prod-eu", Failure.CREDENTIALS_REFUSED)
        viewModel.connect("prod-eu")

        val link = assertIs<LinkState.Failed>(h.active.state.value)
        assertEquals(link.name, viewModel.state.value.currentConnection?.name, "the current connection follows the last Connect")
        assertEquals(LinkState.Disconnected("uat-eu"), h.active.stateOf("uat-eu"))
        assertIs<PermissionCheckStatus.Done>(viewModel.state.value.currentConnection?.status, "the failed profile was checked: its pill is shown")
    }
}
