package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.DeclareCheck
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M9-05 step 4 (test developer): exploratory checks of `PlanCheck` beyond the brief's rules. */
class PlanCheckExploratoryTest {
    private val context = PlannerContext("uat-eu", "/", TopologyControlLevel.FULL_CONTROL, true, null)
    private fun plan(vararg a: MigrationAction) = TopologyPlan.of("", a.toList(), "/")
    private fun steps(r: PlanCheckResult) = assertIs<PlanCheckResult.Checked>(r).steps

    @Test
    fun `a plan of 400 steps is read one at a time, in order, and progress is 1-based with the total`() {
        val actions = (1..400).map { MigrationAction.DeclareQueue("q$it") }
        val running = AtomicInteger(); val peak = AtomicInteger(); val order = mutableListOf<String>()
        val port = object : DeclareCheck {
            override suspend fun find(action: MigrationAction): BrokerFinding {
                peak.updateAndGet { maxOf(it, running.incrementAndGet()) }
                yield()
                order += action.description
                running.decrementAndGet()
                return BrokerFinding.Absent
            }
        }
        val seen = mutableListOf<Pair<Int, Int>>()
        val result = steps(runBlocking { PlanCheck.run(plan(*actions.toTypedArray()), context, port) { n, t -> seen += n to t } })
        assertEquals(400, result.size)
        assertEquals(1, peak.get(), "two reads ran at once")
        assertEquals(actions.map { it.description }, order)
        assertEquals((1..400).map { it to 400 }, seen)
    }

    @Test
    fun `hostile names never reach a sentence unless the echo rule allows them`() {
        val names = listOf("<script>alert(1)</script>", "x".repeat(200), "‮evil‬", "a/b", "100%25", "line\nbreak", "q\"uote", " ", "..", "%2F")
        for (name in names) {
            val binding = MigrationAction.DeclareBinding(name, "orders", "k")
            val port = FakeDeclareCheck().answerNamed(name, BrokerFinding.Absent).answerNamed("orders", BrokerFinding.Same).answer(binding, BrokerFinding.Absent)
            val finding = steps(runBlocking { PlanCheck.run(plan(binding), context, port) }).single().finding
            val what = assertIs<StepFinding.Missing>(finding).what
            assertFalse(what.contains(name) && name.length > 64, "a 200-character name was echoed")
            assertTrue(Regex("^The exchange( [A-Za-z0-9._-]+)? is not on the broker and is not declared earlier in this plan\\.$").matches(what), "[$name] -> $what")
        }
    }

    @Test
    fun `an empty name does not crash the check`() {
        val q = MigrationAction.DeclareQueue("")
        val result = steps(runBlocking { PlanCheck.run(plan(q), context, FakeDeclareCheck().answer(q, BrokerFinding.Unreadable("this action cannot be sent to the broker"))) })
        assertEquals(StepFinding.NotChecked("this action cannot be sent to the broker"), result.single().finding)
    }

    @Test
    fun `a broker-level action in a plan is not read and not claimed`() {
        val vhost = MigrationAction.DeclareVhost("team")
        val check = FakeDeclareCheck()
        val step = steps(runBlocking { PlanCheck.run(plan(vhost), context, check) }).single()
        assertTrue(step.finding is StepFinding.NotChecked, "${step.finding}")
        assertTrue(check.calls.isEmpty(), "${check.calls}")
    }

    @Test
    fun `a connection lost mid-check keeps the earlier results and marks the later steps could not be checked`() {
        val lost = BrokerFinding.Unreadable("the broker did not answer")
        val a = MigrationAction.DeclareQueue("a"); val b = MigrationAction.DeclareQueue("b"); val c = MigrationAction.DeclareQueue("c")
        val check = FakeDeclareCheck().answer(a, BrokerFinding.Same).answer(b, lost).answer(c, lost)
        val result = steps(runBlocking { PlanCheck.run(plan(a, b, c), context, check) })
        assertEquals(listOf(StepFinding.AlreadyThere, StepFinding.NotChecked("the broker did not answer"), StepFinding.NotChecked("the broker did not answer")), result.map { it.finding })
    }

    @Test
    fun `a check cancelled before it starts reads nothing`() = runBlocking<Unit> {
        val check = FakeDeclareCheck().answerNamed("orders", BrokerFinding.Same)
        val job = async(start = CoroutineStart.LAZY) { PlanCheck.run(plan(MigrationAction.DeclareQueue("orders")), context, check) }
        job.cancel()
        assertFailsWith<kotlinx.coroutines.CancellationException> { job.await() }
        assertTrue(check.calls.isEmpty())
    }

    @Test
    fun `a queue name is not an exchange, a binding to a queue the plan only declared as an exchange is missing`() {
        val exchange = MigrationAction.DeclareExchange("orders")
        val bind = MigrationAction.DeclareBinding("orders", "orders", "k")
        val port = FakeDeclareCheck().answerNamed("orders", BrokerFinding.Absent).answer(bind, BrokerFinding.Absent)
        val findings = steps(runBlocking { PlanCheck.run(plan(exchange, bind), context, port) }).map { it.finding }
        assertEquals(StepFinding.Missing("The queue orders is not on the broker and is not declared earlier in this plan."), findings[1])
    }

    @Test
    fun `a binding to an exchange destination declared earlier as an exchange is fine`() {
        val x = MigrationAction.DeclareExchange("src"); val y = MigrationAction.DeclareExchange("dst")
        val bind = MigrationAction.DeclareBinding("src", "dst", "k", destinationType = BindingDestinationType.EXCHANGE)
        val port = FakeDeclareCheck().answerNamed("src", BrokerFinding.Absent).answerNamed("dst", BrokerFinding.Absent).answer(bind, BrokerFinding.Absent)
        assertEquals(List(3) { StepFinding.WillBeCreated }, steps(runBlocking { PlanCheck.run(plan(x, y, bind), context, port) }).map { it.finding })
    }

    @Test
    fun `an operator policy and a policy are compared like the others and a policy delete is present or gone`() {
        val p = MigrationAction.DeclarePolicy("p", "^q"); val op = MigrationAction.DeclareOperatorPolicy("o", "^q"); val d = MigrationAction.DeletePolicy("old")
        val port = FakeDeclareCheck().answer(p, BrokerFinding.Different).answer(op, BrokerFinding.Same).answer(d, BrokerFinding.Absent)
        val ctx = context.copy(controlLevel = TopologyControlLevel.FULL_CONTROL)
        assertEquals(listOf(StepFinding.Different, StepFinding.AlreadyThere, StepFinding.AlreadyGone), steps(runBlocking { PlanCheck.run(plan(p, op, d), ctx, port) }).map { it.finding })
    }
}
