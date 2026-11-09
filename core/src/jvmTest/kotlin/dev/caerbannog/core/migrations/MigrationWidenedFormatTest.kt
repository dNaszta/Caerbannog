package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationTestSupport.actions
import dev.caerbannog.core.migrations.MigrationTestSupport.fileWith
import dev.caerbannog.core.migrations.MigrationTestSupport.keyOrders
import dev.caerbannog.core.migrations.MigrationTestSupport.only
import dev.caerbannog.core.migrations.fakes.MigrationFixtures
import dev.caerbannog.core.topology.BindingDestinationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-04 format additions (docs/spec.md "Format additions for converted topology"): every new key reads, writes, round-trips; defaults are not written. */
class MigrationWidenedFormatTest {

    private val everyValue = mapOf(
        "x-whole" to ArgumentValue.Whole(5),
        "x-ratio" to ArgumentValue.Decimal(0.5),
        "x-flag" to ArgumentValue.Flag(true),
        "x-none" to ArgumentValue.Nothing,
        "x-text" to ArgumentValue.Text("dlx"),
        "x-list" to ArgumentValue.Items(listOf(ArgumentValue.Whole(1), ArgumentValue.Text("a"))),
        "x-obj" to ArgumentValue.Table(mapOf("k" to ArgumentValue.Whole(2))),
    )

    private fun roundTrip(vararg actions: MigrationAction) {
        val text = MigrationParser.exportToJson("0001", "round trip", actions.toList())
        assertEquals(actions.toList(), actions(text), "parse(exportToJson(actions)) must give the actions back; exported:\n$text")
    }

    @Test
    fun `a queue with arguments and auto-delete round-trips`() =
        roundTrip(MigrationAction.DeclareQueue("orders", "quorum", vhost = "v1", durable = false, arguments = everyValue, autoDelete = true))

    @Test
    fun `an exchange with flags and arguments round-trips`() =
        roundTrip(MigrationAction.DeclareExchange("events.x", "topic", "v1", durable = false, autoDelete = true, internal = true, arguments = everyValue))

    @Test
    fun `a binding to a queue and a binding to an exchange round-trip with arguments`() = roundTrip(
        MigrationAction.DeclareBinding("events", "orders", "k", arguments = mapOf("x-match" to ArgumentValue.Text("all"))),
        MigrationAction.DeclareBinding("ex-e2e", "dlx", "dead", "v1", BindingDestinationType.EXCHANGE, everyValue),
    )

    @Test
    fun `a policy and an operator policy round-trip with definition, apply-to and priority`() = roundTrip(
        MigrationAction.DeclarePolicy("limits", "^orders", everyValue, "v1", "quorum_queues", 7),
        MigrationAction.DeclareOperatorPolicy("op-limit", ".*", mapOf("max-length" to ArgumentValue.Whole(100000)), "v1", "queues", -3),
    )

    @Test
    fun `the widened fixture parses to the expected typed actions and round-trips`() {
        val up = actions(MigrationFixtures.read(MigrationFixtures.WIDENED))
        assertEquals(5, up.size)
        val queue = up[0] as MigrationAction.DeclareQueue
        assertTrue(queue.autoDelete)
        assertEquals(ArgumentValue.Whole(5), queue.arguments["x-delivery-limit"])
        assertEquals(ArgumentValue.Whole(60000), queue.arguments["x-message-ttl"])
        assertEquals(ArgumentValue.Decimal(0.5), queue.arguments["x-ratio"])
        assertEquals(ArgumentValue.Nothing, queue.arguments["x-none"])
        val exchange = up[1] as MigrationAction.DeclareExchange
        assertEquals(Triple(false, true, true), Triple(exchange.durable, exchange.autoDelete, exchange.internal))
        val binding = up[2] as MigrationAction.DeclareBinding
        assertEquals(BindingDestinationType.EXCHANGE, binding.destinationType)
        assertEquals("dlx", binding.queue)
        val policy = up[3] as MigrationAction.DeclarePolicy
        assertEquals("quorum_queues" to 7, policy.applyTo to policy.priority)
        assertEquals(ArgumentValue.Whole(1000), policy.definition["max-length"])
        val operator = up[4] as MigrationAction.DeclareOperatorPolicy
        assertEquals("queues", operator.applyTo)
        assertEquals("declareOperatorPolicy", operator.actionName)
        roundTrip(*up.toTypedArray())
    }

    // ---- defaults are never written

    @Test
    fun `a queue at its defaults writes none of the new keys`() {
        val text = MigrationParser.exportToJson("0001", "d", listOf(MigrationAction.DeclareQueue("orders", "quorum")))
        assertEquals(listOf(listOf("action", "name", "type")), keyOrders(text))
    }

    @Test
    fun `an exchange at its defaults writes none of the new keys`() {
        val text = MigrationParser.exportToJson("0001", "d", listOf(MigrationAction.DeclareExchange("events", "topic")))
        assertEquals(listOf(listOf("action", "name", "type")), keyOrders(text))
    }

    @Test
    fun `a binding to a queue without arguments writes no destinationType and no arguments`() {
        val text = MigrationParser.exportToJson("0001", "d", listOf(MigrationAction.DeclareBinding("events", "orders", "k")))
        assertEquals(listOf(listOf("action", "exchange", "queue", "routingKey")), keyOrders(text))
    }

    @Test
    fun `a policy at its defaults writes no definition, applyTo or priority`() {
        val text = MigrationParser.exportToJson(
            "0001", "d",
            listOf(MigrationAction.DeclarePolicy("limits", "^o"), MigrationAction.DeclareOperatorPolicy("op", ".*")),
        )
        assertEquals(listOf(listOf("action", "name", "pattern"), listOf("action", "name", "pattern")), keyOrders(text))
    }

    @Test
    fun `a key is written only when it differs from its default`() {
        fun keys(a: MigrationAction) = keyOrders(MigrationParser.exportToJson("0001", "d", listOf(a))).single()
        assertEquals(listOf("action", "name", "type", "autoDelete"), keys(MigrationAction.DeclareQueue("q", "quorum", autoDelete = true)))
        assertEquals(listOf("action", "name", "type", "internal"), keys(MigrationAction.DeclareExchange("x", "direct", internal = true)))
        assertEquals(listOf("action", "name", "type", "autoDelete"), keys(MigrationAction.DeclareExchange("x", "direct", autoDelete = true)))
        assertEquals(listOf("action", "name", "pattern", "priority"), keys(MigrationAction.DeclarePolicy("p", ".*", priority = 2)))
        assertEquals(listOf("action", "name", "pattern", "applyTo"), keys(MigrationAction.DeclarePolicy("p", ".*", applyTo = "queues")))
        assertEquals(listOf("action", "name", "pattern", "applyTo"), keys(MigrationAction.DeclareOperatorPolicy("p", ".*", applyTo = "queues")))
    }

    // ---- fixed key order

    @Test
    fun `new keys come after the existing keys in the fixed order`() {
        val args = mapOf("a" to ArgumentValue.Whole(1))
        val text = MigrationParser.exportToJson(
            "0001", "order",
            listOf(
                MigrationAction.DeclareQueue("q", "quorum", vhost = "v", durable = false, arguments = args, autoDelete = true),
                MigrationAction.DeclareExchange("x", "topic", "v", durable = false, autoDelete = true, internal = true, arguments = args),
                MigrationAction.DeclareBinding("x", "e2", "k", "v", BindingDestinationType.EXCHANGE, args),
                MigrationAction.DeclarePolicy("p", ".*", args, "v", "queues", 5),
                MigrationAction.DeclareOperatorPolicy("o", ".*", args, "v", "queues", 5),
            ),
        )
        assertEquals(
            listOf(
                listOf("action", "vhost", "name", "type", "durable", "autoDelete", "arguments"),
                listOf("action", "vhost", "name", "type", "durable", "autoDelete", "internal", "arguments"),
                listOf("action", "vhost", "exchange", "queue", "routingKey", "destinationType", "arguments"),
                listOf("action", "vhost", "name", "pattern", "definition", "applyTo", "priority"),
                listOf("action", "vhost", "name", "pattern", "definition", "applyTo", "priority"),
            ),
            keyOrders(text),
        )
    }

    @Test
    fun `the destination name of an exchange binding is written under queue and destinationType says which`() {
        val text = MigrationParser.exportToJson("0001", "d", listOf(MigrationAction.DeclareBinding("ex-e2e", "dlx", "dead", destinationType = BindingDestinationType.EXCHANGE)))
        assertEquals(listOf(listOf("action", "exchange", "queue", "routingKey", "destinationType")), keyOrders(text))
        assertTrue(text.contains("\"queue\": \"dlx\""), text)
        assertTrue(text.contains("\"destinationType\": \"exchange\""), text)
        assertFalse(text.contains("\"destination\":"), "the key `destination` is only read, never written")
    }

    // ---- reading

    @Test
    fun `the destination synonym is read for a queue and an exchange destination`() {
        val queue = only(fileWith("""{ "action": "declareBinding", "exchange": "e", "destination": "q1", "routingKey": "k" }""")) as MigrationAction.DeclareBinding
        assertEquals("q1", queue.queue)
        assertEquals(BindingDestinationType.QUEUE, queue.destinationType)
        val exchange = only(fileWith("""{ "action": "declareBinding", "exchange": "e", "destination": "e2", "destinationType": "exchange" }""")) as MigrationAction.DeclareBinding
        assertEquals("e2", exchange.queue)
        assertEquals(BindingDestinationType.EXCHANGE, exchange.destinationType)
    }

    @Test
    fun `an exchange destination is described as such and a queue destination keeps its old description`() {
        val toExchange = MigrationAction.DeclareBinding("ex-e2e", "dlx", "dead", destinationType = BindingDestinationType.EXCHANGE)
        assertEquals("Bind ex-e2e to exchange dlx with dead", toExchange.description)
        assertEquals("Bind ex-e2e to exchange dlx", toExchange.copy(routingKey = "").description)
        assertEquals("Bind events to orders with k", MigrationAction.DeclareBinding("events", "orders", "k").description)
    }

    @Test
    fun `every apply-to the spec lists is accepted`() {
        for (value in listOf("all", "queues", "classic_queues", "quorum_queues", "streams", "exchanges")) {
            val policy = only(fileWith("""{ "action": "declarePolicy", "name": "p", "pattern": ".*", "applyTo": "$value" }""")) as MigrationAction.DeclarePolicy
            assertEquals(value, policy.applyTo)
            val operator = only(fileWith("""{ "action": "declareOperatorPolicy", "name": "p", "pattern": ".*", "applyTo": "$value" }""")) as MigrationAction.DeclareOperatorPolicy
            assertEquals(value, operator.applyTo)
        }
    }

    @Test
    fun `an operator policy without optional keys gets the defaults and needs a name`() {
        val op = only(fileWith("""{ "action": "declareOperatorPolicy", "name": "op" }""")) as MigrationAction.DeclareOperatorPolicy
        assertEquals(Triple(".*", "all", 0), Triple(op.pattern, op.applyTo, op.priority))
        assertTrue(op.definition.isEmpty())
        assertEquals("policy name is required", MigrationTestSupport.reason(fileWith("""{ "action": "declareOperatorPolicy", "pattern": ".*" }""")))
    }

    @Test
    fun `a destructive flag is only set by delete actions and an operator policy is not destructive`() {
        assertFalse(MigrationAction.DeclareOperatorPolicy("op", ".*").isDestructive)
        assertEquals("Declare operator policy op", MigrationAction.DeclareOperatorPolicy("op", ".*").description)
    }

    @Test
    fun `an action with no argument key has an empty map, not null`() {
        assertEquals(emptyMap(), (only(fileWith("""{ "action": "declareQueue", "name": "q" }""")) as MigrationAction.DeclareQueue).arguments)
        assertNull(MigrationAction.DeclareQueue("q").detail)
    }
}
