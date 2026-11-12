package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M8-04 scope 6: the request a declare action becomes (method, path, body), pure, no broker. Numbers stay numbers; `x-queue-type` comes first.
 * Delete actions and streams are the executor's job (M5-08) and give null here.
 */
class MigrationRequestsTest {

    private fun write(action: MigrationAction): ManagementWrite = assertNotNull(MigrationRequests.requestFor(action), "no request for ${action.actionName}")
    private fun body(action: MigrationAction): JsonObject = assertNotNull(write(action).body, "no body for ${action.actionName}")

    private val typed = mapOf(
        "x-whole" to ArgumentValue.Whole(5),
        "x-decimal" to ArgumentValue.Decimal(0.5),
        "x-flag" to ArgumentValue.Flag(true),
        "x-none" to ArgumentValue.Nothing,
        "x-text" to ArgumentValue.Text("dlx"),
        "x-list" to ArgumentValue.Items(listOf(ArgumentValue.Whole(1), ArgumentValue.Text("a"))),
        "x-obj" to ArgumentValue.Table(mapOf("k" to ArgumentValue.Whole(2))),
    )
    private val typedJson = JsonObject(
        mapOf(
            "x-whole" to JsonPrimitive(5), "x-decimal" to JsonPrimitive(0.5), "x-flag" to JsonPrimitive(true), "x-none" to JsonNull,
            "x-text" to JsonPrimitive("dlx"), "x-list" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("a"))),
            "x-obj" to JsonObject(mapOf("k" to JsonPrimitive(2))),
        ),
    )

    // ---- queues

    @Test
    fun `a queue is a PUT to the queue path with durable, auto_delete and arguments, queue type first`() {
        val w = write(MigrationAction.DeclareQueue("orders", "quorum", vhost = "/", durable = true, arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5))))
        assertEquals("PUT", w.method)
        assertEquals("/api/queues/%2F/orders", w.path)
        assertEquals(
            buildJsonObject {
                put("durable", true); put("auto_delete", false)
                put("arguments", buildJsonObject { put("x-queue-type", "quorum"); put("x-delivery-limit", 5) })
            },
            w.body,
        )
        assertEquals(listOf("durable", "auto_delete", "arguments"), w.body!!.keys.toList())
        assertEquals(listOf("x-queue-type", "x-delivery-limit"), (w.body["arguments"] as JsonObject).keys.toList())
    }

    @Test
    fun `a queue with no arguments still carries its queue type and the flags are written out`() {
        val b = body(MigrationAction.DeclareQueue("q", "classic", "v1", durable = false, autoDelete = true))
        assertEquals(JsonPrimitive(false), b["durable"])
        assertEquals(JsonPrimitive(true), b["auto_delete"])
        assertEquals(buildJsonObject { put("x-queue-type", "classic") }, b["arguments"])
    }

    @Test
    fun `queue argument types survive as JSON types`() {
        val args = body(MigrationAction.DeclareQueue("q", "quorum", arguments = typed))["arguments"] as JsonObject
        assertEquals(typedJson.keys + "x-queue-type", args.keys)
        for ((key, expected) in typedJson) assertEquals(expected, args[key], key)
        assertEquals("x-queue-type", args.keys.first())
    }

    @Test
    fun `numbers are JSON numbers, never strings`() {
        val args = body(MigrationAction.DeclareQueue("q", "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5), "x-message-ttl" to ArgumentValue.Whole(60000))))["arguments"] as JsonObject
        for (key in listOf("x-delivery-limit", "x-message-ttl")) {
            val p = args[key] as JsonPrimitive
            assertFalse(p.isString, "$key was written as the string \"${p.content}\"")
        }
        assertEquals("5", (args["x-delivery-limit"] as JsonPrimitive).content)
        assertTrue(body(MigrationAction.DeclareQueue("q", "quorum", arguments = mapOf("a" to ArgumentValue.Text("5"))))["arguments"].toString().contains("\"a\":\"5\""))
    }

    // ---- exchanges

    @Test
    fun `an exchange is a PUT with type, durable, auto_delete, internal and arguments`() {
        val w = write(MigrationAction.DeclareExchange("events.x", "topic", "/", durable = false, autoDelete = true, internal = true, arguments = mapOf("alternate-exchange" to ArgumentValue.Text("ae"))))
        assertEquals("PUT", w.method)
        assertEquals("/api/exchanges/%2F/events.x", w.path)
        assertEquals(
            buildJsonObject {
                put("type", "topic"); put("durable", false); put("auto_delete", true); put("internal", true)
                put("arguments", buildJsonObject { put("alternate-exchange", "ae") })
            },
            w.body,
        )
        assertEquals(listOf("type", "durable", "auto_delete", "internal", "arguments"), w.body!!.keys.toList())
    }

    @Test
    fun `an exchange at its defaults writes auto_delete false and internal false and an empty arguments object`() {
        val b = body(MigrationAction.DeclareExchange("x", "direct"))
        assertEquals(JsonPrimitive(false), b["auto_delete"])
        assertEquals(JsonPrimitive(false), b["internal"])
        assertEquals(JsonPrimitive(true), b["durable"])
        assertEquals(JsonObject(emptyMap()), b["arguments"])
    }

    // ---- bindings

    @Test
    fun `a binding to a queue is a POST to the e-q path with routing_key and arguments`() {
        val w = write(MigrationAction.DeclareBinding("events", "orders", "order.created", "/", BindingDestinationType.QUEUE, mapOf("x-match" to ArgumentValue.Text("all"))))
        assertEquals("POST", w.method)
        assertEquals("/api/bindings/%2F/e/events/q/orders", w.path)
        assertEquals(buildJsonObject { put("routing_key", "order.created"); put("arguments", buildJsonObject { put("x-match", "all") }) }, w.body)
        assertEquals(listOf("routing_key", "arguments"), w.body!!.keys.toList())
    }

    @Test
    fun `a binding to an exchange is a POST to the e-e path`() {
        val w = write(MigrationAction.DeclareBinding("ex-e2e", "dlx", "dead", "/", BindingDestinationType.EXCHANGE))
        assertEquals("POST", w.method)
        assertEquals("/api/bindings/%2F/e/ex-e2e/e/dlx", w.path)
        assertEquals(buildJsonObject { put("routing_key", "dead"); put("arguments", JsonObject(emptyMap())) }, w.body)
    }

    @Test
    fun `a binding without a routing key sends an empty one`() {
        assertEquals(JsonPrimitive(""), body(MigrationAction.DeclareBinding("e", "q"))["routing_key"])
    }

    // ---- policies

    @Test
    fun `a policy is a PUT with pattern, definition, priority and apply-to`() {
        val w = write(MigrationAction.DeclarePolicy("limits", "^orders", mapOf("max-length" to ArgumentValue.Whole(1000), "overflow" to ArgumentValue.Text("reject-publish")), "/", "quorum_queues", 7))
        assertEquals("PUT", w.method)
        assertEquals("/api/policies/%2F/limits", w.path)
        assertEquals(
            buildJsonObject {
                put("pattern", "^orders")
                put("definition", buildJsonObject { put("max-length", 1000); put("overflow", "reject-publish") })
                put("priority", 7); put("apply-to", "quorum_queues")
            },
            w.body,
        )
        assertEquals(listOf("pattern", "definition", "priority", "apply-to"), w.body!!.keys.toList())
        assertFalse((((w.body["definition"]) as JsonObject)["max-length"] as JsonPrimitive).isString)
        assertFalse((w.body["priority"] as JsonPrimitive).isString)
    }

    @Test
    fun `an operator policy is a PUT to the operator-policies path with the same body`() {
        val w = write(MigrationAction.DeclareOperatorPolicy("op-limit", ".*", mapOf("max-length" to ArgumentValue.Whole(100000)), "/", "queues", 0))
        assertEquals("PUT", w.method)
        assertEquals("/api/operator-policies/%2F/op-limit", w.path)
        assertEquals(
            buildJsonObject { put("pattern", ".*"); put("definition", buildJsonObject { put("max-length", 100000) }); put("priority", 0); put("apply-to", "queues") },
            w.body,
        )
    }

    @Test
    fun `a policy at its defaults sends priority 0, apply-to all and an empty definition`() {
        val b = body(MigrationAction.DeclarePolicy("p", ".*"))
        assertEquals(JsonPrimitive(0), b["priority"])
        assertEquals(JsonPrimitive("all"), b["apply-to"])
        assertEquals(JsonObject(emptyMap()), b["definition"])
    }

    // ---- paths

    @Test
    fun `the default vhost slash is percent-2F and other vhosts keep their name`() {
        assertEquals("/api/queues/%2F/q", write(MigrationAction.DeclareQueue("q", vhost = "/")).path)
        assertEquals("/api/queues/uat/q", write(MigrationAction.DeclareQueue("q", vhost = "uat")).path)
    }

    @Test
    fun `special characters in vhost and names are encoded in every path`() {
        val vhost = "my vhost/2"
        val name = "a b#c?d%e+f"
        val encodedVhost = "my%20vhost%2F2"
        val encodedName = "a%20b%23c%3Fd%25e%2Bf"
        assertEquals("/api/queues/$encodedVhost/$encodedName", write(MigrationAction.DeclareQueue(name, vhost = vhost)).path)
        assertEquals("/api/exchanges/$encodedVhost/$encodedName", write(MigrationAction.DeclareExchange(name, "direct", vhost)).path)
        assertEquals("/api/policies/$encodedVhost/$encodedName", write(MigrationAction.DeclarePolicy(name, ".*", vhost = vhost)).path)
        assertEquals("/api/operator-policies/$encodedVhost/$encodedName", write(MigrationAction.DeclareOperatorPolicy(name, ".*", vhost = vhost)).path)
        assertEquals("/api/bindings/$encodedVhost/e/$encodedName/q/$encodedName", write(MigrationAction.DeclareBinding(name, name, vhost = vhost)).path)
        assertEquals("/api/bindings/$encodedVhost/e/$encodedName/e/$encodedName", write(MigrationAction.DeclareBinding(name, name, vhost = vhost, destinationType = BindingDestinationType.EXCHANGE)).path)
    }

    @Test
    fun `a name cannot break out of its path segment`() {
        val path = write(MigrationAction.DeclareQueue("../../users/guest", vhost = "/")).path
        assertFalse(path.removePrefix("/api/queues/").contains("../"), path)
        assertEquals(listOf("", "api", "queues", "%2F", "..%2F..%2Fusers%2Fguest"), path.split("/"))
    }

    // ---- everything else gives null

    @Test
    fun `delete actions and streams give no request here`() {
        val others = listOf(
            MigrationAction.DeleteQueue("q"), MigrationAction.DeleteExchange("x"), MigrationAction.DeleteBinding("e", "q", "k"),
            MigrationAction.DeleteStream("s"), MigrationAction.DeletePolicy("p"), MigrationAction.DeclareStream("s"),
        )
        for (a in others) assertNull(MigrationRequests.requestFor(a), "${a.actionName} is the executor's job (M5-08)")
    }

    @Test
    fun `a request is only a PUT or a POST and its path is under api`() {
        val all = listOf(
            MigrationAction.DeclareQueue("q"), MigrationAction.DeclareExchange("x"), MigrationAction.DeclareBinding("x", "q"),
            MigrationAction.DeclarePolicy("p", ".*"), MigrationAction.DeclareOperatorPolicy("o", ".*"),
        )
        for (a in all) {
            val w = write(a)
            assertTrue(w.method == "PUT" || w.method == "POST", "${a.actionName}: ${w.method}")
            assertTrue(w.path.startsWith("/api/"), w.path)
        }
    }

    @Test
    fun `the body holds only the specified keys, nothing else from the action`() {
        assertEquals(setOf("durable", "auto_delete", "arguments"), body(MigrationAction.DeclareQueue("q", "quorum", vhost = "v", arguments = typed)).keys)
        assertEquals(setOf("type", "durable", "auto_delete", "internal", "arguments"), body(MigrationAction.DeclareExchange("x", vhost = "v")).keys)
        assertEquals(setOf("routing_key", "arguments"), body(MigrationAction.DeclareBinding("x", "q", vhost = "v")).keys)
        assertEquals(setOf("pattern", "definition", "priority", "apply-to"), body(MigrationAction.DeclarePolicy("p", ".*", vhost = "v")).keys)
        assertEquals(setOf("pattern", "definition", "priority", "apply-to"), body(MigrationAction.DeclareOperatorPolicy("p", ".*", vhost = "v")).keys)
    }

    @Test
    fun `a name made only of dots is encoded so no dot segment reaches the broker, and the default vhost stays percent-2F`() {
        assertEquals("/api/queues/%2F/%2E%2E", write(MigrationAction.DeclareQueue("..", vhost = "/")).path)
        assertEquals("/api/queues/%2F/%2E", write(MigrationAction.DeclareQueue(".", vhost = "/")).path)
        assertEquals("/api/queues/%2E%2E/q", write(MigrationAction.DeclareQueue("q", vhost = "..")).path)
        assertEquals("/api/exchanges/%2F/%2E%2E", write(MigrationAction.DeclareExchange("..", "direct")).path)
        assertEquals("/api/policies/%2F/%2E%2E", write(MigrationAction.DeclarePolicy("..", ".*")).path)
        assertEquals("/api/operator-policies/%2F/%2E", write(MigrationAction.DeclareOperatorPolicy(".", ".*")).path)
        assertEquals("/api/bindings/%2F/e/%2E%2E/q/%2E", write(MigrationAction.DeclareBinding("..", ".")).path)
        assertEquals("/api/bindings/%2F/e/%2E/e/%2E%2E", write(MigrationAction.DeclareBinding(".", "..", destinationType = BindingDestinationType.EXCHANGE)).path)
        for (path in listOf(write(MigrationAction.DeclareQueue("..")).path, write(MigrationAction.DeclareBinding("..", "..")).path)) {
            assertFalse(path.split("/").any { it == "." || it == ".." }, path)
        }
    }

    @Test
    fun `names that merely contain dots keep them`() {
        assertEquals("/api/queues/%2F/a.b", write(MigrationAction.DeclareQueue("a.b")).path)
    }
}
