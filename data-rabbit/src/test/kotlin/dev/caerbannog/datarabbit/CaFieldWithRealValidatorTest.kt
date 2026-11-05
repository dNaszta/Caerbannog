package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileRepository
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.SecretStore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M2-05 round 2, F1: the view model wired to the REAL validator (the core test cannot reach data-rabbit).
 * Typing "/" in the CA field calls validateCaCertificateFile("/") on every keystroke; nothing may escape and
 * the state the screen reads (lastCaCertificateValidationResult) must carry the error text.
 */
class CaFieldWithRealValidatorTest {

    private val viewModel = ConnectionsViewModel(
        object : ConnectionProfileRepository {
            override fun list(): List<ConnectionProfile> = emptyList()
            override fun add(profile: ConnectionProfile): SaveConnectionResult = error("unused")
            override fun update(profile: ConnectionProfile): SaveConnectionResult = error("unused")
            override fun delete(name: String) = error("unused")
        },
        object : SecretStore {
            override fun store(profileName: String, password: String) = error("unused")
            override fun retrieve(profileName: String): String? = null
            override fun delete(profileName: String) = error("unused")
        },
        object : BrokerReachabilityChecker {
            override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult = error("unused")
        },
        X509CaCertificateValidator(),
    )

    @Test
    fun `typing a slash into the CA field shows the could-not-be-read text and throws nothing`() {
        for (typed in listOf("/", "/tmp", "/tmp/")) {
            val result = try {
                viewModel.validateCaCertificateFile(typed)
            } catch (e: Throwable) {
                fail("validateCaCertificateFile(\"$typed\") must not throw, but threw $e")
            }
            assertEquals(CaCertificateValidationResult.Invalid("the file could not be read"), result, "typed \"$typed\"")
            assertEquals(result, viewModel.state.value.lastCaCertificateValidationResult, "state after \"$typed\"")
        }
    }
}
