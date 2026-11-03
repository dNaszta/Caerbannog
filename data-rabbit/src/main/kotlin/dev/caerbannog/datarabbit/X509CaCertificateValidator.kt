package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.CaCertificateValidator
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory

/**
 * Real X.509 parsing for CA certificate files (docs/tasks/M1-02-connection-profiles.md Scope: "CA
 * certificate handling: ... rejected up front if the file is not a valid certificate"). Mirrors
 * `acceptance-tests`' `FakeCaCertificateValidator` (same `java.security.cert.CertificateFactory`
 * parsing), which exists separately only so `01_connections.feature`'s step definitions do not need a
 * dependency on this module.
 */
class X509CaCertificateValidator : CaCertificateValidator {

    override fun validate(filePath: String): CaCertificateValidationResult {
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) {
            return CaCertificateValidationResult.Invalid("the file could not be read")
        }
        return try {
            file.inputStream().use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input)
            }
            CaCertificateValidationResult.Valid
        } catch (e: CertificateException) {
            CaCertificateValidationResult.Invalid("the file is not a valid certificate")
        }
    }
}
