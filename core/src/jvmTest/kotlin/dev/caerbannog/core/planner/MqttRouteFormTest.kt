package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.topology.BindingDestinationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-10 step 2: the MQTT route form. API fixed here (for the architect to confirm):
 * `data class MqttRouteForm(val filter: String, val destination: String, val destinationKind: MqttDestinationKind? = null) : StepForm` with
 * `enum class MqttDestinationKind { Queue, Stream }` (null: not said, as when `StepForms.formOf` rebuilds a form from an action, which cannot tell),
 * `fun bindingKey(): String?` (null while the filter is refused) and `fun matchingTopic(): String?` (the suggested concrete topic, from the existing
 * `StreamInboundRoute.routingKeyToSuggestedMqttTopic`; null while the filter is refused). The kind changes no action: a stream is a queue for a binding.
 * Problems: `FieldProblem("filter", <text>)` and `FieldProblem("destination", PlannerTexts.BINDING_DESTINATION_MISSING)`, the filter's first. No hints.
 */
class MqttRouteFormTest {
    private fun form(filter: String, destination: String = "telemetry", kind: MqttDestinationKind? = MqttDestinationKind.Stream) =
        MqttRouteForm(filter = filter, destination = destination, destinationKind = kind)

    @Test
    fun `a good filter and a destination have no problem and no hint`() {
        assertEquals(emptyList(), form("sensors/+/temp").problems(ctx()))
        assertEquals(emptyList(), form("sensors/+/temp").hints(ctx()))
    }

    @Test
    fun `builds one declare binding on amq topic for a queue and for a stream alike`() {
        val c = ctx(plan = TopologyPlan(vhost = "uat"))
        val expected = listOf(
            MigrationAction.DeclareBinding(
                exchange = "amq.topic", queue = "telemetry", routingKey = "sensors.*.temp", vhost = "uat", destinationType = BindingDestinationType.QUEUE,
            ),
        )
        assertEquals(expected, form("sensors/+/temp", kind = MqttDestinationKind.Stream).actions(c))
        assertEquals(expected, form("sensors/+/temp", kind = MqttDestinationKind.Queue).actions(c))
        assertEquals(expected, form("sensors/+/temp", kind = null).actions(c))
    }

    @Test
    fun `the saved action has no arguments and never an exchange destination`() {
        val a = form("a/#").actions(ctx()).single() as MigrationAction.DeclareBinding
        assertEquals(emptyMap(), a.arguments)
        assertEquals(BindingDestinationType.QUEUE, a.destinationType)
    }

    @Test
    fun `a refused filter is a problem on the filter with its text and gives no action`() {
        val cases = mapOf(
            "" to "Enter a topic filter",
            "sensors/#/temp" to "# must be the last level",
            "sensors/te#" to "# must be the last level",
            "sens+/temp" to "+ must stand for a whole level",
            "sensors/v1.2/temp" to "A level cannot contain a dot: RabbitMQ would read it as another level",
            "sensors/a*b/temp" to "A level cannot contain *: use + for a whole level",
            "sensors/*/temp" to "A level cannot contain *: use + for a whole level",
            "sensors//temp" to "A level cannot be empty",
            "/sensors/temp" to "A level cannot be empty",
            "sensors/temp/" to "A level cannot be empty",
        )
        for ((filter, text) in cases) {
            assertEquals(listOf(FieldProblem("filter", text)), form(filter).problems(ctx()), "filter \"$filter\"")
            assertEquals(emptyList(), form(filter).actions(ctx()), "filter \"$filter\"")
            assertNull(form(filter).bindingKey(), "filter \"$filter\"")
            assertNull(form(filter).matchingTopic(), "filter \"$filter\"")
        }
    }

    @Test
    fun `a missing destination is a problem on the destination`() {
        assertEquals(listOf(FieldProblem("destination", PlannerTexts.BINDING_DESTINATION_MISSING)), form("a/#", destination = "").problems(ctx()))
        assertEquals(emptyList(), form("a/#", destination = "").actions(ctx()))
    }

    @Test
    fun `both problems are listed, the filter first`() {
        assertEquals(
            listOf(FieldProblem("filter", "Enter a topic filter"), FieldProblem("destination", PlannerTexts.BINDING_DESTINATION_MISSING)),
            form("", destination = "").problems(ctx()),
        )
    }

    @Test
    fun `the form shows the key and a matching topic`() {
        assertEquals("sensors.*.temp", form("sensors/+/temp").bindingKey())
        assertEquals("sensors/item/temp", form("sensors/+/temp").matchingTopic())
        assertEquals("sensors/test", form("sensors/#").matchingTopic())
        assertEquals("item", form("+").matchingTopic())
        assertEquals("test", form("#").matchingTopic())
        assertEquals("a/b/c", form("a/b/c").matchingTopic())
        assertEquals("\$SYS/test", form("\$SYS/#").matchingTopic())
        assertEquals("sensors/a b/temp", form("sensors/a b/temp").matchingTopic())
    }

    @Test
    fun `the matching topic is built from the filter's own levels and keeps a space at either end`() {
        // Decided at the step 2 gate: + gives item, # gives test, every other level is unchanged. The shared helper trims the whole key; this one must not.
        assertEquals(" a/item", form(" a/+").matchingTopic())
        assertEquals("a/b ", form("a/b ").matchingTopic())
    }

    @Test
    fun `a matching topic of the form is a topic the filter matches`() {
        // The suggestion must be a concrete topic (no wildcard) with as many levels as the filter, except that a trailing # may stand for one level.
        for (f in listOf("sensors/+/temp", "sensors/#", "+", "#", "a/b/c", "+/+/#", "x/+")) {
            val topic = form(f).matchingTopic()!!
            assertTrue('+' !in topic && '#' !in topic && '*' !in topic, "wildcard left in \"$topic\" for $f")
            assertTrue(topic.isNotEmpty() && !topic.startsWith("/") && !topic.endsWith("/") && "//" !in topic, "\"$topic\" for $f")
        }
    }

    @Test
    fun `an accepted form is always written as a file the parser accepts`() {
        val filters = listOf("a", "a b", " ", "\$SYS/#", "sensors/ü", "a/\\/b", "x".repeat(200), "a\tb")
        val destinations = listOf("telemetry", "q 1", "q.with.dots", "ü", "x".repeat(255))
        for (f in filters) for (d in destinations) {
            val form = form(f, d)
            val actions = form.actions(ctx())
            if (form.problems(ctx()).isEmpty()) {
                assertEquals(1, actions.size, "filter \"$f\" destination \"$d\"")
                val file = MigrationParser.exportToJson("x", "", actions)
                assertTrue(MigrationParser.parse(file) is MigrationValidationResult.Valid, "filter \"$f\" destination \"$d\": the parser refuses what the form accepted")
            } else {
                assertEquals(emptyList(), actions, "a form with a problem gives no action")
            }
        }
    }

    @Test
    fun `a destination the parser refuses is a problem and gives no action`() {
        for (d in listOf(".", "..")) {
            val f = form("a/#", destination = d)
            assertTrue(f.problems(ctx()).isNotEmpty(), "destination \"$d\"")
            assertEquals(emptyList(), f.actions(ctx()), "destination \"$d\"")
        }
    }

    @Test
    fun `hostile filters give a problem or a parseable action and never throw`() {
        for (f in listOf("\u0000", "a\nb", "a/\u0000", "x".repeat(70_000), "/".repeat(500), "\uD800/#")) {
            val form = form(f)
            val problems = form.problems(ctx())
            val actions = form.actions(ctx())
            if (problems.isEmpty()) {
                assertEquals(1, actions.size)
                assertTrue(MigrationParser.parse(MigrationParser.exportToJson("x", "", actions)) is MigrationValidationResult.Valid, "the parser refuses an accepted form")
            } else {
                assertEquals(emptyList(), actions)
            }
        }
    }

    @Test
    fun `the assumption sentence is the decided one and says the app cannot check`() {
        assertEquals(
            "Assumes the MQTT plugin uses its default exchange amq.topic. The app cannot check this: on a broker with another MQTT exchange this route receives nothing from MQTT.",
            PlannerTexts.MQTT_EXCHANGE_ASSUMPTION,
        )
        assertTrue("Uses the broker's default MQTT exchange" !in PlannerTexts.MQTT_EXCHANGE_ASSUMPTION, "the old sentence states a fact")
    }

    @Test
    fun `the line that says what is saved names the exchange and the key`() {
        assertEquals("Saved as a binding on amq.topic with the key sensors.*.temp", PlannerTexts.mqttRouteSaves("sensors.*.temp"))
        assertEquals("MQTT route", PlannerTexts.MQTT_ROUTE_LABEL)
    }
}
