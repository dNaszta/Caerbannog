package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * M9-10 step 2: recognition (A10) and the words of a route, the one function the planner list, the Migrations rows and the review graph share.
 * API fixed here (for the architect to confirm): `data class MqttRoute(val filter: String, val key: String, val destination: String)`;
 * `object MqttRoutes { fun routeOf(action: MigrationAction): MqttRoute?; fun kindOf(destination: String, plan: List<MigrationAction>, known: KnownTopology): MqttDestinationKind?;
 * fun words(action: MigrationAction, plan: List<MigrationAction>, known: KnownTopology = KnownTopology.None): MqttRouteWords? }` and
 * `data class MqttRouteWords(val title: String, val detail: String)`. `words` is null for everything that is not a route (the caller then uses the
 * action's own description and detail). `plan` is the other actions of the same plan or migration (the kind word comes from them, then from `known`).
 */
class MqttRoutesTest {
    private fun bind(key: String, to: String = "telemetry", exchange: String = "amq.topic", kind: BindingDestinationType = BindingDestinationType.QUEUE,
                     args: Map<String, ArgumentValue> = emptyMap(), vhost: String = "/") =
        MigrationAction.DeclareBinding(exchange = exchange, queue = to, routingKey = key, vhost = vhost, destinationType = kind, arguments = args)

    // ---- recognition ----

    @Test
    fun `a plain binding on amq topic to a queue is a route with its filter`() {
        assertEquals(MqttRoute("sensors/+/temp", "sensors.*.temp", "telemetry"), MqttRoutes.routeOf(bind("sensors.*.temp")))
        assertEquals(MqttRoute("sensors/#", "sensors.#", "alarms"), MqttRoutes.routeOf(bind("sensors.#", to = "alarms")))
        assertEquals(MqttRoute("#", "#", "t"), MqttRoutes.routeOf(bind("#", to = "t")))
        assertEquals(MqttRoute("sensors/a b/temp", "sensors.a b.temp", "t"), MqttRoutes.routeOf(bind("sensors.a b.temp", to = "t")))
        assertEquals(MqttRoute("\$SYS/#", "\$SYS.#", "t"), MqttRoutes.routeOf(bind("\$SYS.#", to = "t")))
    }

    @Test
    fun `a route in another virtual host is still a route`() {
        assertNotNull(MqttRoutes.routeOf(bind("a.b", vhost = "uat")))
    }

    @Test
    fun `the three not-a-route cases and an exchange destination`() {
        assertNull(MqttRoutes.routeOf(bind("sensors.#", to = "fan-out", kind = BindingDestinationType.EXCHANGE)), "an exchange destination is never a route")
        assertNull(MqttRoutes.routeOf(bind("sensors.#", to = "alarms", args = mapOf("x-note" to ArgumentValue.Text("n")))), "arguments")
        assertNull(MqttRoutes.routeOf(bind("sensors..temp", to = "alarms")), "an empty word")
    }

    @Test
    fun `other exchanges and other actions are not routes`() {
        assertNull(MqttRoutes.routeOf(bind("sensors.#", exchange = "events")))
        assertNull(MqttRoutes.routeOf(bind("sensors.#", exchange = "AMQ.TOPIC")))
        assertNull(MqttRoutes.routeOf(bind("sensors.#", exchange = "amq.topic ")))
        assertNull(MqttRoutes.routeOf(MigrationAction.DeleteBinding("amq.topic", "telemetry", "sensors.#")))
        assertNull(MqttRoutes.routeOf(MigrationAction.DeclareStream("telemetry")))
        assertNull(MqttRoutes.routeOf(MigrationAction.DeclareQueue("telemetry", "quorum")))
    }

    @Test
    fun `a key whose filter would not give the key again is not a route`() {
        for (k in listOf("", "a..b", ".a", "a.", "a.#.b", "#.a", "a.b#", "a.*b", "a/b", "a.+", "+")) assertNull(MqttRoutes.routeOf(bind(k)), "key \"$k\"")
    }

    @Test
    fun `recognition and the form agree for every key either one accepts`() {
        val keys = listOf("sensors.*.temp", "a", "*", "#", "x y.z", "a.b.c.d", "\$SYS.#", "*.#")
        for (k in keys) {
            val route = MqttRoutes.routeOf(bind(k)) ?: error("no route for $k")
            val form = MqttRouteForm(route.filter, route.destination)
            assertEquals(emptyList(), form.problems(ctx()), "key $k")
            assertEquals(listOf(bind(k)), form.actions(ctx()), "key $k")
        }
    }

    // ---- the kind of the destination ----

    @Test
    fun `the kind comes from the plan first, then from the broker, else nobody knows`() {
        val plan = listOf(MigrationAction.DeclareStream("telemetry"), MigrationAction.DeclareQueue("alarms", "quorum"))
        val known = KnownTopology(queues = setOf("old-q"), streams = setOf("old-s"))
        assertEquals(MqttDestinationKind.Stream, MqttRoutes.kindOf("telemetry", plan, KnownTopology.None))
        assertEquals(MqttDestinationKind.Queue, MqttRoutes.kindOf("alarms", plan, KnownTopology.None))
        assertEquals(MqttDestinationKind.Stream, MqttRoutes.kindOf("old-s", plan, known))
        assertEquals(MqttDestinationKind.Queue, MqttRoutes.kindOf("old-q", plan, known))
        assertNull(MqttRoutes.kindOf("ghost", plan, known))
        assertNull(MqttRoutes.kindOf("ghost", emptyList(), KnownTopology.None))
    }

    @Test
    fun `the plan wins over the broker when they disagree`() {
        assertEquals(MqttDestinationKind.Queue, MqttRoutes.kindOf("x", listOf(MigrationAction.DeclareQueue("x", "quorum")), KnownTopology(streams = setOf("x"))))
        assertEquals(MqttDestinationKind.Stream, MqttRoutes.kindOf("x", listOf(MigrationAction.DeclareStream("x")), KnownTopology(queues = setOf("x"))))
    }

    // ---- the words ----

    @Test
    fun `words say stream, queue or nothing about the kind`() {
        val route = bind("sensors.*.temp")
        assertEquals("MQTT route sensors/+/temp to stream telemetry", MqttRoutes.words(route, listOf(MigrationAction.DeclareStream("telemetry")))!!.title)
        assertEquals("MQTT route sensors/+/temp to queue telemetry", MqttRoutes.words(route, listOf(MigrationAction.DeclareQueue("telemetry", "classic")))!!.title)
        assertEquals("MQTT route sensors/+/temp to stream telemetry", MqttRoutes.words(route, emptyList(), KnownTopology(streams = setOf("telemetry")))!!.title)
        assertEquals("MQTT route sensors/+/temp to queue telemetry", MqttRoutes.words(route, emptyList(), KnownTopology(queues = setOf("telemetry")))!!.title)
        assertEquals("MQTT route sensors/+/temp to telemetry", MqttRoutes.words(route, emptyList())!!.title)
    }

    @Test
    fun `the second line names the binding and the key`() {
        val words = MqttRoutes.words(bind("sensors.*.temp"), emptyList())!!
        assertEquals("Binding on amq.topic with the key sensors.*.temp", words.detail)
    }

    @Test
    fun `the route itself is also found among the plan's actions without confusing the kind`() {
        val route = bind("a.b", to = "telemetry")
        assertEquals("MQTT route a/b to stream telemetry", MqttRoutes.words(route, listOf(MigrationAction.DeclareStream("telemetry"), route))!!.title)
    }

    @Test
    fun `words are null for a binding that is not a route`() {
        assertNull(MqttRoutes.words(bind("sensors.#", exchange = "events"), emptyList()))
        assertNull(MqttRoutes.words(bind("sensors..temp"), emptyList()))
        assertNull(MqttRoutes.words(bind("x", args = mapOf("a" to ArgumentValue.Whole(1))), emptyList()))
        assertNull(MqttRoutes.words(MigrationAction.DeclareStream("telemetry"), emptyList()))
    }
}
