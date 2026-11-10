package dev.caerbannog.datarabbit

import com.sun.net.httpserver.HttpServer
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * M8-02b scope 3: the real gateway hands `brokerVersion` (the displayable text of the one overview read) and, ONLY for a known version below 4,
 * `threeXReads` to the held connection. 4.x, an unknown version and an odd text get no reads, so a 4.x broker can never be read by the live source.
 */
class RabbitMqConnectionGatewayLiveSourceTest {

    private class Store : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = "pw"
        override fun delete(profileName: String) = Unit
    }

    private var server: HttpServer? = null
    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach fun stop() { opened.forEach { runCatching { it.close() } }; server?.stop(0) }

    private fun open(overview: Pair<Int, String>): HeldConnection {
        server?.stop(0)
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 10)
        s.createContext("/") { ex ->
            val (status, body) = when (ex.requestURI.rawPath) {
                "/api/whoami" -> 200 to """{"name":"svc","tags":["administrator"]}"""
                "/api/overview" -> overview
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        s.start(); server = s
        val profile = ConnectionProfile(name = "live", host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = closedLocalPort(), managementPort = s.address.port, tls = false)
        val result = runBlocking { withTimeout(60_000) { RabbitMqConnectionGateway(Store()).open(profile) } }
        val held = assertInstanceOf(OpenResult.Opened::class.java, result, "the connection itself must open whatever the version: $result").connection
        opened += held
        return held
    }

    private fun overview(version: String) = 200 to """{"rabbitmq_version":${JsonPrimitive(version)},"erlang_version":"26"}"""

    @Test
    fun `a 3x broker gives the version and the reads`() {
        for (version in listOf("3.13.7", "3.9", "3.12.0-rc.1", "2.8")) {
            val held = open(overview(version))
            assertEquals(version, held.brokerVersion, version)
            assertInstanceOf(ManagementThreeXReads::class.java, held.threeXReads, "reads for $version")
        }
    }

    @Test
    fun `a 4x broker gives the version and no reads`() {
        for (version in listOf("4.4.0", "4.0", "10.1")) {
            val held = open(overview(version))
            assertEquals(version, held.brokerVersion, version)
            assertNull(held.threeXReads, "a 4.x broker must never be handed read access of the live source ($version)")
        }
    }

    @Test
    fun `an unknown or odd version gives no version and no reads`() {
        val cases = listOf(
            overview("3 anything\nx"), overview("3." + "1".repeat(40)), overview(""), overview("abc"),
            200 to """{"erlang_version":"26"}""", 200 to "not json", 500 to "boom", 401 to "{}",
        )
        for (case in cases) {
            val held = open(case)
            assertNull(held.brokerVersion, "version for $case")
            assertNull(held.threeXReads, "reads for $case")
        }
    }

    @Test
    fun `the version text is trimmed`() {
        val held = open(overview(" 3.13.7 "))
        assertEquals("3.13.7", held.brokerVersion)
        assertInstanceOf(ManagementThreeXReads::class.java, held.threeXReads)
    }
}
