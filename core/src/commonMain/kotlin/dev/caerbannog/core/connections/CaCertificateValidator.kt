package dev.caerbannog.core.connections

/** Result of validating a file as a usable CA certificate. */
sealed interface CaCertificateValidationResult {
    data object Valid : CaCertificateValidationResult
    data class Invalid(val reason: String) : CaCertificateValidationResult
}

/**
 * Validates that a file at [filePath] is a readable X.509 certificate, without `core`'s commonMain
 * ever importing `java.security.cert` (a JVM-only type `core` must stay free of, since it is declared
 * as a Kotlin Multiplatform module for more than the `jvm()` target even though only `jvm()` exists
 * today). The real X.509 parsing lives only in `data-rabbit`'s `X509CaCertificateValidator`.
 */
interface CaCertificateValidator {
    fun validate(filePath: String): CaCertificateValidationResult
}
