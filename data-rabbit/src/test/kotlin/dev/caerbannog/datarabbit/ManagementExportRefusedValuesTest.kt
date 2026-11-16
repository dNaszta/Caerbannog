package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.QueueDeclaration
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** M9-14b step 4 (round 1): refused values at scale and the unchanged tolerant reads of the Topology screen and planner (exchanges and bindings). */
class ManagementExportRefusedValuesTest {

    private val tooDeep = "[".repeat(40) + "1" + "]".repeat(40)

    private fun catalog(answer: (page: String?) -> Pair<Int, String>, pages: MutableList<String?> = CopyOnWriteArrayList()): ManagementTopologyCatalog {
        val engine = MockEngine { request ->
            pages += request.url.parameters["page"]
            val (status, body) = answer(request.url.parameters["page"])
            respond(body, HttpStatusCode.fromValue(status), headersOf("Content-Type", "application/json"))
        }
        return ManagementTopologyCatalog(M508a.http(engine), ManagementRequests("http://broker.example:15672", BrokerTransport.Plain))
    }

    private fun item(n: Int) =
        """{"name":"q-%05d","type":"quorum","durable":true,"auto_delete":false,"exclusive":false,"arguments":{"x-queue-type":"quorum"${if (n % 10 == 0) ""","deep":$tooDeep""" else ""},"x-delivery-limit":$n}}""".format(n)
    private fun page(p: Int, count: Int) = """{"items":[${((p - 1) * 500 + 1..minOf(p * 500, 5000)).joinToString(",") { item(it) }}],"page":$p,"page_count":$count,"page_size":500,"total_count":5000}"""

    @Test
    fun `5000 queues over ten pages with every tenth refused argument are all read, 500 are marked, the typable argument is kept`() {
        val pages = CopyOnWriteArrayList<String?>()
        val result = runBlocking { catalog({ p -> 200 to page(p!!.toInt(), 10) }, pages).queueDeclarations("/") } as CatalogResult.Success<List<QueueDeclaration>>
        assertEquals(5000, result.value.size)
        assertEquals(500, result.value.count { !it.typedArgumentsComplete })
        assertTrue(result.value.filter { !it.typedArgumentsComplete }.all { it.name.takeLast(1) == "0" })
        assertTrue(result.value.all { it.arguments["x-delivery-limit"] is ArgumentValue.Whole && !it.arguments.containsKey("deep") })
        assertEquals((1..10).map { it.toString() }, pages)
    }

    @Test
    fun `a failed page after refused values is still the failure, never a partial list`() {
        val result = runBlocking { catalog({ p -> if (p == "4") 500 to "{}" else 200 to page(p!!.toInt(), 10) }).queueDeclarations("/") }
        assertEquals(CatalogResult.Unexpected, result)
    }

    @Test
    fun `a refused value next to a refused structure on a later page is still Unexpected`() {
        val bad = """{"items":[{"name":"q","type":"quorum","durable":true,"auto_delete":false,"exclusive":false,"arguments":"x"}],"page":2,"page_count":2,"page_size":500,"total_count":501}"""
        val result = runBlocking { catalog({ p -> if (p == "1") 200 to page(1, 2) else 200 to bad }).queueDeclarations("/") }
        assertEquals(CatalogResult.Unexpected, result)
    }

    @Test
    fun `the exchange and binding reads are unchanged, a non-object arguments is a marked row and a refused value is left out`() {
        val exchange = """[{"name":"x","vhost":"/","type":"headers","durable":true,"auto_delete":false,"internal":false,"arguments":"oops"},
            {"name":"y","vhost":"/","type":"headers","durable":true,"auto_delete":false,"internal":false,"arguments":{"deep":$tooDeep,"ok":1}}]"""
        val rows = (ManagementJson.parseExchanges(exchange) as CatalogResult.Success).value.associateBy { it.name }
        assertFalse(rows.getValue("x").typedArgumentsComplete)
        assertFalse(rows.getValue("y").typedArgumentsComplete)
        assertEquals(mapOf("ok" to ArgumentValue.Whole(1)), rows.getValue("y").typedArguments)
        val binding = """[{"source":"x","vhost":"/","destination":"q","destination_type":"queue","routing_key":"","arguments":[1]}]"""
        assertFalse((ManagementJson.parseBindingList(binding) as CatalogResult.Success).value.single().typedArgumentsComplete)
    }

    @Test
    fun `a null or missing queue arguments is complete and empty, a null policy definition is still Unexpected`() {
        fun page(arguments: String) = """{"items":[{"name":"q","type":"classic","durable":true,"auto_delete":false,"exclusive":false$arguments}],"page":1,"page_count":1,"page_size":500,"total_count":1}"""
        for (variant in listOf("", ""","arguments":null""", ""","arguments":{}""")) {
            val row = ((ManagementJson.parseQueueDeclarations(page(variant), "/") as CatalogResult.Success).value.rows).single()
            assertTrue(row.typedArgumentsComplete && row.arguments.isEmpty(), variant)
        }
        assertEquals(CatalogResult.Unexpected, ManagementJson.parsePolicies("""[{"vhost":"/","name":"p","pattern":".*","apply-to":"all","priority":0,"definition":null}]"""))
    }

    @Test
    fun `unicode keys and values around a refused one are kept exactly`() {
        val body = """[{"vhost":"/","name":"p","pattern":".*","apply-to":"all","priority":0,"definition":{"ключ":"значение","deep":$tooDeep,"日本":3}}]"""
        val row = (ManagementJson.parsePolicies(body) as CatalogResult.Success).value.single()
        assertFalse(row.definitionComplete)
        assertEquals(mapOf("ключ" to ArgumentValue.Text("значение"), "日本" to ArgumentValue.Whole(3)), row.definition)
    }
}
