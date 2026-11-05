package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M2-14a step 2 (no Docker): what the stream client really puts on the wire, seen by a local TCP server that speaks nothing.
 * Plain (`BrokerTransport.Plain`): the first bytes are a stream protocol frame (a 4 byte length that starts with 0x00), never a TLS
 * ClientHello (0x16 0x03). TLS on against the same plain port: the first bytes ARE a ClientHello, the read fails, and nothing else
 * (no username, no password, no stream frame) was sent: the app never falls back to plain on its own.
 * Contract: `StreamEnvironments(profile, password, transport: BrokerTransport, environmentFactory = ...)` (the third parameter was a
 * `TrustManagerFactory` before M2-14).
 */
class StreamEnvironmentsPlainTest {

    private val password = "Hunter2-do-not-send-4711"

    private fun profile(port: Int, tls: Boolean) = ConnectionProfile(
        name = "plain-test", host = "127.0.0.1", virtualHost = "/", username = "svc-plain", streamPort = port, managementPort = 1, tls = tls,
    )

    private fun readFailure(profile: ConnectionProfile, transport: BrokerTransport): Throwable? =
        StreamEnvironments(profile, password, transport).use { runCatching { it.read("/", "s") }.exceptionOrNull() }

    @Test
    fun `a plain environment sends a stream frame and no TLS ClientHello`() {
        RecordingSocketServer().use { server ->
            readFailure(profile(server.port, tls = false), BrokerTransport.Plain)

            val first = server.firstBytes.firstOrNull { it.isNotEmpty() }
            assertTrue(first != null, "the plain client never sent anything to the plain stream port")
            assertEquals(0x00.toByte(), first!![0], "a stream frame starts with its 4 byte length, bytes: ${first.take(8)}")
            assertFalse(first.size >= 2 && first[0] == 0x16.toByte() && first[1] == 0x03.toByte(), "a TLS ClientHello was sent on a plain profile")
        }
    }

    @Test
    fun `a plain environment against a closed port fails with an exception that does not contain the password`() {
        val failure = readFailure(profile(closedLocalPort(), tls = false), BrokerTransport.Plain)

        assertTrue(failure != null, "the read must fail, nothing listens")
        var cause: Throwable? = failure
        while (cause != null) {
            assertFalse(cause.message.orEmpty().contains(password), "password in ${cause.javaClass.name}: ${cause.message}")
            cause = cause.cause
        }
    }

    @Test
    fun `a TLS environment against a plain port is refused and sends only a ClientHello`() {
        RecordingSocketServer().use { server ->
            val failure = readFailure(profile(server.port, tls = true), BrokerTransport.Tls(TlsSupport.trustManagerFactory(null)))

            assertTrue(failure != null, "a TLS client must not get a usable environment from a plain port")
            val first = server.firstBytes.firstOrNull { it.isNotEmpty() }
            assertTrue(first != null, "the TLS client never connected")
            assertEquals(0x16.toByte(), first!![0], "TLS record type handshake, bytes: ${first.take(8)}")
            assertEquals(0x03.toByte(), first[1])
            // Nothing in clear text: neither the user, the password nor a stream frame followed on a plain connection.
            val everything = server.firstBytes.joinToString("") { String(it, Charsets.ISO_8859_1) }
            assertFalse(everything.contains(password) || everything.contains("svc-plain"), "credentials went over a failed TLS attempt")
            assertEquals(1, server.firstBytes.size, "no second, plain attempt (no fallback): ${server.firstBytes.map { it.size }}")
        }
    }

    @Test
    fun `a plain environment sends the user name only as part of the stream protocol, never the password in the clear before authentication`() {
        // The stream protocol authenticates after the peer properties exchange; the first frame carries neither.
        RecordingSocketServer().use { server ->
            readFailure(profile(server.port, tls = false), BrokerTransport.Plain)
            val everything = server.firstBytes.joinToString("") { String(it, Charsets.ISO_8859_1) }
            assertFalse(everything.contains(password), "the password was sent before the server answered anything")
        }
    }
}
