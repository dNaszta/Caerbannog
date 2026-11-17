package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.topology.BindingDestinationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-10 step 2: `StepForms.formOf` tries `MqttRoutes.routeOf` first (a plain route is an `MqttRouteForm`, its kind not known from the action alone),
 * else a binding keeps `BindingForm`. A saved route comes back byte for byte.
 */
class StepFormsMqttTest {
    private fun json(vararg a: MigrationAction) = MigrationParser.exportToJson("0005", "", a.toList())

    @Test
    fun `a plain route gives the MQTT form with its filter and destination`() {
        val form = StepForms.formOf(MigrationAction.DeclareBinding("amq.topic", "telemetry", "sensors.*.temp"))
        assertEquals(MqttRouteForm(filter = "sensors/+/temp", destination = "telemetry", destinationKind = null), form)
    }

    @Test
    fun `a binding that is not a route keeps the binding form`() {
        assertEquals(
            BindingForm("amq.topic", "fan-out", BindingDestinationType.EXCHANGE, "sensors.#"),
            StepForms.formOf(MigrationAction.DeclareBinding("amq.topic", "fan-out", "sensors.#", destinationType = BindingDestinationType.EXCHANGE)),
        )
        assertEquals(
            BindingForm("amq.topic", "alarms", BindingDestinationType.QUEUE, "sensors..temp"),
            StepForms.formOf(MigrationAction.DeclareBinding("amq.topic", "alarms", "sensors..temp")),
        )
        assertEquals(
            BindingForm("events", "alarms", BindingDestinationType.QUEUE, "sensors.#"),
            StepForms.formOf(MigrationAction.DeclareBinding("events", "alarms", "sensors.#")),
        )
        assertNull(StepForms.formOf(MigrationAction.DeclareBinding("amq.topic", "alarms", "sensors.#", arguments = mapOf("x-note" to ArgumentValue.Text("n")))))
    }

    @Test
    fun `a route step is editable and a route with arguments is not`() {
        assertTrue(PlanStep(1, MigrationAction.DeclareBinding("amq.topic", "t", "a.b")).isEditable)
        assertTrue(!PlanStep(1, MigrationAction.DeclareBinding("amq.topic", "t", "a.b", arguments = mapOf("k" to ArgumentValue.Whole(1)))).isEditable)
    }

    @Test
    fun `a saved route comes back byte for byte, also in another virtual host`() {
        for ((vhost, key) in listOf("/" to "sensors.*.temp", "uat" to "sensors.#", "/" to "\$SYS.#", "v 1" to "a b.c")) {
            val saved = MigrationAction.DeclareBinding("amq.topic", "telemetry", key, vhost = vhost)
            val form = assertIs<MqttRouteForm>(StepForms.formOf(saved))
            val back = form.actions(ctx(plan = TopologyPlan(vhost = vhost)))
            assertEquals(listOf(saved), back, "vhost $vhost key $key")
            assertEquals(json(saved), json(*back.toTypedArray()), "vhost $vhost key $key")
        }
    }

    @Test
    fun `the binding form written on amq topic still writes the binding it always did`() {
        val action = BindingForm("amq.topic", "telemetry", BindingDestinationType.QUEUE, "sensors.#").actions(ctx()).single()
        assertEquals(MigrationAction.DeclareBinding("amq.topic", "telemetry", "sensors.#"), action)
        // and it is worded as a route afterwards (one path for the words)
        assertEquals("MQTT route sensors/# to telemetry", MqttRoutes.words(action, emptyList())!!.title)
    }
}
