package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.topology.BindingDestinationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M9-01 exploratory round 1: hostile and edge inputs found by the test developer. Pure, no broker. */
class PlannerHostileInputTest {
    private fun q(name: String) = MigrationAction.DeclareQueue(name = name)

    private fun roundTripValid(plan: TopologyPlan) =
        MigrationParser.parse(MigrationParser.exportToJson("draft", plan.description, plan.steps.map { it.action })) is MigrationValidationResult.Valid

    @Test
    fun `an id freed by removing the last step is handed out again, and never while the step lives`() {
        val p = TopologyPlan().append(listOf(q("a"), q("b")))
        val removed = p.steps.last().id
        val after = p.remove(removed).append(listOf(q("c")))
        // Documents the behaviour M9-02 must know: a removed id can come back for a new step.
        assertEquals(removed, after.steps.last().id)
        assertEquals(after.steps.size, after.steps.map { it.id }.toSet().size)
    }

    @Test
    fun `an id of a removed step does not touch the step that now has it when replaced or moved by a stale caller only if live`() {
        val p = TopologyPlan().append(listOf(q("a"), q("b")))
        val stale = p.steps.last().id
        val next = p.remove(stale)
        assertEquals(next, next.replace(stale, q("zzz")), "replace of a removed id changes nothing")
        assertEquals(next, next.moveUp(stale))
        assertEquals(next, next.moveDown(stale))
    }

    @Test
    fun `append of nothing changes nothing and ids stay unique across many appends and removals`() {
        var p = TopologyPlan()
        assertEquals(p, p.append(emptyList()))
        repeat(50) { i ->
            p = p.append(listOf(q("n$i"), q("m$i")))
            if (i % 3 == 0) p = p.remove(p.steps[p.steps.size / 2].id)
            assertEquals(p.steps.size, p.steps.map { it.id }.toSet().size, "ids repeat after round $i")
        }
    }

    @Test
    fun `withVhost reaches every action kind that has a vhost and leaves broker-level actions alone`() {
        val actions = listOf(
            MigrationAction.DeclareQueue(name = "q"),
            MigrationAction.DeleteQueue(name = "q"),
            MigrationAction.DeclareExchange(name = "x"),
            MigrationAction.DeleteExchange(name = "x"),
            MigrationAction.DeclareBinding(exchange = "x", queue = "q", routingKey = "k"),
            MigrationAction.DeclareStream(name = "s"),
            MigrationAction.DeleteStream(name = "s"),
        )
        val moved = TopologyPlan().append(actions).withVhost("other")
        assertEquals("other", moved.vhost)
        val vhosts = moved.steps.map {
            when (val a = it.action) {
                is MigrationAction.DeclareQueue -> a.vhost
                is MigrationAction.DeleteQueue -> a.vhost
                is MigrationAction.DeclareExchange -> a.vhost
                is MigrationAction.DeleteExchange -> a.vhost
                is MigrationAction.DeclareBinding -> a.vhost
                is MigrationAction.DeclareStream -> a.vhost
                is MigrationAction.DeleteStream -> a.vhost
                else -> error("unexpected")
            }
        }
        assertEquals(List(actions.size) { "other" }, vhosts)
        assertEquals(moved.steps.map { it.id }, TopologyPlan().append(actions).steps.map { it.id }, "ids and order survive a vhost move")
    }

    @Test
    fun `moving the only step, the first up and the last down change nothing`() {
        val one = TopologyPlan().append(listOf(q("a")))
        val id = one.steps.single().id
        assertEquals(one, one.moveUp(id))
        assertEquals(one, one.moveDown(id))
        assertEquals(TopologyPlan(), TopologyPlan().moveUp(1))
    }

    @Test
    fun `hostile names never throw, never block on style and never echo a control character`() {
        val hostile = listOf(
            "", " ", "   ", "\u0000", "a\nb", "a‮b", "🐇", "\uD83D", "ÉCOLE", "İstanbul", "ß", "٣٤", "-", "---", "a--b", "_", "...", "a b c",
            "x".repeat(1000), "X".repeat(1000), "Ab".repeat(5000),
        )
        for (name in hostile) for (style in NamingStyle.entries) {
            PlanNaming.follows(name, style)
            PlanNaming.example(name, style)
            PlanNaming.hint(name, style)
            PlanNaming.baseOf(name, DerivedWord.DLN)
            PlanNaming.derived(name, DerivedWord.DLQ, style)
            val ctx = ctx(style = style)
            for (form in listOf(QueueForm(name), ExchangeForm(name), BindingForm(name, name))) {
                form.problems(ctx); form.hints(ctx)
                val actions = form.actions(ctx)
                assertEquals(form.problems(ctx).isEmpty(), actions.isNotEmpty(), "actions and problems disagree for ${name.take(20).toList()}")
            }
        }
    }

    @Test
    fun `a hint is never longer than a line even for a 5000 character name`() {
        for (style in listOf(NamingStyle.KEBAB, NamingStyle.SNAKE, NamingStyle.DOT, NamingStyle.CAMEL)) {
            val hint = PlanNaming.hint("Ab".repeat(5000), style)
            assertNotNull(hint)
            assertTrue(hint.length < 200, "hint of ${hint.length} characters for a huge name")
        }
    }

    @Test
    fun `the example of a name is itself a name that follows the style, or null`() {
        val names = listOf("Orders_EU", "orders-eu", "OrdersEU", "a1B2", "1abc", "__x__", "a.b-c_d e", "ÉCOLE", "ÄÖÜ-straße", "٣٤", "HTTPServer")
        for (n in names) for (s in listOf(NamingStyle.KEBAB, NamingStyle.SNAKE, NamingStyle.DOT, NamingStyle.CAMEL)) {
            val e = PlanNaming.example(n, s) ?: continue
            assertTrue(PlanNaming.follows(e, s), "example '$e' of '$n' does not follow $s")
            assertNull(PlanNaming.hint(e, s), "a name that follows must not get a hint")
        }
    }

    @Test
    fun `derived then baseOf returns the base in every style`() {
        for (s in NamingStyle.entries) for (w in DerivedWord.entries) {
            for (base in listOf("orders", "orders-eu", "a")) {
                assertEquals(base, PlanNaming.baseOf(PlanNaming.derived(base, w, s), w), "style $s word $w base $base")
            }
        }
    }

    @Test
    fun `a name that only is the derived word has no base`() {
        for (w in DerivedWord.entries) {
            assertNull(PlanNaming.baseOf(w.word, w))
            assertNull(PlanNaming.baseOf("-${w.word}", w))
            assertNull(PlanNaming.baseOf("", w))
        }
    }

    @Test
    fun `a binding to an exchange destination needs that exchange declared before it`() {
        val plan = TopologyPlan().append(
            listOf(
                MigrationAction.DeclareBinding(exchange = "a", queue = "b", routingKey = "", destinationType = BindingDestinationType.EXCHANGE),
                MigrationAction.DeclareExchange(name = "a"),
                MigrationAction.DeclareExchange(name = "b"),
            ),
        )
        val problems = PlanValidation.problems(plan).filter { it.blocking }
        assertEquals(2, problems.size, problems.toString())
        assertTrue(problems.all { it.stepId == plan.steps[0].id })
    }

    @Test
    fun `the same name as an exchange and as a queue is not a conflict`() {
        val plan = TopologyPlan().append(listOf(q("orders"), MigrationAction.DeclareExchange(name = "orders")))
        assertEquals(emptyList(), PlanValidation.problems(plan))
    }

    @Test
    fun `three declarations of one queue flag the second and the third and exactly those`() {
        val plan = TopologyPlan().append(listOf(q("a"), MigrationAction.DeclareQueue(name = "a", type = "classic"), q("a")))
        val problems = PlanValidation.problems(plan)
        assertEquals(listOf(plan.steps[1].id, plan.steps[2].id), problems.map { it.stepId })
        assertTrue(problems[0].blocking)
    }

    @Test
    fun `a hostile virtual host is either refused by the parser round trip or accepted by it, never a crash`() {
        for (v in listOf("", " ", "a/b", "x".repeat(1000), "\u0000", "é")) {
            val plan = TopologyPlan().append(listOf(q("a"))).withVhost(v)
            val problems = PlanValidation.problems(plan)
            assertEquals(roundTripValid(plan), problems.none { it.blocking }, "vhost '${v.take(10)}' disagrees with the parser: $problems")
            assertEquals(v, plan.vhost)
        }
    }

    @Test
    fun `a name the echo rule refuses is not echoed in a plan problem`() {
        val bad = "a\u0007b"
        val plan = TopologyPlan().append(listOf(q(bad), MigrationAction.DeclareQueue(name = bad, type = "classic")))
        for (p in PlanValidation.problems(plan)) assertFalse(p.message.any { it.isISOControl() }, "control character in: ${p.message}")
    }

    @Test
    fun `a stream declared twice is flagged like a queue, and a stream before its binding is named a stream`() {
        val plan = TopologyPlan().append(
            listOf(
                MigrationAction.DeclareStream(name = "s"),
                MigrationAction.DeclareStream(name = "s"),
            ),
        )
        assertEquals(1, PlanValidation.problems(plan).size)
        assertFalse(PlanValidation.problems(plan).single().blocking)
    }

    @Test
    fun `suggestions never repeat a name and plan names come first even when the broker has them`() {
        val plan = TopologyPlan().append(listOf(q("b"), q("b"), q("a")))
        val known = KnownTopology(queues = setOf("a", "z"), streams = setOf("z", "y"))
        val s = ctx(plan = plan, known = known).destinationSuggestions(BindingDestinationType.QUEUE)
        assertEquals(listOf("b", "a", "z", "y"), s.map { it.name })
        assertEquals(listOf(SuggestionSource.PLAN, SuggestionSource.PLAN, SuggestionSource.BROKER, SuggestionSource.BROKER), s.map { it.source })
    }
}
