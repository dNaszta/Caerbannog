package dev.caerbannog.datarabbit

import java.security.cert.CertificateException

/**
 * Shared classifier for TLS failures across HTTP (management API) and raw SSLSocket (stream endpoint).
 * Tells untrusted certificate errors apart from handshake refusals / unencrypted endpoints.
 *
 * Rule: a [CertificateException] anywhere in the cause chain (walked up to [MAX_CAUSE_DEPTH] to handle
 * cause loops cleanly) indicates the certificate was rejected by the trust manager.
 */
internal object TlsExceptionClassifier {
    private const val MAX_CAUSE_DEPTH = 20

    fun hasCertificateCause(error: Throwable): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            if (cause is CertificateException) return true
            cause = cause.cause
            depth++
        }
        return false
    }


}
