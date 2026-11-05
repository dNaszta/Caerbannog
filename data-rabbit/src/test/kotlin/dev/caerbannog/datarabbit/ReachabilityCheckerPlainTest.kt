package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.Endpoint
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M2-14a step 2 (no Docker): `StreamAndManagementReachabilityChecker` over plain and the no-fallback rule. Local plain servers stand in
 * for a broker with plain listeners (see [PlainManagementServer], [RecordingSocketServer]).
 */
class ReachabilityCheckerPlainTest {

    private val ok = mapOf("/api/overview" to (200 to "{}"), "/api/stream/connections" to (200 to "[]"))

    private fun profile(stream: Int, management: Int, tls: Boolean, ca: String? = null) = ConnectionProfile(
        name = "plain-test", host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = stream, managementPort = management,
        tls = tls, caCertificatePath = ca,
    )

    private fun check(profile: ConnectionProfile): ConnectionCheckResult =
        runBlocking { StreamAndManagementReachabilityChecker(connectTimeoutMillis = 2_000).check(profile) }

    @Test
    fun `plain endpoints that answer are both reachable`() {
        RecordingSocketServer().use { stream -> PlainManagementServer(ok).use { management ->
            assertEquals(ConnectionCheckResult.BothReachable, check(profile(stream.port, management.port, tls = false)))
            assertTrue(management.seen.any { it.rawPath == "/api/overview" && it.method == "GET" }, "management must be asked over http: ${management.seen}")
        } }
    }

    @Test
    fun `the check over plain sends no ClientHello to the stream port`() {
        RecordingSocketServer().use { stream -> PlainManagementServer(ok).use { management ->
            check(profile(stream.port, management.port, tls = false))
            assertTrue(stream.firstBytes.none { it.isNotEmpty() && it[0] == 0x16.toByte() }, "a TLS handshake was started on a plain profile")
        } }
    }

    @Test
    fun `the check over plain never sends a credential`() {
        RecordingSocketServer().use { stream -> PlainManagementServer(ok).use { management ->
            check(profile(stream.port, management.port, tls = false))
            assertTrue(management.seen.all { it.authorization == null }, "a reachability check has no password: ${management.seen}")
        } }
    }

    @Test
    fun `the CA path is ignored over plain, even a missing one`() {
        RecordingSocketServer().use { stream -> PlainManagementServer(ok).use { management ->
            assertEquals(
                ConnectionCheckResult.BothReachable,
                check(profile(stream.port, management.port, tls = false, ca = "/no/such/ca.pem")),
            )
        } }
    }

    @Test
    fun `plain with the stream port closed names the stream endpoint`() {
        PlainManagementServer(ok).use { management ->
            val result = check(profile(closedLocalPort(), management.port, tls = false))
            assertEquals(Endpoint.STREAM, (result as? ConnectionCheckResult.EndpointUnreachable)?.endpoint, "got $result")
        }
    }

    @Test
    fun `plain with the management port closed names the management endpoint`() {
        RecordingSocketServer().use { stream ->
            val result = check(profile(stream.port, closedLocalPort(), tls = false))
            assertEquals(Endpoint.MANAGEMENT, (result as? ConnectionCheckResult.EndpointUnreachable)?.endpoint, "got $result")
        }
    }

    @Test
    fun `plain with the stream plugin route missing says the plugin is missing`() {
        RecordingSocketServer().use { stream -> PlainManagementServer(mapOf("/api/overview" to (200 to "{}"))).use { management ->
            assertEquals(
                ConnectionCheckResult.ManagementPluginMissing("rabbitmq_stream_management"),
                check(profile(stream.port, management.port, tls = false)),
            )
        } }
    }

    @Test
    fun `plain failure reasons never mention a certificate or TLS`() {
        val result = check(profile(closedLocalPort(), closedLocalPort(), tls = false))
        val reason = (result as ConnectionCheckResult.EndpointUnreachable).reason
        assertFalse(reason.contains("certificate", ignoreCase = true) || reason.contains("TLS", ignoreCase = true), reason)
    }

    @Test
    fun `TLS on against a plain broker is refused, nothing else is sent and no plain attempt follows`() {
        RecordingSocketServer().use { stream -> PlainManagementServer(ok).use { management ->
            val result = check(profile(stream.port, management.port, tls = true))

            assertEquals(ConnectionCheckResult.TlsRefused, result)
            assertEquals(0x16.toByte(), stream.firstBytes.first { it.isNotEmpty() }[0], "only a TLS ClientHello reaches the plain stream port")
            assertEquals(1, stream.firstBytes.size, "no second, plain attempt on the stream port")
            assertEquals(emptyList<PlainManagementServer.Seen>(), management.seen, "no plain HTTP request after the TLS refusal (no fallback)")
        } }
    }

    @Test
    fun `TLS on against a plain management port with a plain stream port refuses on the first endpoint`() {
        // Even when only the management side is plain, the app never retries over http.
        PlainManagementServer(ok).use { management ->
            RecordingSocketServer().use { stream ->
                val result = check(profile(stream.port, management.port, tls = true))
                assertEquals(ConnectionCheckResult.TlsRefused, result)
                assertTrue(management.seen.none { it.rawPath.startsWith("/api") }, "plain HTTP was attempted: ${management.seen}")
            }
        }
    }
}
