package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.StreamListRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Collections

/**
 * M2-14a step 2 (no Docker): the held connection over plain (the real `RabbitMqConnectionGateway`, `ManagementRequests`, the management
 * client) against a local plain management server, plus the failures a TLS-off profile must NOT show (certificate texts) and a TLS-on
 * profile against a plain broker (refused, nothing sent). The password reaches the broker only as the Basic header of a plain call
 * (that is what the "Not encrypted" warning is about) and appears nowhere else.
 */
class RabbitMqConnectionGatewayPlainTest {

    private val password = "Hunter2-do-not-log-4711"
    private val lines: MutableList<String> = Collections.synchronizedList(ArrayList())

    private class Store(private val password: String) : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = password
        override fun delete(profileName: String) = Unit
    }

    private fun profile(management: Int, tls: Boolean, ca: String? = null, stream: Int = closedLocalPort()) = ConnectionProfile(
        name = "gw-plain", host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = stream, managementPort = management,
        tls = tls, caCertificatePath = ca,
    )

    private fun open(profile: ConnectionProfile): OpenResult =
        runBlocking { withTimeout(30_000) { RabbitMqConnectionGateway(Store(password)) { lines += it }.open(profile) } }

    private val whoami = "/api/whoami" to (200 to """{"name":"svc","tags":["monitoring"]}""")

    private fun stringsOf(result: OpenResult) = result.toString() + lines

    @Test
    fun `a plain profile opens against a plain management API and sends the password only as the Basic header`() {
        PlainManagementServer(mapOf(whoami)).use { server ->
            val result = open(profile(server.port, tls = false))

            val opened = assertInstanceOf(OpenResult.Opened::class.java, result, "got $result")
            try {
                val request = server.seen.single { it.rawPath == "/api/whoami" }
                assertEquals("Basic " + Base64.getEncoder().encodeToString("svc:$password".toByteArray()), request.authorization)
                assertFalse(stringsOf(result).contains(password), "password in the result or log: ${stringsOf(result)}")
            } finally {
                opened.connection.close()
            }
        }
    }

    @Test
    fun `a plain profile ignores a CA path that does not exist`() {
        PlainManagementServer(mapOf(whoami)).use { server ->
            val result = open(profile(server.port, tls = false, ca = "/no/such/ca.pem"))
            assertInstanceOf(OpenResult.Opened::class.java, result, "a CA path must not matter without TLS, got $result")
            (result as OpenResult.Opened).connection.close()
        }
    }

    @Test
    fun `the held plain connection lists streams over http`() {
        val page = FixtureFiles.text("m2-02/queues-page-one-stream.json")
        PlainManagementServer(mapOf(whoami, "/api/queues/%2F" to (200 to page))).use { server ->
            val opened = open(profile(server.port, tls = false)) as OpenResult.Opened
            try {
                val result = runBlocking { withTimeout(30_000) { opened.connection.catalog.listStreams(StreamListRequest(vhost = "/")) } }

                assertInstanceOf(CatalogResult.Success::class.java, result, "got $result")
                assertTrue(server.seen.any { it.rawPath == "/api/queues/%2F" }, "the list must be asked over the plain base: ${server.seen}")
            } finally {
                opened.connection.close()
            }
        }
    }

    @Test
    fun `a plain profile that the broker refuses is credentials refused`() {
        PlainManagementServer(mapOf("/api/whoami" to (401 to """{"error":"not_authorised","reason":"Login failed"}"""))).use { server ->
            assertEquals(OpenResult.Failed(ConnectionFailureReason.CREDENTIALS_REFUSED), open(profile(server.port, tls = false)))
        }
    }

    @Test
    fun `a plain profile on a closed port is broker not reachable, never a certificate failure, and leaves no password`() {
        val result = open(profile(closedLocalPort(), tls = false))

        assertEquals(OpenResult.Failed(ConnectionFailureReason.BROKER_NOT_REACHABLE), result)
        assertFalse(stringsOf(result).contains(password), stringsOf(result))
    }

    @Test
    fun `a plain profile never fails with the certificate reason, whatever the CA path says`() {
        listOf("/no/such/ca.pem", "/", "").forEach { ca ->
            val result = open(profile(closedLocalPort(), tls = false, ca = ca))
            assertFalse(result is OpenResult.Failed && result.reason == ConnectionFailureReason.CERTIFICATE_NOT_TRUSTED, "ca='$ca': $result")
            assertFalse(result is OpenResult.Failed && result.reason == ConnectionFailureReason.NO_TLS_ANSWER, "ca='$ca': the TLS hint with TLS off: $result")
        }
    }

    @Test
    fun `a TLS profile against a plain management port is refused and no HTTP request with the password reaches it`() {
        PlainManagementServer(mapOf(whoami)).use { server ->
            val result = open(profile(server.port, tls = true))

            assertInstanceOf(OpenResult.Failed::class.java, result, "TLS on must not fall back to plain, got $result")
            assertEquals(emptyList<PlainManagementServer.Seen>(), server.seen, "the plain port saw a request: no fallback, nothing in clear text")
            assertFalse(stringsOf(result).contains(password), stringsOf(result))
        }
    }

    @Test
    fun `a TLS profile against a plain broker says the broker may not use TLS, sends nothing and keeps the password out`() {
        // The plain server answers the ClientHello with 400 like the real broker does (measured against the dev broker, brief M2-14c).
        PlainManagementServer(mapOf(whoami), answerGarbageWith400 = true).use { server ->
            val result = open(profile(server.port, tls = true))

            assertEquals(OpenResult.Failed(ConnectionFailureReason.NO_TLS_ANSWER), result)
            assertEquals(emptyList<PlainManagementServer.Seen>(), server.seen, "no request, so no Authorization header, reached the plain port")
            assertFalse(stringsOf(result).contains(password), stringsOf(result))
            assertFalse(lines.joinToString("\n").contains("Basic "), lines.toString())
        }
    }

    @Test
    fun `a TLS profile with nothing listening is broker not reachable, not a TLS hint`() {
        assertEquals(OpenResult.Failed(ConnectionFailureReason.BROKER_NOT_REACHABLE), open(profile(closedLocalPort(), tls = true)))
    }

    @Test
    fun `a failed plain open logs nothing with the password or an authorization header`() {
        open(profile(closedLocalPort(), tls = false))
        val text = lines.joinToString("\n")
        assertFalse(text.contains(password), text)
        assertFalse(text.contains("Basic "), text)
    }
}
