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

    // --- M2-05 round 2, F1: the validator runs on every keystroke in the CA field and must never throw ---

    @Test
    fun `a directory path is rejected as unreadable and does not throw`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        assertUnreadable(dir.toString())
        assertUnreadable(dir.toString() + java.io.File.separator)
    }

    @Test
    fun `every prefix of a real path is rejected and never throws`() {
        val real = fixturePath("certs/valid-certificate.pem")
        val prefixes = mutableListOf("/", "", " ")
        var index = real.indexOf('/', 1)
        while (index > 0) {
            prefixes += real.substring(0, index) // no trailing slash
            prefixes += real.substring(0, index + 1) // trailing slash form
            index = real.indexOf('/', index + 1)
        }
        prefixes += real.dropLast(1) // a file name cut one character short
        prefixes += "/tmp"
        prefixes += "/tmp/"
        for (prefix in prefixes) {
            val result = try {
                validator.validate(prefix)
            } catch (e: Throwable) {
                fail("validate(\"$prefix\") must return Invalid, but threw $e")
            }
            assertTrue(result is CaCertificateValidationResult.Invalid, "validate(\"$prefix\") returned $result, expected Invalid")
        }
    }

    @Test
    fun `a path with a NUL character is rejected and does not throw`() {
        assertUnreadable("/tmp/ca\u0000.pem")
    }

    @Test
    fun `a file without read permission is rejected as unreadable and does not throw`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val file = dir.resolve("locked.pem").toFile()
        file.writeText("x")
        file.setReadable(false, false)
        org.junit.jupiter.api.Assumptions.assumeFalse(file.canRead(), "permissions are not enforced here (running as root?)")
        assertUnreadable(file.absolutePath)
    }

    private fun assertUnreadable(path: String) {
        val result = try {
            validator.validate(path)
        } catch (e: Throwable) {
            fail("validate(\"$path\") must return Invalid, but threw $e")
        }
        assertEquals(CaCertificateValidationResult.Invalid("the file could not be read"), result, "path \"$path\"")
    }

    private fun fixturePath(resource: String): String {
        val url = javaClass.classLoader.getResource(resource) ?: error("Missing test fixture: $resource")
        return java.io.File(url.toURI()).absolutePath
    }
}
