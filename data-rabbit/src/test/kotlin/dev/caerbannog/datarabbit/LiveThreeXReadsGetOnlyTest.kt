package dev.caerbannog.datarabbit

import com.sun.net.httpserver.HttpServer
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.migrate3x.ClassicIndexVersion
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.DefinitionsParser
import dev.caerbannog.core.migrate3x.FetchResult
import dev.caerbannog.core.migrate3x.LiveDefinitionsSource
import dev.caerbannog.core.migrate3x.QueueKind
import dev.caerbannog.core.migrate3x.QueueRef
import dev.caerbannog.core.migrate3x.STORAGE_VERSION_READS
import dev.caerbannog.core.topology.TopologyCatalog.Companion.QUEUE_PAGE_SIZE
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Scenario "Every request the live source reading makes to a 3.x broker is a GET, also when a read fails" (`@safety @m8-02b`), plus the REAL check
 * of "The broker version is read once" (ruling 10): a recording Ktor mock engine behind the real `ManagementHttp` serves 3.13 fixtures; the whole read
 * path runs (`LiveDefinitionsSource` over `ManagementThreeXReads`), once with every read succeeding and once with the deprecated feature list
 * refused (403) and the second queue page failing (500). Every recorded method is GET and no path reaches a message endpoint (`/get`, `/contents`,
 * `/purge`). The connect half uses the real gateway against a loopback server that records every request.
 */
class LiveThreeXReadsGetOnlyTest {

    private class Seen(val method: String, val path: String, val query: String)

    private val seen = CopyOnWriteArrayList<Seen>()
    private val pauses = CopyOnWriteArrayList<Long>()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResource("fixtures/m8-00/$name")) { "missing fixture $name" }.readText()

    private val definitionsText = fixture("definitions-3.13-full.json")
    private val classicQueues = (DefinitionsParser.parse(definitionsText) as DefinitionsParseResult.Parsed).document.queues.filter { it.type == QueueKind.Classic }

    private fun queueJson(name: String) = """{"name":"$name","vhost":"x","type":"classic","durable":true,"storage_version":1}"""

    /** The 3.13 stand-in: the export, the queue pages (page 1 holds every classic queue of the vhost, page 2 whatever [secondPage] says), the deprecated list. */
    private fun standIn(deprecated: Pair<Int, String>, secondPage: Pair<Int, String>) = MockEngine { request ->
        val path = request.url.encodedPath
        val query = request.url.encodedQuery
        seen += Seen(request.method.value, path, query)
        val (status, body) = when {
            path == "/api/definitions" -> 200 to definitionsText
            path == "/api/deprecated-features/used" -> deprecated
            path.startsWith("/api/queues/") && "page=2" in query -> secondPage
            path.startsWith("/api/queues/") -> {
                val vhost = java.net.URLDecoder.decode(path.removePrefix("/api/queues/"), Charsets.UTF_8)
                val names = classicQueues.filter { it.vhost == vhost }.map { queueJson(it.name) }
                200 to """{"items":[${names.joinToString(",")}],"page":1,"page_count":2,"page_size":$QUEUE_PAGE_SIZE,"total_count":${names.size}}"""
            }
            else -> 404 to "{}"
        }
        respond(body, HttpStatusCode.fromValue(status), headersOf("Content-Type", "application/json"))
    }

    private fun fetch(engine: MockEngine): FetchResult =
        ManagementHttp(HttpClient(engine), "svc", "pw").use { http ->
            runBlocking {
                withTimeout(20_000) {
                    LiveDefinitionsSource("legacy-eu", "3.13.7", ManagementThreeXReads(http, M508a.requests())) { pauses += it }.fetch()
                }
            }
        }

    private fun assertGetOnly() {
        assertTrue(seen.isNotEmpty(), "the read path sent nothing, so the check proves nothing")
        assertEquals(emptyList<String>(), seen.filter { it.method != "GET" }.map { "${it.method} ${it.path}" }, "a non-GET request reached the 3.x broker")
        val message = seen.filter { p -> listOf("/get", "/contents", "/purge").any { p.path.contains(it) } }.map { it.path }
        assertEquals(emptyList<String>(), message, "a message endpoint was called on the 3.x broker")
        val allowed = Regex("""/api/(definitions|deprecated-features/used|queues/[^/]+)""")
        assertEquals(emptyList<String>(), seen.map { it.path }.filterNot { allowed.matches(it) }, "the live read touched a path outside its three reads")
        assertEquals(1, seen.count { it.path == "/api/definitions" }, "the export is read once")
    }

    @Test
    fun `the whole live read with every read succeeding sends only GET requests`() {
        val result = fetch(standIn(deprecated = 200 to fixture("deprecated-features-used-3.13.json"), secondPage = 200 to """{"items":[],"page":2,"page_count":2,"page_size":$QUEUE_PAGE_SIZE,"total_count":0}"""))
        val fetched = assertInstanceOf(FetchResult.Fetched::class.java, result, "result: $result")
        assertEquals(setOf("classic_queue_mirroring"), fetched.facts.deprecatedFeaturesInUse)
        assertTrue(classicQueues.isNotEmpty())
        for (queue in classicQueues) {
            assertEquals(ClassicIndexVersion.V1, fetched.facts.classicIndexVersions[QueueRef(queue.vhost, queue.name)], "storage version of ${queue.vhost}/${queue.name}")
        }
        assertGetOnly()
    }

    @Test
    fun `the whole live read with the deprecated list refused and the second queue page failing still sends only GET requests`() {
        val result = fetch(standIn(deprecated = 403 to "{}", secondPage = 500 to "boom"))
        val fetched = assertInstanceOf(FetchResult.Fetched::class.java, result, "a refused list and a failed page never fail the fetch: $result")
        assertEquals(emptySet<String>(), fetched.facts.deprecatedFeaturesInUse)
        for (queue in classicQueues) {
            assertEquals(ClassicIndexVersion.Unknown, fetched.facts.classicIndexVersions[QueueRef(queue.vhost, queue.name)], "a failed read gives Unknown for ${queue.vhost}/${queue.name}")
        }
        assertTrue(seen.any { it.path == "/api/deprecated-features/used" }, "the refused list must have been asked")
        assertTrue(seen.any { "page=2" in it.query }, "the second page must have been asked")
        assertGetOnly()
        assertTrue(pauses.size <= STORAGE_VERSION_READS, "no unbounded waiting: $pauses")
    }

    // ---- the version is read once on connect (the real check of "The broker version is read once when the connection opens") ----------

    private class Store : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = "pw"
        override fun delete(profileName: String) = Unit
    }

    private val requests = CopyOnWriteArrayList<String>()
    private var server: HttpServer? = null
    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach fun stop() { opened.forEach { runCatching { it.close() } }; server?.stop(0) }

    private fun connect(version: String): dev.caerbannog.core.connections.HeldConnection {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 10)
        s.createContext("/") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI.rawPath}"
            val (status, body) = when (ex.requestURI.rawPath) {
                "/api/whoami" -> 200 to """{"name":"svc","tags":["administrator"]}"""
                "/api/overview" -> 200 to """{"rabbitmq_version":"$version","erlang_version":"26","cluster_name":"rabbit@x"}"""
                "/api/definitions" -> 200 to definitionsText
                "/api/deprecated-features/used" -> 200 to fixture("deprecated-features-used-3.13.json")
                else -> if (ex.requestURI.rawPath.startsWith("/api/queues/")) 200 to """{"items":[],"page":1,"page_count":1,"page_size":$QUEUE_PAGE_SIZE,"total_count":0}""" else 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start(); server = s
        val port = closedLocalPort()
        val profile = ConnectionProfile(name = "legacy-eu", host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = port, managementPort = s.address.port, tls = false)
        val result = runBlocking { withTimeout(60_000) { RabbitMqConnectionGateway(Store()).open(profile) } }
        val held = assertInstanceOf(OpenResult.Opened::class.java, result, "the connection must open: $result").connection
        opened += held
        return held
    }

    @Test
    fun `a connect to a 3_13 broker sends exactly one request to the overview and the whole read afterwards only GETs`() {
        val held = connect("3.13.7")
        assertEquals(1, requests.count { it == "GET /api/overview" }, "the version is read once: $requests")
        assertEquals("3.13.7", held.brokerVersion)
        val reads = assertInstanceOf(ManagementThreeXReads::class.java, held.threeXReads, "a 3.x connection hands its reads to the live source")
        val result = runBlocking { withTimeout(20_000) { LiveDefinitionsSource("legacy-eu", held.brokerVersion, reads) { }.fetch() } }
        assertInstanceOf(FetchResult.Fetched::class.java, result, "result: $result")
        assertEquals(1, requests.count { it == "GET /api/overview" }, "reading the definitions must not read the overview again: $requests")
        assertFalse(requests.any { !it.startsWith("GET ") }, "a non-GET reached the broker: $requests")
        assertFalse(requests.any { r -> listOf("/get", "/contents", "/purge").any { r.contains(it) } }, "a message endpoint was called: $requests")
    }
}
