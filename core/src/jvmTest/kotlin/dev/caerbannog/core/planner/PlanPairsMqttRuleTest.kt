package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * M9-07b step 2: the ONE rule "an MQTT route leads into a response stream", shared by the graph note and the summary sentence (decision 4).
 * API fixed by these tests (beyond the brief, for the architect to confirm):
 *  - `PlanPairs.responseIndexes(streamNames: List<String>): Map<Int, Int>`: index of a response stream in the list to the index of its request
 *    stream (the second entry point over names; `responseOf(plan)` must agree with it);
 *  - `PlanPairs.requestsAnsweredOverMqtt(actions: List<MigrationAction>): List<String>`: the names of the request streams whose response stream
 *    has an MQTT route (`MqttRoutes.routeOf`) as a destination, each once, in the order the request streams are declared.
 */
class PlanPairsMqttRuleTest {
    private fun stream(name: String) = MigrationAction.DeclareStream(name = name)
    private fun route(to: String, key: String = "devices.*.answer") = MigrationAction.DeclareBinding("amq.topic", to, key)
    private fun rule(vararg actions: MigrationAction) = PlanPairs.requestsAnsweredOverMqtt(actions.toList())

    @Test
    fun `the entry point over names agrees with the plan one`() {
        val names = listOf("orders", "orders-dln", "free", "free-replies", "-dln", "")
        val plan = planOf(*names.map { stream(it) }.toTypedArray())
        val byIndex = PlanPairs.responseIndexes(names)
        assertEquals(mapOf(1 to 0), byIndex)
        val ids = plan.steps.map { it.id }
        assertEquals(PlanPairs.responseOf(plan), byIndex.entries.associate { (resp, req) -> ids[resp] to ids[req] })
    }

    @Test
    fun `the entry point over names knows each naming style and the order`() {
        for (style in NamingStyle.entries) {
            assertEquals(mapOf(0 to 1), PlanPairs.responseIndexes(listOf(PlanNaming.derived("orders", DerivedWord.DLN, style), "orders")), "style $style")
        }
        assertEquals(emptyMap(), PlanPairs.responseIndexes(emptyList()))
    }

    @Test
    fun `an answers route into the response stream names its request stream`() {
        assertEquals(listOf("orders"), rule(stream("orders"), stream("orders-dln"), route("orders-dln")))
    }

    @Test
    fun `the route may come before the streams of the plan`() {
        assertEquals(listOf("orders"), rule(route("orders-dln"), stream("orders-dln"), stream("orders")))
    }

    @Test
    fun `a route into the request stream says nothing`() {
        assertEquals(emptyList(), rule(stream("orders"), stream("orders-dln"), route("orders", "commands.#")))
    }

    @Test
    fun `a route into a stream that is not a response stream says nothing`() {
        assertEquals(emptyList(), rule(stream("telemetry"), route("telemetry", "sensors.#")))
        assertEquals(emptyList(), rule(stream("orders"), stream("orders-replies"), route("orders-replies")))
        assertEquals(emptyList(), rule(stream("orders-dln"), route("orders-dln")), "a name that looks derived without a request stream")
    }

    @Test
    fun `a pair without any route says nothing`() {
        assertEquals(emptyList(), rule(stream("orders"), stream("orders-dln")))
        assertEquals(emptyList(), rule())
    }

    @Test
    fun `two routes into one response stream give one request stream once`() {
        assertEquals(listOf("orders"), rule(stream("orders"), stream("orders-dln"), route("orders-dln"), route("orders-dln", "devices.#")))
    }

    @Test
    fun `two pairs give their request streams in declaration order`() {
        assertEquals(
            listOf("a", "b"),
            rule(stream("a"), stream("a-dln"), stream("b"), stream("b-dln"), route("a-dln"), route("b-dln", "x.*.y")),
        )
        assertEquals(listOf("b"), rule(stream("a"), stream("a-dln"), stream("b"), stream("b-dln"), route("b-dln")))
    }

    @Test
    fun `a pair typed by hand and a route typed by hand are the same as the form's`() {
        val byForm = pair().actions(ctx()) + MqttRouteForm("devices/+/answer", "orders-dln", MqttDestinationKind.Stream).actions(ctx())
        assertEquals(listOf("orders"), PlanPairs.requestsAnsweredOverMqtt(byForm))
        val byHand = listOf(stream("orders"), stream("orders-dln"), MigrationAction.DeclareBinding("amq.topic", "orders-dln", "devices.*.answer"))
        assertEquals(listOf("orders"), PlanPairs.requestsAnsweredOverMqtt(byHand))
    }

    private fun pair() = StreamForm(name = "orders", withResponse = true)

    @Test
    fun `a binding that is not an MQTT route does not count`() {
        val pair = arrayOf(stream("orders"), stream("orders-dln"))
        assertEquals(emptyList(), rule(*pair, MigrationAction.DeclareBinding("events", "orders-dln", "devices.*.answer")), "another exchange")
        assertEquals(emptyList(), rule(*pair, route("orders-dln", "devices..answer")), "a key no filter gives back")
        assertEquals(
            emptyList(),
            rule(*pair, MigrationAction.DeclareBinding("amq.topic", "orders-dln", "devices.#", arguments = mapOf("x" to ArgumentValue.Text("y")))),
            "a binding with arguments",
        )
        assertEquals(
            emptyList(),
            rule(*pair, MigrationAction.DeclareBinding("amq.topic", "orders-dln", "devices.#", destinationType = BindingDestinationType.EXCHANGE)),
            "a binding to an exchange of that name",
        )
    }

    @Test
    fun `a queue of the response name is not a stream and a delete step is not a declaration`() {
        assertEquals(emptyList(), rule(stream("orders"), MigrationAction.DeclareQueue(name = "orders-dln"), route("orders-dln")))
        assertEquals(emptyList(), rule(MigrationAction.DeleteStream(name = "orders"), stream("orders-dln"), route("orders-dln")))
    }
}
