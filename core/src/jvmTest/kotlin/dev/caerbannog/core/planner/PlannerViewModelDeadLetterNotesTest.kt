package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.planner.fakes.FakeBrokerPolicyReader
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import dev.caerbannog.core.topology.PolicyRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-09 step 2, written first: the policy warning of M9-08 in the dead-lettering form (brief, "The policy warning of M9-08 in the form"). API fixed by the brief:
 * `WizardState.Details.formNotes: List<String>` (default empty). The view model, when the queue of a `DeadLetterForm` in the wizard changes (and when the form is first
 * shown with a queue), launches ONE read job and cancels the previous one, builds the policy action the form would add with priority 0, asks `BrokerPolicyNotes.notesFor`
 * and puts the strings into the wizard state. No reader, or a failed read: the list stays empty (no claim). RED until built (`formNotes`, `DeadLetterForm`).
 * Inferences beyond the brief: a change that leaves the queue as it was (the strategy, the target, a name) does not read again; an empty queue reads nothing.
 */
class PlannerViewModelDeadLetterNotesTest {
    private val rigs = mutableListOf<PlannerRig>()

    @AfterEach
    fun close() = rigs.forEach { it.close() }

    private val kind = "Dead-lettering for a queue"
    private val broker = KnownTopology(
        queues = setOf("orders", "invoices"),
        queueDetails = mapOf("orders" to KnownQueue("quorum", "limits"), "invoices" to KnownQueue("classic", "other")),
    )

    private fun row(name: String, priority: Int) = PolicyRow(name, "/", "^orders", "all", priority, mapOf("max-length" to ArgumentValue.Whole(5)))

    private fun rig(
        policies: BrokerPolicyReader?,
        known: KnownTopology? = broker,
        context: PlannerContext = PlannerContext(),
    ): PlannerRig = PlannerRig(context = context, reader = FakeKnownTopologyReader(known), policies = policies).also { rigs += it }

    private fun PlannerRig.show(form: DeadLetterForm) {
        vm.openWizard()
        vm.chooseKind(kind)
        vm.updateForm(form)
    }

    private fun PlannerRig.details() = assertIs<WizardState.Details>(state.wizard)
    private fun PlannerRig.notes() = details().formNotes

    @Test
    fun `a queue that has a policy gives the note when the form is shown with it, for the recipe's priority 0`() {
        val reader = FakeBrokerPolicyReader().policy(row("limits", -1))
        val r = rig(reader)
        r.show(DeadLetterForm("orders"))
        assertEquals(listOf(PlannerTexts.brokerPolicyTakenPlace("limits", "orders")), r.notes())
        assertEquals(
            "The broker applies the policy limits to orders. Only one policy applies to a queue: this one would take its place.",
            r.notes().single(),
        )
        assertEquals(listOf("/" to "limits"), reader.calls.toList(), "one read of the one policy, by name")
    }

    @Test
    fun `the priority decides which of the three decided sentences it is`() {
        for ((priority, expected) in listOf(
            -1 to PlannerTexts.brokerPolicyTakenPlace("limits", "orders"),
            0 to PlannerTexts.brokerPolicySamePriority("limits", "orders"),
            5 to PlannerTexts.brokerPolicyWins("limits", "orders"),
        )) {
            val r = rig(FakeBrokerPolicyReader().policy(row("limits", priority)))
            r.show(DeadLetterForm("orders"))
            assertEquals(listOf(expected), r.notes(), "broker priority $priority against the recipe's 0")
        }
    }

    @Test
    fun `the note is a state of the wizard, not a hint of the pure form`() {
        val r = rig(FakeBrokerPolicyReader().policy(row("limits", -1)))
        r.show(DeadLetterForm("orders"))
        assertTrue(r.details().hints.none { it.message.contains("Only one policy applies") }, r.details().hints.toString())
        assertEquals(emptyList(), r.details().problems, "a note never blocks")
        assertTrue(r.details().nextEnabled)
    }

    @Test
    fun `a queue without a policy gives no note and no read`() {
        val reader = FakeBrokerPolicyReader().policy(row("limits", -1))
        val r = rig(reader, known = KnownTopology(queues = setOf("orders"), queueDetails = mapOf("orders" to KnownQueue("quorum", null))))
        r.show(DeadLetterForm("orders"))
        assertEquals(emptyList(), r.notes())
        assertEquals(emptyList(), reader.calls.toList())
    }

    @Test
    fun `a policy of the recipe's own name is the one this step replaces, so no note`() {
        val known = KnownTopology(queues = setOf("orders"), queueDetails = mapOf("orders" to KnownQueue("quorum", "orders-dlx")))
        val r = rig(FakeBrokerPolicyReader().policy(row("orders-dlx", -1)), known = known)
        r.show(DeadLetterForm("orders"))
        assertEquals(emptyList(), r.notes())
    }

    @Test
    fun `an empty queue reads nothing`() {
        val reader = FakeBrokerPolicyReader().policy(row("limits", -1))
        val r = rig(reader)
        r.show(DeadLetterForm(""))
        assertEquals(emptyList(), r.notes())
        assertEquals(emptyList(), reader.calls.toList())
    }

    @Test
    fun `a new queue reads again and the note is the new queue's`() {
        val reader = FakeBrokerPolicyReader().policy(row("limits", -1)).policy(row("other", -1))
        val r = rig(reader)
        r.show(DeadLetterForm("orders"))
        r.vm.updateForm(DeadLetterForm("invoices"))
        assertEquals(listOf("/" to "limits", "/" to "other"), reader.calls.toList())
        assertEquals(listOf(PlannerTexts.brokerPolicyTakenPlace("other", "invoices")), r.notes(), "the note of invoices, not of orders")
        r.vm.updateForm(DeadLetterForm("unlisted"))
        assertEquals(emptyList(), r.notes(), "a name nobody knows has no note")
    }

    @Test
    fun `a change that keeps the queue does not read again`() {
        val reader = FakeBrokerPolicyReader().policy(row("limits", -1))
        val r = rig(reader)
        r.show(DeadLetterForm("orders"))
        r.vm.updateForm(DeadLetterForm("orders", target = DeadLetterTarget.Stream))
        r.vm.updateForm(DeadLetterForm("orders", target = DeadLetterTarget.Stream, exchangeName = "x"))
        r.vm.updateForm(DeadLetterForm("orders").withStrategy(DeadLetterStrategy.AtLeastOnce))
        assertEquals(1, reader.calls.size, "calls: ${reader.calls}")
        assertEquals(1, r.notes().size, "the note stays")
    }

    @Test
    fun `the old read is cancelled when the queue changes, and its late answer is never shown`() {
        val gate = GateReader()
        val r = rig(gate)
        r.show(DeadLetterForm("orders"))
        assertEquals(listOf("limits"), gate.started.toList())
        r.vm.updateForm(DeadLetterForm("invoices"))
        assertEquals(listOf("limits"), gate.cancelled.toList(), "the first job was cancelled")
        gate.finish("limits", PolicyReadResult.Found(row("limits", -1)))
        assertEquals(emptyList(), r.notes(), "the late answer for orders is dropped")
        gate.finish("other", PolicyReadResult.Found(row("other", -1)))
        assertEquals(listOf("limits", "other"), gate.started.toList())
        assertEquals(emptyList(), gate.cancelled.toList() - "limits")
    }

    @Test
    fun `only one read job runs at a time`() {
        val gate = GateReader()
        val r = rig(gate)
        r.show(DeadLetterForm("orders"))
        r.vm.updateForm(DeadLetterForm("invoices"))
        r.vm.updateForm(DeadLetterForm("orders"))
        assertEquals(listOf("limits", "other", "limits"), gate.started.toList())
        assertEquals(listOf("limits", "other"), gate.cancelled.toList(), "each earlier job was cancelled")
    }

    @Test
    fun `leaving the form drops the note, the next form starts without it`() {
        val r = rig(FakeBrokerPolicyReader().policy(row("limits", -1)))
        r.show(DeadLetterForm("orders"))
        assertEquals(1, r.notes().size)
        r.vm.cancelWizard()
        r.vm.openWizard()
        r.vm.chooseKind(kind)
        assertEquals(emptyList(), r.notes())
    }

    @Test
    fun `no reader wired gives no note and the form still works`() {
        val r = rig(policies = null)
        r.show(DeadLetterForm("orders"))
        assertEquals(emptyList(), r.notes())
        assertTrue(r.details().nextEnabled)
    }

    @Test
    fun `a read that is refused or fails gives no note, never a guess`() {
        val refused = FakeBrokerPolicyReader().policy(row("limits", -1)).unreadable("/", "limits")
        val a = rig(refused)
        a.show(DeadLetterForm("orders"))
        assertEquals(emptyList(), a.notes())
        val failing = BrokerPolicyReader { _, _ -> throw IllegalStateException("boom") }
        val b = rig(failing)
        b.show(DeadLetterForm("orders"))
        assertEquals(emptyList(), b.notes())
        assertTrue(b.details().nextEnabled, "a failed extra read blocks nothing")
    }

    @Test
    fun `a broker whose names were not read gives no note`() {
        val reader = FakeBrokerPolicyReader().policy(row("limits", -1))
        val r = rig(reader, known = null)
        r.show(DeadLetterForm("orders"))
        assertEquals(emptyList(), r.notes())
        assertEquals(emptyList(), reader.calls.toList())
    }

    @Test
    fun `a locked planner (3x) opens no wizard and reads no policy`() {
        val reader = FakeBrokerPolicyReader().policy(row("limits", -1))
        val r = rig(reader, context = PlannerContext(unavailableReason = "RabbitMQ 3.x"))
        r.show(DeadLetterForm("orders"))
        assertEquals(WizardState.Closed, r.state.wizard)
        assertEquals(emptyList(), reader.calls.toList())
    }

    @Test
    fun `other kinds have no form notes`() {
        val r = rig(FakeBrokerPolicyReader().policy(row("limits", -1)))
        r.vm.openWizard()
        r.vm.chooseKind("Queue")
        r.vm.updateForm(QueueForm("orders"))
        assertEquals(emptyList(), r.notes())
    }

    @Test
    fun `the summary holds the four steps and says what is dead-lettered and that the queue is not changed`() {
        val r = rig(policies = null)
        r.show(DeadLetterForm("orders"))
        r.vm.next()
        val summary = assertIs<WizardState.Summary>(r.state.wizard)
        assertEquals(4, summary.actions.size)
        val note = summary.note.orEmpty()
        assertTrue(PlannerTexts.DEAD_LETTER_SUMMARY_CAUSES in note, "the causes sentence is in the note: <$note>")
        assertTrue("The queue orders itself is not changed." in note, "the untouched sentence is in the note: <$note>")
        assertEquals("This adds 4 steps", PlannerTexts.summaryTitle(summary.actions.size))
    }

    /** A reader whose answers the test gives, one gate per policy name; it records when a read starts and when it is cancelled. */
    private class GateReader : BrokerPolicyReader {
        val started: MutableList<String> = Collections.synchronizedList(ArrayList())
        val cancelled: MutableList<String> = Collections.synchronizedList(ArrayList())
        private val gates = Collections.synchronizedMap(HashMap<String, CompletableDeferred<PolicyReadResult>>())

        private fun gate(name: String) = gates.getOrPut(name) { CompletableDeferred() }

        fun finish(name: String, result: PolicyReadResult) { gate(name).complete(result) }

        override suspend fun read(vhost: String, name: String): PolicyReadResult {
            started += name
            try {
                return gate(name).await()
            } catch (e: CancellationException) {
                cancelled += name
                throw e
            }
        }
    }
}
