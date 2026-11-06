package dev.caerbannog.core.connections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2-14a step 2: the wording of the Test connection result (brief "UX", "Test connection wording"). The texts that exist today are pinned
 * as literals so moving them out of the UI cannot change them. Red until `checkResultMessage` is built.
 */
class ConnectionCheckTextsTest {

    private val results = listOf(
        ConnectionCheckResult.BothReachable,
        ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, "connection refused"),
        ConnectionCheckResult.EndpointUnreachable(Endpoint.MANAGEMENT, "timed out connecting"),
        ConnectionCheckResult.ManagementPluginMissing("rabbitmq_stream_management"),
    )

    @Test
    fun `TLS on keeps today's texts`() {
        assertEquals(
            "Connection test passed. The stream endpoint and the management API are both reachable.",
            checkResultMessage(ConnectionCheckResult.BothReachable, tls = true),
        )
        assertEquals(
            "The stream endpoint could not be reached: connection refused",
            checkResultMessage(ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, "connection refused"), tls = true),
        )
        assertEquals(
            "The management endpoint could not be reached: timed out connecting",
            checkResultMessage(ConnectionCheckResult.EndpointUnreachable(Endpoint.MANAGEMENT, "timed out connecting"), tls = true),
        )
        assertEquals(
            "Enable the \"rabbitmq_stream_management\" plugin on the broker.",
            checkResultMessage(ConnectionCheckResult.ManagementPluginMissing("rabbitmq_stream_management"), tls = true),
        )
    }

    @Test
    fun `TLS on against a plain broker gives the hint`() {
        assertEquals(
            "The broker did not answer the TLS handshake. It may not use TLS; turn off 'Use TLS' in this connection's settings if that is intended.",
            checkResultMessage(ConnectionCheckResult.TlsRefused, tls = true),
        )
    }

    // --- M2-14f: untrusted certificate on Check says "The certificate was not trusted" --------------------------

    @Test
    fun `untrusted certificate on Check says certificate was not trusted and does not suggest turning TLS off`() {
        val text = checkResultMessage(ConnectionCheckResult.CertificateRejected, tls = true)
        assertEquals(ConnectionFailureReason.CERTIFICATE_NOT_TRUSTED, text)
        assertFalse(text.contains("may not use TLS"), "should not suggest turning off TLS for a certificate failure: $text")
        assertFalse(text.contains("turn off 'Use TLS'"), "should not suggest turning off TLS: $text")
    }


    @Test
    fun `the old refusal text is gone`() {
        assertFalse(checkResultMessage(ConnectionCheckResult.TlsRefused, tls = true).contains("does not connect without TLS"))
    }

    @Test
    fun `the success text with TLS on does not mention TLS or encryption`() {
        val text = checkResultMessage(ConnectionCheckResult.BothReachable, tls = true)
        assertFalse(text.contains("TLS"), text)
        assertFalse(text.contains("encrypt", ignoreCase = true), text)
    }

    @Test
    fun `TLS off success says both endpoints are reachable and the connection is not encrypted`() {
        assertEquals(
            "Both endpoints are reachable. This connection is not encrypted.",
            checkResultMessage(ConnectionCheckResult.BothReachable, tls = false),
        )
    }

    @Test
    fun `TLS off failures name the endpoint like today`() {
        assertEquals(
            "The stream endpoint could not be reached: connection refused",
            checkResultMessage(ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, "connection refused"), tls = false),
        )
        assertEquals(
            "The management endpoint could not be reached: timed out connecting",
            checkResultMessage(ConnectionCheckResult.EndpointUnreachable(Endpoint.MANAGEMENT, "timed out connecting"), tls = false),
        )
    }

    @Test
    fun `with TLS off no text mentions TLS or a certificate`() {
        results.forEach { result ->
            val text = checkResultMessage(result, tls = false)
            assertFalse(text.contains("TLS", ignoreCase = true), "TLS in '$text'")
            assertFalse(text.contains("certificate", ignoreCase = true), "certificate in '$text'")
        }
    }

    @Test
    fun `the reason of a failed endpoint is passed through verbatim at the end`() {
        assertTrue(checkResultMessage(ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, "R-EASON"), tls = false).endsWith("R-EASON"))
    }
}
