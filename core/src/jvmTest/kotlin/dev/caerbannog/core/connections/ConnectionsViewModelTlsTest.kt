package dev.caerbannog.core.connections

import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
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

    // --- CA validation off the UI thread (M2-14, P8): the late-result rule, added by the test developer in step 4 (mutation "result not
    // dropped" survived the step 2 tests). The validator runs on the dispatcher given; a result for a panel the user left is dropped.

    private class GateValidator : CaCertificateValidator {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var thread: String? = null
        override fun validate(filePath: String): CaCertificateValidationResult {
            thread = Thread.currentThread().name
            started.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "gate never released" }
            return CaCertificateValidationResult.Invalid("not a certificate")
        }
    }

    @Test
    fun `a late CA validation result is dropped when the user opened another panel meanwhile`() {
        val gate = GateValidator()
        val vm = ConnectionsViewModel(repository, h.secrets, Reachable(), gate, h.session, h.active)
        vm.addConnection(input(name = "a"))
        vm.addConnection(input(name = "b"))
        vm.selectConnection("a")
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ca-validation-test") }
        try {
            runBlocking {
                withTimeout(10_000) {
                    val result = async(kotlinx.coroutines.Dispatchers.Unconfined) {
                        vm.validateCaCertificateOffThread("a", "/some/ca.pem", pool.asCoroutineDispatcher())
                    }
                    gate.started.await()
                    vm.selectConnection("b") // the user moves on while the file is still being read
                    gate.release.countDown()
                    assertNull(result.await(), "a dropped result is null")
                }
            }
        } finally {
            pool.shutdownNow()
        }
        assertTrue(gate.thread.orEmpty().startsWith("ca-validation-test"), "the validator ran on the given dispatcher, not on the caller; ran on ${gate.thread}")
        assertNull(vm.state.value.lastCaCertificateValidationResult, "the late answer for panel a must not show on panel b")
    }

    @Test
    fun `a CA validation result for the panel still open is applied`() {
        val gate = GateValidator().also { it.release.countDown() }
        val vm = ConnectionsViewModel(repository, h.secrets, Reachable(), gate, h.session, h.active)
        vm.startNewConnection()
        val result = runBlocking { withTimeout(10_000) { vm.validateCaCertificateOffThread(null, "/some/ca.pem", kotlinx.coroutines.Dispatchers.IO) } }
        assertEquals(CaCertificateValidationResult.Invalid("not a certificate"), result)
        assertEquals(result, vm.state.value.lastCaCertificateValidationResult)
    }

    private class RejectingValidator : CaCertificateValidator {
        val validated = mutableListOf<String>()
        override fun validate(filePath: String): CaCertificateValidationResult {
            validated += filePath
            return CaCertificateValidationResult.Invalid("not a certificate")
        }
    }

    private fun inputWithCa(ca: String?, tls: Boolean? = null) = input(tls = tls).copy(caCertificatePath = ca)

    // M2-14a R2 (flipped from the step 4 KNOWN GAP characterisation): the CA path typed in the form is stored with a NEW profile.
    @Test
    fun `a new connection saved with a CA path typed in the form stores the CA path`() {
        assertEquals(CaCertificateValidationResult.Valid, viewModel.validateCaCertificateFile("/some/ca.pem"))
        viewModel.addConnection(inputWithCa("/some/ca.pem", tls = true))
        assertEquals("/some/ca.pem", saved().caCertificatePath, "the path typed in the form is stored on add")
        assertEquals(listOf("/some/ca.pem", "/some/ca.pem"), validator.validated.takeLast(2), "stored only after the validator said Valid")
        viewModel.updateConnection("uat-eu", input(tls = true))
        assertEquals("/some/ca.pem", saved().caCertificatePath, "and an edit without touching it keeps it")
    }

    @Test
    fun `a new connection without a CA path has none`() {
        viewModel.addConnection(inputWithCa(null))
        assertNull(saved().caCertificatePath)
    }

    @Test
    fun `an update with a null CA path keeps the saved path`() {
        viewModel.addConnection(input())
        viewModel.setCaCertificate("uat-eu", "/some/ca.pem")
        viewModel.updateConnection("uat-eu", inputWithCa(null))
        assertEquals("/some/ca.pem", saved().caCertificatePath)
    }

    @Test
    fun `an update with a blank CA path clears the saved path`() {
        viewModel.addConnection(input())
        viewModel.setCaCertificate("uat-eu", "/some/ca.pem")
        viewModel.updateConnection("uat-eu", inputWithCa(""))
        assertNull(saved().caCertificatePath, "blank clears")
        viewModel.setCaCertificate("uat-eu", "/some/ca.pem")
        viewModel.updateConnection("uat-eu", inputWithCa("   "))
        assertNull(saved().caCertificatePath, "whitespace only is blank too")
    }

    @Test
    fun `an update with a valid new CA path stores it`() {
        viewModel.addConnection(input())
        viewModel.updateConnection("uat-eu", inputWithCa("/other/ca.pem"))
        assertEquals("/other/ca.pem", saved().caCertificatePath)
    }

    @Test
    fun `an invalid CA path is not stored on add, the message is shown and the save still succeeds`() {
        val rejecting = RejectingValidator()
        val vm = ConnectionsViewModel(repository, h.secrets, Reachable(), rejecting, h.session, h.active)
        val result = vm.addConnection(inputWithCa("/not/a/cert.txt"))
        assertIs<SaveConnectionResult.Saved>(result, "P8: Save itself goes through")
        assertNull(saved().caCertificatePath, "an invalid path is never stored")
        assertEquals(CaCertificateValidationResult.Invalid("not a certificate"), vm.state.value.lastCaCertificateValidationResult)
    }

    @Test
    fun `an invalid CA path is not stored on update and does not replace the saved one, the save still succeeds`() {
        viewModel.addConnection(input())
        viewModel.setCaCertificate("uat-eu", "/some/ca.pem")
        val rejecting = RejectingValidator()
        val vm = ConnectionsViewModel(repository, h.secrets, Reachable(), rejecting, h.session, h.active)
        val result = vm.updateConnection("uat-eu", input().copy(host = "other.example", caCertificatePath = "/not/a/cert.txt"))
        assertIs<SaveConnectionResult.Saved>(result)
        assertEquals("other.example", saved().host, "the rest of the edit was saved")
        assertEquals("/some/ca.pem", saved().caCertificatePath, "the old valid path stays")
        assertEquals(CaCertificateValidationResult.Invalid("not a certificate"), vm.state.value.lastCaCertificateValidationResult)
    }

    @Test
    fun `a CA path is stored with TLS off too, and then the validator is not asked (O3)`() {
        viewModel.addConnection(inputWithCa("/some/ca.pem", tls = false))
        assertEquals("/some/ca.pem", saved().caCertificatePath, "kept but unused")
        assertEquals(emptyList(), validator.validated, "with TLS off nothing is validated")
    }

    @Test
    fun `an update that changes the CA path closes the held connection with the settings note`() {
        viewModel.addConnection(input(tls = true))
        h.secrets.store("uat-eu", PASSWORD)
        runBlocking { withTimeout(5_000) { viewModel.connect("uat-eu") } }
        assertIs<LinkState.Connected>(h.active.stateOf("uat-eu"))

        viewModel.updateConnection("uat-eu", inputWithCa("/some/ca.pem", tls = true))

        assertEquals("Connection settings changed. Connect again.", assertIs<LinkState.Disconnected>(h.active.stateOf("uat-eu")).note)
        assertTrue(h.gateway.heldNow.isEmpty(), "the connection that trusted the old CA is closed")
    }
}
