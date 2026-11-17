package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.fakes.FakeBrokerPolicyReader
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import dev.caerbannog.core.topology.PolicyRow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-09 step 4 (test developer): exploratory checks of the built recipe that the step 2 tests do not state: hostile names are never echoed raw in a note, a problem or the
 * summary, very long and odd names do not break the form, the same queue twice, a stream target never gets the at-least-once keys, and the policy is always the last step.
 */
class DeadLetterExploratoryTest {
    private val rigs = mutableListOf<PlannerRig>()

    @AfterEach
    fun close() = rigs.forEach { it.close() }

    private val hostile = listOf("a\"b", "a b", "<b>x</b>", "a\nb", "a\\b", "x".repeat(300), "a'b;DROP", "é", "‮")

    private fun known(name: String, own: String? = null, policy: String? = null) = KnownTopology(
        queues = setOf(name), queueDetails = mapOf(name to KnownQueue("quorum", policy, ownDeadLetterExchange = own)),
    )

    private fun everyText(form: DeadLetterForm, c: FormContext): List<String> =
        form.problems(c).map { it.message } + form.hints(c).map { it.message }

    @Test
    fun `a hostile queue name is never echoed raw in a problem, a hint or the summary note`() {
        for (name in hostile) {
            val c = ctx(known = known(name, own = name))
            val texts = everyText(DeadLetterForm(name), c) +
                PlannerTexts.deadLetterQueueUntouched(name) + PlannerTexts.deadLetterOwnArgument(name, name) +
                PlannerTexts.deadLetterTargetQueueLine(name) + PlannerTexts.deadLetterTargetStreamLine(name)
            for (t in texts) assertFalse(name in t && name.length > 1 && !name.all { it.isLetterOrDigit() || it in "._-" }, "<$name> echoed raw in <$t>")
        }
    }

    @Test
    fun `a hostile name through the view model gives a whole policy note and a whole summary`() {
        val name = "a b\"<x>"
        val reader = FakeBrokerPolicyReader().policy(PolicyRow("p", "/", "x", "all", -1, emptyMap()))
        val r = PlannerRig(reader = FakeKnownTopologyReader(known(name, policy = "p")), policies = reader).also { rigs += it }
        r.vm.openWizard(); r.vm.chooseKind(PlannerTexts.DEAD_LETTER_KIND); r.vm.updateForm(DeadLetterForm(name))
        val details = assertIs<WizardState.Details>(r.state.wizard)
        details.formNotes.forEach { assertFalse("<x>" in it || "\"" in it, "raw name in note <$it>") }
        r.vm.next()
        val note = assertIs<WizardState.Summary>(r.state.wizard).note.orEmpty()
        assertFalse("<x>" in note || "a b" in note, "raw name in summary <$note>")
    }

    @Test
    fun `an odd or long queue name never throws and the policy pattern matches exactly that queue`() {
        for (name in hostile) {
            val form = DeadLetterForm(name)
            val c = ctx(known = known(name))
            form.problems(c); form.hints(c)
            val actions = form.actions(c)
            if (actions.isNotEmpty()) {
                val policy = actions.last() as MigrationAction.DeclarePolicy
                assertEquals(name, ExactPattern.nameOf(policy.pattern), "the pattern reads back as the queue: <$name>")
            }
        }
    }

    @Test
    fun `the policy is the last step of the recipe for every target and strategy`() {
        for (target in DeadLetterTarget.entries) for (strategy in DeadLetterStrategy.entries) {
            val f = DeadLetterForm("orders", target, strategy = strategy, publishersRefusedAccepted = true)
            val actions = f.actions(ctx(known = known("orders")))
            if (actions.isEmpty()) continue
            assertEquals(4, actions.size, "$target $strategy")
            assertTrue(actions.last() is MigrationAction.DeclarePolicy, "$target $strategy")
            assertTrue(actions.dropLast(1).none { it is MigrationAction.DeclarePolicy })
        }
    }

    @Test
    fun `at least once is never the default`() {
        assertEquals(DeadLetterStrategy.AtMostOnce, DeadLetterForm("orders").strategy)
        assertEquals(DeadLetterStrategy.AtMostOnce, DeadLetterForm(queue = "").strategy)
    }

    @Test
    fun `a stream target with at least once on a quorum queue keeps the policy on the source type`() {
        val f = DeadLetterForm("orders", DeadLetterTarget.Stream, strategy = DeadLetterStrategy.AtLeastOnce, publishersRefusedAccepted = true)
        val actions = f.actions(ctx(known = known("orders")))
        assertEquals(4, actions.size)
        assertEquals("quorum_queues", (actions.last() as MigrationAction.DeclarePolicy).applyTo)
        assertTrue(actions[1] is MigrationAction.DeclareStream)
    }

    @Test
    fun `the recipe twice for one queue is flagged for the exchange and the queue, the policy twice is known gap 10 of M9-08`() {
        val c = ctx(known = known("orders"))
        val once = DeadLetterForm("orders").actions(c)
        val plan = TopologyPlan().append(once).append(once)
        val problems = PlanValidation.problems(plan, c.known).map { it.message }
        assertEquals(8, plan.steps.size)
        assertEquals(
            listOf("The plan declares orders-dlx twice with the same settings", "The plan declares orders-dlq twice with the same settings"), problems,
            "known gap: the second policy step of the same name is not flagged",
        )
    }

    @Test
    fun `the exchange the recipe names that the broker already has is a hint, not a block`() {
        val c = ctx(known = known("orders").copy(exchanges = mapOf("orders-dlx" to "fanout")))
        assertEquals(emptyList(), DeadLetterForm("orders").problems(c))
        assertEquals(4, DeadLetterForm("orders").actions(c).size)
    }

    @Test
    fun `an exchange that the broker has with another type is not hidden by the recipe`() {
        val c = ctx(known = known("orders").copy(exchanges = mapOf("orders-dlx" to "direct")))
        // the fanout declaration would be refused by the broker (406); the planner check of M9-05 judges it, the form lets it pass
        assertEquals(4, DeadLetterForm("orders").actions(c).size)
    }
}
