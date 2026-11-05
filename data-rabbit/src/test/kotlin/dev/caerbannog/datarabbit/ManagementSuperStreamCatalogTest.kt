package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.SuperStreamLayout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M2-07 step 2 (no Docker): the REAL gateway and catalog against a local plain management server serving the recorded S-A bodies:
 * `superStreams(vhost)` makes exactly two GETs (exchanges, then bindings), nothing else, and maps failures to values.
 * The stream port is closed on purpose: nothing here may need it.
 */
class ManagementSuperStreamCatalogTest {

    private class Store : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = "pw-not-a-secret"
        override fun delete(profileName: String) = Unit
    }

    private val whoami = "/api/whoami" to (200 to """{"name":"svc","tags":["monitoring"]}""")
    private val exchangesPath = "/api/exchanges/%2F"
    private val bindingsPath = "/api/bindings/%2F"
    private val exchanges = FixtureFiles.text("m2-07/exchanges-vhost-regions.json")
    private val bindings = FixtureFiles.text("m2-07/bindings-vhost-regions.json")

    private fun <T> withCatalog(server: PlainManagementServer, block: suspend (dev.caerbannog.core.streams.StreamCatalog) -> T): T {
        val profile = ConnectionProfile(
            name = "ss", host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = closedLocalPort(), managementPort = server.port, tls = false,
        )
        val opened = runBlocking { withTimeout(30_000) { RabbitMqConnectionGateway(Store()) { }.open(profile) } }
        assertInstanceOf(OpenResult.Opened::class.java, opened, "got $opened")
        val connection = (opened as OpenResult.Opened).connection
        try {
            return runBlocking { withTimeout(30_000) { block(connection.catalog) } }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `superStreams makes two GET calls, the exchanges then the bindings, and parses them`() {
        PlainManagementServer(mapOf(whoami, exchangesPath to (200 to exchanges), bindingsPath to (200 to bindings))).use { server ->
            val result = withCatalog(server) { it.superStreams("/") }

            assertInstanceOf(CatalogResult.Success::class.java, result, "got $result")
            @Suppress("UNCHECKED_CAST")
            val layouts = (result as CatalogResult.Success<List<SuperStreamLayout>>).value.associateBy { it.name }
            assertEquals(listOf("regions-us", "regions-eu", "regions-apac"), layouts.getValue("regions").partitions)
            val calls = server.seen.filter { it.rawPath != "/api/whoami" }
            assertEquals(listOf("GET $exchangesPath", "GET $bindingsPath"), calls.map { "${it.method} ${it.rawPath}" }, "calls: $calls")
        }
    }

    // Architect ruling 1: no exchange with the marker (direct + boolean true) means no bindings call.
    @Test
    fun `without a marker exchange the layout is empty and only the exchanges are asked`() {
        val plainExchanges = """[{"name":"","vhost":"/","type":"direct","arguments":{}},{"name":"f","vhost":"/","type":"fanout","arguments":{"x-super-stream":true}},""" +
            """{"name":"s","vhost":"/","type":"direct","arguments":{"x-super-stream":"true"}}]"""
        PlainManagementServer(mapOf(whoami, exchangesPath to (200 to plainExchanges), bindingsPath to (200 to bindings))).use { server ->
            val result = withCatalog(server) { it.superStreams("/") }

            assertEquals(CatalogResult.Success(emptyList<SuperStreamLayout>()), result)
            val calls = server.seen.filter { it.rawPath != "/api/whoami" }
            assertEquals(listOf("GET $exchangesPath"), calls.map { "${it.method} ${it.rawPath}" }, "calls: $calls")
        }
    }

    @Test
    fun `a user without the management tag gets NotAuthorised from the 401 and no layout`() {
        val denied = 401 to """{"error":"not_authorised","reason":"Not management user"}"""
        PlainManagementServer(mapOf(whoami, exchangesPath to denied, bindingsPath to denied)).use { server ->
            assertEquals(CatalogResult.NotAuthorised, withCatalog(server) { it.superStreams("/") })
        }
    }

    @Test
    fun `a failing bindings call fails the layout as a whole, never half a layout`() {
        PlainManagementServer(mapOf(whoami, exchangesPath to (200 to exchanges), bindingsPath to (500 to """{"error":"x"}"""))).use { server ->
            val result = withCatalog(server) { it.superStreams("/") }
            assertTrue(result is CatalogResult.Failure, "got $result")
        }
    }

    @Test
    fun `a body that is not what the spec records is Unexpected`() {
        PlainManagementServer(mapOf(whoami, exchangesPath to (200 to "<html>proxy</html>"), bindingsPath to (200 to bindings))).use { server ->
            assertEquals(CatalogResult.Unexpected, withCatalog(server) { it.superStreams("/") })
        }
    }

    @Test
    fun `a missing path is a failure, not a crash`() {
        PlainManagementServer(mapOf(whoami)).use { server ->
            val result = withCatalog(server) { it.superStreams("/") }
            assertTrue(result is CatalogResult.Failure, "got $result")
        }
    }
}
