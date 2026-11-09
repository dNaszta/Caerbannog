package dev.caerbannog.datarabbit

import com.sun.net.httpserver.HttpServer
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M5-08a R1 (F3, fail closed): the real gateway makes ONE `GET /api/overview` after the management side answered, and gives the held connection a
 * migration executor only for a RabbitMQ major version of 4 or more. 3.x gives none and the fixed reason "migration source only"; anything that
 * does not say 4 or more (401, 5xx, timeout, malformed JSON, a missing, null, numeric or odd field, too deep JSON) gives none and the fixed reason
 * "could not be read". A local plain HTTP server plays the broker and records every request: no write request is ever sent, before the version is
 * known or after.
 */
class RabbitMqConnectionGatewayVersionTest {

    private val threeX = "This broker runs RabbitMQ 3.x: it is a migration source only"
    private val unknown = "The broker version could not be read, so migrations are not offered"

    private class Store : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = "pw"
        override fun delete(profileName: String) = Unit
    }

    private val requests = CopyOnWriteArrayList<String>()
    private var server: HttpServer? = null
    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach fun stop() { opened.forEach { runCatching { it.close() } }; server?.stop(0) }

    /** [overview] is (status, body); [delayMillis] holds the answer back. */
    private fun open(overview: Pair<Int, String>, delayMillis: Long = 0): RabbitMqHeldConnection {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 10)
        s.createContext("/") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI.rawPath}"
            val (status, body) = when (ex.requestURI.rawPath) {
                "/api/whoami" -> 200 to """{"name":"svc","tags":["administrator"]}"""
                "/api/overview" -> { if (delayMillis > 0) Thread.sleep(delayMillis); overview }
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        s.start(); server = s
        val profile = ConnectionProfile(name = "ver", host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = closedLocalPort(), managementPort = s.address.port, tls = false)
        val result = runBlocking { withTimeout(60_000) { RabbitMqConnectionGateway(Store()).open(profile) } }
        val connection = assertInstanceOf(OpenResult.Opened::class.java, result, "the connection itself must open whatever the version: $result").connection
        opened += connection
        return connection as RabbitMqHeldConnection
    }

    private fun overview(version: String) = 200 to """{"rabbitmq_version":"$version","erlang_version":"27","cluster_name":"rabbit@x"}"""

    private fun assertOnlyReads() = assertTrue(requests.all { it.startsWith("GET ") }, "a write reached the broker: $requests")

    @Test
    fun `4_4_0 gives an executor, no reason, exactly one extra GET of the overview and no write`() {
        val c = open(overview("4.4.0"))
        assertNotNull(c.migrationExecutor)
        assertNull(c.migrationUnavailableReason)
        assertEquals(listOf("GET /api/whoami", "GET /api/overview"), requests.toList())
    }

    @Test
    fun `4 and later versions in other shapes give an executor`() {
        for (v in listOf("4.0", "4", "10.1", "4.0.0-rc.1", "5.2.1")) {
            requests.clear(); server?.stop(0)
            val c = open(overview(v))
            assertNotNull(c.migrationExecutor, v)
            assertNull(c.migrationUnavailableReason, v)
            assertOnlyReads()
        }
    }

    @Test
    fun `3_13_7 gives no executor and the fixed 3x reason, and nothing but reads was sent`() {
        for (v in listOf("3.13.7", "3.9", "3.12.0", "2.8", "0.1")) {
            requests.clear(); server?.stop(0)
            val c = open(overview(v))
            assertNull(c.migrationExecutor, v)
            assertEquals(threeX, c.migrationUnavailableReason, v)
            assertOnlyReads()
        }
    }

    @Test
    fun `an overview that cannot be read gives no executor and the could-not-be-read reason`() {
        val cases = mapOf(
            "401" to (401 to """{"error":"not_authorised"}"""),
            "403" to (403 to "{}"),
            "404" to (404 to "{}"),
            "500" to (500 to "boom SENTINEL-500"),
            "503" to (503 to ""),
            "malformed" to (200 to """{"rabbitmq_version":"4.4.0""""),
            "not json" to (200 to "SENTINEL not json"),
            "empty body" to (200 to ""),
            "array" to (200 to """["4.4.0"]"""),
            "null body" to (200 to "null"),
            "missing field" to (200 to """{"erlang_version":"27"}"""),
            "numeric field" to (200 to """{"rabbitmq_version":4}"""),
            "decimal field" to (200 to """{"rabbitmq_version":4.4}"""),
            "null field" to (200 to """{"rabbitmq_version":null}"""),
            "object field" to (200 to """{"rabbitmq_version":{"major":4}}"""),
            "array field" to (200 to """{"rabbitmq_version":["4.4.0"]}"""),
            "empty text" to (200 to """{"rabbitmq_version":""}"""),
            "odd text" to (200 to """{"rabbitmq_version":"abc"}"""),
            "v-prefixed" to (200 to """{"rabbitmq_version":"v4.1"}"""),
            "huge major" to (200 to """{"rabbitmq_version":"99999999999.0"}"""),
            "deep json" to (200 to """{"rabbitmq_version":"4.4.0","x":""" + "[".repeat(100) + "]".repeat(100) + "}"),
            "very deep json" to (200 to """{"rabbitmq_version":"4.4.0","x":""" + "[".repeat(10_000) + "]".repeat(10_000) + "}"),
        )
        for ((name, body) in cases) {
            requests.clear(); server?.stop(0)
            val c = open(body)
            assertNull(c.migrationExecutor, "$name must fail closed")
            assertEquals(unknown, c.migrationUnavailableReason, name)
            assertOnlyReads()
            assertFalse(c.migrationUnavailableReason.orEmpty().contains("SENTINEL"))
        }
    }

    @Test
    fun `an overview that does not answer in time gives no executor and the could-not-be-read reason`() {
        val c = open(overview("4.4.0"), delayMillis = ConnectionTimeouts.MANAGEMENT_MILLIS + 3_000)
        assertNull(c.migrationExecutor)
        assertEquals(unknown, c.migrationUnavailableReason)
        assertOnlyReads()
    }

    @Test
    fun `the overview is asked once per connection, a failed whoami asks nothing more`() {
        open(overview("4.4.0"))
        assertEquals(1, requests.count { it == "GET /api/overview" })
        assertEquals(1, requests.count { it == "GET /api/whoami" })
    }
}
