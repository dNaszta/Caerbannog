package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * M2-03 part 2 verify, mutation (b) gap: decision D7 says the stream client does NOT check the host name (Netty 4.2
 * does by default, `StreamEnvironments` clears it). The Testcontainers certificate has SAN localhost/127.0.0.1 so
 * a broker test cannot show a mismatch being tolerated. This test can: a local TLS server whose certificate names
 * a host we do not connect to, trusted as the profile CA. The only thing asserted is the TLS handshake on the
 * SERVER side (what comes after, the stream protocol, is not spoken and fails by design). No Docker; needs the
 * JDK's `keytool` next to the running JVM.
 */
class StreamHostNameToleranceTest {

    @Test
    fun `a certificate for another host name is accepted when the profile CA trusts it`(@TempDir dir: Path) {
        val keystore = File(dir.toFile(), "server.p12")
        val cert = File(dir.toFile(), "server.pem")
        keytool("-genkeypair", "-keyalg", "RSA", "-keysize", "2048", "-alias", "srv", "-dname", "CN=some-other-host.invalid",
            "-ext", "san=dns:some-other-host.invalid", "-validity", "2", "-keystore", keystore.path, "-storetype", "PKCS12", "-storepass", "changeit")
        keytool("-exportcert", "-rfc", "-alias", "srv", "-keystore", keystore.path, "-storepass", "changeit", "-file", cert.path)

        val ks = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val server = context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        val handshake = CompletableFuture<Boolean>()
        val thread = Thread {
            try {
                (server.accept() as SSLSocket).use { it.startHandshake(); handshake.complete(true) }
            } catch (e: Exception) {
                handshake.complete(false)
            }
        }.apply { isDaemon = true; start() }

        val profile = ConnectionProfile(
            name = "hn", host = "127.0.0.1", virtualHost = "/", username = "u",
            streamPort = server.localPort, managementPort = 1, caCertificatePath = cert.path,
        )
        try {
            StreamEnvironments(profile, "pw", BrokerTransport.Tls(TlsSupport.trustManagerFactory(cert.path))).use {
                runCatching { it.read("/", "s") } // the stream protocol is not spoken, so this fails; only the handshake counts
            }
            assertEquals(true, handshake.get(30, TimeUnit.SECONDS), "the TLS handshake must complete although the certificate names another host (D7)")
        } finally {
            server.close()
            thread.join(5000)
        }
        assertTrue(handshake.isDone)
    }

    private fun keytool(vararg args: String) {
        val exe = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "keytool failed: $out" }
    }
}
