package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.PolicyRow
import dev.caerbannog.core.topology.QueueDeclaration
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M9-14 step 2 (no Docker): the reads the topology export needs, over the answers MEASURED in `RealBrokerM9ExportSpikeTest`
 * (fixtures `fixtures/m9-14/`, results `docs/tasks/M9-14-spike-results.md`). Contract fixed by these tests:
 *  - `ManagementRequests.queueDeclarations(vhost, page)`: `GET /api/queues/{vhost}?page=&page_size=500&columns=name,type,durable,auto_delete,exclusive,arguments`
 *    (the broker honours the column list exactly, measured; streams ARE in this list);
 *  - `ManagementRequests.policies(vhost)` = `GET /api/policies/{vhost}`, `operatorPolicies(vhost)` = `GET /api/operator-policies/{vhost}` (operator policies are NOT in
 *    `/api/policies`, measured);
 *  - `internal data class QueueDeclarationPage(rows: List<QueueDeclaration>, page: Int, pageCount: Int)` and
 *    `ManagementJson.parseQueueDeclarations(body, vhost): CatalogResult<QueueDeclarationPage>`: `arguments` typed ([ArgumentValue]) WITHOUT `x-queue-type`
 *    (the `type` key says it; measured: the broker adds no other argument); a missing flag or a wrong-typed field is `Unexpected`;
 *  - `ExchangeRow.typedArguments` and `BindingRow.typedArguments` filled by the existing `parseExchanges` / `parseBindingList` (the display text `arguments` is unchanged);
 *  - `ManagementJson.parsePolicies(body): CatalogResult<List<PolicyRow>>` (the same shape for operator policies);
 *  - `ManagementTopologyCatalog.queueDeclarations(vhost)` reads EVERY page (one GET per page, 1-based, until the broker's `page_count`) and returns all rows or one failure;
 *    `policies` and `operatorPolicies` are one GET each; statuses map like the other calls (401 and 403 NotAuthorised, 404 NotFound, other Unexpected, unreachable
 *    Unreachable, a body that is not what the broker sends Unexpected);
 *  - THE GUARD, as for the other calls: only GET, only paths under `/api/queues/{vhost}`, `/api/policies/{vhost}`, `/api/operator-policies/{vhost}`, never a path under a queue's name.
 */
class ManagementExportReadsTest {

    private val requests = ManagementRequests("https://rabbit.example.com:15671")

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }

    // ---- requests ------------------------------------------------------------------------------------------------------------

    @Test
    fun `the declarations list is the vhost queue path with the page, the maximum page size and exactly the export columns`() {
        val url = requests.queueDeclarations("my vhost/1", 3)
        assertEquals("https://rabbit.example.com:15671/api/queues/my%20vhost%2F1", url.substringBefore('?'))
        val q = query(url)
        assertEquals(setOf("page", "page_size", "columns"), q.keys)
        assertEquals("3", q["page"]); assertEquals("500", q["page_size"])
        assertEquals(setOf("name", "type", "durable", "auto_delete", "exclusive", "arguments"), q.getValue("columns").split(",").toSet())
    }

    @Test
    fun `the display queue list keeps its columns, the screens do not pay for arguments`() {
        val columns = query(requests.queues("/", 1)).getValue("columns").split(",").toSet()
        assertEquals(setOf("name", "type", "messages_ready", "messages_unacknowledged", "consumers", "leader", "members", "online", "policy", "state"), columns)
    }

    @Test
    fun `the policy and operator policy reads are plain vhost paths with no query`() {
        assertEquals("https://rabbit.example.com:15671/api/policies/%2F", requests.policies("/"))
        assertEquals("https://rabbit.example.com:15671/api/operator-policies/%2F", requests.operatorPolicies("/"))
        assertEquals("https://rabbit.example.com:15671/api/policies/a%2Bb", requests.policies("a+b"))
    }

    // ---- parsing -------------------------------------------------------------------------------------------------------------

    @Test
    fun `the declarations page keeps type, flags and typed arguments, streams included, without x-queue-type`() {
        val result = ManagementJson.parseQueueDeclarations(FixtureFiles.text("m9-14/e1-queues-columns.json"), "v")
        val page = (result as CatalogResult.Success<QueueDeclarationPage>).value
        assertEquals(1 to 1, page.page to page.pageCount)
        val byName = page.rows.associateBy { it.name }
        assertEquals(setOf("amq.gen-rzkEUd4HTHeWWNo24x6BCw", "classic-args", "exclusive-named", "quorum-args", "stream-args"), byName.keys)
        assertEquals(
            QueueDeclaration(
                name = "classic-args", vhost = "v", type = "classic", durable = true, autoDelete = false, exclusive = false,
                arguments = mapOf(
                    "x-dead-letter-exchange" to ArgumentValue.Text("dlx"), "x-max-length" to ArgumentValue.Whole(1000), "x-max-priority" to ArgumentValue.Whole(5),
                    "x-message-ttl" to ArgumentValue.Whole(60000), "x-overflow" to ArgumentValue.Text("reject-publish"),
                ),
            ),
            byName.getValue("classic-args"),
        )
        assertEquals(mapOf("x-delivery-limit" to ArgumentValue.Whole(5), "x-dead-letter-exchange" to ArgumentValue.Text("dlx"), "x-max-length" to ArgumentValue.Whole(100)), byName.getValue("quorum-args").arguments)
        val stream = byName.getValue("stream-args")
        assertEquals("stream", stream.type)
        assertEquals(mapOf("x-max-age" to ArgumentValue.Text("7D"), "x-max-length-bytes" to ArgumentValue.Whole(1_000_000_000)), stream.arguments)
        val exclusive = byName.getValue("exclusive-named")
        assertEquals(Triple(false, false, true), Triple(exclusive.durable, exclusive.autoDelete, exclusive.exclusive))
        val server = byName.getValue("amq.gen-rzkEUd4HTHeWWNo24x6BCw")
        assertEquals(Triple(false, true, true), Triple(server.durable, server.autoDelete, server.exclusive))
    }

    @Test
    fun `a declarations page without a flag or with a wrong-typed flag is Unexpected, nothing is guessed`() {
        fun page(item: String) = """{"items":[$item],"page":1,"page_count":1,"page_size":500,"total_count":1}"""
        val ok = """{"name":"q","type":"classic","durable":true,"auto_delete":false,"exclusive":false,"arguments":{}}"""
        assertInstanceOf(CatalogResult.Success::class.java, ManagementJson.parseQueueDeclarations(page(ok), "/"))
        for (broken in listOf(
            """{"name":"q","type":"classic","auto_delete":false,"exclusive":false,"arguments":{}}""",
            """{"name":"q","type":"classic","durable":"true","auto_delete":false,"exclusive":false,"arguments":{}}""",
            """{"name":"q","durable":true,"auto_delete":false,"exclusive":false,"arguments":{}}""",
            """{"type":"classic","durable":true,"auto_delete":false,"exclusive":false,"arguments":{}}""",
            """{"name":"q","type":"classic","durable":true,"auto_delete":false,"exclusive":false,"arguments":[1]}""",
        )) {
            assertEquals(CatalogResult.Unexpected, ManagementJson.parseQueueDeclarations(page(broken), "/"), broken)
        }
        assertEquals(CatalogResult.Unexpected, ManagementJson.parseQueueDeclarations("<html/>", "/"))
    }

    @Test
    fun `exchanges and bindings keep typed arguments, the display text stays as it was`() {
        val exchanges = (ManagementJson.parseExchanges(FixtureFiles.text("m9-14/e4-exchanges.json")) as CatalogResult.Success).value.associateBy { it.name }
        assertEquals(mapOf("alternate-exchange" to ArgumentValue.Text("dlx")), exchanges.getValue("with-ae").typedArguments)
        assertEquals(mapOf("x-super-stream" to ArgumentValue.Flag(true)), exchanges.getValue("marker").typedArguments)
        assertEquals(mapOf("x-super-stream" to "true"), exchanges.getValue("marker").arguments, "the display text of the Topology screen is unchanged")
        assertTrue(exchanges.getValue("dlx").typedArguments.isEmpty())
        assertTrue(exchanges.getValue("internal-x").internal && !exchanges.getValue("auto-x").durable && exchanges.getValue("auto-x").autoDelete)

        val bindings = (ManagementJson.parseBindingList(FixtureFiles.text("m9-14/e4-bindings.json")) as CatalogResult.Success).value
        val header = bindings.single { it.source == "hdr" }
        assertEquals(
            mapOf("x-match" to ArgumentValue.Text("all"), "region" to ArgumentValue.Text("eu"), "level" to ArgumentValue.Whole(3), "urgent" to ArgumentValue.Flag(true), "ratio" to ArgumentValue.Decimal(1.5)),
            header.typedArguments,
        )
        assertEquals(mapOf("x-stream-partition-order" to ArgumentValue.Whole(0)), bindings.single { it.source == "marker" }.typedArguments)
        assertEquals(BindingDestinationType.EXCHANGE, bindings.single { it.source == "with-ae" }.destinationType)
        assertTrue(bindings.single { it.source == "amq.topic" }.typedArguments.isEmpty(), "a user binding on a built-in exchange is listed")
    }

    @Test
    fun `policies keep name, pattern, apply-to, priority and the typed definition, a negative priority too`() {
        val policies = (ManagementJson.parsePolicies(FixtureFiles.text("m9-14/e6-policies.json")) as CatalogResult.Success).value.associateBy { it.name }
        assertEquals(setOf("e6-all", "e6-streams", "e6-limits"), policies.keys)
        assertEquals(
            PolicyRow(
                name = "e6-limits", vhost = "m914-e6-2f939df3", pattern = "^e6-", applyTo = "queues", priority = 7,
                definition = mapOf(
                    "dead-letter-exchange" to ArgumentValue.Text("dlx"), "delivery-limit" to ArgumentValue.Whole(10), "max-length" to ArgumentValue.Whole(5000),
                    "message-ttl" to ArgumentValue.Whole(86400000), "overflow" to ArgumentValue.Text("reject-publish"),
                ),
            ),
            policies.getValue("e6-limits"),
        )
        assertEquals(-1, policies.getValue("e6-all").priority)
        assertEquals("streams", policies.getValue("e6-streams").applyTo)
        assertEquals(ArgumentValue.Text("1D"), policies.getValue("e6-streams").definition["max-age"])
        val operator = (ManagementJson.parsePolicies(FixtureFiles.text("m9-14/e6-operator-policies.json")) as CatalogResult.Success).value.single()
        assertEquals("e6-op", operator.name); assertEquals(3, operator.priority)
    }

    @Test
    fun `a policy list that is not a list of policies is Unexpected`() {
        assertEquals(CatalogResult.Unexpected, ManagementJson.parsePolicies("<html/>"))
        assertEquals(CatalogResult.Unexpected, ManagementJson.parsePolicies("""{"error":"x"}"""))
        assertEquals(CatalogResult.Unexpected, ManagementJson.parsePolicies("""[{"name":"p","pattern":"^a","definition":{}}]"""), "no apply-to and no priority: nothing is guessed")
        assertEquals(CatalogResult.Success(emptyList<PolicyRow>()), ManagementJson.parsePolicies("[]"))
    }

    // ---- M9-14b F3: the two parsers are tolerant like the exchange and binding ones ---------------------------------------------

    private val tooDeep = "[".repeat(40) + "1" + "]".repeat(40)
    private fun declarationsPage(vararg items: String) = """{"items":[${items.joinToString(",")}],"page":1,"page_count":1,"page_size":500,"total_count":${items.size}}"""

    @Test
    fun `a queue argument the model refuses is left out and the row says so, the typable arguments and the neighbours are untouched`() {
        val bad = """{"name":"x","type":"quorum","durable":true,"auto_delete":false,"exclusive":false,"arguments":{"x-queue-type":"quorum","deep":$tooDeep,"x-delivery-limit":3}}"""
        val good = """{"name":"y","type":"quorum","durable":true,"auto_delete":false,"exclusive":false,"arguments":{"x-queue-type":"quorum","x-delivery-limit":3}}"""
        val result = ManagementJson.parseQueueDeclarations(declarationsPage(bad, good), "/")
        val rows = (result as CatalogResult.Success<QueueDeclarationPage>).value.rows.associateBy { it.name }
        val x = rows.getValue("x")
        assertFalse(x.typedArgumentsComplete)
        assertEquals(mapOf("x-delivery-limit" to ArgumentValue.Whole(3)), x.arguments, "a refused value is never guessed, x-queue-type is still not an argument")
        assertTrue(rows.getValue("y").typedArgumentsComplete, "a neighbour is not marked")
        assertEquals(x.arguments, rows.getValue("y").arguments)
    }

    // M9-14c step 2b (brief table): the writer needs the TYPED values of the four stream settings (S-14c-1: x-max-age is TEXT, the other numbers).
    @Test
    fun `the four stream settings survive the read with their JSON types, the text 7D and the two numbers and the locator`() {
        val stream = """{"name":"s","type":"stream","durable":true,"auto_delete":false,"exclusive":false,"arguments":{"x-queue-type":"stream","x-max-age":"7D","x-max-length-bytes":1000000000,"x-stream-max-segment-size-bytes":100000000,"x-queue-leader-locator":"client-local"}}"""
        val row = (ManagementJson.parseQueueDeclarations(declarationsPage(stream), "/") as CatalogResult.Success<QueueDeclarationPage>).value.rows.single()
        assertEquals(
            mapOf(
                "x-max-age" to ArgumentValue.Text("7D"), "x-max-length-bytes" to ArgumentValue.Whole(1_000_000_000),
                "x-stream-max-segment-size-bytes" to ArgumentValue.Whole(100_000_000), "x-queue-leader-locator" to ArgumentValue.Text("client-local"),
            ),
            row.arguments,
        )
        assertTrue(row.typedArgumentsComplete)
    }

    @Test
    fun `a stream setting the broker shows as another JSON type is kept as that type, nothing is converted`() {
        val stream = """{"name":"s","type":"stream","durable":true,"auto_delete":false,"exclusive":false,"arguments":{"x-queue-type":"stream","x-max-age":604800,"x-max-length-bytes":"1000"}}"""
        val row = (ManagementJson.parseQueueDeclarations(declarationsPage(stream), "/") as CatalogResult.Success<QueueDeclarationPage>).value.rows.single()
        assertEquals(mapOf("x-max-age" to ArgumentValue.Whole(604800), "x-max-length-bytes" to ArgumentValue.Text("1000")), row.arguments)
    }

    @Test
    fun `a stream or exclusive queue with a refused argument is marked too, the page still succeeds`() {
        val stream = """{"name":"s","type":"stream","durable":true,"auto_delete":false,"exclusive":false,"arguments":{"x-queue-type":"stream","deep":$tooDeep}}"""
        val exclusive = """{"name":"e","type":"classic","durable":false,"auto_delete":false,"exclusive":true,"arguments":{"deep":$tooDeep}}"""
        val rows = (ManagementJson.parseQueueDeclarations(declarationsPage(stream, exclusive), "/") as CatalogResult.Success<QueueDeclarationPage>).value.rows
        assertEquals(listOf(false, false), rows.map { it.typedArgumentsComplete })
        assertEquals(listOf(emptyMap<String, ArgumentValue>(), emptyMap<String, ArgumentValue>()), rows.map { it.arguments })
    }

    @Test
    fun `a declarations page that is refused as a structure is still Unexpected even next to a refused value`() {
        val bad = """{"name":"x","type":"quorum","durable":true,"auto_delete":false,"exclusive":false,"arguments":{"deep":$tooDeep}}"""
        val noFlag = """{"name":"z","type":"quorum","auto_delete":false,"exclusive":false,"arguments":{}}"""
        assertEquals(CatalogResult.Unexpected, ManagementJson.parseQueueDeclarations(declarationsPage(bad, noFlag), "/"))
    }

    @Test
    fun `a policy definition value the model refuses is left out and the row says so, neighbours are untouched`() {
        val bad = """{"vhost":"/","name":"p","pattern":".*","apply-to":"all","priority":2,"definition":{"deep":$tooDeep,"max-length":5}}"""
        val good = """{"vhost":"/","name":"q","pattern":".*","apply-to":"queues","priority":0,"definition":{"max-length":5}}"""
        val rows = (ManagementJson.parsePolicies("[$bad,$good]") as CatalogResult.Success<List<PolicyRow>>).value.associateBy { it.name }
        val p = rows.getValue("p")
        assertFalse(p.definitionComplete)
        assertEquals(mapOf("max-length" to ArgumentValue.Whole(5)), p.definition)
        assertEquals(Triple(".*", "all", 2), Triple(p.pattern, p.applyTo, p.priority), "the rest of the row is read as usual")
        assertTrue(rows.getValue("q").definitionComplete, "a neighbour is not marked")
    }

    @Test
    fun `operator policies are tolerant the same way, and a policy without a refused value is complete by default`() {
        assertTrue(PolicyRow("p", "/", ".*", "all", 0, emptyMap()).definitionComplete)
        assertTrue(QueueDeclaration("q", "/", "quorum").typedArgumentsComplete)
        val bad = """[{"vhost":"/","name":"op","pattern":".*","apply-to":"queues","priority":1,"definition":{"deep":$tooDeep}}]"""
        val row = (ManagementJson.parsePolicies(bad) as CatalogResult.Success<List<PolicyRow>>).value.single()
        assertFalse(row.definitionComplete)
        assertTrue(row.definition.isEmpty())
    }

    @Test
    fun `the whole catalog read of declarations and policies succeeds with a refused value, no CatalogResult variant is new`() {
        val seen = CopyOnWriteArrayList<Seen>()
        val bad = """{"name":"x","type":"quorum","durable":true,"auto_delete":false,"exclusive":false,"arguments":{"deep":$tooDeep}}"""
        val policy = """[{"vhost":"/","name":"p","pattern":".*","apply-to":"all","priority":0,"definition":{"deep":$tooDeep}}]"""
        val catalog = catalog(seen) { path, _ -> 200 to (if (path.contains("policies")) policy else declarationsPage(bad)) }
        val declarations = runBlocking { catalog.queueDeclarations("/") }
        assertFalse((declarations as CatalogResult.Success).value.single().typedArgumentsComplete)
        assertFalse((runBlocking { catalog.policies("/") } as CatalogResult.Success).value.single().definitionComplete)
        assertFalse((runBlocking { catalog.operatorPolicies("/") } as CatalogResult.Success).value.single().definitionComplete)
    }

    // ---- the catalog over a recording engine ---------------------------------------------------------------------------------

    private class Seen(val method: String, val path: String, val page: String?)

    private fun catalog(seen: MutableList<Seen>, answer: (path: String, page: String?) -> Pair<Int, String>): ManagementTopologyCatalog {
        val engine = MockEngine { request ->
            val page = request.url.parameters["page"]
            seen += Seen(request.method.value, request.url.encodedPath, page)
            val (status, body) = answer(request.url.encodedPath, page)
            respond(body, HttpStatusCode.fromValue(status), headersOf("Content-Type", "application/json"))
        }
        return ManagementTopologyCatalog(M508a.http(engine), ManagementRequests("http://broker.example:15672", BrokerTransport.Plain))
    }

    private fun queueItem(n: Int) = """{"name":"queue-%04d","type":"quorum","durable":true,"auto_delete":false,"exclusive":false,"arguments":{"x-queue-type":"quorum","x-delivery-limit":$n}}""".format(n)

    private fun pageBody(page: Int, pageCount: Int, from: Int, to: Int) =
        """{"items":[${(from..to).joinToString(",") { queueItem(it) }}],"page":$page,"page_count":$pageCount,"page_size":500,"total_count":1200}"""

    @Test
    fun `the declarations of 1,200 queues come from three pages, one GET each, in order, and nothing is lost`() {
        val seen = CopyOnWriteArrayList<Seen>()
        val catalog = catalog(seen) { _, page ->
            when (page) {
                "1" -> 200 to pageBody(1, 3, 1, 500)
                "2" -> 200 to pageBody(2, 3, 501, 1000)
                "3" -> 200 to pageBody(3, 3, 1001, 1200)
                else -> 400 to "{}"
            }
        }
        val result = runBlocking { catalog.queueDeclarations("/") } as CatalogResult.Success<List<QueueDeclaration>>
        assertEquals(1200, result.value.size)
        assertEquals((1..1200).map { "queue-%04d".format(it) }, result.value.map { it.name })
        assertEquals(ArgumentValue.Whole(1200), result.value.last().arguments["x-delivery-limit"])
        assertEquals(listOf("GET /api/queues/%2F 1", "GET /api/queues/%2F 2", "GET /api/queues/%2F 3"), seen.map { "${it.method} ${it.path} ${it.page}" })
    }

    @Test
    fun `an empty vhost is one page, an exact multiple of the page size does not ask for a page past the last`() {
        val seen = CopyOnWriteArrayList<Seen>()
        val empty = catalog(seen) { _, _ -> 200 to """{"items":[],"page":1,"page_count":0,"page_size":500,"total_count":0}""" }
        assertEquals(CatalogResult.Success(emptyList<QueueDeclaration>()), runBlocking { empty.queueDeclarations("/") })
        assertEquals(listOf("1"), seen.map { it.page })
        seen.clear()
        val two = catalog(seen) { _, page -> 200 to if (page == "1") pageBody(1, 2, 1, 500) else pageBody(2, 2, 501, 1000) }
        assertEquals(1000, (runBlocking { two.queueDeclarations("/") } as CatalogResult.Success).value.size)
        assertEquals(listOf("1", "2"), seen.map { it.page })
    }

    @Test
    fun `a page that fails gives that failure and no partial list`() {
        for ((status, expected) in listOf(500 to CatalogResult.Unexpected, 401 to CatalogResult.NotAuthorised, 403 to CatalogResult.NotAuthorised, 404 to CatalogResult.NotFound)) {
            val seen = CopyOnWriteArrayList<Seen>()
            val catalog = catalog(seen) { _, page -> if (page == "2") status to "{}" else 200 to pageBody(1, 3, 1, 500) }
            assertEquals(expected, runBlocking { catalog.queueDeclarations("/") }, "page 2 -> $status")
            assertEquals(listOf("1", "2"), seen.map { it.page }, "no page after the failed one is asked for")
        }
    }

    @Test
    fun `a page that disagrees with the one asked for stops the read instead of looping`() {
        val seen = CopyOnWriteArrayList<Seen>()
        val catalog = catalog(seen) { _, _ -> 200 to pageBody(1, 2, 1, 500) }
        assertEquals(CatalogResult.Unexpected, runBlocking { catalog.queueDeclarations("/") })
        assertTrue(seen.size <= 3, "asked ${seen.size} times")
    }

    @Test
    fun `policies and operator policies are one GET each of their own path`() {
        val seen = CopyOnWriteArrayList<Seen>()
        val catalog = catalog(seen) { path, _ ->
            when (path) {
                "/api/policies/%2F" -> 200 to FixtureFiles.text("m9-14/e6-policies.json")
                "/api/operator-policies/%2F" -> 200 to FixtureFiles.text("m9-14/e6-operator-policies.json")
                else -> 404 to "{}"
            }
        }
        assertEquals(3, (runBlocking { catalog.policies("/") } as CatalogResult.Success).value.size)
        assertEquals("e6-op", (runBlocking { catalog.operatorPolicies("/") } as CatalogResult.Success).value.single().name)
        assertEquals(listOf("GET /api/policies/%2F", "GET /api/operator-policies/%2F"), seen.map { "${it.method} ${it.path}" })
    }

    @Test
    fun `policy reads map statuses like the other reads, and a vhost without permission is NotFound`() {
        for (read in listOf<suspend (ManagementTopologyCatalog) -> CatalogResult<List<PolicyRow>>>({ it.policies("/") }, { it.operatorPolicies("/") })) {
            for ((status, expected) in listOf(401 to CatalogResult.NotAuthorised, 403 to CatalogResult.NotAuthorised, 404 to CatalogResult.NotFound, 500 to CatalogResult.Unexpected)) {
                assertEquals(expected, runBlocking { read(catalog(CopyOnWriteArrayList()) { _, _ -> status to "{}" }) }, "status $status")
            }
            assertEquals(CatalogResult.Unexpected, runBlocking { read(catalog(CopyOnWriteArrayList()) { _, _ -> 200 to "<html/>" }) })
        }
    }

    @Test
    fun `an unreachable broker is Unreachable for the three reads`() {
        val http = ManagementHttp(TlsSupport.buildHttpClient(BrokerTransport.Plain, 2_000), "svc", "pw")
        try {
            val dead = ManagementTopologyCatalog(http, ManagementRequests("http://127.0.0.1:${closedLocalPort()}", BrokerTransport.Plain))
            assertEquals(CatalogResult.Unreachable, runBlocking { dead.queueDeclarations("/") })
            assertEquals(CatalogResult.Unreachable, runBlocking { dead.policies("/") })
            assertEquals(CatalogResult.Unreachable, runBlocking { dead.operatorPolicies("/") })
        } finally {
            http.close()
        }
    }

    @Test
    fun `THE GUARD over every export read and every outcome, only GET, only the allowed paths, a queue named get or purge is just a name`() {
        val seen = CopyOnWriteArrayList<Seen>()
        val tricky = """{"items":[{"name":"get","type":"classic","durable":true,"auto_delete":false,"exclusive":false,"arguments":{}},{"name":"purge","type":"quorum","durable":true,"auto_delete":false,"exclusive":false,"arguments":{}}],"page":1,"page_count":1,"page_size":500,"total_count":2}"""
        for (status in listOf(200, 401, 403, 404, 500)) {
            val catalog = catalog(seen) { _, _ -> status to if (status == 200) tricky else "{}" }
            runBlocking { catalog.queueDeclarations("/"); catalog.policies("/"); catalog.operatorPolicies("/"); catalog.queueDeclarations("a vhost") }
        }
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it.method == "GET" }, seen.map { "${it.method} ${it.path}" }.toString())
        val allowed = Regex("^/api/(queues|policies|operator-policies)/[^/]+$")
        assertTrue(seen.all { allowed.matches(it.path) }, "a path outside the allowed shapes: ${seen.map { it.path }.distinct()}")
        assertTrue(seen.none { listOf("/get", "/contents", "purge", "consume", "message").any { w -> it.path.contains(w) } }, seen.map { it.path }.toString())
    }
}
