package dev.caerbannog.datarabbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.security.cert.CertificateException
import java.time.Duration
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * M2-14c step 2c (architect ruling R2 (a) and (b), no network): how `ManagementHttp` classifies exceptions it is GIVEN, so shapes no
 * local server can produce are still pinned. The rule: `CertificateRejected` iff a `java.security.cert.CertificateException` is in the
 * cause chain, every other `SSLException` is `NoTlsAnswer` (deliberate: "did not answer the TLS handshake" is a conditional hint,
 * brief R2 (c)); the walk has a depth limit, so a cause loop ends. Red until the split is built (compile error until then).
 */
class ManagementHttpClassificationTest {

    private fun replyTo(failure: Throwable): ManagementReply {
        val client = HttpClient(MockEngine { throw failure })
        return ManagementHttp(client, "svc", "pw").use { http ->
            runBlocking { withTimeout(10_000) { http.get("https://broker.example:15671/api/whoami") } }
        }
    }

    @Test
    fun `a handshake failure without a certificate cause is NoTlsAnswer`() {
        // The shape of a server that closes during the handshake. Not measured against a broker; pinned on purpose (R2 b).
        assertEquals(ManagementReply.NoTlsAnswer, replyTo(SSLHandshakeException("Remote host terminated the handshake")))
    }

    @Test
    fun `a bare SSLException, the measured plain broker shape, is NoTlsAnswer`() {
        assertEquals(ManagementReply.NoTlsAnswer, replyTo(SSLException("Unrecognized SSL message, plaintext connection?")))
    }

    @Test
    fun `a certificate exception anywhere near the top of the cause chain is CertificateRejected`() {
        val inner = CertificateException("PKIX path building failed")
        val middle = RuntimeException("wrapper", inner)
        assertEquals(ManagementReply.CertificateRejected, replyTo(SSLHandshakeException("handshake").apply { initCause(middle) }))
    }

    @Test
    fun `a cause loop without a certificate ends and is NoTlsAnswer`() {
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            val a = SSLException("a")
            val b = RuntimeException("b")
            a.initCause(b)
            b.initCause(a) // a -> b -> a -> ...
            assertEquals(ManagementReply.NoTlsAnswer, replyTo(a))
        }
    }

    @Test
    fun `a very deep chain ends, whatever sits at the bottom`() {
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            var cause: Throwable = CertificateException("too deep to matter or not, but the walk must end")
            repeat(10_000) { cause = RuntimeException("level $it", cause) }
            val reply = replyTo(SSLHandshakeException("top").apply { initCause(cause) })
            // The depth limit decides which; only termination and a typed answer are pinned.
            assert(reply == ManagementReply.NoTlsAnswer || reply == ManagementReply.CertificateRejected) { "got $reply" }
        }
    }
}
