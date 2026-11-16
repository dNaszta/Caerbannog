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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** M9-14 step 4 exploratory checks (no Docker): hostile data and scale for the new reads, found while verifying round 1. */
class ManagementExportExploratoryTest {

    private fun catalog(answer: (page: String?) -> Pair<Int, String>, pages: MutableList<String?> = CopyOnWriteArrayList()): ManagementTopologyCatalog {
        val engine = MockEngine { request ->
            pages += request.url.parameters["page"]
            val (status, body) = answer(request.url.parameters["page"])
            respond(body, HttpStatusCode.fromValue(status), headersOf("Content-Type", "application/json"))
        }
        return ManagementTopologyCatalog(M508a.http(engine), ManagementRequests("http://broker.example:15672", BrokerTransport.Plain))
    }

    private fun item(n: Int) = """{"name":"q-%05d","type":"quorum","durable":true,"auto_delete":false,"exclusive":false,"arguments":{"x-queue-type":"quorum"}}""".format(n)
    private fun page(p: Int, count: Int) = """{"items":[${((p - 1) * 500 + 1..minOf(p * 500, 5000)).joinToString(",") { item(it) }}],"page":$p,"page_count":$count,"page_size":500,"total_count":5000}"""

    @Test
    fun `5000 queues over ten pages are all read in order`() {
        val pages = CopyOnWriteArrayList<String?>()
        val result = runBlocking { catalog({ p -> 200 to page(p!!.toInt(), 10) }, pages).queueDeclarations("/") } as CatalogResult.Success<List<QueueDeclaration>>
        assertEquals(5000, result.value.size)
        assertEquals((1..5000).map { "q-%05d".format(it) }, result.value.map { it.name })
        assertEquals((1..10).map { it.toString() }, pages)
    }

    @Test
    fun `a failed page in the middle of ten gives the failure and no list, and the later pages are not asked for`() {
        val pages = CopyOnWriteArrayList<String?>()
        val result = runBlocking { catalog({ p -> if (p == "6") 500 to "{}" else 200 to page(p!!.toInt(), 10) }, pages).queueDeclarations("/") }
        assertEquals(CatalogResult.Unexpected, result)
        assertEquals((1..6).map { it.toString() }, pages)
    }

    @Test
    fun `hostile names and arguments are read as data, unicode keys, markup, 200 characters, right-to-left and huge numbers`() {
        val hostile = "<b>x</b>&amp;‮evil" + "n".repeat(200)
        val body = """{"items":[{"name":${kotlinx.serialization.json.JsonPrimitive(hostile)},"type":"classic","durable":true,"auto_delete":false,"exclusive":false,"arguments":{"ключ-キー":"значение","big":99999999999999999999,"neg":-9223372036854775808,"nothing":null,"nested":{"a":[1,{"b":true}]}}}],"page":1,"page_count":1,"page_size":500,"total_count":1}"""
        val row = ((ManagementJson.parseQueueDeclarations(body, "/") as CatalogResult.Success).value.rows).single()
        assertEquals(hostile, row.name)
        assertEquals(ArgumentValue.Text("значение"), row.arguments["ключ-キー"])
        assertEquals(ArgumentValue.Whole(Long.MIN_VALUE), row.arguments["neg"])
        assertEquals(ArgumentValue.Nothing, row.arguments["nothing"])
        assertEquals(ArgumentValue.Table(mapOf("a" to ArgumentValue.Items(listOf(ArgumentValue.Whole(1), ArgumentValue.Table(mapOf("b" to ArgumentValue.Flag(true))))))), row.arguments["nested"])
        // characterisation: a number above the Long range is read as a decimal (1.0E20), not kept exactly
        assertInstanceOf(ArgumentValue.Decimal::class.java, row.arguments["big"])
    }

    @Test
    fun `a policy whose definition is not an object, or a policy list with a null, is Unexpected, never a half list`() {
        val ok = """{"vhost":"/","name":"p","pattern":".*","apply-to":"all","priority":0,"definition":{}}"""
        for (bad in listOf(
            """[{"vhost":"/","name":"p","pattern":".*","apply-to":"all","priority":0,"definition":"max-length=5"}]""",
            """[{"vhost":"/","name":"p","pattern":".*","apply-to":"all","priority":0,"definition":null}]""",
            """[$ok,null]""",
            """[$ok,{"vhost":"/","name":"q","pattern":".*","apply-to":"all","priority":"high","definition":{}}]""",
        )) assertEquals(CatalogResult.Unexpected, ManagementJson.parsePolicies(bad), bad)
    }

    @Test
    fun `operator policies answer 404 for a vhost without permission exactly like policies, NotFound`() {
        val c = catalog({ 404 to """{"error":"Object Not Found","reason":"Not Found"}""" })
        assertEquals(CatalogResult.NotFound, runBlocking { c.policies("secret") })
        assertEquals(CatalogResult.NotFound, runBlocking { c.operatorPolicies("secret") })
    }

    // ---- F1: the Topology screen's reads must not start to fail for an argument the migration model refuses ----------------

    private val tooDeep = "[".repeat(40) + "1" + "]".repeat(40)

    @Disabled("M9-14 round 1 finding F1 (major): parseExchanges and parseBindingList now answer Unexpected for an argument nested deeper than 32, so the read-only Topology screen cannot open a vhost it opened before. Enable when the architect decides the screen stays tolerant.")
    @Test
    fun `an exchange or binding argument the migration model refuses does not stop the Topology screen reads`() {
        val exchange = """[{"name":"x","vhost":"/","type":"headers","durable":true,"auto_delete":false,"internal":false,"arguments":{"deep":$tooDeep}}]"""
        val binding = """[{"source":"x","vhost":"/","destination":"q","destination_type":"queue","routing_key":"","arguments":{"deep":$tooDeep}}]"""
        assertInstanceOf(CatalogResult.Success::class.java, ManagementJson.parseExchanges(exchange))
        assertInstanceOf(CatalogResult.Success::class.java, ManagementJson.parseBindingList(binding))
    }

    @Test
    fun `characterisation of F1, today the same bodies are Unexpected for the screen reads`() {
        val exchange = """[{"name":"x","vhost":"/","type":"headers","durable":true,"auto_delete":false,"internal":false,"arguments":{"deep":$tooDeep}}]"""
        assertTrue(ManagementJson.parseExchanges(exchange) == CatalogResult.Unexpected, "if this fails the screen read became tolerant: enable the Disabled test above")
    }
}
