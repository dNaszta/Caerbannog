package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-07b step 2: the two optional MQTT ticks of the stream form (owner decision O13). API fixed by these tests (beyond the brief, for the
 * architect to confirm):
 *  - `StreamForm(name, withResponse = false, responseName = null, answersTopic: String? = null, requestsTopic: String? = null)`; null means the
 *    tick is off, a string (even "") means it is on; a tick that is switched on starts with "" (no default filter, decision 1);
 *  - `StreamForm.withPair(on: Boolean): StreamForm` is the pure switch of the pair: off also clears both ticks and keeps the typed response name;
 *  - problems of a tick are under the fields `answersTopic` and `requestsTopic`, the text is `PlannerTexts.mqttRefusal` of the route form's filter check;
 *  - `actions`: the two streams, then the answers route (into the EFFECTIVE response stream name), then the requests route (into the stream),
 *    each exactly what `MqttRouteForm(filter, destination, MqttDestinationKind.Stream).actions(ctx)` gives;
 *  - `problems`, `actions` and `hints` ignore the two fields while `withResponse` is false.
 */
class StreamFormMqttTicksTest {
    private val answers = "devices/+/answer"
    private val requests = "commands/#"

    private fun route(key: String, to: String, vhost: String = "/") =
        MigrationAction.DeclareBinding(exchange = "amq.topic", queue = to, routingKey = key, vhost = vhost, destinationType = BindingDestinationType.QUEUE)

    private fun stream(name: String, vhost: String = "/") = MigrationAction.DeclareStream(name = name, vhost = vhost)

    private fun pair(a: String? = null, r: String? = null, name: String = "device-commands", responseName: String? = null) =
        StreamForm(name = name, withResponse = true, responseName = responseName, answersTopic = a, requestsTopic = r)

    // ---- defaults and the switch ----

    @Test
    fun `both ticks are off by default and null means off`() {
        val form = StreamForm(name = "telemetry")
        assertNull(form.answersTopic)
        assertNull(form.requestsTopic)
        assertNull(StreamForm(name = "telemetry", withResponse = true).answersTopic)
        assertNull(StreamForm(name = "telemetry", withResponse = true).requestsTopic)
    }

    @Test
    fun `a pair without ticks is still the two streams and nothing else`() {
        assertEquals(listOf(stream("device-commands"), stream("device-commands-dln")), pair().actions(ctx()))
    }

    @Test
    fun `switching the pair on keeps both ticks off`() {
        val on = StreamForm(name = "orders").withPair(true)
        assertEquals(StreamForm(name = "orders", withResponse = true), on)
    }

    @Test
    fun `switching the pair off clears both ticks and the pair, and keeps the typed response name for the next tick`() {
        val off = pair(a = answers, r = requests, responseName = "orders-replies").withPair(false)
        assertEquals(false, off.withResponse)
        assertNull(off.answersTopic)
        assertNull(off.requestsTopic)
        assertEquals("orders-replies", off.responseName)
        assertEquals("device-commands", off.name)
        // and back on: the ticks are off again, nothing stale comes back
        val again = off.withPair(true)
        assertNull(again.answersTopic)
        assertNull(again.requestsTopic)
    }

    @Test
    fun `switching the pair on twice or off twice changes nothing more`() {
        val on = pair(a = answers)
        assertEquals(on, on.withPair(true), "on keeps a tick that is on")
        val off = StreamForm(name = "x")
        assertEquals(off, off.withPair(false))
    }

    // ---- actions and their order ----

    @Test
    fun `the answers tick adds one route into the response stream, after the two streams`() {
        assertEquals(
            listOf(stream("device-commands"), stream("device-commands-dln"), route("devices.*.answer", "device-commands-dln")),
            pair(a = answers).actions(ctx()),
        )
    }

    @Test
    fun `the requests tick adds one route into the request stream, after the two streams`() {
        assertEquals(
            listOf(stream("device-commands"), stream("device-commands-dln"), route("commands.#", "device-commands")),
            pair(r = requests).actions(ctx()),
        )
    }

    @Test
    fun `both ticks add two routes, the answers route first`() {
        assertEquals(
            listOf(
                stream("device-commands"),
                stream("device-commands-dln"),
                route("devices.*.answer", "device-commands-dln"),
                route("commands.#", "device-commands"),
            ),
            pair(a = answers, r = requests).actions(ctx()),
        )
    }

    @Test
    fun `a route is exactly what the MQTT route form gives, so nothing is copied`() {
        val c = ctx(plan = TopologyPlan(vhost = "uat"))
        val actions = pair(a = answers, r = requests).actions(c)
        assertEquals(MqttRouteForm(answers, "device-commands-dln", MqttDestinationKind.Stream).actions(c), listOf(actions[2]))
        assertEquals(MqttRouteForm(requests, "device-commands", MqttDestinationKind.Stream).actions(c), listOf(actions[3]))
        assertEquals(listOf("uat"), actions.map { (it as? MigrationAction.DeclareBinding)?.vhost ?: (it as MigrationAction.DeclareStream).vhost }.distinct())
    }

    @Test
    fun `the answers route follows the response name, typed by hand or derived in the naming style`() {
        val typed = pair(a = answers, responseName = "device-answers").actions(ctx())
        assertEquals(route("devices.*.answer", "device-answers"), typed.last())
        val snake = pair(a = answers).actions(ctx(style = NamingStyle.SNAKE))
        assertEquals(route("devices.*.answer", "device-commands_dln"), snake.last())
    }

    @Test
    fun `the plan holds nothing but streams and routes, no exchange, policy or marker`() {
        val actions = pair(a = answers, r = requests).actions(ctx())
        assertEquals(4, actions.size)
        assertTrue(actions.all { it is MigrationAction.DeclareStream || it is MigrationAction.DeclareBinding })
        assertEquals(setOf("amq.topic"), actions.filterIsInstance<MigrationAction.DeclareBinding>().map { it.exchange }.toSet())
    }

    @Test
    fun `the same filter in both ticks is two routes, one for each stream`() {
        val actions = pair(a = "x/#", r = "x/#").actions(ctx())
        assertEquals(listOf("device-commands-dln", "device-commands"), actions.filterIsInstance<MigrationAction.DeclareBinding>().map { it.queue })
    }

    // ---- the filter is checked like an MQTT route ----

    private val hostile = listOf(
        "", " ", "#", "+", "devices/#/answer", "sens+/x", "te#", "a.b", "a/v1.2/b", "a/*/b", "a*b", "a//b", "/a", "a/", "\$SYS/#", "ü/+",
        "a b/+", "\u0000", "a\nb/+", "x".repeat(300), "/", "//", "+/+/+", "#/",
    )

    @Test
    fun `a filter of either tick has the problem of the route form, under its own field`() {
        for (filter in hostile) {
            val expected = MqttRouteForm(filter, "x", MqttDestinationKind.Stream).problems(ctx()).filter { it.field == "filter" }
            assertEquals(
                expected.map { FieldProblem("answersTopic", it.message) },
                pair(a = filter).problems(ctx()),
                "answers filter \"$filter\"",
            )
            assertEquals(
                expected.map { FieldProblem("requestsTopic", it.message) },
                pair(r = filter).problems(ctx()),
                "requests filter \"$filter\"",
            )
            if (expected.isNotEmpty()) {
                assertEquals(emptyList(), pair(a = filter).actions(ctx()), "no step while the answers filter is refused: \"$filter\"")
                assertEquals(emptyList(), pair(r = filter).actions(ctx()), "no step while the requests filter is refused: \"$filter\"")
            }
        }
    }

    @Test
    fun `the texts of the three outline rows`() {
        assertEquals(listOf(FieldProblem("answersTopic", "Enter a topic filter")), pair(a = "").problems(ctx()))
        assertEquals(listOf(FieldProblem("answersTopic", "# must be the last level")), pair(a = "devices/#/answer").problems(ctx()))
        assertEquals(listOf(FieldProblem("requestsTopic", "+ must stand for a whole level")), pair(r = "sens+/x").problems(ctx()))
    }

    @Test
    fun `a tick that is on and empty blocks, so the form says Enter a topic filter until one is typed`() {
        val form = pair(a = "")
        assertEquals(listOf(FieldProblem("answersTopic", "Enter a topic filter")), form.problems(ctx()))
        assertEquals(emptyList(), form.actions(ctx()))
    }

    @Test
    fun `both ticks refused give two problems, the answers one first, and no step`() {
        val form = pair(a = "", r = "a//b")
        assertEquals(
            listOf(FieldProblem("answersTopic", "Enter a topic filter"), FieldProblem("requestsTopic", "A level cannot be empty")),
            form.problems(ctx()),
        )
        assertEquals(emptyList(), form.actions(ctx()))
    }

    @Test
    fun `one refused tick blocks the whole form, the good tick adds nothing alone`() {
        assertEquals(emptyList(), pair(a = answers, r = "").actions(ctx()))
        assertEquals(emptyList(), pair(a = "", r = requests).actions(ctx()))
    }

    @Test
    fun `a bad stream name gives its own message and no second one for the tick`() {
        val noName = pair(a = answers, name = "")
        assertEquals(listOf(FieldProblem("name", PlannerTexts.STREAM_NAME_MISSING)), noName.problems(ctx()))
        assertEquals(emptyList(), noName.actions(ctx()))
        val noResponse = pair(a = answers, responseName = "")
        assertEquals(listOf(FieldProblem("responseName", PlannerTexts.RESPONSE_NAME_MISSING)), noResponse.problems(ctx()))
        val same = pair(a = answers, responseName = "device-commands")
        assertEquals(listOf(FieldProblem("responseName", PlannerTexts.RESPONSE_NAME_SAME)), same.problems(ctx()))
        assertEquals(emptyList(), same.actions(ctx()))
    }

    @Test
    fun `a bad name and a bad filter are both told, one message for each mistake`() {
        assertEquals(
            listOf(FieldProblem("name", PlannerTexts.STREAM_NAME_MISSING), FieldProblem("answersTopic", "Enter a topic filter")),
            pair(a = "", name = "").problems(ctx()),
        )
    }

    // ---- while the pair is off the ticks are ignored ----

    @Test
    fun `without the pair a stale tick value gives no step, no problem and no hint`() {
        val stale = StreamForm(name = "telemetry", withResponse = false, answersTopic = answers, requestsTopic = "a//b")
        assertEquals(emptyList(), stale.problems(ctx()))
        assertEquals(listOf(stream("telemetry")), stale.actions(ctx()))
        assertEquals(StreamForm(name = "telemetry").hints(ctx()), stale.hints(ctx()))
        val refused = StreamForm(name = "telemetry", withResponse = false, answersTopic = "", requestsTopic = "")
        assertEquals(emptyList(), refused.problems(ctx()))
        assertEquals(listOf(stream("telemetry")), refused.actions(ctx()))
    }

    @Test
    fun `a tick never adds a hint`() {
        assertEquals(pair().hints(ctx()), pair(a = answers, r = requests).hints(ctx()))
        assertEquals(pair().hints(ctx()), pair(a = "", r = "#/").hints(ctx()))
    }

    // ---- a reopened pair is ordinary steps ----

    @Test
    fun `a reopened stream is an ordinary stream form and a reopened route an ordinary route form`() {
        assertEquals(StreamForm(name = "device-commands-dln"), StepForms.formOf(stream("device-commands-dln")))
        assertEquals(MqttRouteForm(filter = answers, destination = "device-commands-dln"), StepForms.formOf(route("devices.*.answer", "device-commands-dln")))
    }
}
