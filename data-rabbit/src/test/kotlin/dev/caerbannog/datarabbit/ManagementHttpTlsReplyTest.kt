package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * M2-14c step 2 (no Docker): the TYPED reply of the management client for each way a TLS-on request can fail before any HTTP is spoken.
 * One test per case measured in the brief's Status (real dev broker, JDK 21 HttpClient):
 *  - plain broker (Cowboy answers 400 to the ClientHello): bare `SSLException "Unrecognized SSL message, plaintext connection?"`, no cause;
 *  - wrong or missing CA: `SSLHandshakeException` with a `ValidatorException` (a `CertificateException`) cause;
 *  - host name mismatch: D7 says tolerated (spec "Connection lifecycle", "the host name is not verified"); MEASURED: the management
 *    client refuses it with `SSLHandshakeException` caused by a `CertificateException` ("No subject alternative names matching ..."),
 *    so the test "a certificate for another host name is tolerated" is red today, independent of M2-14c (finding for the architect);
 *  - nothing listening: `java.net.ConnectException`.
 * Names `ManagementReply.NoTlsAnswer` and `ManagementReply.CertificateRejected` are the brief's suggestion (the split of `TlsRefused`).
 * Red until the split is built (compile error until then).
 */
class ManagementHttpTlsReplyTest {

    private val caPath = File(javaClass.classLoader.getResource("tls/ca.pem")!!.toURI()).absolutePath

    private fun profile(port: Int, ca: String?) = ConnectionProfile(
        name = "reply", host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = 1, managementPort = port,
        tls = true, caCertificatePath = ca,
    )

    private fun get(port: Int, ca: String?): ManagementReply {
        val transport = BrokerTransport.from(profile(port, ca))
        return ManagementHttp(TlsSupport.buildHttpClient(transport, 10_000), "svc", "pw").use { http ->
            runBlocking { withTimeout(30_000) { http.get("https://127.0.0.1:$port/api/whoami") } }
        }
    }

    @Test
    fun `a plain broker that answers the ClientHello with 400 is NoTlsAnswer`() {
        PlainManagementServer(answerGarbageWith400 = true).use { server ->
            assertEquals(ManagementReply.NoTlsAnswer, get(server.port, caPath))
            assertEquals(ManagementReply.NoTlsAnswer, get(server.port, null), "the CA setting does not change what a plain port is")
            assertEquals(emptyList<PlainManagementServer.Seen>(), server.seen, "nothing HTTP reached the plain port")
        }
    }

    @Test
    fun `an untrusted certificate is CertificateRejected, with the profile CA or the JVM store`(@TempDir dir: Path) {
        TlsHttpsServer(dir).use { server ->
            assertEquals(ManagementReply.CertificateRejected, get(server.port, caPath), "a CA that did not sign the certificate")
            assertEquals(ManagementReply.CertificateRejected, get(server.port, null), "no CA: the JVM store does not know it either")
        }
    }

    @Test
    fun `a certificate for another host name is tolerated when the CA is trusted (D7)`(@TempDir dir: Path) {
        TlsHttpsServer(dir, commonName = "some-other-host.invalid").use { server ->
            val reply = get(server.port, server.certificatePath)
            assertInstanceOf(ManagementReply.Response::class.java, reply, "host name must not be verified on the management client, got $reply")
            assertEquals(200, (reply as ManagementReply.Response).status)
        }
    }

    @Test
    fun `nothing listening is Unreachable, not a TLS reply`() {
        assertEquals(ManagementReply.Unreachable, get(closedLocalPort(), caPath))
    }
}
