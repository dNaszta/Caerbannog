package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import dev.caerbannog.core.planner.fakes.FakeBrokerPolicyReader
import dev.caerbannog.core.topology.PolicyRow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-08 slice 2, written first: the check notes of a policy step (`PlanCheck`, notes under the step, never a blocker). API fixed here, beyond the brief
 * (the architect confirms it): `PlanCheck.run(plan, context, check, policies: BrokerPolicyReader? = null, known: KnownTopology = KnownTopology.None, onStep = ...)`
 * (the brief names `policies`; the check also needs the names of the broker with their `KnownQueue(type, policy)`, so `known` is the second additive parameter. Both go BEFORE `onStep`,
 * which stays the last parameter: five callers pass it as a trailing lambda, and one test (`PlanCheckTest`) passes it by name now. And
 * `PlanCheck.MAX_POLICY_READS = 20`; the view model passes both: see `PlannerViewModelPolicyCheckTest`).
 * A policy step that would bind to a queue on which the broker already applies ANOTHER policy gets one of three decided sentences: higher priority (`this one would
 * take its place`), lower (`this one would not apply`), equal (`Only one of the two will apply, and RabbitMQ does not say which`). An operator policy is not
 * warned about (S-F2b). What is read: the broker's `policy` NAME of the matching queue from `known`, then ONE item read per DISTINCT policy name, at most 20 per
 * check; a policy that cannot be read gives no note and changes no finding; beyond the cap the note is left out, never guessed. A plan without a policy step
 * makes no read. A stream already on the broker has a null policy (StreamSummary carries none): no note, a stated limitation.
 */
class PlanCheckPolicyNoteTest {
    private val context = PlannerContext("uat-eu", "/", dev.caerbannog.core.connections.TopologyControlLevel.DECLARE_AND_EDIT, true, null)

    private fun policyStep(name: String = "orders-dlx", pattern: String = "^orders$", priority: Int = 0, applyTo: String = "all", vararg keys: String) =
        MigrationAction.DeclarePolicy(name, pattern, (keys.ifEmpty { arrayOf("max-length") }).associateWith { ArgumentValue.Whole(10) }, "/", applyTo, priority)

    private fun brokerPolicy(name: String, priority: Int, applyTo: String = "all", pattern: String = "^orders") =
        PolicyRow(name, "/", pattern, applyTo, priority, mapOf("max-length" to ArgumentValue.Whole(5)))

    private fun known(vararg queues: Pair<String, KnownQueue>) = KnownTopology(queues = queues.map { it.first }.toSet(), queueDetails = queues.toMap())

    private val orders = "orders" to KnownQueue("quorum", "limits")

    private fun check(vararg actions: MigrationAction, known: KnownTopology, reader: BrokerPolicyReader?, finding: BrokerFinding = BrokerFinding.Absent): List<StepCheck> {
        val port = FakeDeclareCheck().also { f -> actions.forEach { a -> if (a is MigrationAction.DeclarePolicy) f.answerNamed(a.name, finding) } }
        val result = runBlocking { PlanCheck.run(TopologyPlan.of("", actions.toList(), "/"), context, port, policies = reader, known = known) }
        return assertIs<PlanCheckResult.Checked>(result).steps
    }

    private val takePlace = "The broker applies the policy limits to orders. Only one policy applies to a queue: this one would take its place."
    private val notApply = "The broker applies the policy limits to orders, which has a higher priority. Only one policy applies to a queue: this one would not apply."
    private val samePriority = "The broker applies the policy limits to orders at the same priority. Only one of the two will apply, and RabbitMQ does not say which."

    @Test
    fun `a higher priority would take the place of the broker's policy`() {
        val reader = FakeBrokerPolicyReader().policy(brokerPolicy("limits", 0))
        val steps = check(policyStep(priority = 10), known = known(orders), reader = reader)
        assertEquals(StepFinding.WillBeCreated, steps.single().finding)
        assertEquals(listOf(takePlace), steps.single().notes)
        assertEquals(listOf("/" to "limits"), reader.calls)
    }

    @Test
    fun `a lower priority would never apply`() {
        val steps = check(policyStep(priority = 0), known = known(orders), reader = FakeBrokerPolicyReader().policy(brokerPolicy("limits", 10)))
        assertEquals(listOf(notApply), steps.single().notes)
    }

    @Test
    fun `the same priority says one of the two applies and the broker does not say which`() {
        val steps = check(policyStep(priority = 3), known = known(orders), reader = FakeBrokerPolicyReader().policy(brokerPolicy("limits", 3)))
        assertEquals(listOf(samePriority), steps.single().notes)
    }

    @Test
    fun `negative priorities compare as numbers`() {
        assertEquals(listOf(takePlace), check(policyStep(priority = -1), known = known(orders), reader = FakeBrokerPolicyReader().policy(brokerPolicy("limits", -5))).single().notes)
        assertEquals(listOf(notApply), check(policyStep(priority = -5), known = known(orders), reader = FakeBrokerPolicyReader().policy(brokerPolicy("limits", -1))).single().notes)
    }

    @Test
    fun `a queue the broker applies no policy to gets no note and no read`() {
        val reader = FakeBrokerPolicyReader()
        val steps = check(policyStep(), known = known("orders" to KnownQueue("quorum", null)), reader = reader)
        assertEquals(emptyList(), steps.single().notes)
        assertEquals(emptyList(), reader.calls)
    }

    @Test
    fun `a policy that cannot be read gives no note and every step still has its result`() {
        val reader = FakeBrokerPolicyReader().unreadable("/", "limits")
        val steps = check(policyStep(), MigrationAction.DeclarePolicy("other", "^zzz", mapOf("max-length" to ArgumentValue.Whole(1))), known = known(orders), reader = reader)
        assertEquals(2, steps.size)
        assertEquals(listOf(StepFinding.WillBeCreated, StepFinding.WillBeCreated), steps.map { it.finding })
        assertEquals(emptyList(), steps.flatMap { it.notes })
        assertEquals(listOf("/" to "limits"), reader.calls, "it tried once")
    }

    @Test
    fun `a policy the reader does not have (a 404) gives no note`() {
        assertEquals(emptyList(), check(policyStep(), known = known(orders), reader = FakeBrokerPolicyReader()).single().notes)
    }

    @Test
    fun `without a reader there is no note and no failure (a connection that gets none)`() {
        val steps = check(policyStep(priority = 10), known = known(orders), reader = null)
        assertEquals(emptyList(), steps.single().notes)
        assertEquals(StepFinding.WillBeCreated, steps.single().finding)
    }

    @Test
    fun `an existing broker stream has no policy name known, so no note`() {
        val reader = FakeBrokerPolicyReader().policy(brokerPolicy("limits", 0))
        val steps = check(policyStep(pattern = "^telemetry$", applyTo = "streams", keys = arrayOf("max-age")), known = known("telemetry" to KnownQueue("stream", null)), reader = reader)
        assertEquals(emptyList(), steps.single().notes)
        assertEquals(emptyList(), reader.calls)
    }

    @Test
    fun `only a queue the policy step would bind to is considered`() {
        val reader = FakeBrokerPolicyReader().policy(brokerPolicy("limits", 0))
        val onlyOtherName = check(policyStep(pattern = "^invoices$"), known = known(orders), reader = reader)
        assertEquals(emptyList(), onlyOtherName.single().notes)
        val wrongApplyTo = check(policyStep(applyTo = "streams", keys = arrayOf("max-age")), known = known(orders), reader = reader)
        assertEquals(emptyList(), wrongApplyTo.single().notes, "apply-to streams never binds to a quorum queue")
        val ignoredKey = check(policyStep(keys = arrayOf("max-length", "max-age")), known = known(orders), reader = reader)
        assertEquals(emptyList(), ignoredKey.single().notes, "a key the quorum queue ignores: the step would bind to nothing")
        assertEquals(emptyList(), reader.calls, "no queue qualified, so nothing was read")
    }

    @Test
    fun `a plan without a policy step makes no read at all`() {
        val reader = FakeBrokerPolicyReader().policy(brokerPolicy("limits", 0))
        val port = FakeDeclareCheck().answerNamed("orders", BrokerFinding.Same)
        val result = runBlocking { PlanCheck.run(TopologyPlan.of("", listOf(MigrationAction.DeclareQueue("orders")), "/"), context, port, policies = reader, known = known(orders)) }
        assertEquals(emptyList(), assertIs<PlanCheckResult.Checked>(result).steps.flatMap { it.notes })
        assertEquals(emptyList(), reader.calls)
    }

    @Test
    fun `a name is read once however many queues have the policy`() {
        val reader = FakeBrokerPolicyReader().policy(brokerPolicy("limits", 0))
        val many = (1..30).map { "orders-$it" to KnownQueue("quorum", "limits") }.toTypedArray()
        check(policyStep(pattern = "^orders-", priority = 5), known = known(*many), reader = reader)
        assertEquals(listOf("/" to "limits"), reader.calls, "one GET per DISTINCT policy name")
    }

    @Test
    fun `at most 20 policies are read per check, beyond the cap the note is left out`() {
        val reader = FakeBrokerPolicyReader()
        val many = (1..25).map { "orders-$it" to KnownQueue("quorum", "p$it") }
        many.forEach { (_, q) -> reader.policy(PolicyRow(q.policy!!, "/", "^orders-", "all", 0, mapOf("max-length" to ArgumentValue.Whole(1)))) }
        val steps = check(policyStep(pattern = "^orders-", priority = 5), known = known(*many.toTypedArray()), reader = reader)
        assertEquals(PlanCheck.MAX_POLICY_READS, 20)
        assertEquals(20, reader.calls.size, "reads: ${reader.calls}")
        assertEquals(20, reader.calls.toSet().size, "twenty distinct names")
        val notes = steps.single().notes
        assertTrue(notes.size <= 20, "no note without a read: ${notes.size}")
        assertTrue(notes.isNotEmpty(), "the notes of the 20 policies that were read")
        assertEquals(StepFinding.WillBeCreated, steps.single().finding)
    }

    @Test
    fun `reads are item reads of the plan's virtual host, by name, nothing else`() {
        val reader = FakeBrokerPolicyReader().policy(brokerPolicy("limits", 0))
        check(policyStep(priority = 1), known = known(orders), reader = reader)
        reader.calls.forEach { (vhost, name) ->
            assertEquals("/", vhost)
            assertEquals("limits", name, "the policy's own name, never a queue's name")
        }
    }

    @Test
    fun `the note does not change the finding or block the step`() {
        val steps = check(policyStep(priority = 10), known = known(orders), reader = FakeBrokerPolicyReader().policy(brokerPolicy("limits", 0)), finding = BrokerFinding.Same)
        assertEquals(StepFinding.AlreadyThere, steps.single().finding)
        assertEquals(null, steps.single().blocked)
        assertFalse(steps.single().isProblem)
    }

    @Test
    fun `a broker policy name that cannot be echoed is left out of the sentence`() {
        val bad = "li\"mits\nx"
        val reader = FakeBrokerPolicyReader().policy(brokerPolicy(bad, 0))
        val steps = check(policyStep(priority = 10), known = known("orders" to KnownQueue("quorum", bad)), reader = reader)
        steps.single().notes.forEach {
            assertFalse('\n' in it, "no line break: $it")
            assertFalse("li\"mits" in it, "the raw name is not echoed: $it")
        }
    }

    @Test
    fun `a name from the broker that is long is not echoed either`() {
        val long = "p".repeat(200)
        val steps = check(policyStep(priority = 10), known = known("orders" to KnownQueue("quorum", long)), reader = FakeBrokerPolicyReader().policy(brokerPolicy(long, 0)))
        steps.single().notes.forEach { assertTrue(it.length < 400, "a note of ${it.length} characters") }
    }
}
