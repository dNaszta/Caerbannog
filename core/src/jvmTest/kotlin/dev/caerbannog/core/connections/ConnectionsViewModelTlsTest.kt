package dev.caerbannog.core.connections

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-14a step 2: the TLS switch on the profile through `ConnectionsViewModel` (brief "Scope": model, "keep" semantics like the level; O3: the
 * CA path is kept but unused when TLS is off; toggling TLS is an edit, so the held connection ends with "Connection settings changed.
 * Connect again."). Red until `tls` is copied by `addConnection` and `updateConnection`.
 */
class ConnectionsViewModelTlsTest {

    private class InMemoryRepository : ConnectionProfileRepository {
        private val profiles = linkedMapOf<String, ConnectionProfile>()
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

    private class CountingValidator : CaCertificateValidator {
        val validated = mutableListOf<String>()
        override fun validate(filePath: String): CaCertificateValidationResult {
            validated += filePath
            return CaCertificateValidationResult.Valid
        }
    }

    private class Reachable : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult = ConnectionCheckResult.BothReachable
    }

    private val h = Harness()
    private val repository = InMemoryRepository()
    private val validator = CountingValidator()
    private val viewModel = ConnectionsViewModel(repository, h.secrets, Reachable(), validator, h.session, h.active)

    private fun input(name: String = "uat-eu", tls: Boolean? = null, stream: Int = 5551, management: Int = 15671) = ConnectionProfileInput(
        name = name, host = "rabbit.example", virtualHost = "/", username = "svc", password = "pw",
        streamPort = stream, managementPort = management, tls = tls,
    )

    private fun saved(name: String = "uat-eu") = repository.list().first { it.name == name }

    @Test
    fun `an added connection without a TLS value has TLS on`() {
        viewModel.addConnection(input(tls = null))
        assertTrue(saved().tls)
    }

    @Test
    fun `an added connection can have TLS off`() {
        val result = viewModel.addConnection(input(tls = false, stream = 5552, management = 15672))
        assertIs<SaveConnectionResult.Saved>(result)
        assertFalse(saved().tls)
        assertEquals(5552, saved().streamPort)
        assertEquals(15672, saved().managementPort)
        assertFalse(assertIs<SaveConnectionResult.Saved>(result).profile.tls, "the result carries the saved profile")
    }

    @Test
    fun `an added connection can say TLS on explicitly`() {
        viewModel.addConnection(input(tls = true))
        assertTrue(saved().tls)
    }

    @Test
    fun `an update without a TLS value keeps TLS on`() {
        viewModel.addConnection(input(tls = true))
        viewModel.updateConnection("uat-eu", input(tls = null).copy(host = "changed.example"))
        assertTrue(saved().tls)
        assertEquals("changed.example", saved().host)
    }

    @Test
    fun `an update without a TLS value keeps TLS off - a caller that does not know the field cannot turn it on`() {
        viewModel.addConnection(input(tls = false, stream = 5552, management = 15672))
        viewModel.updateConnection("uat-eu", input(tls = null, stream = 5552, management = 15672).copy(host = "changed.example"))
        assertFalse(saved().tls)
    }

    @Test
    fun `an update can turn TLS off and on`() {
        viewModel.addConnection(input(tls = true))
        viewModel.updateConnection("uat-eu", input(tls = false, stream = 5552, management = 15672))
        assertFalse(saved().tls)
        viewModel.updateConnection("uat-eu", input(tls = true))
        assertTrue(saved().tls)
    }

    @Test
    fun `a rename keeps TLS off`() {
        viewModel.addConnection(input(tls = false, stream = 5552, management = 15672))
        viewModel.updateConnection("uat-eu", input(name = "dev-local", tls = null, stream = 5552, management = 15672))
        assertFalse(saved("dev-local").tls)
    }

    @Test
    fun `the topology level and a CA path do not change TLS`() {
        viewModel.addConnection(input(tls = false, stream = 5552, management = 15672))
        viewModel.setTopologyControlLevel("uat-eu", TopologyControlLevel.FULL_CONTROL)
        viewModel.setCaCertificate("uat-eu", "/some/ca.pem")
        assertFalse(saved().tls)
    }

    @Test
    fun `turning TLS off keeps the saved CA path, and turning it on again still has it (O3)`() {
        viewModel.addConnection(input(tls = true))
        viewModel.setCaCertificate("uat-eu", "/some/ca.pem")
        viewModel.updateConnection("uat-eu", input(tls = false, stream = 5552, management = 15672))
        assertEquals("/some/ca.pem", saved().caCertificatePath, "kept but unused")
        viewModel.updateConnection("uat-eu", input(tls = true))
        assertEquals("/some/ca.pem", saved().caCertificatePath)
    }

    @Test
    fun `saving a plain profile with a CA path never asks the validator`() {
        viewModel.addConnection(input(tls = true))
        viewModel.setCaCertificate("uat-eu", "/some/ca.pem")
        validator.validated.clear()
        viewModel.updateConnection("uat-eu", input(tls = false, stream = 5552, management = 15672))
        runBlocking { viewModel.saveProfileChanges("uat-eu", input(tls = false, stream = 5552, management = 15672)) }
        assertEquals(emptyList(), validator.validated, "with TLS off the CA path is not validated")
    }

    @Test
    fun `turning TLS off on the held profile ends the connection with the settings note, and the next Connect asks for plain`() {
        viewModel.addConnection(input(tls = true))
        h.secrets.store("uat-eu", PASSWORD)
        runBlocking { withTimeout(5_000) { viewModel.connect("uat-eu") } }
        assertIs<LinkState.Connected>(h.active.stateOf("uat-eu"))
        assertTrue(h.gateway.openedProfiles.last().tls)

        viewModel.updateConnection("uat-eu", input(tls = false, stream = 5552, management = 15672))

        val state = assertIs<LinkState.Disconnected>(h.active.stateOf("uat-eu"))
        assertEquals("Connection settings changed. Connect again.", state.note)
        assertTrue(h.gateway.heldNow.isEmpty(), "the connection made with the old mode is closed")

        runBlocking { withTimeout(5_000) { viewModel.connect("uat-eu") } }
        assertFalse(h.gateway.openedProfiles.last().tls, "the new connection is opened for the saved mode, not the old one")
        assertEquals(5552, h.gateway.openedProfiles.last().streamPort)
    }

    @Test
    fun `turning TLS on on the held plain profile ends the connection too`() {
        viewModel.addConnection(input(tls = false, stream = 5552, management = 15672))
        h.secrets.store("uat-eu", PASSWORD)
        runBlocking { withTimeout(5_000) { viewModel.connect("uat-eu") } }

        viewModel.updateConnection("uat-eu", input(tls = true))

        assertEquals("Connection settings changed. Connect again.", assertIs<LinkState.Disconnected>(h.active.stateOf("uat-eu")).note)
    }

    @Test
    fun `no exposed value of a plain connection contains the password`() {
        viewModel.addConnection(input(tls = false, stream = 5552, management = 15672))
        h.secrets.store("uat-eu", PASSWORD)
        runBlocking { withTimeout(5_000) { viewModel.connect("uat-eu") } }
        val exposed = listOf(
            h.active.state.value.toString(), viewModel.state.value.toString(), h.gateway.log.toList().toString(),
            h.gateway.openedProfiles.toString(), repository.list().toString(),
        ).joinToString("\n")
        assertFalse(exposed.contains(PASSWORD), exposed)
        assertNull(viewModel.state.value.lastCaCertificateValidationResult)
    }
}
