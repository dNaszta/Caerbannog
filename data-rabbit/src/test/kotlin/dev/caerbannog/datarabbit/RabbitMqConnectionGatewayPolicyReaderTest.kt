package dev.caerbannog.datarabbit

import com.sun.net.httpserver.HttpServer
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.planner.PolicyReadResult
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
 * M9-08 slice 2, written first: the held connection of the real gateway brings a `policyReader` (the planner's one-policy read port) exactly when it brings a
 * migration executor and a `declareCheck`: from the same one `GET /api/overview` (`MigrationAccess`). A 4.x broker gives the port; 3.13.7, an unreadable version and
 * every odd text give none, so a 3.x connection is sent GET requests only and none for a policy (the planner is not offered there). Nothing is sent for the port when
 * the connection opens. Pinned names: `RabbitMqHeldConnection.policyReader: BrokerPolicyReader?` and `MigrationAccess.policyReader`.
 */
class RabbitMqConnectionGatewayPolicyReaderTest {
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
                "/api/policies/%2F/limits" -> 200 to """{"vhost":"/","name":"limits","pattern":"^orders","apply-to":"all","definition":{"max-length":5},"priority":2}"""
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        s.start(); server = s
        val profile = ConnectionProfile(name = "pol", host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = closedLocalPort(), managementPort = s.address.port, tls = false)
        val result = runBlocking { withTimeout(60_000) { RabbitMqConnectionGateway(Store()).open(profile) } }
        val connection = assertInstanceOf(OpenResult.Opened::class.java, result, "the connection must open whatever the version: $result").connection
        opened += connection
        return connection as RabbitMqHeldConnection
    }

    private fun overview(version: String) = 200 to """{"rabbitmq_version":"$version","erlang_version":"27"}"""

    @Test
    fun `4_4_0 gives a port and no request beyond whoami and the overview`() {
        val c = open(overview("4.4.0"))
        assertNotNull(c.policyReader)
        assertEquals(listOf("GET /api/whoami", "GET /api/overview"), requests.toList())
    }

    @Test
    fun `the port reads one policy with one GET of its item`() {
        val c = open(overview("4.4.0"))
        requests.clear()
        val found = runBlocking { withTimeout(30_000) { c.policyReader!!.read("/", "limits") } }
        assertInstanceOf(PolicyReadResult.Found::class.java, found)
        assertEquals(2, (found as PolicyReadResult.Found).row.priority)
        assertEquals(listOf("GET /api/policies/%2F/limits"), requests.toList())
        requests.clear()
        assertEquals(PolicyReadResult.Unreadable, runBlocking { withTimeout(30_000) { c.policyReader!!.read("/", "nothing") } })
        assertEquals(listOf("GET /api/policies/%2F/nothing"), requests.toList())
    }

    @Test
    fun `3_13_7 gives no port and no extra request, so a 3_x broker is never sent a policy read`() {
        val c = open(overview("3.13.7"))
        assertNull(c.policyReader)
        assertEquals(listOf("GET /api/whoami", "GET /api/overview"), requests.toList())
    }

    @Test
    fun `an unreadable or odd version gives no port, and no write is ever sent`() {
        val cases = listOf(500 to "", 401 to """{"error":"x"}""", 200 to "not json", 200 to """{"rabbitmq_version":null}""", 200 to """{"rabbitmq_version":"4 beta"}""", 200 to "{}")
        for (case in cases) {
            requests.clear(); server?.stop(0)
            val c = open(case)
            assertNull(c.policyReader, "no port for $case")
            assertTrue(requests.all { it.startsWith("GET ") }, "a write reached the broker: $requests")
            assertEquals(listOf("GET /api/whoami", "GET /api/overview"), requests.toList(), "extra request for $case")
        }
    }

    @Test
    fun `MigrationAccess decides the executor, the check and the policy port together from the one overview`() {
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
            assertEquals(expectPort, access.policyReader != null, "version [$text]")
            assertEquals(access.executor != null, access.policyReader != null, "the port is null exactly when the executor is null: [$text]")
            assertEquals(listOf("GET /api/overview"), paths.toList(), "[$text]: one overview read, nothing else")
        }
    }
}
