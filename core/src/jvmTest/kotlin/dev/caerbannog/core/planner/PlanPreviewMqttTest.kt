package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * M9-10 step 2: the review graph labels a route. API fixed here: `PlanGraph.edgeLabels[edge id]` (existing field, empty until now) holds the topic
 * filter of an MQTT route's edge, and the new field `PlanGraph.mqttRouteEdgeIds: Set<String>` holds the ids of the edges that are routes (the UI draws
 * the words `PlannerTexts.MQTT_ROUTE_LABEL` for those). A binding that is not a route has no label and is not in the set.
 */
class PlanPreviewMqttTest {
    private fun route(key: String, to: String = "telemetry") = MigrationAction.DeclareBinding(exchange = "amq.topic", queue = to, routingKey = key)

    @Test
    fun `an MQTT route's edge carries its filter and is marked as a route`() {
        val g = planPreview(planOf(MigrationAction.DeclareStream("telemetry"), route("sensors.*.temp")), KnownTopology.None)
        val edge = g.graph.edges.single().edge
        assertEquals("e:amq.topic", edge.sourceId)
        assertEquals("q:telemetry", edge.targetId)
        assertEquals(mapOf(edge.id to "sensors/+/temp"), g.edgeLabels)
        assertEquals(setOf(edge.id), g.mqttRouteEdgeIds)
        assertEquals("sensors.*.temp", edge.routingKey, "the binding itself is unchanged")
        assertEquals("MQTT route", PlannerTexts.MQTT_ROUTE_LABEL)
    }

    @Test
    fun `a binding that is not a route has no label`() {
        val plan = planOf(
            MigrationAction.DeclareQueue("alarms"),
            MigrationAction.DeclareExchange("events", "topic"),
            MigrationAction.DeclareBinding("events", "alarms", "sensors.#"),
            MigrationAction.DeclareBinding("amq.topic", "alarms", "sensors..temp"),
            MigrationAction.DeclareBinding("amq.topic", "alarms", "sensors.#", arguments = mapOf("x-note" to ArgumentValue.Text("n"))),
            MigrationAction.DeclareBinding("amq.topic", "events", "sensors.#", destinationType = BindingDestinationType.EXCHANGE),
        )
        val g = planPreview(plan, KnownTopology.None)
        assertEquals(emptyMap(), g.edgeLabels)
        assertEquals(emptySet(), g.mqttRouteEdgeIds)
    }

    @Test
    fun `two routes to one stream are two labelled edges`() {
        val g = planPreview(planOf(MigrationAction.DeclareStream("t"), route("a.#", "t"), route("b.*", "t")), KnownTopology.None)
        assertEquals(2, g.graph.edges.size)
        assertEquals(setOf("a/#", "b/+"), g.edgeLabels.values.toSet())
        assertEquals(g.edgeLabels.keys, g.mqttRouteEdgeIds)
        assertEquals(g.graph.edges.map { it.edge.id }.toSet(), g.mqttRouteEdgeIds)
    }

    @Test
    fun `a route to something nobody knows is still labelled`() {
        val g = planPreview(planOf(route("sensors.#", "ghost")), KnownTopology.None)
        assertEquals(setOf("sensors/#"), g.edgeLabels.values.toSet())
    }

    // ---- F2 (round 2): built-in amq.* exchanges ----

    private fun streamRoute() = planOf(MigrationAction.DeclareStream("telemetry"), route("sensors.#"))

    @Test
    fun `amq topic that the plan does not declare is existing whether or not the broker was read`() {
        val lacking = KnownTopology(exchanges = mapOf("events" to "topic"), streams = setOf("other"))
        for ((label, known) in listOf("null" to null, "None" to KnownTopology.None, "a list without amq.topic" to lacking)) {
            val g = planPreview(streamRoute(), known)
            assertEquals(PlanMark.Existing, g.markOf("e:amq.topic"), "known = $label")
            assertEquals(PlanMark.New, g.markOf("q:telemetry"), "the stream stays new, known = $label")
        }
    }

    @Test
    fun `reading nothing keeps the notice that only the plan is drawn`() {
        assertEquals(PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.NotConnected), planPreview(streamRoute(), null).notice)
        assertEquals(PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.BrokerNotRead), planPreview(streamRoute(), null, OnlyThePlanCause.BrokerNotRead).notice)
    }

    @Test
    fun `the rule is general, a plain binding on amq direct reads the same`() {
        val plan = planOf(MigrationAction.DeclareQueue("orders"), MigrationAction.DeclareBinding("amq.direct", "orders", "k"))
        for (known in listOf(null, KnownTopology.None)) assertEquals(PlanMark.Existing, planPreview(plan, known).markOf("e:amq.direct"), "known = $known")
    }

    @Test
    fun `a declared exchange whose name starts with amq is new, an undeclared one that is not built in stays not in this plan`() {
        val declared = planOf(MigrationAction.DeclareExchange("amq.custom", "topic"), MigrationAction.DeclareQueue("q"), MigrationAction.DeclareBinding("amq.custom", "q", "k"))
        assertEquals(PlanMark.New, planPreview(declared, KnownTopology.None).markOf("e:amq.custom"))
        val plain = planOf(MigrationAction.DeclareQueue("q"), MigrationAction.DeclareBinding("events", "q", "k"))
        assertEquals(PlanMark.NotInPlan, planPreview(plain, KnownTopology.None).markOf("e:events"))
        assertEquals(PlanMark.NotInPlan, planPreview(plain, null).markOf("e:events"))
    }

    @Test
    fun `while a read is pending an undeclared exchange has no verdict, but a built-in one is existing`() {
        val g = planPreview(planOf(MigrationAction.DeclareQueue("q"), MigrationAction.DeclareBinding("events", "q", "k"), MigrationAction.DeclareBinding("amq.topic", "q", "a.b")), null, pending = true)
        assertEquals(PlanMark.Existing, g.markOf("e:amq.topic"))
        assertEquals(PlanMark.Existing, g.markOf("e:events"), "pending with no read: no verdict means the default mark")
    }
}
