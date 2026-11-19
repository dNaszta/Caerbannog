package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
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
 * M9-11 step 2, live (@broker): the headers binding the planner form writes, on a real RabbitMQ 4.x.
 *  1. The small probe the brief asks for: does a BOOLEAN header match a boolean binding argument (positive control: a number argument and a number
 *     header on the same exchange)? The result decides whether the form offers "true or false" as a routing value (brief, Open points). Everything
 *     else of S-E is already measured by RealBrokerM9SpikeHeadersBindingTest and is not repeated.
 *  2. A round trip of what the form writes: the action is built as `BindingForm.actions` builds it (x-match plus typed values, empty routing key),
 *     sent by the app's own request builder (`MigrationRequests`), read back with its types, and a publish with the matching and a non-matching header
 *     set reaches the queue or not. Queue messages are only peeked with the test's own requeue peek, never consumed.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9HeadersBindingTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RealBrokerM9HeadersBindingTest {

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

    /** Publishes [headers] (a JSON object) to [exchange]; returns the queues of [queues] that hold the message afterwards. */
    private fun arrivals(exchange: String, queues: Map<String, String>, headers: String): Set<String> {
        val marker = "m-${System.nanoTime()}"
        val reply = api.post("/api/exchanges/%2F/$exchange/publish", """{"properties":{"headers":$headers},"routing_key":"","payload":"$marker","payload_encoding":"string"}""")
        assertTrue(reply.ok, "publish: $reply")
        val routed = (reply.obj["routed"] as JsonPrimitive).content.toBoolean()
        val got = queues.filter { (_, q) -> m9Peek(api, q).any { it.payload == marker } }.keys
        assertEquals(got.isNotEmpty(), routed, "the publish reply's routed flag and the queues disagree for $headers")
        return got
    }

    private fun bindAs(action: MigrationAction.DeclareBinding): MgmtApi.Reply {
        val write = requireNotNull(MigrationRequests.requestFor(action)) { "no request for ${action.actionName}" }
        return api.call(write.method, write.path, write.body.toString())
    }

    private fun headersBinding(exchange: String, queue: String, vararg args: Pair<String, ArgumentValue>) =
        MigrationAction.DeclareBinding(exchange = exchange, queue = queue, routingKey = "", arguments = args.toMap())

    @Test @Order(1)
    fun `probe - a boolean header matches a boolean argument, and only a boolean`() {
        val ex = scope.exchange("m9bx", "headers")
        val control = scope.quorum("m9bc"); val flagTrue = scope.quorum("m9bt"); val flagFalse = scope.quorum("m9bf"); val textTrue = scope.quorum("m9bs")
        val qs = linkedMapOf("control number" to control, "flag=true" to flagTrue, "flag=false" to flagFalse, "flag=\"true\" (text)" to textTrue)
        assertTrue(bindAs(headersBinding(ex, control, "x-match" to ArgumentValue.Text("all"), "n" to ArgumentValue.Whole(10))).ok)
        assertTrue(bindAs(headersBinding(ex, flagTrue, "x-match" to ArgumentValue.Text("all"), "flag" to ArgumentValue.Flag(true))).ok)
        assertTrue(bindAs(headersBinding(ex, flagFalse, "x-match" to ArgumentValue.Text("all"), "flag" to ArgumentValue.Flag(false))).ok)
        assertTrue(bindAs(headersBinding(ex, textTrue, "x-match" to ArgumentValue.Text("all"), "flag" to ArgumentValue.Text("true"))).ok)
        val table = linkedMapOf<String, Set<String>>()
        mapOf(
            "n=10" to """{"n":10}""",
            "flag=true (boolean)" to """{"flag":true}""",
            "flag=false (boolean)" to """{"flag":false}""",
            "flag=\"true\" (text)" to """{"flag":"true"}""",
        ).forEach { (label, headers) ->
            table[label] = arrivals(ex, qs, headers)
            m9Note("M9-11 boolean", "publish $label -> arrived in ${table[label]!!.sorted()}")
        }
        m9Fixture("m9-11-boolean-routing.txt", table.entries.joinToString("\n") { "${it.key}: ${it.value.sorted()}" })
        assertEquals(setOf("control number"), table["n=10"], "positive control: a number header matches a number argument")
        assertEquals(setOf("flag=true"), table["flag=true (boolean)"], "a boolean true header matches a boolean true argument and nothing else")
        assertEquals(setOf("flag=false"), table["flag=false (boolean)"], "a boolean false header matches a boolean false argument and nothing else")
        assertEquals(setOf("flag=\"true\" (text)"), table["flag=\"true\" (text)"], "a text header matches only the text argument")
    }

    @Test @Order(2)
    fun `the arguments the form writes are read back with their types`() {
        val ex = scope.exchange("m9rx", "headers"); val q = scope.quorum("m9rq")
        val reply = bindAs(
            headersBinding(
                ex, q, "x-match" to ArgumentValue.Text("any"), "format" to ArgumentValue.Text("pdf"), "pages" to ArgumentValue.Whole(10),
                "copies" to ArgumentValue.Text("10"), "delta" to ArgumentValue.Whole(-3), "signed" to ArgumentValue.Flag(true),
            ),
        )
        assertTrue(reply.ok, "declare: $reply")
        val args = (api.get("/api/bindings/%2F/e/$ex/q/$q").arr.single() as JsonObject)["arguments"] as JsonObject
        assertEquals(
            mapOf("x-match" to "\"any\"", "format" to "\"pdf\"", "pages" to "10", "copies" to "\"10\"", "delta" to "-3", "signed" to "true"),
            args.mapValues { it.value.toString() }, "arguments as the broker holds them",
        )
        assertEquals("", ((api.get("/api/bindings/%2F/e/$ex/q/$q").arr.single() as JsonObject)["routing_key"] as JsonPrimitive).content, "the form writes an empty routing key")
    }

    @Test @Order(3)
    fun `a binding the form wrote routes by its headers for all and for any`() {
        val ex = scope.exchange("m9mx", "headers")
        val all = scope.quorum("m9mall"); val any = scope.quorum("m9many"); val everything = scope.quorum("m9mnone")
        val qs = linkedMapOf("all(format=pdf,pages=10)" to all, "any(format=pdf,pages=10)" to any, "all(only x-match)" to everything)
        bindAs(headersBinding(ex, all, "x-match" to ArgumentValue.Text("all"), "format" to ArgumentValue.Text("pdf"), "pages" to ArgumentValue.Whole(10)))
        bindAs(headersBinding(ex, any, "x-match" to ArgumentValue.Text("any"), "format" to ArgumentValue.Text("pdf"), "pages" to ArgumentValue.Whole(10)))
        bindAs(headersBinding(ex, everything, "x-match" to ArgumentValue.Text("all")))
        val every = "all(only x-match)"
        assertEquals(setOf("all(format=pdf,pages=10)", "any(format=pdf,pages=10)", every), arrivals(ex, qs, """{"format":"pdf","pages":10}"""), "both headers")
        assertEquals(setOf("any(format=pdf,pages=10)", every), arrivals(ex, qs, """{"format":"pdf"}"""), "one header: only any")
        assertEquals(setOf(every), arrivals(ex, qs, """{"format":"csv","pages":11}"""), "no header matches")
        assertEquals(setOf(every), arrivals(ex, qs, """{"pages":"10","format":"csv"}"""), "a text 10 is not the number 10")
        assertEquals(setOf(every), arrivals(ex, qs, "{}"), "no header at all: the binding with only x-match=all still matches (the decided warning)")
    }

    @Test @Order(4)
    fun `two bindings that differ only in their headers are two bindings that route separately`() {
        val ex = scope.exchange("m9tx", "headers"); val q = scope.quorum("m9tq")
        assertTrue(bindAs(headersBinding(ex, q, "x-match" to ArgumentValue.Text("all"), "format" to ArgumentValue.Text("pdf"))).ok)
        assertTrue(bindAs(headersBinding(ex, q, "x-match" to ArgumentValue.Text("all"), "format" to ArgumentValue.Text("csv"))).ok)
        assertEquals(2, api.get("/api/bindings/%2F/e/$ex/q/$q").arr.size)
        assertEquals(setOf("q"), arrivals(ex, mapOf("q" to q), """{"format":"csv"}"""))
        assertEquals(emptySet<String>(), arrivals(ex, mapOf("q" to q), """{"format":"xml"}"""))
    }
}
