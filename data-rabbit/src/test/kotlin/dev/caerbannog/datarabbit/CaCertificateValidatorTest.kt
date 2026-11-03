package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.CaCertificateValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * docs/tasks/M1-02-connection-profiles.md, Scope: "CA certificate handling: ... rejected up front if
 * the file is not a valid certificate ... stored as a file-path reference in the profile JSON."
 *
 * Mirrors acceptance-tests' FakeCaCertificateValidator (same real java.security.cert.CertificateFactory
 * parsing, same two fixtures), but exercises the actual `data-rabbit` production implementation
 * instead of the acceptance-tests-local stand-in that exists so 01_connections.feature's step
 * definitions do not need a dependency on this module. Both are expected to agree.
 *
 * Expected to fail to compile right now: dev.caerbannog.datarabbit.X509CaCertificateValidator (the
 * real implementation this task adds) does not exist yet, and neither does
 * dev.caerbannog.core.connections.CaCertificateValidator/CaCertificateValidationResult in `core`.
 */
class CaCertificateValidatorTest {

    private val validator = X509CaCertificateValidator()

    @Test
    fun `a real X509 certificate file is accepted`() {
        val path = fixturePath("certs/valid-certificate.pem")

        assertEquals(CaCertificateValidationResult.Valid, validator.validate(path))
    }

    @Test
    fun `a file that is not a certificate is rejected with a message naming the problem`() {
        val path = fixturePath("certs/not-a-certificate.txt")

        val result = validator.validate(path) as? CaCertificateValidationResult.Invalid
            ?: fail("Expected the file to be rejected, got ${validator.validate(path)}")
        assertTrue(result.reason.contains("valid certificate", ignoreCase = true))
    }

    @Test
    fun `a missing file is rejected`() {
        val result = validator.validate("/no/such/file/ever-${System.nanoTime()}.pem")

        assertTrue(result is CaCertificateValidationResult.Invalid)
    }

    private fun fixturePath(resource: String): String {
        val url = javaClass.classLoader.getResource(resource) ?: error("Missing test fixture: $resource")
        return java.io.File(url.toURI()).absolutePath
    }
}
