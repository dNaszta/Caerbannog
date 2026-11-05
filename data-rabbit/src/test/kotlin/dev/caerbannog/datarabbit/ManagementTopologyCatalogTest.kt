package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M2-08a step 2 (no Docker): the REAL [ManagementTopologyCatalog] against a local plain management server serving the recorded S-A bodies.
 * Contract: `internal class ManagementTopologyCatalog(http: ManagementHttp, requests: ManagementRequests) : TopologyCatalog`, built like
 * `ManagementStreamCatalog` (the test builds it with `TlsSupport.buildHttpClient(BrokerTransport.Plain, ...)`; the developer wires the same instance
 * into the held connection in 08b). What is pinned:
 *  - each call is ONE GET: `queues` on `/api/queues/{vhost}`, `exchanges` on `/api/exchanges/{vhost}`, `bindings` on `/api/bindings/{vhost}`,
 *    `queueDetail` on `/api/queues/{vhost}/{name}`, and NOTHING else (no whoami, no probe);
 *  - THE GUARD (brief M2-08 acceptance 1): over every call, every outcome and every failure, no request is anything but a GET, none targets
 *    `/get`, `/contents` or a path with `purge`, `consume` or `message`; the only paths are under `/api/queues`, `/api/exchanges`, `/api/bindings`;
 *  - statuses map like the stream catalog's (`ManagementErrors.map`): 401 and 403 NotAuthorised, 404 on a list is NotFound (a vhost the user cannot
 *    see, MEASURED: `monitor` on a vhost without permission gets 404, not 403), 404 on a detail NotFound, 400 BadPaging, other statuses Unexpected,
 *    unreachable Unreachable; the credentials go as Basic auth and the body of an error is never copied.
 */
class ManagementTopologyCatalogTest {

    private val queuesPath = "/api/queues/%2F"
    private val columnsBody = FixtureFiles.text("m2-08/queues-quorum-classic-stream-columns.json")
    private val singleBody = FixtureFiles.text("m2-08/queue-single-quorum-with-policy.json")
    private val exchangesBody = FixtureFiles.text("m2-07/exchanges-vhost-root.json")
    private val bindingsBody = FixtureFiles.text("m2-07/bindings-vhost-root.json")

    private val allRoutes: Map<String, Pair<Int, String>> = mapOf(
        queuesPath to (200 to columnsBody),
        "$queuesPath/spike-m208-quorum" to (200 to singleBody),
        "/api/exchanges/%2F" to (200 to exchangesBody),
        "/api/bindings/%2F" to (200 to bindingsBody),
    )

    private fun <T> withCatalog(server: PlainManagementServer, block: suspend (TopologyCatalog) -> T): T {
        val http = ManagementHttp(TlsSupport.buildHttpClient(BrokerTransport.Plain, 5_000), "svc", "pw-not-a-secret")
        val requests = ManagementRequests("http://127.0.0.1:${server.port}", BrokerTransport.Plain)
        try {
            return runBlocking { withTimeout(30_000) { block(ManagementTopologyCatalog(http, requests)) } }
        } finally {
            http.close()
        }
    }

    private fun lines(server: PlainManagementServer) = server.seen.map { "${it.method} ${it.rawPath}" }

    // --- calls made ---

    @Test
    fun `queues is one GET of the vhost list and parses the quorum and classic queues, not the stream`() {
        PlainManagementServer(allRoutes).use { server ->
            val result = withCatalog(server) { it.queues("/", 1) }
            assertInstanceOf(CatalogResult.Success::class.java, result, "got $result")
            val page = (result as CatalogResult.Success<QueuePage>).value
            assertEquals(listOf("dev-jobs", "spike-m208-classic", "spike-m208-quorum"), page.rows.map { it.name })
            assertEquals(listOf("GET $queuesPath"), lines(server))
        }
    }

    @Test
    fun `queueDetail is one GET of the single queue path`() {
        PlainManagementServer(allRoutes).use { server ->
            val result = withCatalog(server) { it.queueDetail("/", "spike-m208-quorum") }
            assertEquals("spike-m208-pol", (result as CatalogResult.Success<QueueRow>).value.policy)
            assertEquals(listOf("GET $queuesPath/spike-m208-quorum"), lines(server))
        }
    }

    @Test
    fun `exchanges and bindings are one GET each and keep the default exchange`() {
        PlainManagementServer(allRoutes).use { server ->
            val (exchanges, bindings) = withCatalog(server) { it.exchanges("/") to it.bindings("/") }
            assertTrue((exchanges as CatalogResult.Success<List<ExchangeRow>>).value.any { it.isDefault })
            assertTrue((bindings as CatalogResult.Success<List<BindingRow>>).value.any { it.source.isEmpty() })
            assertEquals(listOf("GET /api/exchanges/%2F", "GET /api/bindings/%2F"), lines(server))
        }
    }

    @Test
    fun `the Queues tab alone makes one call per tick, never bindings or exchanges`() {
        PlainManagementServer(allRoutes).use { server ->
            withCatalog(server) { it.queues("/", 1) }
            assertEquals(1, server.seen.size, "calls: ${lines(server)}")
        }
    }

    @Test
    fun `the credentials go as Basic auth`() {
        PlainManagementServer(allRoutes).use { server ->
            withCatalog(server) { it.queues("/", 1) }
            val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("svc:pw-not-a-secret".toByteArray())
            assertEquals(expected, server.seen.single().authorization)
        }
    }

    // --- status mapping ---

    private fun answer(status: Int, body: String = """{"error":"x","reason":"leak-canary-text"}""", call: suspend (TopologyCatalog) -> CatalogResult<*>): CatalogResult<*> =
        PlainManagementServer(allRoutes.mapValues { status to body }).use { server -> withCatalog(server, call) }

    @Test
    fun `401 and 403 are NotAuthorised on every call`() {
        for (status in listOf(401, 403)) {
            assertEquals(CatalogResult.NotAuthorised, answer(status) { it.queues("/", 1) }, "queues $status")
            assertEquals(CatalogResult.NotAuthorised, answer(status) { it.exchanges("/") }, "exchanges $status")
            assertEquals(CatalogResult.NotAuthorised, answer(status) { it.bindings("/") }, "bindings $status")
            assertEquals(CatalogResult.NotAuthorised, answer(status) { it.queueDetail("/", "spike-m208-quorum") }, "detail $status")
        }
    }

    @Test
    fun `the no tag 401 body of the recorded fixture is NotAuthorised`() {
        val (status, body) = FixtureFiles.errorBody("m2-02/non-admin-no-tag-401.json")
        assertEquals(401, status)
        assertEquals(CatalogResult.NotAuthorised, answer(status, body) { it.queues("/", 1) })
    }

    @Test
    fun `404 on a list or a detail is NotFound, the vhost the user cannot see`() {
        val body = FixtureFiles.text("m2-08/queues-vhost-not-found-404.json")
        assertEquals(CatalogResult.NotFound, answer(404, body) { it.queues("/", 1) })
        assertEquals(CatalogResult.NotFound, answer(404, body) { it.exchanges("/") })
        assertEquals(CatalogResult.NotFound, answer(404, body) { it.bindings("/") })
        assertEquals(CatalogResult.NotFound, answer(404, body) { it.queueDetail("/", "spike-m208-quorum") })
    }

    @Test
    fun `400 on the queue list is BadPaging, other statuses are Unexpected`() {
        assertEquals(CatalogResult.BadPaging, answer(400) { it.queues("/", 1) })
        assertEquals(CatalogResult.Unexpected, answer(500) { it.queues("/", 1) })
        assertEquals(CatalogResult.Unexpected, answer(500) { it.exchanges("/") })
        assertEquals(CatalogResult.Unexpected, answer(502) { it.bindings("/") })
    }

    @Test
    fun `a body that is not what the spec records is Unexpected, and nothing of it is copied`() {
        assertEquals(CatalogResult.Unexpected, answer(200, "<html>proxy leak-canary-text</html>") { it.queues("/", 1) })
        assertEquals(CatalogResult.Unexpected, answer(200, "<html/>") { it.exchanges("/") })
        assertEquals(CatalogResult.Unexpected, answer(200, "<html/>") { it.bindings("/") })
        assertEquals(CatalogResult.Unexpected, answer(200, "<html/>") { it.queueDetail("/", "spike-m208-quorum") })
    }

    @Test
    fun `a stream asked for as a queue detail is NotFound`() {
        val stream = """{"name":"dev-orders","vhost":"/","type":"stream","messages_ready":0,"consumers":0}"""
        PlainManagementServer(mapOf("$queuesPath/dev-orders" to (200 to stream))).use { server ->
            assertEquals(CatalogResult.NotFound, withCatalog(server) { it.queueDetail("/", "dev-orders") })
        }
    }

    @Test
    fun `a closed management port is Unreachable on every call`() {
        val http = ManagementHttp(TlsSupport.buildHttpClient(BrokerTransport.Plain, 2_000), "svc", "pw")
        val catalog = ManagementTopologyCatalog(http, ManagementRequests("http://127.0.0.1:${closedLocalPort()}", BrokerTransport.Plain))
        try {
            runBlocking {
                assertEquals(CatalogResult.Unreachable, catalog.queues("/", 1))
                assertEquals(CatalogResult.Unreachable, catalog.exchanges("/"))
                assertEquals(CatalogResult.Unreachable, catalog.bindings("/"))
                assertEquals(CatalogResult.Unreachable, catalog.queueDetail("/", "q"))
            }
        } finally {
            http.close()
        }
    }

    @Test
    fun `after the connection is closed every call is Unreachable and sends nothing`() {
        PlainManagementServer(allRoutes).use { server ->
            val http = ManagementHttp(TlsSupport.buildHttpClient(BrokerTransport.Plain, 5_000), "svc", "pw")
            val catalog = ManagementTopologyCatalog(http, ManagementRequests("http://127.0.0.1:${server.port}", BrokerTransport.Plain))
            http.close()
            runBlocking { assertEquals(CatalogResult.Unreachable, catalog.queues("/", 1)) }
            assertEquals(emptyList<String>(), lines(server))
        }
    }

    // --- THE GUARD: the app never asks the broker for a queue's messages ---

    // RabbitMQ reads messages through POST /api/queues/{vhost}/{name}/get and DELETE .../contents (purge): FOUR segments after /api. A queue may
    // be NAMED "get" or "contents", so the guard checks the shape of the path (vhost and at most one name segment), not only its suffix.
    private fun assertReadOnlyTopologyRequest(request: PlainManagementServer.Seen) {
        val line = "${request.method} ${request.rawPath}"
        assertEquals("GET", request.method, line)
        val segments = request.rawPath.removePrefix("/api/").split("/")
        when (segments[0]) {
            "queues" -> assertTrue(segments.size in 2..3, "a queue path with more than {vhost}/{name}: $line")
            "exchanges", "bindings" -> assertEquals(2, segments.size, "an exchange or binding path with more than {vhost}: $line")
            else -> org.junit.jupiter.api.Assertions.fail<Unit>("outside the topology paths: $line")
        }
        assertTrue(request.rawPath.startsWith("/api/"), line)
    }

    @Test
    fun `GUARD every request of every call, in every outcome, is a GET on queues, exchanges or bindings and never reads or purges a message`() {
        val outcomes = listOf(200, 401, 403, 404, 400, 500)
        val seen = mutableListOf<PlainManagementServer.Seen>()
        for (status in outcomes) {
            PlainManagementServer(allRoutes.mapValues { (_, reply) -> if (status == 200) reply else status to "{}" }).use { server ->
                withCatalog(server) {
                    it.queues("/", 1)
                    it.queues("/", 2)
                    it.exchanges("/")
                    it.bindings("/")
                    it.queueDetail("/", "spike-m208-quorum")
                    it.queueDetail("/", "get")
                    it.queueDetail("/", "contents")
                }
                seen += server.seen
            }
        }
        assertEquals(outcomes.size * 7, seen.size, "calls: ${seen.map { "${it.method} ${it.rawPath}" }}")
        seen.forEach(::assertReadOnlyTopologyRequest)
    }

    // Sensitivity check of the guard itself: the shapes it must refuse. If this fails the guard above proves nothing.
    @Test
    fun `GUARD the check refuses the message paths and the write verbs`() {
        val bad = listOf(
            PlainManagementServer.Seen("POST", "/api/queues/%2F/q/get", null),
            PlainManagementServer.Seen("DELETE", "/api/queues/%2F/q/contents", null),
            PlainManagementServer.Seen("GET", "/api/queues/%2F/q/get", null),
            PlainManagementServer.Seen("GET", "/api/queues/%2F/q/bindings", null),
            PlainManagementServer.Seen("GET", "/api/exchanges/%2F/x/bindings/source", null),
            PlainManagementServer.Seen("GET", "/api/consumers/%2F", null),
            PlainManagementServer.Seen("PUT", "/api/queues/%2F/q", null),
        )
        bad.forEach { request ->
            val failed = try {
                assertReadOnlyTopologyRequest(request)
                false
            } catch (e: AssertionError) {
                true
            }
            assertTrue(failed, "the guard accepted ${request.method} ${request.rawPath}")
        }
    }
}
