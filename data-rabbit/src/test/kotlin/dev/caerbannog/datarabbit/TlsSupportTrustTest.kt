package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * M2-03 part 2 (no Docker): the stream client builds its trust from the SAME source as the management client
 * (`TlsSupport`, brief: "Build it from the same KeyStore ... no trust-all"), and a wrong CA is refused. The spike
 * control for the real Environment is the @broker test; this one pins the shared trust source without a server.
 * Contract: `TlsSupport.trustManagerFactory(caCertificatePath: String?): javax.net.ssl.TrustManagerFactory`
 * (`buildSslContext` and the stream client's `SslContextBuilder.trustManager(...)` both use it).
 * Fixtures: `tls/server.pem` is issued by `tls/ca.pem` (CN=Caerbannog Test CA); `certs/valid-certificate.pem` is an unrelated self-signed CA.
 */
class TlsSupportTrustTest {

    private fun resource(path: String) = File(javaClass.classLoader.getResource(path)!!.toURI()).absolutePath

    private fun chain(path: String): Array<X509Certificate> =
        File(resource(path)).inputStream().use { arrayOf(CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate) }

    private fun trust(caPath: String?): X509TrustManager =
        TlsSupport.trustManagerFactory(caPath).trustManagers.filterIsInstance<X509TrustManager>().single()

    @Test
    fun `the profile CA trusts a server certificate it issued`() {
        trust(resource("tls/ca.pem")).checkServerTrusted(chain("tls/server.pem"), "RSA")
    }

    @Test
    fun `a wrong CA refuses the server certificate`() {
        assertThrows<CertificateException> { trust(resource("certs/valid-certificate.pem")).checkServerTrusted(chain("tls/server.pem"), "RSA") }
    }

    @Test
    fun `with a profile CA the JVM default roots are not used, so a CA of its own is the only anchor`() {
        val anchors = trust(resource("tls/ca.pem")).acceptedIssuers
        assertTrue(anchors.size == 1, "only the profile CA, not the system roots: ${anchors.map { it.subjectX500Principal }}")
    }

    @Test
    fun `without a profile CA the default roots refuse a private CA's certificate`() {
        assertThrows<CertificateException> { trust(null).checkServerTrusted(chain("tls/server.pem"), "RSA") }
    }
}
