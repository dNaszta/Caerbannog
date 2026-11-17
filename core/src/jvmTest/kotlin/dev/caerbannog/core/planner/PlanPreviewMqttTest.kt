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
}
