package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.DeclareCheck
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-06 step 2 (red until the production code exists): the check in [PlannerViewModel], over the scripted [FakeDeclareCheck]. No broker, no
 * sleep: a read that must be "late" or "at step 4" is held by a gate ([FakeDeclareCheck.holdOn]), and the clock is a number the test sets.
 *
 * The shape these tests fix (the architect confirms it in the step 2 report):
 *  - `PlannerViewModel(..., reader, declareCheck: DeclareCheck? = null, clock: RefreshClock)`, `fun check()` (asynchronous like every event);
 *  - `PlannerState.check: CheckState` = `None | Running(step, total) | Done(results: List<StepCheck>, connectionName: String, atMillis: Long, stale: Boolean)`;
 *  - `PlannerState.checkEnabled`, `checkDisabledReason`, `checkLabel`, and `PlannerState.stepCheck(stepId): StepCheck?` (null: no result for that step,
 *    also for a step added after the check), `PlannerState.connectionName`;
 *  - the result of a step belongs to its STEP ID: a move keeps it with the step; a step added later has none.
 */
class PlannerViewModelCheckTest {
    private val rigs = mutableListOf<PlannerRig>()

    @AfterEach
    fun close() = rigs.forEach { it.close() }

    private val threeX = "This broker runs RabbitMQ 3.x: it is a migration source only"
    private val T0 = 1_700_000_000_000L

    private fun rig(
        check: DeclareCheck? = FakeDeclareCheck(),
        context: PlannerContext = PlannerContext(),
        prepare: PlannerRig.() -> Unit = {},
    ) = PlannerRig(context = context, declareCheck = check, nowMillis = T0).also { rigs += it; it.prepare() }

    private fun nameOf(action: MigrationAction): String? = when (action) {
        is MigrationAction.DeclareQueue -> action.name
        is MigrationAction.DeclareExchange -> action.name
        else -> null
    }

    /** Scripts every step of the plan of [r] as [finding]: a queue or exchange by name, a binding by its exact action. */
    private fun FakeDeclareCheck.scriptAll(r: PlannerRig, finding: BrokerFinding = BrokerFinding.Absent): FakeDeclareCheck = apply {
        r.state.plan.steps.forEach { step ->
            val name = nameOf(step.action)
            if (name != null) answerNamed(name, finding) else answer(step.action, finding)
        }
    }

    private fun PlannerRig.done(): CheckState.Done = assertIs<CheckState.Done>(state.check, "the check is ${state.check}")

    private fun threeSteps(check: FakeDeclareCheck): PlannerRig = rig(check) { threeSteps() }

    // ---- start, progress, result ----

    @Test
    fun `before any check the state says none`() {
        assertEquals(CheckState.None, rig().state.check)
    }

    @Test
    fun `a check ends Done with one result per step, the connection and the time of the clock`() {
        val port = FakeDeclareCheck()
        val r = threeSteps(port)
        port.answerNamed("orders", BrokerFinding.Absent).answerNamed("events", BrokerFinding.Same).answer(r.state.plan.steps[2].action, BrokerFinding.Absent)
        r.vm.check()
        val done = r.done()
        assertEquals(r.ids(), done.results.map { it.stepId }, "one result per step, in plan order, keyed by the step id")
        assertEquals(
            listOf(StepFinding.WillBeCreated, StepFinding.AlreadyThere, StepFinding.WillBeCreated),
            done.results.map { it.finding },
        )
        assertEquals("uat-eu", done.connectionName)
        assertEquals(T0, done.atMillis, "the time comes from the injected clock, never from the system clock")
        assertFalse(done.stale)
        assertEquals(3, port.calls.size, "one read per step here (the binding's ends are declared earlier in the plan)")
    }

    @Test
    fun `while the check is at a step the state says which step of how many`() {
        val port = FakeDeclareCheck()
        val r = threeSteps(port)
        port.scriptAll(r)
        val gate = port.holdOn("events")
        r.vm.check()
        assertEquals(CheckState.Running(step = 2, total = 3), r.state.check, "steps 1 of 3 is read, step 2 waits for the broker")
        gate.release()
        assertIs<CheckState.Done>(r.state.check)
    }

    @Test
    fun `progress counts steps, never requests, even when a binding step needs three reads`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("audit"); binding("events", "orders", "k") }
        val bind = r.state.plan.steps[1].action
        port.answerNamed("audit", BrokerFinding.Absent)
        port.answerNamed("events", BrokerFinding.Same)
        port.answer(bind, BrokerFinding.Absent)
        val gate = port.holdOn("orders", BrokerFinding.Same)
        r.vm.check()
        assertEquals(CheckState.Running(step = 2, total = 2), r.state.check, "the second probe of the binding is a request, not a step")
        assertTrue(port.calls.size >= 3, "the read of the queue, then the binding's first end, then the second end: ${port.calls.map { it.description }}")
        gate.release()
        assertEquals(2, r.done().results.size)
    }

    @Test
    fun `a check that found something does not publish a half result while it runs`() {
        val port = FakeDeclareCheck()
        val r = threeSteps(port)
        port.scriptAll(r)
        port.holdOn("events")
        r.vm.check()
        assertFalse(r.state.check is CheckState.Done, "no result until every step was read")
    }

    // ---- the result of a step is the step's, by id ----

    @Test
    fun `a result stays with its step when the step moves`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a"); queue("b") }
        port.answerNamed("a", BrokerFinding.Absent).answerNamed("b", BrokerFinding.Same)
        r.vm.check()
        val (a, b) = r.ids()
        r.vm.moveStepDown(a)
        assertEquals(listOf(b, a), r.ids())
        assertEquals(StepFinding.WillBeCreated, r.state.stepCheck(a)?.finding, "step a asked for a queue the broker lacks")
        assertEquals(StepFinding.AlreadyThere, r.state.stepCheck(b)?.finding)
    }

    @Test
    fun `a step added after the check has no result and the others keep theirs`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a"); queue("b") }
        port.scriptAll(r)
        r.vm.check()
        val before = r.ids()
        r.queue("invoices")
        val added = (r.ids() - before.toSet()).single()
        assertNull(r.state.stepCheck(added))
        before.forEach { assertNotNull(r.state.stepCheck(it), "step $it lost its result") }
        assertEquals(before, r.done().results.map { it.stepId }, "the check did not run again by itself")
    }

    @Test
    fun `removing a step keeps the results of the others`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a"); queue("b"); queue("c") }
        port.answerNamed("a", BrokerFinding.Absent).answerNamed("b", BrokerFinding.Same).answerNamed("c", BrokerFinding.Different)
        r.vm.check()
        val (a, b, c) = r.ids()
        r.vm.removeStep(b)
        assertEquals(StepFinding.WillBeCreated, r.state.stepCheck(a)?.finding)
        assertEquals(StepFinding.Different, r.state.stepCheck(c)?.finding)
    }

    @Test
    fun `a step added after the last step was removed does not inherit the removed step's result`() {
        // TopologyPlan.append gives max(id) + 1, so removing the LAST step frees its id for the next step.
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a"); queue("b") }
        port.answerNamed("a", BrokerFinding.Absent).answerNamed("b", BrokerFinding.Same)
        r.vm.check()
        val (_, b) = r.ids()
        r.vm.removeStep(b)
        r.queue("c")
        val reused = r.ids().last()
        assertEquals(b, reused, "this test needs the freed id to be given again; the plan changed its id rule")
        assertNull(r.state.stepCheck(reused), "the new step shows the result of the step that was removed")
    }

    // ---- stale ----

    private fun staleAfter(change: PlannerRig.() -> Unit, steps: PlannerRig.() -> Unit = { queue("a"); queue("b") }): Pair<CheckState.Done, CheckState.Done> {
        val port = FakeDeclareCheck()
        val r = rig(port, prepare = steps)
        port.scriptAll(r)
        r.vm.check()
        val fresh = r.done()
        assertFalse(fresh.stale, "a fresh result is not stale")
        r.change()
        return fresh to r.done()
    }

    private fun assertStaleAndKept(label: String, pair: Pair<CheckState.Done, CheckState.Done>) {
        val (fresh, now) = pair
        assertTrue(now.stale, "$label: the result must be marked stale")
        assertEquals(fresh.results, now.results, "$label: a stale result is kept, not dropped")
        assertEquals(fresh.connectionName, now.connectionName)
        assertEquals(fresh.atMillis, now.atMillis)
    }

    @Test
    fun `adding a step marks the result stale and keeps it`() = assertStaleAndKept("add", staleAfter({ queue("c") }))

    @Test
    fun `editing a step marks the result stale and keeps it`() = assertStaleAndKept("edit", staleAfter({
        val id = ids().first()
        vm.editStep(id); vm.updateForm(QueueForm(name = "a2")); vm.next(); vm.addToPlan()
        assertEquals("a2", (state.plan.steps.first().action as MigrationAction.DeclareQueue).name, "the edit did not happen")
    }))

    @Test
    fun `removing a step marks the result stale and keeps the results of the other steps`() {
        // Whether the removed step's own entry is kept is the view model's choice (it must not be given to a new step, see below): only the others are pinned.
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a"); queue("b") }
        port.scriptAll(r)
        r.vm.check()
        val fresh = r.done()
        val (a, b) = r.ids()
        r.vm.removeStep(a)
        val now = r.done()
        assertTrue(now.stale)
        assertEquals(fresh.results.single { it.stepId == b }, now.results.single { it.stepId == b }, "the result of the step that stayed")
        assertEquals(fresh.connectionName, now.connectionName)
        assertEquals(fresh.atMillis, now.atMillis)
    }

    @Test
    fun `moving a step down marks the result stale and keeps it`() = assertStaleAndKept("move down", staleAfter({ vm.moveStepDown(ids().first()) }))

    @Test
    fun `moving a step up marks the result stale and keeps it`() = assertStaleAndKept("move up", staleAfter({ vm.moveStepUp(ids().last()) }))

    @Test
    fun `changing the virtual host marks the result stale and keeps it`() = assertStaleAndKept("vhost", staleAfter({ vm.setVirtualHost("billing") }))

    @Test
    fun `a change that changes nothing does not mark the result stale`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a"); queue("b") }
        port.scriptAll(r)
        r.vm.check()
        r.vm.moveStepUp(r.ids().first()) // the first step cannot move up: the plan is the same
        r.vm.setVirtualHost(r.state.plan.vhost)
        assertFalse(r.done().stale)
    }

    @Test
    fun `a plan that changes while the check runs is not dropped - the result arrives, stale, without the new step`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a"); queue("b") }
        port.scriptAll(r)
        val gate = port.holdOn("b")
        r.vm.check()
        assertEquals(CheckState.Running(2, 2), r.state.check)
        val before = r.ids()
        r.queue("invoices") // the plan stays editable while the check waits for the broker
        assertEquals(3, r.state.plan.steps.size, "the check blocked the plan")
        assertEquals(CheckState.Running(2, 2), r.state.check)
        gate.release()
        val done = r.done()
        assertTrue(done.stale, "the plan changed after the check began")
        assertEquals(before, done.results.map { it.stepId }, "the check covered the plan it started with")
        assertNull(r.state.stepCheck((r.ids() - before.toSet()).single()))
    }

    @Test
    fun `a check again after a change gives a fresh result for the new plan`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a"); queue("b") }
        port.scriptAll(r)
        r.vm.check()
        r.queue("c")
        port.scriptAll(r)
        assertTrue(r.state.checkEnabled, "after a result the button can be used again")
        r.vm.check()
        val done = r.done()
        assertFalse(done.stale)
        assertEquals(r.ids(), done.results.map { it.stepId }, "the new check covers the new step")
    }

    // ---- no second run, cancel, late result ----

    @Test
    fun `no second check while one runs`() {
        val port = FakeDeclareCheck()
        val r = threeSteps(port)
        port.scriptAll(r)
        val gate = port.holdOn("events")
        r.vm.check()
        val callsWhileRunning = port.calls.size
        assertFalse(r.state.checkEnabled, "the button is disabled while a check runs")
        r.vm.check()
        r.vm.check()
        assertEquals(callsWhileRunning, port.calls.size, "a second check started: ${port.calls.map { it.description }}")
        assertEquals(CheckState.Running(2, 3), r.state.check)
        gate.release()
        assertEquals(3, r.done().results.size)
        assertEquals(3, port.calls.size, "the one check read each of the three steps once")
    }

    @Test
    fun `a check that is dismissed is cancelled and a late answer is dropped`() {
        val port = FakeDeclareCheck()
        val r = threeSteps(port)
        port.scriptAll(r)
        val gate = port.holdOn("events", ignoreCancellation = true)
        r.vm.check()
        r.vm.dismiss()
        r.vm.confirm()
        assertTrue(r.state.dismissed)
        gate.release()
        assertFalse(r.state.check is CheckState.Done, "the answer of a check that was dismissed must not be shown: ${r.state.check}")
    }

    @Test
    fun `dismissing cancels the running read`() {
        val port = FakeDeclareCheck()
        val r = threeSteps(port)
        port.scriptAll(r)
        port.holdOn("events", ignoreCancellation = false)
        r.vm.check()
        assertTrue(port.cancelled.isEmpty())
        r.vm.dismiss()
        r.vm.confirm()
        assertEquals(listOf("events"), port.cancelled.mapNotNull { nameOf(it) }, "the read was not cancelled")
    }

    @Test
    fun `when the scope ends the running read is cancelled`() {
        val port = FakeDeclareCheck()
        val r = threeSteps(port)
        port.scriptAll(r)
        port.holdOn("events", ignoreCancellation = false)
        r.vm.check()
        r.close()
        assertEquals(listOf("events"), port.cancelled.mapNotNull { nameOf(it) }, "a check outlived its view model")
    }

    @Test
    fun `a read that never ends is cancelled with the scope`() {
        val started = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val port = FakeDeclareCheck().answerWith("a") {
            started.set(true)
            try { awaitCancellation() } finally { cancelled.set(true) }
        }
        val r = rig(port) { queue("a") }
        r.vm.check()
        assertTrue(started.get())
        r.close()
        assertTrue(cancelled.get())
    }

    @Test
    fun `a connection switch is a new view model and starts with no result, the old port is never asked again`() {
        val old = FakeDeclareCheck()
        val r = rig(old) { queue("a") }
        old.scriptAll(r)
        r.vm.check()
        assertIs<CheckState.Done>(r.state.check)
        val callsOfOld = old.calls.size
        val next = FakeDeclareCheck()
        r.restart(PlannerContext(connectionName = "prod-eu"), next)
        assertEquals(CheckState.None, r.state.check, "the result of uat-eu must not show on prod-eu")
        assertEquals("Check against prod-eu", r.state.checkLabel)
        assertEquals("prod-eu", r.state.connectionName)
        next.scriptAll(r)
        r.vm.check()
        assertEquals("prod-eu", r.done().connectionName)
        assertEquals(callsOfOld, old.calls.size, "the check of prod-eu read through the port of uat-eu")
        assertTrue(next.calls.isNotEmpty())
    }

    @Test
    fun `a reconnect to the same profile with a new port reads through the new port only`() {
        val first = FakeDeclareCheck()
        val r = rig(first) { queue("a") }
        val second = FakeDeclareCheck()
        r.restart(r.context, second) // the window rebuilds the view model: same profile, the new connection's port
        second.scriptAll(r)
        r.vm.check()
        assertIs<CheckState.Done>(r.state.check)
        assertTrue(second.calls.isNotEmpty())
        assertTrue(first.calls.isEmpty(), "the closed connection's port was asked: ${first.calls.map { it.description }}")
    }

    @Test
    fun `a late answer for the old view model does not reach the new one`() {
        val old = FakeDeclareCheck()
        val r = rig(old) { queue("a") }
        old.scriptAll(r)
        val gate = old.holdOn("a")
        r.vm.check()
        r.restart(PlannerContext(connectionName = "prod-eu"), FakeDeclareCheck())
        gate.release()
        assertEquals(CheckState.None, r.vm.state.value.check, "the answer for uat-eu is shown on prod-eu")
    }

    // ---- a port that fails ----

    @Test
    fun `a port that throws leaves the planner usable and the check not running`() {
        val port = FakeDeclareCheck().answerWith("a") { throw IllegalStateException("boom") }
        val r = rig(port) { queue("a") }
        r.vm.check()
        assertFalse(r.state.check is CheckState.Running, "the check is stuck on Running after a failure: ${r.state.check}")
        r.queue("b")
        assertEquals(2, r.state.plan.steps.size, "the planner stopped answering")
        assertTrue(r.state.checkEnabled, "the button stays disabled for ever after a failed check")
    }

    @Test
    fun `an unreadable resource is a result of that step, never will be created`() {
        val port = FakeDeclareCheck().answerNamed("a", BrokerFinding.Unreadable("the broker did not answer"))
        val r = rig(port) { queue("a") }
        r.vm.check()
        assertEquals(StepFinding.NotChecked("the broker did not answer"), r.done().results.single().finding)
    }

    // ---- both pills: a blocked step carries its result AND its reason ----

    @Test
    fun `a step the dry run blocks still has its result, so the screen can draw both pills`() {
        val port = FakeDeclareCheck()
        val r = rig(port, PlannerContext(controlLevel = TopologyControlLevel.READ_ONLY)) { queue("a") }
        port.scriptAll(r, BrokerFinding.Same)
        r.vm.check()
        val step = r.done().results.single()
        assertEquals(StepFinding.AlreadyThere, step.finding)
        assertNotNull(step.blocked, "the dry run's reason is missing")
        assertTrue(step.blocked!!.isNotBlank())
    }

    @Test
    fun `a check works at the Read-only level and without configure permission, and Save is not touched`() {
        listOf(
            PlannerContext(controlLevel = TopologyControlLevel.READ_ONLY),
            PlannerContext(hasConfigurePermission = false),
        ).forEach { context ->
            val port = FakeDeclareCheck()
            val r = rig(port, context) { queue("a") }
            assertTrue(r.state.checkEnabled, "$context: the check is allowed, the steps are only shown as blocked")
            port.scriptAll(r)
            r.vm.check()
            assertIs<CheckState.Done>(r.state.check)
        }
    }

    // ---- the gate of the button ----

    @Test
    fun `the check is enabled when connected, with a port and at least one step`() {
        val r = rig { queue("a") }
        assertTrue(r.state.checkEnabled)
        assertNull(r.state.checkDisabledReason)
    }

    @Test
    fun `no step - disabled, and the reason is the empty-plan reason`() {
        val r = rig()
        assertFalse(r.state.checkEnabled)
        assertEquals(PlannerTexts.ADD_A_STEP_FIRST, r.state.checkDisabledReason)
    }

    @Test
    fun `a connection that cannot run migrations - the reason of the connection comes first, also before the other reasons`() {
        val r = rig(null, PlannerContext(unavailableReason = threeX))
        assertFalse(r.state.checkEnabled)
        assertEquals(threeX, r.state.checkDisabledReason, "no port and no step too: the connection's own reason still wins")
        val withPort = rig(FakeDeclareCheck(), PlannerContext(unavailableReason = threeX))
        assertEquals(threeX, withPort.state.checkDisabledReason)
    }

    @Test
    fun `connected but no port - the fixed no-check text`() {
        val r = rig(null) { queue("a") }
        assertFalse(r.state.checkEnabled)
        assertEquals(PlanCheck.NO_CHECK, r.state.checkDisabledReason)
    }

    @Test
    fun `not connected, with a port handed in - the not-connected text`() {
        val r = rig(FakeDeclareCheck(), PlannerContext(connectionName = null)) { queue("a") }
        assertFalse(r.state.checkEnabled)
        assertEquals(PlannerTexts.NOT_CONNECTED, r.state.checkDisabledReason)
    }

    @Test
    fun `an unreadable draft locks the planner and the check`() {
        val r = rig { store.putUnreadable() }
        r.restart()
        assertFalse(r.state.checkEnabled)
    }

    @Test
    fun `a check that is asked for while it is disabled does nothing at all`() {
        listOf(
            "3.x" to rig(FakeDeclareCheck(), PlannerContext(unavailableReason = threeX)) { },
            "not connected" to rig(FakeDeclareCheck(), PlannerContext(connectionName = null)) { queue("a") },
            "no port" to rig(null) { queue("a") },
            "empty plan" to rig(FakeDeclareCheck()),
        ).forEach { (why, r) ->
            val port = r.declareCheck as? FakeDeclareCheck
            r.vm.check()
            assertEquals(CheckState.None, r.state.check, "$why: a refused check must leave no state")
            assertTrue(port?.calls.orEmpty().isEmpty(), "$why: a refused check read the broker: ${port?.calls}")
        }
    }

    @Test
    fun `the label names the connection, or just says Check while there is none`() {
        assertEquals("Check against uat-eu", rig().state.checkLabel)
        assertEquals("Check", rig(null, PlannerContext(connectionName = null)).state.checkLabel)
    }

    // ---- the check never decides anything else ----

    @Test
    fun `a check never changes the save gate, the plan or the draft`() {
        val port = FakeDeclareCheck()
        val r = threeSteps(port)
        port.scriptAll(r)
        val gate = port.holdOn("events")
        val savedBefore = r.store.savedTexts.size
        val plan = r.state.plan
        val saveGate = r.state.saveGate
        assertEquals(SaveGate.Enabled, saveGate)
        r.vm.check()
        assertEquals(saveGate, r.state.saveGate, "Save changed while the check ran")
        gate.release()
        assertEquals(saveGate, r.state.saveGate, "Save changed after the check")
        assertEquals(plan, r.state.plan)
        assertEquals(savedBefore, r.store.savedTexts.size, "a check wrote the draft")
        assertEquals(emptyList(), r.folder.written, "a check wrote a file")
        assertEquals(null, r.state.errorMessage)
    }

    @Test
    fun `a check that found different or missing things does not block Save either`() {
        val port = FakeDeclareCheck()
        val r = rig(port) { queue("a"); queue("b") }
        port.answerNamed("a", BrokerFinding.Different).answerNamed("b", BrokerFinding.Unreadable("x"))
        r.vm.check()
        assertEquals(SaveGate.Enabled, r.state.saveGate)
    }

    @Test
    fun `the check reads only what the port offers, one read per step of the plan`() {
        val port = FakeDeclareCheck()
        val r = threeSteps(port)
        port.scriptAll(r)
        r.vm.check()
        assertEquals(listOf("orders", "events"), port.calls.take(2).mapNotNull { nameOf(it) })
        assertTrue(port.calls.all { !it.isDestructive }, "a declare plan was read as a delete")
    }
}
