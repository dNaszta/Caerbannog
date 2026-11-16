package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BindingFormTest {
    @Test
    fun `defaults are a queue destination`() {
        assertEquals(BindingDestinationType.QUEUE, BindingForm(exchange = "e", destination = "q").destinationKind)
    }

    @Test
    fun `problems as a table`() {
        val none = emptyList<FieldProblem>()
        assertEquals(listOf(FieldProblem("exchange", "Choose the exchange to bind from")), BindingForm(exchange = "", destination = "q").problems(ctx()))
        assertEquals(listOf(FieldProblem("destination", "Choose what to bind to")), BindingForm(exchange = "e", destination = "").problems(ctx()))
        assertEquals(
            listOf(FieldProblem("exchange", "Choose the exchange to bind from"), FieldProblem("destination", "Choose what to bind to")),
            BindingForm(exchange = "", destination = "").problems(ctx()),
        )
        assertEquals(none, BindingForm(exchange = "e", destination = "q", routingKey = "").problems(ctx()))
        assertEquals(none, BindingForm(exchange = "e", destination = "q", routingKey = "a.b.#").problems(ctx()))
    }

    @Test
    fun `builds one declare binding step on the virtual host of the plan`() {
        val c = ctx(plan = TopologyPlan(vhost = "uat"))
        assertEquals(
            listOf(MigrationAction.DeclareBinding(exchange = "events", queue = "orders", routingKey = "order.created", vhost = "uat")),
            BindingForm(exchange = "events", destination = "orders", routingKey = "order.created").actions(c),
        )
        assertEquals(
            listOf(MigrationAction.DeclareBinding(exchange = "a", queue = "b", destinationType = BindingDestinationType.EXCHANGE)),
            BindingForm(exchange = "a", destination = "b", destinationKind = BindingDestinationType.EXCHANGE).actions(ctx()),
        )
    }

    @Test
    fun `a binding form never gives a naming hint`() {
        val known = KnownTopology(exchanges = mapOf("Legacy_Events" to "direct"))
        val f = BindingForm(exchange = "Legacy_Events", destination = "Some_Queue", routingKey = "Order.Created")
        assertEquals(emptyList(), f.hints(ctx(known = known)))
        assertEquals(emptyList(), f.hints(ctx()))
    }

    @Test
    fun `the routing key is asked unless the exchange is a known fanout or headers exchange`() {
        val known = KnownTopology(exchanges = mapOf("f" to "fanout", "h" to "headers", "d" to "direct", "t" to "topic"))
        val c = ctx(known = known)
        assertTrue(c.asksRoutingKey("d"))
        assertTrue(c.asksRoutingKey("t"))
        assertEquals(false, c.asksRoutingKey("f"))
        assertEquals(false, c.asksRoutingKey("h"))
        assertTrue(c.asksRoutingKey("unknown"), "an exchange of unknown type: ask")
    }

    @Test
    fun `the type of an exchange declared in the plan counts as known`() {
        val c = ctx(plan = planOf(MigrationAction.DeclareExchange(name = "fan", type = "fanout")))
        assertEquals(false, c.asksRoutingKey("fan"))
    }

    @Test
    fun `suggestions list the plan first and then the broker, without repeating a name`() {
        val plan = planOf(MigrationAction.DeclareQueue(name = "orders"), MigrationAction.DeclareStream(name = "feed"))
        val known = KnownTopology(queues = setOf("audit", "orders"), streams = setOf("legacy"))
        val s = ctx(plan = plan, known = known).destinationSuggestions(BindingDestinationType.QUEUE)
        assertEquals(
            listOf("orders" to SuggestionSource.PLAN, "feed" to SuggestionSource.PLAN, "audit" to SuggestionSource.BROKER, "legacy" to SuggestionSource.BROKER),
            s.map { it.name to it.source },
        )
    }

    @Test
    fun `exchange suggestions list the plan first, then the broker`() {
        val plan = planOf(MigrationAction.DeclareExchange(name = "events"))
        val known = KnownTopology(exchanges = mapOf("amq.topic" to "topic", "events" to "direct"))
        assertEquals(
            listOf("events" to SuggestionSource.PLAN, "amq.topic" to SuggestionSource.BROKER),
            ctx(plan = plan, known = known).exchangeSuggestions().map { it.name to it.source },
        )
    }

    @Test
    fun `no suggestion without a connection`() {
        assertEquals(emptyList(), ctx().destinationSuggestions(BindingDestinationType.QUEUE))
        assertEquals(emptyList(), ctx().exchangeSuggestions())
    }

    @Test
    fun `formOf round trip and null for arguments and deletes`() {
        val queue = MigrationAction.DeclareBinding(exchange = "e", queue = "q", routingKey = "k")
        assertEquals(BindingForm(exchange = "e", destination = "q", routingKey = "k"), StepForms.formOf(queue))
        val toExchange = MigrationAction.DeclareBinding(exchange = "e", queue = "x", destinationType = BindingDestinationType.EXCHANGE)
        assertEquals(BindingForm(exchange = "e", destination = "x", destinationKind = BindingDestinationType.EXCHANGE), StepForms.formOf(toExchange))
        assertNull(StepForms.formOf(queue.copy(arguments = mapOf("x-match" to ArgumentValue.Text("all")))))
        assertNull(StepForms.formOf(MigrationAction.DeleteBinding(exchange = "e", queue = "q")))
    }

    @Test
    fun `formOf is null for every other action, including the four access actions`() {
        val others = listOf(
            MigrationAction.DeleteQueue(name = "q"),
            MigrationAction.DeleteExchange(name = "e"),
            MigrationAction.DeleteStream(name = "s"),
            MigrationAction.DeletePolicy(name = "p"),
            MigrationAction.DeclareVhost(name = "v"),
            MigrationAction.DeclareUser(name = "u", passwordHash = dev.caerbannog.core.json.SecretValue.of("h"), hashingAlgorithm = "rabbit_password_hashing_sha256"),
            MigrationAction.SetPermissions(vhost = "/", user = "u", configure = ".*", write = ".*", read = ".*"),
            MigrationAction.SetTopicPermissions(vhost = "/", user = "u", exchange = "e", write = ".*", read = ".*"),
        )
        for (a in others) assertNull(StepForms.formOf(a), a.actionName)
    }

    @Test
    fun `every action a form builds is held by the same form again`() {
        val forms = listOf(
            QueueForm(name = "q"), QueueForm(name = "q", type = "classic"),
            ExchangeForm(name = "e", type = "topic"),
            BindingForm(exchange = "e", destination = "q", routingKey = "k"),
            BindingForm(exchange = "e", destination = "q"),
        )
        for (f in forms) assertEquals(f, StepForms.formOf(f.actions(ctx()).single()), f.toString())
    }
}
