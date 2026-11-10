package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrate3x.ReadOutcome
import dev.caerbannog.core.topology.TopologyCatalog.Companion.QUEUE_PAGE_SIZE
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.security.cert.CertificateException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLException

/**
 * M8-02b scope 1: `ManagementThreeXReads` turns the three GET calls of a live 3.x source into `ReadOutcome` values. 200 is Ok, 401 and 403 are
 * NotPermitted, a TLS or network failure is Unreachable, anything else or a body that cannot be read is Failed with a FIXED text that never holds a
 * body, a URL, a header or the credential. The class only ever calls `ManagementHttp.get` (also asserted on the source and on every request).
 * No container: a mock engine behind the real ManagementHttp.
 */
class ManagementThreeXReadsTest {

    private val sentinel = "SENTINEL-BODY-5c1e"
    private val headerSentinel = "SENTINEL-HEADER-9b2d"

    private class Seen(val method: String, val path: String, val query: String, val authorization: String?)

    private val seen = CopyOnWriteArrayList<Seen>()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResource("fixtures/m8-00/$name")) { "missing fixture $name" }.readText()

    /** [route] gets the path and the query and answers (status, body); a thrown exception is a transport failure. */
    private fun <T> reading(route: (path: String, query: String) -> Pair<Int, String>, block: suspend (ManagementThreeXReads) -> T): T {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val query = request.url.encodedQuery
            seen += Seen(request.method.value, path, query, request.headers["Authorization"])
            val (status, body) = route(path, query)
            respond(body, HttpStatusCode.fromValue(status), headersOf("Content-Type" to listOf("application/json"), "X-Secret" to listOf(headerSentinel)))
        }
        return reading(engine, block)
    }

    private fun <T> reading(engine: MockEngine, block: suspend (ManagementThreeXReads) -> T): T =
        ManagementHttp(HttpClient(engine), "svc", "pw").use { http ->
            runBlocking { withTimeout(10_000) { block(ManagementThreeXReads(http, M508a.requests())) } }
        }

    private fun onlyRequest(): Seen {
        assertEquals(1, seen.size, "expected exactly one request, saw ${seen.map { it.method + " " + it.path }}")
        return seen.single()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> ok(outcome: ReadOutcome<T>): T = assertInstanceOf(ReadOutcome.Ok::class.java, outcome, "outcome: $outcome").value as T

    private fun answering(status: Int, body: String) = { _: String, _: String -> status to body }

    // ---- definitions ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `definitions is a GET of api definitions and returns the text unparsed`() {
        val text = fixture("definitions-3.13-full.json")
        val outcome = reading(answering(200, text)) { it.definitions() }
        assertEquals(ReadOutcome.Ok(text), outcome)
        val request = onlyRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/definitions", request.path)
        assertEquals("", request.query, "no query on the export")
        assertTrue(request.authorization.orEmpty().startsWith("Basic "), "the connection's own credential is sent")
    }

    @Test
    fun `definitions returns a text that is not JSON as it is, because the core parser decides`() {
        assertEquals(ReadOutcome.Ok("not json at all"), reading(answering(200, "not json at all")) { it.definitions() })
    }

    @Test
    fun `401 and 403 of the definitions call are NotPermitted`() {
        // Measured (S-A): 401 "Not administrator user" for management, policymaker and monitoring users.
        assertEquals(ReadOutcome.NotPermitted, reading(answering(401, """{"error":"not_authorised","reason":"Not administrator user"}""")) { it.definitions() })
        assertEquals(ReadOutcome.NotPermitted, reading(answering(403, """{"error":"not_authorised"}""")) { it.definitions() })
    }

    @Test
    fun `a TLS or network failure of any read is Unreachable`() {
        val failures = mapOf(
            "certificate" to { throw SSLException("handshake", CertificateException("chain")) },
            "plain broker" to { throw SSLException("Unrecognized SSL message, plaintext connection?") },
            "refused" to { throw IOException("refused") },
        )
        for ((name, fail) in failures) {
            val engine = { MockEngine { fail() } }
            assertEquals(ReadOutcome.Unreachable, reading(engine()) { it.definitions() }, "definitions, $name")
            assertEquals(ReadOutcome.Unreachable, reading(engine()) { it.storageVersions("/") }, "storage versions, $name")
            assertEquals(ReadOutcome.Unreachable, reading(engine()) { it.deprecatedFeaturesInUse() }, "deprecated features, $name")
        }
    }

    @Test
    fun `any other status is Failed with a fixed text that holds no body, URL, header or credential`() {
        for (status in listOf(204, 301, 400, 404, 500, 502, 503)) {
            val a = reading(answering(status, "$sentinel boom")) { it.definitions() }
            val b = reading(answering(status, "another $sentinel text")) { it.definitions() }
            val failed = assertInstanceOf(ReadOutcome.Failed::class.java, a, "status $status")
            assertEquals(a, b, "the reason is a fixed text, it does not follow the body (status $status)")
            assertFixedText(failed.reason)
        }
    }

    private fun assertFixedText(reason: String) {
        assertFalse(reason.isBlank(), "a Failed needs a reason")
        for (forbidden in listOf(sentinel, headerSentinel, "broker.example", "15672", "/api/", "http", "pw", "svc", "Basic")) {
            assertFalse(reason.contains(forbidden, ignoreCase = true), "the reason [$reason] must not hold [$forbidden]")
        }
    }

    // ---- storage versions ----------------------------------------------------------------------------------------------------------

    private fun queuePage(page: Int, pageCount: Int, vararg queues: String) =
        """{"items":[${queues.joinToString(",")}],"page":$page,"page_count":$pageCount,"page_size":$QUEUE_PAGE_SIZE,"total_count":${queues.size}}"""

    private fun classic(name: String, storage: Int?) =
        """{"name":"$name","vhost":"/","type":"classic","durable":true,"state":"running"${storage?.let { ""","storage_version":$it""" }.orEmpty()}}"""

    @Test
    fun `the storage versions of a one page vhost are read with page and page_size and no columns filter`() {
        val body = queuePage(1, 1, classic("orders", 1), classic("fresh", null), classic("v2", 2))
        val outcome = reading(answering(200, body)) { it.storageVersions("/") }
        val map = ok(outcome)
        assertEquals(1, map["orders"])
        assertEquals(2, map["v2"])
        assertNull(map["fresh"], "a queue without statistics yet has no value (the core reads again)")
        val request = onlyRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/queues/%2F", request.path, "the vhost is encoded")
        assertTrue("page=1" in request.query && "page_size=$QUEUE_PAGE_SIZE" in request.query, "query was ${request.query}")
        // M8-02b scope 1: the default is NO columns filter on this call (the live test records whether columns= could include storage_version).
        assertFalse("columns" in request.query, "query was ${request.query}")
    }

    @Test
    fun `a vhost name with special characters is encoded in the path`() {
        reading(answering(200, queuePage(1, 1))) { it.storageVersions("my vhost/é") }
        assertEquals("/api/queues/my%20vhost%2F%C3%A9", onlyRequest().path)
    }

    @Test
    fun `the queue list is read page by page until page_count and merged`() {
        val pages = mapOf(
            "1" to queuePage(1, 3, classic("a", 1)),
            "2" to queuePage(2, 3, classic("b", 2)),
            "3" to queuePage(3, 3, classic("c", null)),
        )
        val outcome = reading({ _, query -> 200 to pages.getValue(Regex("page=(\\d+)").find(query)!!.groupValues[1]) }) { it.storageVersions("/") }
        val map = ok(outcome)
        assertEquals(1, map["a"])
        assertEquals(2, map["b"])
        assertNull(map["c"])
        assertEquals(listOf("page=1", "page=2", "page=3"), seen.map { Regex("page=\\d+").find(it.query)!!.value }, "exactly the pages up to page_count")
        assertTrue(seen.all { it.method == "GET" })
    }

    @Test
    fun `a second page that fails fails the whole read, no partial map`() {
        val outcome = reading({ _, query ->
            if ("page=1" in query) 200 to queuePage(1, 2, classic("a", 1)) else 500 to "$sentinel boom"
        }) { it.storageVersions("/") }
        val failed = assertInstanceOf(ReadOutcome.Failed::class.java, outcome)
        assertFixedText(failed.reason)
    }

    @Test
    fun `a refused queue list is NotPermitted and a body that is not a queue page is Failed`() {
        assertEquals(ReadOutcome.NotPermitted, reading(answering(403, "{}")) { it.storageVersions("/") })
        assertEquals(ReadOutcome.NotPermitted, reading(answering(401, "{}")) { it.storageVersions("/") })
        val bodies = listOf("$sentinel not json", """{"items":"x","page_count":1}""", "null", """["a"]""", "", """{"items":[1,2],"page_count":1}""")
        for (body in bodies) {
            val failed = assertInstanceOf(ReadOutcome.Failed::class.java, reading(answering(200, body)) { it.storageVersions("/") }, "body [$body]")
            assertFixedText(failed.reason)
        }
        assertFalse(seen.any { it.method != "GET" })
    }

    @Test
    fun `a queue list nested far too deep is Failed, not a crash`() {
        val deep = """{"items":[],"page_count":1,"x":""" + "[".repeat(10_000) + "]".repeat(10_000) + "}"
        assertInstanceOf(ReadOutcome.Failed::class.java, reading(answering(200, deep)) { it.storageVersions("/") })
    }

    // ---- page_count rulings R1 and R2 ----------------------------------------------------------------------------------------------

    private fun pageNumber(query: String) = Regex("page=(\\d+)").find(query)!!.groupValues[1].toInt()

    @Test
    fun `a page_count above the cap fails with a fixed text after exactly one request`() {
        val outcome = reading(answering(200, queuePage(1, MAX_QUEUE_PAGES + 1, classic("a", 1)))) { it.storageVersions("/") }
        val failed = assertInstanceOf(ReadOutcome.Failed::class.java, outcome)
        assertEquals("The queue list is too long to read", failed.reason)
        assertFixedText(failed.reason)
        assertEquals(1, seen.size, "a hostile page_count costs one GET: ${seen.map { it.query }}")
        assertTrue("page=1" in seen.single().query)
    }

    @Test
    fun `a page_count of exactly the cap reads every page`() {
        val outcome = reading({ _, query -> val n = pageNumber(query); 200 to queuePage(n, MAX_QUEUE_PAGES, classic("q$n", 1)) }) { it.storageVersions("/") }
        val map = ok(outcome)
        assertEquals(MAX_QUEUE_PAGES, seen.size, "one request per page")
        assertEquals((1..MAX_QUEUE_PAGES).toList(), seen.map { pageNumber(it.query) })
        assertEquals(MAX_QUEUE_PAGES, map.size)
        assertEquals(1, map["q$MAX_QUEUE_PAGES"])
    }

    @Test
    fun `a later page announcing another page_count is ignored, page 1 decides`() {
        val outcome = reading({ _, query ->
            when (pageNumber(query)) {
                1 -> 200 to queuePage(1, 3, classic("a", 1))
                2 -> 200 to queuePage(2, 5000, classic("b", 2))
                else -> 200 to queuePage(3, 1, classic("c", 1))
            }
        }) { it.storageVersions("/") }
        assertEquals(mapOf<String, Int?>("a" to 1, "b" to 2, "c" to 1), ok(outcome))
        assertEquals(listOf(1, 2, 3), seen.map { pageNumber(it.query) })
    }

    @Test
    fun `a body without page_count is one page, and so are 0 and 1`() {
        for (body in listOf(
            """{"items":[${classic("a", 1)}]}""",
            """{"items":[${classic("a", 1)}],"page_count":0}""",
            """{"items":[${classic("a", 1)}],"page_count":1}""",
        )) {
            seen.clear()
            assertEquals(mapOf<String, Int?>("a" to 1), ok(reading(answering(200, body)) { it.storageVersions("/") }), body)
            assertEquals(1, seen.size, "exactly one request for $body, saw ${seen.map { it.query }}")
        }
    }

    @Test
    fun `a page_count that is not a whole non-negative number is Failed and no partial map is returned`() {
        for (count in listOf("\"x\"", "-1", "1.5", "null", "true", "[]", "{}", "\"3\"")) {
            seen.clear()
            val body = """{"items":[${classic("a", 1)}],"page_count":$count}"""
            val failed = assertInstanceOf(ReadOutcome.Failed::class.java, reading(answering(200, body)) { it.storageVersions("/") }, "page_count $count")
            assertFixedText(failed.reason)
        }
    }

    @Test
    fun `a bare JSON array and an items field that is not an array are Failed`() {
        for (body in listOf("""[${classic("a", 1)}]""", """{"items":{"a":1},"page_count":1}""", """{"page_count":1}""", """{"items":null,"page_count":1}""")) {
            val failed = assertInstanceOf(ReadOutcome.Failed::class.java, reading(answering(200, body)) { it.storageVersions("/") }, "body $body")
            assertFixedText(failed.reason)
        }
    }

    // ---- deprecated features -------------------------------------------------------------------------------------------------------

    @Test
    fun `the deprecated features in use are the names of the list`() {
        val outcome = reading(answering(200, fixture("deprecated-features-used-3.13.json"))) { it.deprecatedFeaturesInUse() }
        assertEquals(ReadOutcome.Ok(setOf("classic_queue_mirroring")), outcome)
        val request = onlyRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/deprecated-features/used", request.path)
    }

    @Test
    fun `an empty list is an empty set, and refusals and bad bodies map like the other reads`() {
        assertEquals(ReadOutcome.Ok(emptySet<String>()), reading(answering(200, "[]")) { it.deprecatedFeaturesInUse() })
        assertEquals(ReadOutcome.NotPermitted, reading(answering(403, "{}")) { it.deprecatedFeaturesInUse() })
        // A 3.x broker before the feature exists answers 404: Failed (the core never lets it fail a fetch).
        assertInstanceOf(ReadOutcome.Failed::class.java, reading(answering(404, "{}")) { it.deprecatedFeaturesInUse() })
        for (body in listOf("$sentinel x", "{}", "null", "\"x\"")) {
            val failed = assertInstanceOf(ReadOutcome.Failed::class.java, reading(answering(200, body)) { it.deprecatedFeaturesInUse() }, "body [$body]")
            assertFixedText(failed.reason)
        }
    }

    // ---- GET only and the transport -------------------------------------------------------------------------------------------------

    @Test
    fun `every request of every read is a GET`() {
        reading(answering(200, "[]")) { it.definitions(); it.deprecatedFeaturesInUse() }
        reading(answering(200, queuePage(1, 1))) { it.storageVersions("/") }
        assertTrue(seen.size >= 3)
        assertTrue(seen.all { it.method == "GET" }, "methods: ${seen.map { it.method }}")
    }

    @Test
    fun `the source of the class calls only get of ManagementHttp`() {
        val file = File("src/main/kotlin/dev/caerbannog/datarabbit/ManagementThreeXReads.kt")
        assertTrue(file.isFile, "cannot find ${file.absolutePath}; the guard must not pass by finding nothing")
        val code = file.readLines().map { it.substringBefore("//") }.filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        val write = Regex("""\.(post|put|delete|patch)\s*\(|ManagementHttp\.(post|put|delete)\b|\bHttpMethod\.(Post|Put|Delete|Patch)\b""")
        val offenders = code.filter { write.containsMatchIn(it) }
        assertTrue(offenders.isEmpty(), "ManagementThreeXReads must be GET-only, found: $offenders")
        assertTrue(code.any { it.contains(".get(") }, "the class must use http.get")
    }

    @Test
    fun `a TLS profile pointed at a plain port gives Unreachable and nothing reaches the plain port`() {
        PlainManagementServer(answerGarbageWith400 = true).use { server ->
            val profile = M508a.profile.copy(host = "127.0.0.1", managementPort = server.port, tls = true)
            val http = ManagementHttp(TlsSupport.buildHttpClient(BrokerTransport.from(profile), 10_000), "svc", "pw")
            http.use {
                val reads = ManagementThreeXReads(it, ManagementRequests.forProfile(profile, BrokerTransport.from(profile)))
                runBlocking { withTimeout(30_000) {
                    assertEquals(ReadOutcome.Unreachable, reads.definitions())
                    assertEquals(ReadOutcome.Unreachable, reads.storageVersions("/"))
                    assertEquals(ReadOutcome.Unreachable, reads.deprecatedFeaturesInUse())
                } }
            }
            assertEquals(emptyList<PlainManagementServer.Seen>(), server.seen, "no fallback to plain: the credential must not reach a plain port")
        }
    }

    @Test
    fun `a closed connection reads nothing`() {
        val engine = MockEngine { error("a request left a closed connection") }
        val http = ManagementHttp(HttpClient(engine), "svc", "pw")
        val reads = ManagementThreeXReads(http, M508a.requests())
        http.close()
        runBlocking {
            assertEquals(ReadOutcome.Unreachable, reads.definitions())
            assertEquals(ReadOutcome.Unreachable, reads.storageVersions("/"))
            assertEquals(ReadOutcome.Unreachable, reads.deprecatedFeaturesInUse())
        }
    }

    @Test
    fun `the M8-02b compile stubs of data-rabbit are gone`() {
        val dir = File("src/main/kotlin/dev/caerbannog/datarabbit")
        val markers = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { f -> f.readLines().any { it.contains("M8-02b STUB") } }.toList()
        assertTrue(markers.isEmpty(), "stub markers left in ${markers.map { it.name }}")
    }
}
