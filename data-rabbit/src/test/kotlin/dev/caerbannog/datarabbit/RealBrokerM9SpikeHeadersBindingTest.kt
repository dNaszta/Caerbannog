package dev.caerbannog.datarabbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * M9-00a spike, S-E1 to S-E5: bindings on a headers exchange (argument types, `x-match` values, two bindings that differ only in
 * arguments, routing by header, routing key ignored on fanout and headers exchanges). Findings: docs/tasks/M9-00a-spike-results.md.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9SpikeHeadersBindingTest*'`.
 */
@Tag("broker")
@Tag("spike")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RealBrokerM9SpikeHeadersBindingTest {

    companion object {
        private val rabbit: RabbitMQContainer = brokerWithPlugins(M9_IMAGE)
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { rabbit.start(); api = MgmtApi(rabbit) }

        @JvmStatic @AfterAll fun stop() = rabbit.stop()
    }

    private val scope = M9SpikeScope(MgmtApi(rabbit))

    @AfterEach fun cleanUp() = scope.close()

    @Test @Order(0)
    fun `the image is a 4x broker and its version is recorded`() = m9AssertBrokerIs4x(api)

    private fun bindings(exchange: String, queue: String): JsonArray = api.get("/api/bindings/%2F/e/$exchange/q/$queue").arr

    @Test @Order(1)
    fun `S-E1 argument types are kept when the binding is read back`() {
        val ex = scope.exchange("m9e1x", "headers"); val q = scope.quorum("m9e1q")
        val reply = scope.bind(ex, q, "", """{"x-match":"all","text":"abc","number":10,"negative":-3,"decimal":1.5,"flag":true,"numeric-text":"10"}""")
        m9Note("S-E1", "POST: ${reply.status} ${reply.body.take(120)}")
        assertEquals(201, reply.status)
        val list = bindings(ex, q)
        m9Fixture("s-e1-binding-types.json", list.toString().replace(ex, "<exchange>").replace(q, "<queue>"))
        m9Note("S-E1", "read back: ${list.toString().replace(ex, "<exchange>").replace(q, "<queue>")}")
        val args = (list.single() as JsonObject)["arguments"] as JsonObject
        assertEquals(
            mapOf("x-match" to "\"all\"", "text" to "\"abc\"", "number" to "10", "negative" to "-3", "decimal" to "1.5", "flag" to "true", "numeric-text" to "\"10\""),
            args.mapValues { it.value.toString() }, "argument types as read back",
        )
        // The same through the per-exchange list.
        val viaSource = api.get("/api/exchanges/%2F/$ex/bindings/source").arr
        assertEquals(args, (viaSource.single() as JsonObject)["arguments"])
    }

    @Test @Order(2)
    fun `S-E2 x-match values and the status of an invalid one`() {
        val ex = scope.exchange("m9e2x", "headers")
        val table = linkedMapOf<String, String>()
        val cases = linkedMapOf(
            "all" to """{"x-match":"all","a":1}""", "any" to """{"x-match":"any","a":1}""",
            "all-with-x" to """{"x-match":"all-with-x","a":1}""", "any-with-x" to """{"x-match":"any-with-x","a":1}""",
            "none" to """{"a":1}""", "invalid" to """{"x-match":"bogus","a":1}""", "uppercase" to """{"x-match":"ALL","a":1}""",
            "number" to """{"x-match":1,"a":1}""",
        )
        cases.forEach { (label, args) ->
            val q = scope.quorum("m9e2q")
            val post = scope.bind(ex, q, "", args)
            val list = bindings(ex, q)
            val shown = list.map { ((it as JsonObject)["arguments"] as JsonObject)["x-match"]?.toString() ?: "absent" }
            table[label] = "POST ${post.status}${if (post.status >= 400) " ${post.reason().take(100)}" else ""}; bindings listed: ${list.size}, x-match shown: $shown"
            m9Note("S-E2", "$label: ${table[label]}")
        }
        m9Fixture("s-e2-x-match-statuses.txt", table.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        assertEquals("POST 201; bindings listed: 1, x-match shown: [\"all\"]", table["all"])
        assertEquals("POST 201; bindings listed: 1, x-match shown: [\"any\"]", table["any"])
        assertEquals("POST 201; bindings listed: 1, x-match shown: [\"all-with-x\"]", table["all-with-x"])
        assertEquals("POST 201; bindings listed: 1, x-match shown: [\"any-with-x\"]", table["any-with-x"])
        assertEquals("POST 201; bindings listed: 1, x-match shown: [absent]", table["none"])
        listOf("invalid", "uppercase").forEach {
            assertTrue(table.getValue(it).startsWith("POST 400 ") && table.getValue(it).contains("expected all, any, all-with-x, or any-with-x") && table.getValue(it).endsWith("bindings listed: 0, x-match shown: []"), "$it: ${table[it]}")
        }
        assertTrue(table.getValue("number").startsWith("POST 400 ") && table.getValue("number").contains("expected longstr") && table.getValue("number").endsWith("bindings listed: 0, x-match shown: []"), "number: ${table["number"]}")
    }

    @Test @Order(3)
    fun `S-E3 two bindings that differ only in arguments, properties key and a repeated POST`() {
        val ex = scope.exchange("m9e3x", "headers"); val q = scope.quorum("m9e3q")
        val first = scope.bind(ex, q, "", """{"x-match":"all","a":1}""")
        val second = scope.bind(ex, q, "", """{"x-match":"all","a":2}""")
        val repeat = scope.bind(ex, q, "", """{"x-match":"all","a":1}""")
        val list = bindings(ex, q)
        m9Note("S-E3", "POST1 ${first.status} location=${first.body.take(60)}, POST2 ${second.status}, repeated POST1 ${repeat.status}; list size ${list.size}")
        list.forEach { m9Note("S-E3", "entry: routing_key='${(it as JsonObject)["routing_key"]}' properties_key=${it["properties_key"]} arguments=${it["arguments"]}") }
        m9Fixture("s-e3-two-bindings.json", list.toString().replace(ex, "<exchange>").replace(q, "<queue>"))
        assertEquals(listOf(201, 201), listOf(first.status, second.status))
        assertEquals(2, list.size, "two entries, one per distinct argument set")
        assertEquals(2, list.map { (it as JsonObject)["properties_key"].toString() }.toSet().size, "distinct properties_key")
        assertEquals(201, repeat.status, "a repeated POST of an existing binding")
    }

    private fun headersPublish(exchange: String, routingKey: String, headers: String): MgmtApi.Reply =
        api.post("/api/exchanges/%2F/$exchange/publish", """{"properties":{"headers":$headers},"routing_key":"$routingKey","payload":"p","payload_encoding":"string"}""")

    /** Publishes with the given headers, returns which of the queues got the message (by a control message that reaches all of them). */
    private fun arrivals(exchange: String, queues: Map<String, String>, headers: String, routingKey: String = ""): Pair<Boolean, Set<String>> {
        val marker = "m-${System.nanoTime()}"
        val reply = api.post("/api/exchanges/%2F/$exchange/publish", """{"properties":{"headers":$headers},"routing_key":"$routingKey","payload":"$marker","payload_encoding":"string"}""")
        val routed = (reply.obj["routed"] as JsonPrimitive).content.toBoolean()
        val got = queues.filter { (_, q) -> m9Peek(api, q).any { it.payload == marker } }.keys
        return routed to got
    }

    @Test @Order(4)
    fun `S-E4 routing by header for all and any, number against text, x-match without a header`() {
        val ex = scope.exchange("m9e4x", "headers")
        val qs = linkedMapOf(
            "all(a=1,b=x)" to scope.quorum("m9e4q1"), "any(a=1,b=x)" to scope.quorum("m9e4q2"), "all(n=text 10)" to scope.quorum("m9e4q3"),
            "all(n=number 10)" to scope.quorum("m9e4q4"), "all(only x-match)" to scope.quorum("m9e4q5"), "any(only x-match)" to scope.quorum("m9e4q6"),
        )
        scope.bind(ex, qs.getValue("all(a=1,b=x)"), "", """{"x-match":"all","a":1,"b":"x"}""")
        scope.bind(ex, qs.getValue("any(a=1,b=x)"), "", """{"x-match":"any","a":1,"b":"x"}""")
        scope.bind(ex, qs.getValue("all(n=text 10)"), "", """{"x-match":"all","n":"10"}""")
        scope.bind(ex, qs.getValue("all(n=number 10)"), "", """{"x-match":"all","n":10}""")
        scope.bind(ex, qs.getValue("all(only x-match)"), "", """{"x-match":"all"}""")
        scope.bind(ex, qs.getValue("any(only x-match)"), "", """{"x-match":"any"}""")
        val table = linkedMapOf<String, Pair<Boolean, Set<String>>>()
        mapOf(
            "headers a=1,b=x" to """{"a":1,"b":"x"}""", "headers a=1" to """{"a":1}""", "headers b=x" to """{"b":"x"}""",
            "headers a=2,b=y" to """{"a":2,"b":"y"}""", "headers n=10 (number)" to """{"n":10}""", "headers n=\"10\" (text)" to """{"n":"10"}""",
            "no headers at all" to "{}", "headers z=1 (unrelated)" to """{"z":1}""",
        ).forEach { (label, headers) ->
            table[label] = arrivals(ex, qs, headers)
            m9Note("S-E4", "publish $label -> routed=${table[label]!!.first} arrived in ${table[label]!!.second}")
        }
        m9Fixture("s-e4-routing-table.txt", table.entries.joinToString("\n") { "${it.key}: routed=${it.value.first} arrived=${it.value.second.sorted()}" })
        assertEquals(expectedRouting, table.mapValues { it.value.second }, "routing table")
    }

    private val expectedRouting: Map<String, Set<String>> = mapOf(
        "headers a=1,b=x" to setOf("all(a=1,b=x)", "any(a=1,b=x)", "all(only x-match)"),
        "headers a=1" to setOf("any(a=1,b=x)", "all(only x-match)"),
        "headers b=x" to setOf("any(a=1,b=x)", "all(only x-match)"),
        "headers a=2,b=y" to setOf("all(only x-match)"),
        "headers n=10 (number)" to setOf("all(n=number 10)", "all(only x-match)"),
        "headers n=\"10\" (text)" to setOf("all(n=text 10)", "all(only x-match)"),
        "no headers at all" to setOf("all(only x-match)"),
        "headers z=1 (unrelated)" to setOf("all(only x-match)"),
    )

    @Test @Order(5)
    fun `S-E5 the routing key of a binding is ignored by fanout and headers exchanges`() {
        val fan = scope.exchange("m9e5f", "fanout"); val fq = scope.quorum("m9e5fq")
        val bind = scope.bind(fan, fq, "ignored-key")
        m9Note("S-E5", "fanout binding with a routing key: POST ${bind.status}; listed routing_key='${(bindings(fan, fq).single() as JsonObject)["routing_key"]}'")
        val fanOut = arrivals(fan, mapOf("fanout" to fq), "{}", routingKey = "a-different-key")
        m9Note("S-E5", "fanout, publish with another key: routed=${fanOut.first} arrived=${fanOut.second}")
        val hx = scope.exchange("m9e5h", "headers"); val hq = scope.quorum("m9e5hq")
        scope.bind(hx, hq, "binding-key", """{"x-match":"all","a":1}""")
        val noKeyMatch = arrivals(hx, mapOf("headers" to hq), """{"a":1}""", routingKey = "other-key")
        val sameKeyNoHeaders = arrivals(hx, mapOf("headers" to hq), "{}", routingKey = "binding-key")
        m9Note("S-E5", "headers exchange, headers match and other key: routed=${noKeyMatch.first} arrived=${noKeyMatch.second}; same key but no header: routed=${sameKeyNoHeaders.first} arrived=${sameKeyNoHeaders.second}")
        // Two bindings that differ only in routing key on a headers exchange with the same arguments.
        val hq2 = scope.quorum("m9e5hq2")
        scope.bind(hx, hq2, "k1", """{"x-match":"all","b":1}""")
        scope.bind(hx, hq2, "k2", """{"x-match":"all","b":1}""")
        m9Note("S-E5", "headers bindings that differ only in routing key: ${bindings(hx, hq2).size} entries, properties_keys ${bindings(hx, hq2).map { (it as JsonObject)["properties_key"] }}")
        assertEquals(201, bind.status)
        assertEquals(true to setOf("fanout"), fanOut, "a fanout exchange ignores the routing key of the binding and of the publish")
        assertEquals(true to setOf("headers"), noKeyMatch, "a headers exchange routes on headers: matching headers, other routing key")
        assertEquals(false to emptySet<String>(), sameKeyNoHeaders, "a headers exchange does not route on the routing key alone")
        val keys = bindings(hx, hq2).map { (it as JsonObject)["properties_key"].toString().trim('"') }
        assertEquals(2, keys.size, "bindings that differ only in routing key are two entries")
        assertEquals(1, keys.map { it.substringAfter('~') }.toSet().size, "same arguments, same hash part of the properties key")
    }
}
