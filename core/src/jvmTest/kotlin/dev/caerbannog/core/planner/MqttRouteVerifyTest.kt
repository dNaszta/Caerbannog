package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-10 step 4 (test developer): the checks beyond the scenarios. A seeded property over 100,000 random filters, the same name as a queue and a
 * stream, the suggestions by kind, and the permission and control level of a route step through the existing runner (no new request kind).
 */
class MqttRouteVerifyTest {
    private val alphabet = listOf("/", "/", "+", "#", ".", "*", "a", "b", "c", " ", "\$", "ü", "‮", "1")

    private fun randomFilter(r: Random): String = buildString { repeat(r.nextInt(0, 12)) { append(alphabet[r.nextInt(alphabet.size)]) } }

    @Test
    fun `100000 random filters, an accepted one round-trips and is recognised, a refused one never gives an action`() {
        val r = Random(910)
        var accepted = 0
        var refused = 0
        repeat(100_000) {
            val f = randomFilter(r)
            val form = MqttRouteForm(f, "telemetry", MqttDestinationKind.Stream)
            val actions = form.actions(ctx())
            when (val t = MqttTopicTranslation.toBindingKey(f)) {
                is Translation.Key -> {
                    accepted++
                    assertEquals(f, MqttTopicTranslation.toFilter(t.text), "filter \"$f\"")
                    val action = actions.singleOrNull() ?: error("an accepted filter \"$f\" gave ${actions.size} actions; problems ${form.problems(ctx())}")
                    assertEquals(MqttRoute(f, t.text, "telemetry"), MqttRoutes.routeOf(action), "filter \"$f\"")
                    assertIs<MqttRouteForm>(StepForms.formOf(action))
                    assertEquals(f, (StepForms.formOf(action) as MqttRouteForm).filter)
                    assertEquals(listOf(action), (StepForms.formOf(action) as MqttRouteForm).actions(ctx()))
                    assertTrue(MigrationParser.parse(MigrationParser.exportToJson("x", "", actions)) is MigrationValidationResult.Valid, "parser refuses \"$f\"")
                    val topic = form.matchingTopic()!!
                    assertTrue('+' !in topic && '#' !in topic, "wildcard in topic \"$topic\" of \"$f\"")
                }
                is Translation.Refused -> {
                    refused++
                    assertEquals(emptyList(), actions, "a refused filter \"$f\" gave an action")
                    assertTrue(form.problems(ctx()).any { it.field == "filter" }, "no filter problem for \"$f\"")
                }
            }
        }
        assertTrue(accepted > 1000 && refused > 1000, "the generator must produce both: accepted=$accepted refused=$refused")
    }

    @Test
    fun `random keys, a recognised route always comes back as the same key through its form`() {
        val r = Random(911)
        val words = listOf("a", "b", "*", "#", "+", "x y", "", "a/b", "\$SYS", "*a", "a#")
        var routes = 0
        repeat(50_000) {
            val key = (0 until r.nextInt(1, 6)).joinToString(".") { words[r.nextInt(words.size)] }
            val action = MigrationAction.DeclareBinding("amq.topic", "q", key)
            val route = MqttRoutes.routeOf(action) ?: return@repeat
            routes++
            assertEquals(listOf(action), MqttRouteForm(route.filter, "q").actions(ctx()), "key \"$key\"")
        }
        assertTrue(routes > 500, "routes=$routes")
    }

    @Test
    fun `only slashes, hashes and pluses`() {
        val all = listOf("/", "//", "#", "##", "#/#", "+/+", "+/#", "#/+", "+#", "#+", "/#", "#/", "+/", "/+", "+/+/+/#")
        for (f in all) {
            val form = MqttRouteForm(f, "q")
            val ok = MqttTopicTranslation.toBindingKey(f) is Translation.Key
            assertEquals(ok, form.problems(ctx()).isEmpty(), "filter \"$f\"")
        }
        assertEquals(listOf("#", "+/+", "+/#", "+/+/+/#"), all.filter { MqttTopicTranslation.toBindingKey(it) is Translation.Key })
    }

    @Test
    fun `a space-only level is carried, emoji and right-to-left text are carried`() {
        for (f in listOf("a/ /b", " ", "\$SYS/#", "😀/#", "‮abc/+")) {
            assertTrue(MqttTopicTranslation.toBindingKey(f) is Translation.Key, "filter \"$f\"")
        }
    }

    @Test
    fun `a filter whose key equals an existing binding adds a second equal step the plan may hold`() {
        val existing = MigrationAction.DeclareBinding("amq.topic", "telemetry", "sensors.#")
        val plan = planOf(MigrationAction.DeclareStream("telemetry"), existing)
        val actions = MqttRouteForm("sensors/#", "telemetry").actions(ctx(plan = plan))
        assertEquals(listOf(existing), actions, "the form writes the same binding again; what the plan does with a duplicate is its own rule")
    }

    // ---- the same name as a queue and a stream ----

    @Test
    fun `a plan that declares a queue and a stream of one name gives the stream kind and the form still writes one binding`() {
        val plan = planOf(MigrationAction.DeclareQueue("x", "quorum"), MigrationAction.DeclareStream("x"))
        assertEquals(MqttDestinationKind.Stream, MqttRoutes.kindOf("x", plan.steps.map { it.action }, KnownTopology.None))
        assertEquals(1, MqttRouteForm("a/#", "x", MqttDestinationKind.Queue).actions(ctx(plan = plan)).size)
    }

    // ---- suggestions by kind ----

    @Test
    fun `the kind narrows the suggestions and null gives both`() {
        val plan = planOf(MigrationAction.DeclareStream("s1"), MigrationAction.DeclareQueue("q1", "quorum"))
        val c = ctx(plan = plan, known = KnownTopology(queues = setOf("q2"), streams = setOf("s2")))
        assertEquals(listOf("s1", "q1", "q2", "s2").sorted(), c.routeDestinationSuggestions(null).map { it.name }.sorted())
        assertEquals(listOf("s1", "s2"), c.routeDestinationSuggestions(MqttDestinationKind.Stream).map { it.name }.sorted())
        assertEquals(listOf("q1", "q2"), c.routeDestinationSuggestions(MqttDestinationKind.Queue).map { it.name }.sorted())
        assertEquals(SuggestionSource.PLAN, c.routeDestinationSuggestions(MqttDestinationKind.Stream).first { it.name == "s1" }.source)
    }

    @Test
    fun `with nothing known every kind has no suggestion and a typed destination is still accepted`() {
        val c = ctx()
        assertEquals(emptyList(), c.routeDestinationSuggestions(MqttDestinationKind.Stream))
        assertEquals(emptyList(), MqttRouteForm("a/#", "typed-by-hand", MqttDestinationKind.Stream).problems(c))
    }

    // ---- permission and control level go through the existing runner ----

    private fun route() = MigrationAction.DeclareBinding("amq.topic", "telemetry", "sensors.#")
    private fun file() = (MigrationParser.parse(MigrationParser.exportToJson("0001", "route", listOf(route()))) as MigrationValidationResult.Valid).migration

    @Test
    fun `a route is not applied without configure permission and nothing is sent`() {
        val executor = FakeTopologyMigrationExecutor()
        val result = runBlocking {
            MigrationRunner(InMemoryMigrationHistoryRepository(), executor).applyPending("p", listOf(file()), false, TopologyControlLevel.FULL_CONTROL, "/")
        }
        assertIs<MigrationApplyResult.Blocked>(result)
        assertEquals(emptyList(), executor.executedActions)
    }

    @Test
    fun `a route is not applied at Read only and nothing is sent`() {
        val executor = FakeTopologyMigrationExecutor()
        val result = runBlocking {
            MigrationRunner(InMemoryMigrationHistoryRepository(), executor).applyPending("p", listOf(file()), true, TopologyControlLevel.READ_ONLY, "/")
        }
        assertIs<MigrationApplyResult.Blocked>(result)
        assertEquals(emptyList(), executor.executedActions)
    }

    @Test
    fun `a route in another virtual host than the connection's is blocked`() {
        val other = MigrationAction.DeclareBinding("amq.topic", "t", "a.b", vhost = "other")
        val f = (MigrationParser.parse(MigrationParser.exportToJson("0001", "r", listOf(other))) as MigrationValidationResult.Valid).migration
        val executor = FakeTopologyMigrationExecutor()
        val result = runBlocking {
            MigrationRunner(InMemoryMigrationHistoryRepository(), executor).applyPending("p", listOf(f), true, TopologyControlLevel.FULL_CONTROL, "/")
        }
        assertIs<MigrationApplyResult.Blocked>(result)
        assertEquals(emptyList(), executor.executedActions)
    }

    @Test
    fun `a route is one declareBinding and nothing else reaches the executor`() {
        val executor = FakeTopologyMigrationExecutor()
        val result = runBlocking {
            MigrationRunner(InMemoryMigrationHistoryRepository(), executor).applyPending("p", listOf(file()), true, TopologyControlLevel.FULL_CONTROL, "/")
        }
        assertIs<MigrationApplyResult.Success>(result)
        assertEquals(listOf<MigrationAction>(route()), executor.executedActions)
        assertNull(MqttRoutes.routeOf(MigrationAction.DeclareBinding("amq.topic", "x", "a.b", destinationType = BindingDestinationType.EXCHANGE)))
    }
}
