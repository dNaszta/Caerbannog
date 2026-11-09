package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.VhostRow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * M2-08b step 2 (no Docker): the REAL gateway hands out the topology of the held connection (`HeldConnection.topology`, the 08b wiring the 08a
 * tests left out): a `ManagementTopologyCatalog` over the SAME management client as the stream catalog, so the connection's credentials and
 * its lifetime are shared. Against a local plain management server: `vhosts()` is one GET of `/api/vhosts`, `queues` one GET of the queue list;
 * nothing else is called (the stream port is closed on purpose). RED on the stub: the default `topology` answers Unreachable and sends nothing.
 */
class RabbitMqConnectionGatewayTopologyTest {

    private class Store : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = "pw-not-a-secret"
        override fun delete(profileName: String) = Unit
    }

    private val whoami = "/api/whoami" to (200 to """{"name":"svc","tags":["monitoring"]}""")
    private val queuesBody = FixtureFiles.text("m2-08/queues-quorum-classic-stream-columns.json")

    private fun <T> withHeld(server: PlainManagementServer, block: suspend (HeldConnection) -> T): T {
        val profile = ConnectionProfile(
            name = "tp", host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = closedLocalPort(), managementPort = server.port, tls = false,
        )
        val opened = runBlocking { withTimeout(30_000) { RabbitMqConnectionGateway(Store()) { }.open(profile) } }
        assertInstanceOf(OpenResult.Opened::class.java, opened, "got $opened")
        val connection = (opened as OpenResult.Opened).connection
        try {
            return runBlocking { withTimeout(30_000) { block(connection) } }
        } finally {
            connection.close()
        }
    }

    /** The calls of the test itself: the probe (`whoami`) and the ONE version read of the connection (F3, `GET /api/overview`, never a write) are filtered out and checked. */
    private fun PlainManagementServer.calls(): List<String> {
        assertEquals(1, seen.count { it.rawPath == "/api/overview" && it.method == "GET" }, "exactly one GET /api/overview per connection: $seen")
        assertTrue(seen.filter { it.rawPath == "/api/overview" || it.rawPath == "/api/whoami" }.all { it.method == "GET" })
        return seen.filter { it.rawPath != "/api/whoami" && it.rawPath != "/api/overview" }.map { "${it.method} ${it.rawPath}" }
    }

    @Test
    fun `the held connection's topology reads the vhost list with one GET`() {
        PlainManagementServer(mapOf(whoami, "/api/vhosts" to (200 to FixtureFiles.text("m2-08/vhosts-multi.json")))).use { server ->
            val result = withHeld(server) { it.topology.vhosts() }
            assertEquals(CatalogResult.Success(listOf(VhostRow("/"), VhostRow("many"))), result)
            assertEquals(listOf("GET /api/vhosts"), server.calls())
        }
    }

    @Test
    fun `the held connection's topology reads a queue page with one GET and drops the stream`() {
        PlainManagementServer(mapOf(whoami, "/api/queues/%2F" to (200 to queuesBody))).use { server ->
            val result = withHeld(server) { it.topology.queues("/", 1) }
            assertInstanceOf(CatalogResult.Success::class.java, result, "got $result")
            assertEquals(listOf("dev-jobs", "spike-m208-classic", "spike-m208-quorum"), (result as CatalogResult.Success<QueuePage>).value.rows.map { it.name })
            assertEquals(listOf("GET /api/queues/%2F"), server.calls())
        }
    }

    @Test
    fun `a user without the management tag reads NotAuthorised through the held topology`() {
        val denied = 401 to """{"error":"not_authorised","reason":"Not management user"}"""
        PlainManagementServer(mapOf(whoami, "/api/vhosts" to denied)).use { server ->
            assertEquals(CatalogResult.NotAuthorised, withHeld(server) { it.topology.vhosts() })
        }
    }

    @Test
    fun `after close the held topology answers Unreachable and sends nothing`() {
        PlainManagementServer(mapOf(whoami, "/api/vhosts" to (200 to FixtureFiles.text("m2-08/vhosts-multi.json")))).use { server ->
            val profile = ConnectionProfile(
                name = "tp", host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = closedLocalPort(), managementPort = server.port, tls = false,
            )
            val held = (runBlocking { RabbitMqConnectionGateway(Store()) { }.open(profile) } as OpenResult.Opened).connection
            held.close()
            assertEquals(CatalogResult.Unreachable, runBlocking { held.topology.vhosts() })
            assertEquals(emptyList<String>(), server.calls())
        }
    }
}
