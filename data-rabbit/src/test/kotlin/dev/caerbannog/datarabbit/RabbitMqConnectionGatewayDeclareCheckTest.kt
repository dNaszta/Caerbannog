package dev.caerbannog.datarabbit

import com.sun.net.httpserver.HttpServer
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.streams.UnavailableStreamManager
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M9-05: the held connection of the real gateway brings a `declareCheck` exactly when it brings a migration executor: from the same one
 * `GET /api/overview` (`MigrationAccess`), so a 4.x broker gives a port, and 3.13.7, an unreadable version and every odd text give none and
 * no extra request. Nothing is sent for the check itself when the connection opens: the port only sends when it is asked.
 */
class RabbitMqConnectionGatewayDeclareCheckTest {

    private class Store : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = "pw"
        override fun delete(profileName: String) = Unit
    }

    private val requests = CopyOnWriteArrayList<String>()
    private var server: HttpServer? = null
    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach fun stop() { opened.forEach { runCatching { it.close() } }; server?.stop(0) }

    private fun open(overview: Pair<Int, String>): RabbitMqHeldConnection {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 10)
        s.createContext("/") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI.rawPath}"
            val (status, body) = when (ex.requestURI.rawPath) {
                "/api/whoami" -> 200 to """{"name":"svc","tags":["administrator"]}"""
                "/api/overview" -> overview
                "/api/queues/%2F/orders" -> 404 to "Not Found"
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        s.start(); server = s
        val profile = ConnectionProfile(name = "chk", host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = closedLocalPort(), managementPort = s.address.port, tls = false)
        val result = runBlocking { withTimeout(60_000) { RabbitMqConnectionGateway(Store()).open(profile) } }
        val connection = assertInstanceOf(OpenResult.Opened::class.java, result, "the connection must open whatever the version: $result").connection
        opened += connection
        return connection as RabbitMqHeldConnection
    }

    private fun overview(version: String) = 200 to """{"rabbitmq_version":"$version","erlang_version":"27"}"""

    @Test
    fun `4_4_0 gives a check, no reason, and no request beyond whoami and the overview`() {
        val c = open(overview("4.4.0"))
        assertNotNull(c.declareCheck)
        assertNotNull(c.migrationExecutor)
        assertEquals(listOf("GET /api/whoami", "GET /api/overview"), requests.toList())
    }

    @Test
    fun `the port reads with one GET when it is asked, and a 404 is absent`() {
        val c = open(overview("4.4.0"))
        requests.clear()
        val found = runBlocking { withTimeout(30_000) { c.declareCheck!!.find(MigrationAction.DeclareQueue("orders")) } }
        assertEquals(BrokerFinding.Absent, found)
        assertEquals(listOf("GET /api/queues/%2F/orders"), requests.toList())
    }

    @Test
    fun `3_13_7 gives no check, no executor, and no extra request`() {
        val c = open(overview("3.13.7"))
        assertNull(c.declareCheck)
        assertNull(c.migrationExecutor)
        assertEquals(listOf("GET /api/whoami", "GET /api/overview"), requests.toList())
    }

    @Test
    fun `an unreadable or odd version gives no check, and no write is ever sent`() {
        val cases = listOf(
            500 to "", 401 to """{"error":"x"}""", 200 to "not json", 200 to """{"rabbitmq_version":null}""", 200 to """{"rabbitmq_version":4}""",
            200 to """{"rabbitmq_version":"4 beta"}""", 200 to """{"rabbitmq_version":"3.13.7\nx"}""", 200 to "{}",
        )
        for (case in cases) {
            requests.clear(); server?.stop(0)
            val c = open(case)
            assertNull(c.declareCheck, "no check for $case")
            assertNull(c.migrationExecutor, "no executor for $case")
            assertTrue(requests.all { it.startsWith("GET ") }, "a write reached the broker: $requests")
            assertEquals(listOf("GET /api/whoami", "GET /api/overview"), requests.toList(), "extra request for $case")
        }
    }

    @Test
    fun `MigrationAccess decides the executor and the check together from the one overview`() {
        for ((text, expectPort) in listOf("4.4.0" to true, "4" to true, "5.2.1" to true, "3.13.7" to false, "3.12" to false, "" to false, "x" to false)) {
            val body = """{"rabbitmq_version":"$text","erlang_version":"27"}"""
            val paths = CopyOnWriteArrayList<String>()
            val engine = MockEngine { request ->
                paths += request.method.value + " " + request.url.encodedPath
                respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
            val access = ManagementHttp(io.ktor.client.HttpClient(engine), "svc", "pw").use { http ->
                runBlocking { withTimeout(10_000) { MigrationAccess.forBroker(http, M508a.requests(), UnavailableStreamManager) } }
            }
            assertEquals(expectPort, access.declareCheck != null, "version [$text]")
            assertEquals(access.executor != null, access.declareCheck != null, "the check is null exactly when the executor is null: [$text]")
            assertEquals(listOf("GET /api/overview"), paths.toList(), "[$text]: one overview read, nothing else")
        }
    }
}
