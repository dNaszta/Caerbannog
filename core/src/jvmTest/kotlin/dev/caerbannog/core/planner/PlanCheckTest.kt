package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.TopologyControlLevel.DECLARE_AND_EDIT
import dev.caerbannog.core.connections.TopologyControlLevel.FULL_CONTROL
import dev.caerbannog.core.connections.TopologyControlLevel.READ_ONLY
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.DeclareCheck
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRunRules
import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-05: `PlanCheck.run(plan, context, check, onStep)` says, per step and from reads alone, what the broker has and whether the existing dry
 * run blocks the step. One test per rule of the brief. The port is the scripted [FakeDeclareCheck], which throws on a call nobody scripted, so a
 * read the rules do not allow cannot go unnoticed. The shape pinned here (the architect confirms it in step 2.5):
 *  - `PlanCheck.run(...)` is `suspend` and returns [PlanCheckResult]: `Refused(reason)` or `Checked(steps: List<StepCheck>)`;
 *  - `StepCheck(stepId, finding: StepFinding, blocked: String?, notes: List<String>)`; `blocked` is the dry-run reason of that action, or null;
 *  - `onStep(position, total)` is called before each step is read, 1-based.
 */
class PlanCheckTest {

    private val threeX = "This broker runs RabbitMQ 3.x: it is a migration source only"
    private val orders = MigrationAction.DeclareQueue("orders")
    private val events = MigrationAction.DeclareExchange("events")
    private val bind = MigrationAction.DeclareBinding("events", "orders", "order.created")

    private fun context(
        level: TopologyControlLevel = DECLARE_AND_EDIT,
        configure: Boolean = true,
        vhost: String = "/",
        unavailable: String? = null,
        connection: String? = "uat-eu",
    ) = PlannerContext(connection, vhost, level, configure, unavailable)

    private fun plan(vararg actions: MigrationAction) = TopologyPlan.of("", actions.toList(), "/")

    private fun run(plan: TopologyPlan, context: PlannerContext = context(), check: DeclareCheck?, onStep: (Int, Int) -> Unit = { _, _ -> }): PlanCheckResult =
        runBlocking { PlanCheck.run(plan, context, check, onStep) }

    private fun steps(result: PlanCheckResult): List<StepCheck> = assertIs<PlanCheckResult.Checked>(result).steps

    private fun findings(plan: TopologyPlan, check: DeclareCheck, context: PlannerContext = context()): List<StepFinding> = steps(run(plan, context, check)).map { it.finding }

    // ---- no port: refused, nothing sent ----

    @Test
    fun `with no port the whole check is refused with the connection's reason and nothing is sent`() {
        val result = run(plan(orders), context(unavailable = threeX), check = null)
        assertEquals(PlanCheckResult.Refused(threeX), result)
    }

    @Test
    fun `a connection that is not connected is refused with the not-connected text`() {
        assertEquals(PlanCheckResult.Refused(PlannerTexts.NOT_CONNECTED), run(plan(orders), context(connection = null), check = null))
    }

    @Test
    fun `a connection that cannot run migrations is refused even when a port is handed in, and the port is never called`() {
        val check = FakeDeclareCheck()
        assertEquals(PlanCheckResult.Refused(threeX), run(plan(orders), context(unavailable = threeX), check))
        assertTrue(check.calls.isEmpty(), "a refused check read ${check.calls}")
    }

    @Test
    fun `a refusal has a reason whatever the cause`() {
        val refused = assertIs<PlanCheckResult.Refused>(run(plan(orders), context(), check = null))
        assertTrue(refused.reason.isNotBlank())
    }

    // ---- shape ----

    @Test
    fun `one result per step, in the order of the plan, with the id of the step`() {
        val p = plan(orders, events)
        val check = FakeDeclareCheck().answerNamed("orders", BrokerFinding.Same).answerNamed("events", BrokerFinding.Absent)
        val result = steps(run(p, check = check))
        assertEquals(p.steps.map { it.id }, result.map { it.stepId })
        assertEquals(listOf(StepFinding.AlreadyThere, StepFinding.WillBeCreated), result.map { it.finding })
    }

    @Test
    fun `an empty plan is checked to nothing and reads nothing`() {
        val check = FakeDeclareCheck()
        assertEquals(PlanCheckResult.Checked(emptyList()), run(plan(), check = check))
        assertTrue(check.calls.isEmpty())
    }

    // ---- what the broker has ----

    @Test
    fun `absent is will be created, same is already there, different is different`() {
        val p = plan(orders)
        assertEquals(listOf(StepFinding.WillBeCreated), findings(p, FakeDeclareCheck().answer(orders, BrokerFinding.Absent)))
        assertEquals(listOf(StepFinding.AlreadyThere), findings(p, FakeDeclareCheck().answer(orders, BrokerFinding.Same)))
        assertEquals(listOf(StepFinding.Different), findings(p, FakeDeclareCheck().answer(orders, BrokerFinding.Different)))
    }

    @Test
    fun `unreadable is could not be checked with the executor's reason, and never will be created, for every kind of step`() {
        val kinds = listOf(
            MigrationAction.DeclareQueue("q"), MigrationAction.DeclareExchange("x"), MigrationAction.DeclareStream("s"),
            MigrationAction.DeclarePolicy("p", "^q"), MigrationAction.DeclareOperatorPolicy("op", "^q"),
            MigrationAction.DeleteQueue("q"), MigrationAction.DeleteExchange("x"), MigrationAction.DeleteStream("s"), MigrationAction.DeletePolicy("p"),
        )
        for (action in kinds) {
            val check = FakeDeclareCheck().answer(action, BrokerFinding.Unreadable("the broker did not answer"))
            val result = steps(run(plan(action), context(level = FULL_CONTROL), check)).single()
            assertEquals(StepFinding.NotChecked("the broker did not answer"), result.finding, action.description)
            assertNotEquals(StepFinding.WillBeCreated, result.finding)
        }
    }

    @Test
    fun `a binding is read as itself and its answer is the same table`() {
        val p = plan(events, orders, bind)
        val check = FakeDeclareCheck().answerNamed("events", BrokerFinding.Same).answerNamed("orders", BrokerFinding.Same).answer(bind, BrokerFinding.Absent)
        assertEquals(listOf(StepFinding.AlreadyThere, StepFinding.AlreadyThere, StepFinding.WillBeCreated), findings(p, check))
    }

    @Test
    fun `a delete is present or gone, never created`() {
        val drop = MigrationAction.DeleteQueue("legacy-orders")
        val ctx = context(level = FULL_CONTROL)
        assertEquals(listOf(StepFinding.WillBeDeleted), findings(plan(drop), FakeDeclareCheck().answer(drop, BrokerFinding.Same), ctx))
        assertEquals(listOf(StepFinding.AlreadyGone), findings(plan(drop), FakeDeclareCheck().answer(drop, BrokerFinding.Absent), ctx))
    }

    @Test
    fun `a different result carries a note that applying would stop there and nothing would be overwritten`() {
        val result = steps(run(plan(orders), check = FakeDeclareCheck().answer(orders, BrokerFinding.Different))).single()
        val notes = result.notes.joinToString(" ").lowercase()
        assertTrue("stop" in notes && "overwritten" in notes, "notes: ${result.notes}")
    }

    @Test
    fun `a result that is not different carries no such note`() {
        for (finding in listOf(BrokerFinding.Absent, BrokerFinding.Same)) {
            assertEquals(emptyList(), steps(run(plan(orders), check = FakeDeclareCheck().answer(orders, finding))).single().notes)
        }
    }

    // ---- what the check may read ----

    @Test
    fun `the check reads the plan's own actions, probes of the ends of a binding, and builds no delete and no write of any kind`() {
        val p = plan(bind)
        val check = FakeDeclareCheck().answerNamed("events", BrokerFinding.Same).answerNamed("orders", BrokerFinding.Same).answer(bind, BrokerFinding.Absent)
        run(p, check = check)
        for (call in check.calls) {
            assertTrue(
                call == bind || call is MigrationAction.DeclareExchange || call is MigrationAction.DeclareQueue,
                "the check built a call that is neither the step nor a probe of an end: ${call.description}",
            )
            assertFalse(call.isDestructive, "the check asked about a delete it was not given: ${call.description}")
        }
    }

    @Test
    fun `a probe of an end carries the plan's virtual host and the end's kind`() {
        val toExchange = MigrationAction.DeclareBinding("events", "audit", "k", destinationType = BindingDestinationType.EXCHANGE)
        val check = FakeDeclareCheck().answerNamed("events", BrokerFinding.Same).answerNamed("audit", BrokerFinding.Same).answer(toExchange, BrokerFinding.Absent)
        run(plan(toExchange), check = check)
        val probes = check.calls.filter { it != toExchange }
        assertTrue(probes.isNotEmpty(), "an end that is on no earlier step must be probed")
        assertTrue(probes.all { it is MigrationAction.DeclareExchange && it.vhost == "/" }, "probes: ${probes.map { it.description }}")
    }

    // ---- blocked: the dry run's reason, and the step is still compared ----

    @Test
    fun `a step blocked by Read-only is still compared`() {
        val check = FakeDeclareCheck().answer(orders, BrokerFinding.Same).answerNamed("invoices", BrokerFinding.Absent)
        val result = steps(run(plan(orders, MigrationAction.DeclareQueue("invoices")), context(level = READ_ONLY), check))
        assertEquals(listOf(StepFinding.AlreadyThere, StepFinding.WillBeCreated), result.map { it.finding })
        assertEquals(List(2) { "Topology control level is Read-only" }, result.map { it.blocked })
    }

    @Test
    fun `without configure permission every step is blocked and still compared`() {
        val check = FakeDeclareCheck().answerNamed("orders", BrokerFinding.Absent)
        val step = steps(run(plan(orders), context(configure = false), check)).single()
        assertEquals(StepFinding.WillBeCreated, step.finding)
        assertEquals("User has no configure permission on this virtual host", step.blocked)
    }

    @Test
    fun `a delete without Full control is blocked and still says will be deleted`() {
        val drop = MigrationAction.DeleteQueue("legacy-orders")
        val step = steps(run(plan(drop), context(level = DECLARE_AND_EDIT), FakeDeclareCheck().answer(drop, BrokerFinding.Same))).single()
        assertEquals(StepFinding.WillBeDeleted, step.finding)
        assertEquals("Destructive actions require Full control level", step.blocked)
    }

    @Test
    fun `a step that nothing blocks has no blocked reason`() {
        assertNull(steps(run(plan(orders), check = FakeDeclareCheck().answer(orders, BrokerFinding.Absent))).single().blocked)
    }

    @Test
    fun `blocked is exactly the reason of the extracted dry-run rules, for every combination, in both orders of precedence`() {
        val drop = MigrationAction.DeleteQueue("old")
        val away = MigrationAction.DeclareQueue("billing", vhost = "orders")
        val actions = listOf(orders, drop, away)
        for (configure in listOf(true, false)) for (level in TopologyControlLevel.entries) {
            val expected = MigrationDryRunRules.evaluate(actions, configure, level, "/", emptySet()).actions.map { it.reason }
            val check = FakeDeclareCheck().answerNamed("orders", BrokerFinding.Absent).answerNamed("old", BrokerFinding.Absent)
            val actual = steps(run(TopologyPlan.of("", actions, "/"), context(level = level, configure = configure), check)).map { it.blocked }
            assertEquals(expected, actual, "configure=$configure level=$level")
        }
    }

    @Test
    fun `no configure permission beats Read-only, the topology-only order of today's dry run`() {
        val step = steps(run(plan(orders), context(level = READ_ONLY, configure = false), FakeDeclareCheck().answerNamed("orders", BrokerFinding.Absent))).single()
        assertEquals("User has no configure permission on this virtual host", step.blocked)
    }

    // ---- another virtual host: blocked and not read ----

    @Test
    fun `a step for another virtual host is blocked and nothing is read for it`() {
        val away = MigrationAction.DeclareQueue("invoices", vhost = "orders")
        val check = FakeDeclareCheck()
        val step = steps(run(TopologyPlan.of("", listOf(away), "orders"), context(vhost = "/"), check)).single()
        assertEquals("The action belongs to virtual host orders; this connection is for /", step.blocked)
        assertTrue(step.finding is StepFinding.NotChecked, "finding: ${step.finding}")
        assertTrue(check.calls.isEmpty(), "a request was made for another virtual host: ${check.calls}")
    }

    @Test
    fun `another virtual host is not read even when the user has no configure permission, and a binding there probes nothing`() {
        val away = MigrationAction.DeclareBinding("events", "orders", "k", vhost = "orders")
        val check = FakeDeclareCheck()
        val result = steps(run(TopologyPlan.of("", listOf(away), "orders"), context(configure = false), check)).single()
        assertEquals("User has no configure permission on this virtual host", result.blocked)
        assertTrue(check.calls.isEmpty(), "${check.calls}")
    }

    @Test
    fun `the steps of the right virtual host are read while the one of another is not`() {
        val away = MigrationAction.DeclareQueue("billing", vhost = "orders")
        val check = FakeDeclareCheck().answerNamed("orders", BrokerFinding.Same)
        val result = steps(run(TopologyPlan.of("", listOf(orders, away), "/"), check = check))
        assertEquals(StepFinding.AlreadyThere, result[0].finding)
        assertTrue(result[1].finding is StepFinding.NotChecked)
        assertEquals(listOf<MigrationAction>(orders), check.calls)
    }

    // ---- bindings: both ends must exist on the broker or earlier in the plan ----

    @Test
    fun `a binding to what the plan declares earlier is fine and probes nothing it has to`() {
        val check = FakeDeclareCheck().answerNamed("events", BrokerFinding.Absent).answerNamed("orders", BrokerFinding.Absent).answer(bind, BrokerFinding.Absent)
        assertEquals(List(3) { StepFinding.WillBeCreated }, findings(plan(events, orders, bind), check))
    }

    @Test
    fun `a stream declared earlier is a destination of a binding`() {
        val stream = MigrationAction.DeclareStream("orders")
        val check = FakeDeclareCheck().answerNamed("orders", BrokerFinding.Absent).answerNamed("events", BrokerFinding.Same).answer(bind, BrokerFinding.Absent)
        assertEquals(listOf(StepFinding.WillBeCreated, StepFinding.WillBeCreated), findings(plan(stream, bind), check))
    }

    @Test
    fun `an exchange that is on no step and not on the broker is missing, with the fixed sentence`() {
        val b = MigrationAction.DeclareBinding("evnts", "invoices", "invoice.created")
        val check = FakeDeclareCheck().answerNamed("evnts", BrokerFinding.Absent).answerNamed("invoices", BrokerFinding.Same).answer(b, BrokerFinding.Absent)
        assertEquals(
            listOf(StepFinding.Missing("The exchange evnts is not on the broker and is not declared earlier in this plan.")),
            findings(plan(b), check),
        )
    }

    @Test
    fun `a destination that is on no step and not on the broker is missing, named as a queue, or as an exchange`() {
        val toQueue = MigrationAction.DeclareBinding("events", "ghost", "k")
        val toExchange = MigrationAction.DeclareBinding("events", "phantom", "k", destinationType = BindingDestinationType.EXCHANGE)
        val check = FakeDeclareCheck().answerNamed("events", BrokerFinding.Same).answerNamed("ghost", BrokerFinding.Absent).answerNamed("phantom", BrokerFinding.Absent)
            .answer(toQueue, BrokerFinding.Absent).answer(toExchange, BrokerFinding.Absent)
        assertEquals(
            listOf(
                StepFinding.Missing("The queue ghost is not on the broker and is not declared earlier in this plan."),
                StepFinding.Missing("The exchange phantom is not on the broker and is not declared earlier in this plan."),
            ),
            findings(plan(toQueue, toExchange), check),
        )
    }

    @Test
    fun `something declared LATER in the plan does not count`() {
        val check = FakeDeclareCheck().answerNamed("events", BrokerFinding.Absent).answerNamed("orders", BrokerFinding.Absent).answer(bind, BrokerFinding.Absent)
        val result = findings(plan(bind, events, orders), check)
        assertEquals(StepFinding.Missing("The exchange events is not on the broker and is not declared earlier in this plan."), result[0])
        assertEquals(listOf(StepFinding.WillBeCreated, StepFinding.WillBeCreated), result.drop(1))
    }

    @Test
    fun `an end that is on the broker is present whether the probe says same or different`() {
        for (answer in listOf(BrokerFinding.Same, BrokerFinding.Different)) {
            val check = FakeDeclareCheck().answerNamed("events", answer).answerNamed("orders", answer).answer(bind, BrokerFinding.Absent)
            assertEquals(listOf(StepFinding.WillBeCreated), findings(plan(bind), check), "probe answers $answer")
        }
    }

    @Test
    fun `an end that cannot be read makes the binding could not be checked, never will be created and never missing`() {
        val check = FakeDeclareCheck().answerNamed("events", BrokerFinding.Unreadable("the broker did not answer")).answerNamed("orders", BrokerFinding.Same)
            .answer(bind, BrokerFinding.Absent)
        assertEquals(listOf(StepFinding.NotChecked("the broker did not answer")), findings(plan(bind), check))
        val other = FakeDeclareCheck().answerNamed("events", BrokerFinding.Same).answerNamed("orders", BrokerFinding.Unreadable("the user is not allowed to do this"))
            .answer(bind, BrokerFinding.Absent)
        assertEquals(listOf(StepFinding.NotChecked("the user is not allowed to do this")), findings(plan(bind), other))
    }

    @Test
    fun `a name that may not be echoed is not put in the sentence`() {
        val odd = "bad name\n<b>x</b>"
        val b = MigrationAction.DeclareBinding(odd, "orders", "k")
        val check = FakeDeclareCheck().answerNamed(odd, BrokerFinding.Absent).answerNamed("orders", BrokerFinding.Same).answer(b, BrokerFinding.Absent)
        val missing = assertIs<StepFinding.Missing>(findings(plan(b), check).single())
        assertFalse(odd in missing.what || "<b>" in missing.what || "\n" in missing.what, missing.what)
        assertTrue(missing.what.startsWith("The exchange "), missing.what)
    }

    @Test
    fun `a missing end of a binding in another virtual host is not probed, the step is only blocked`() {
        val away = MigrationAction.DeclareBinding("events", "orders", "k", vhost = "orders")
        val check = FakeDeclareCheck()
        run(TopologyPlan.of("", listOf(away), "orders"), context(vhost = "/"), check)
        assertTrue(check.calls.isEmpty())
    }

    // ---- progress, cancellation, failure of the port ----

    @Test
    fun `onStep is told each position before the step is read, one based`() {
        val seen = mutableListOf<String>()
        val check = FakeDeclareCheck().answerWith("orders") { seen += "read orders"; BrokerFinding.Same }.answerWith("events") { seen += "read events"; BrokerFinding.Same }
        run(plan(events, orders), check = check, onStep = { n, total -> seen += "step $n of $total" })
        assertEquals(listOf("step 1 of 2", "read events", "step 2 of 2", "read orders"), seen)
    }

    @Test
    fun `a cancelled check stops at once and reads nothing more, and gives no result`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val check = FakeDeclareCheck().answerNamed("orders", BrokerFinding.Same).answerWith("events") { gate.await(); BrokerFinding.Same }
        val third = MigrationAction.DeclareQueue("third")
        val job = async(start = CoroutineStart.UNDISPATCHED) { PlanCheck.run(plan(orders, events, third), context(), check) { _, _ -> } }
        yield()
        assertEquals(listOf("Declare quorum queue orders", "Declare direct exchange events"), check.calls.map { it.description })
        job.cancel()
        assertFailsWith<kotlinx.coroutines.CancellationException> { job.await() }
        assertEquals(2, check.calls.size, "a cancelled check kept reading: ${check.calls.map { it.description }}")
        gate.complete(Unit)
    }

    @Test
    fun `a port that throws is not turned into a finding, the failure is not swallowed into will be created`() {
        val check = FakeDeclareCheck().answerWith("orders") { error("boom") }
        assertFailsWith<IllegalStateException> { run(plan(orders), check = check) }
    }
}
