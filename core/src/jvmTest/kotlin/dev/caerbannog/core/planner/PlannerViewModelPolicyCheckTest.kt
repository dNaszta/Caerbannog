package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import dev.caerbannog.core.planner.fakes.FakeBrokerPolicyReader
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import dev.caerbannog.core.topology.PolicyRow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-08 slice 2, written first: the view model wires the policy notes. API fixed here: `PlannerViewModel(..., policies: BrokerPolicyReader? = null)` (the read port
 * for one policy; the window gives it with the check port and gives none to a 3.x connection). What the view model does for the check (docs/tasks/M9-08-policy-step.md,
 * "What the check reads"): when the plan holds a policy step it READS THE NAMES OF THE BROKER AGAIN when the check starts (the copy in the planner state is as old
 * as the screen), through the existing `KnownTopologyReader`, passes the fresh value to `PlanCheck.run`, and the graph takes the fresh value too; a plan without a
 * policy step makes exactly the requests it makes today (no extra read). A user-started read, not polling.
 */
class PlannerViewModelPolicyCheckTest {
    private val rigs = mutableListOf<PlannerRig>()

    @AfterEach
    fun close() = rigs.forEach { it.close() }

    private val limits = PolicyRow("limits", "/", "^orders", "all", 0, mapOf("max-length" to ArgumentValue.Whole(5)))
    private val takePlace = "The broker applies the policy limits to orders. Only one policy applies to a queue: this one would take its place."
    private val withPolicyOnOrders = KnownTopology(queues = setOf("orders"), queueDetails = mapOf("orders" to KnownQueue("quorum", "limits")))

    private class Parts(val reader: FakeKnownTopologyReader, val policies: FakeBrokerPolicyReader, val port: FakeDeclareCheck)

    private fun rig(initial: KnownTopology? = KnownTopology.None, prepare: PlannerRig.(Parts) -> Unit = {}): Pair<PlannerRig, Parts> {
        val parts = Parts(FakeKnownTopologyReader(initial), FakeBrokerPolicyReader().policy(limits), FakeDeclareCheck().answerNamed("orders-dlx", BrokerFinding.Absent).answerNamed("telemetry", BrokerFinding.Absent))
        val r = PlannerRig(declareCheck = parts.port, reader = parts.reader, policies = parts.policies).also { rigs += it }
        r.prepare(parts)
        return r to parts
    }

    private fun PlannerRig.addPolicy(priority: String = "10") = add(
        PolicyForm("orders-dlx", "^orders$", PolicyTarget.QUORUM_QUEUES, priority, listOf(PolicySetting("max-length", "10"))),
    )

    @Test
    fun `a plan with a policy step reads the names again when it is checked, and says what the new read shows`() {
        val (r, parts) = rig(initial = KnownTopology.None)
        r.addPolicy()
        assertEquals(1, parts.reader.calls.size, "the planner read the broker once when it started")
        // the broker applied "limits" to "orders" after the planner was opened
        parts.reader.answer = withPolicyOnOrders
        r.vm.check()
        assertEquals(2, parts.reader.calls.size, "one more read, started by the check: ${parts.reader.calls}")
        val done = assertIs<CheckState.Done>(r.state.check)
        assertEquals(listOf(takePlace), done.results.single().notes)
        assertEquals(withPolicyOnOrders, r.state.known, "the graph takes the fresh value too")
    }

    @Test
    fun `a plan without a policy step makes no extra read`() {
        val (r, parts) = rig()
        r.add(QueueForm("orders"))
        parts.port.answerNamed("orders", BrokerFinding.Absent)
        r.vm.check()
        assertIs<CheckState.Done>(r.state.check)
        assertEquals(1, parts.reader.calls.size, "only the read of the start")
        assertEquals(emptyList(), parts.policies.calls)
    }

    @Test
    fun `each check of a plan with a policy step reads again, and nothing polls`() {
        val (r, parts) = rig(initial = withPolicyOnOrders)
        r.addPolicy()
        r.vm.check()
        r.vm.check()
        assertEquals(3, parts.reader.calls.size, "start, check, check: ${parts.reader.calls}")
        assertEquals(listOf("/", "/", "/"), parts.reader.calls)
    }

    @Test
    fun `when the names cannot be read the check goes on without notes`() {
        val (r, parts) = rig(initial = withPolicyOnOrders)
        r.addPolicy()
        parts.reader.answerFor("/", null)
        r.vm.check()
        val done = assertIs<CheckState.Done>(r.state.check)
        assertEquals(emptyList(), done.results.single().notes)
        assertEquals(StepFinding.WillBeCreated, done.results.single().finding)
        assertEquals(emptyList(), parts.policies.calls)
    }

    @Test
    fun `without a policy port there is no note and the check still runs (a connection that gets none)`() {
        val parts = Parts(FakeKnownTopologyReader(withPolicyOnOrders), FakeBrokerPolicyReader().policy(limits), FakeDeclareCheck().answerNamed("orders-dlx", BrokerFinding.Absent))
        val r = PlannerRig(declareCheck = parts.port, reader = parts.reader, policies = null).also { rigs += it }
        r.addPolicy()
        r.vm.check()
        val done = assertIs<CheckState.Done>(r.state.check)
        assertEquals(emptyList(), done.results.single().notes)
        assertEquals(emptyList(), parts.policies.calls)
    }

    @Test
    fun `the plan's own notes show without any check, from the plan alone`() {
        val (r, _) = rig(initial = KnownTopology.None)
        r.add(QueueForm("orders"))
        r.add(PolicyForm("orders-dlx", "^orders$", PolicyTarget.QUORUM_QUEUES, "0", listOf(PolicySetting("max-length", "10"))))
        r.add(PolicyForm("orders-limits", "^orders", PolicyTarget.QUORUM_QUEUES, "0", listOf(PolicySetting("message-ttl", "5"))))
        val messages = r.state.problems.filter { !it.blocking }.map { it.message }
        assertEquals(
            listOf("The policies orders-dlx and orders-limits both match orders. RabbitMQ applies only one policy to a queue: put the settings into one policy."),
            messages.distinct(),
        )
        assertEquals(2, messages.size, "one on each policy step")
    }

    @Test
    fun `the wizard offers the Policy kind and opens its form`() {
        val (r, _) = rig()
        assertTrue("Policy" in PlannerTexts.BUILT_KINDS, "built kinds: ${PlannerTexts.BUILT_KINDS}")
        assertEquals(listOf("Queue", "Exchange", "Binding", "Stream", "Policy", "Dead-lettering for a queue", "MQTT route"), PlannerTexts.BUILT_KINDS)
        r.vm.openWizard()
        r.vm.chooseKind("Policy")
        val details = assertIs<WizardState.Details>(r.state.wizard)
        assertEquals(PolicyForm(name = "", pattern = ""), details.form)
        assertEquals(PolicyTarget.ALL, (details.form as PolicyForm).target)
    }

    @Test
    fun `a stream added with retention is two steps, the policy last, and Next is enabled`() {
        val (r, _) = rig()
        r.vm.openWizard()
        r.vm.chooseKind("Stream")
        r.vm.updateForm(StreamForm(name = "telemetry", keepFor = "7D"))
        assertTrue(assertIs<WizardState.Details>(r.state.wizard).nextEnabled)
        r.vm.next()
        assertEquals(listOf("Declare stream telemetry", "Declare policy telemetry-retention"), assertIs<WizardState.Summary>(r.state.wizard).actions.map { it.description })
        r.vm.addToPlan()
        assertEquals(listOf("Declare stream telemetry", "Declare policy telemetry-retention"), r.titles())
    }

    @Test
    fun `editing a policy step opens the policy form and saves the change in place`() {
        val (r, _) = rig()
        r.addPolicy(priority = "1")
        val id = r.state.plan.steps.single().id
        r.vm.editStep(id)
        val form = assertIs<PolicyForm>(assertIs<WizardState.Details>(r.state.wizard).form)
        r.vm.updateForm(form.copy(priority = "7"))
        r.vm.next()
        r.vm.addToPlan()
        assertEquals(7, (r.state.plan.steps.single().action as MigrationAction.DeclarePolicy).priority)
    }
}
