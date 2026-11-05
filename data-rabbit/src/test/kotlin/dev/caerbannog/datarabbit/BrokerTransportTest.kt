package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * M2-14a step 2 (no Docker): `BrokerTransport.from(profile)` is the ONE place that turns the profile's TLS switch into a transport, so the
 * four call sites (check, permission check, held connection, error mapping) cannot each decide on their own (brief "Design notes").
 * Contract: `internal sealed interface BrokerTransport { val scheme: String; class Tls(val trust: TrustManagerFactory); data object Plain }`,
 * `BrokerTransport.from(profile)`. TLS on: `Tls` with the trust of [TlsSupport.trustManagerFactory] (an unreadable CA throws
 * `IOException` or `GeneralSecurityException`, as today). TLS off: `Plain`, the CA path is never read, so nothing can throw.
 */
class BrokerTransportTest {

    private fun resource(path: String) = File(javaClass.classLoader.getResource(path)!!.toURI()).absolutePath

    private val base = ConnectionProfile(name = "p", host = "h", virtualHost = "/", username = "u")

    @Test
    fun `TLS on builds a TLS transport on https`() {
        val transport = BrokerTransport.from(base)
        assertInstanceOf(BrokerTransport.Tls::class.java, transport)
        assertEquals("https", transport.scheme)
    }

    @Test
    fun `TLS on with a CA builds the trust from that CA only`() {
        val transport = BrokerTransport.from(base.copy(caCertificatePath = resource("tls/ca.pem")))
        val tls = assertInstanceOf(BrokerTransport.Tls::class.java, transport)
        assertEquals(1, tls.trust.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().single().acceptedIssuers.size)
    }

    @Test
    fun `TLS off builds the plain transport on http`() {
        val transport = BrokerTransport.from(base.copy(tls = false))
        assertEquals(BrokerTransport.Plain, transport)
        assertEquals("http", transport.scheme)
    }

    @Test
    fun `TLS off never reads the CA path - a missing, a directory and a garbage file all give plain`(@TempDir dir: File) {
        val garbage = File(dir, "garbage.pem").apply { writeText("this is not a certificate") }
        listOf("/no/such/ca.pem", dir.path, garbage.path, "").forEach { path ->
            assertEquals(BrokerTransport.Plain, BrokerTransport.from(base.copy(tls = false, caCertificatePath = path)), "ca='$path'")
        }
    }

    @Test
    fun `TLS on with a CA that cannot be read still throws, so the failure stays visible`() {
        val e = assertThrows<Exception> { BrokerTransport.from(base.copy(caCertificatePath = "/no/such/ca.pem")) }
        assertTrue(e is IOException || e is GeneralSecurityException, "got $e")
    }
}
