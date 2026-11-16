package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.topology.BindingDestinationType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-03 `core` additions (brief, Scope (b), (c), (d) and what the built state cannot say): the values the composables read so that no
 * composable filters `problems`, counts, or decides what is locked. Pure: the grouping tests build [PlannerState] by hand, the rest go through
 * the view model over fakes ([PlannerRig]). Texts are quoted from [PlannerTexts].
 */
class PlannerStateDerivedTest {
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun close() = closers.forEach { it() }

    /** [reopen]: start the view model again after [prepare] (what a restart or a rebuilt window does); not for a test about the session itself. */
    private fun rig(context: PlannerContext = PlannerContext(), reopen: Boolean = true, prepare: PlannerRig.() -> Unit = {}): PlannerRig =
        PlannerRig(context = context).also { closers += it::close; it.prepare(); if (reopen) it.restart() }

    private val sample = MigrationAction.DeclareQueue("orders")
    private val reason3x = "This broker runs RabbitMQ 3.x: it is a migration source only"

    // ---- isLocked ----

    @Test
    fun `a normal planner is not locked`() = assertFalse(rig().state.isLocked)

    @Test
    fun `a connection that cannot run migrations locks the planner`() =
        assertTrue(rig(PlannerContext(unavailableReason = reason3x)).state.isLocked)

    @Test
    fun `an unreadable draft locks the planner`() =
        assertTrue(rig { store.putUnreadable() }.state.isLocked)

    @Test
    fun `Read-only level, no permission, not connected and a folder problem do not lock the planner`() {
        assertFalse(rig(PlannerContext(controlLevel = TopologyControlLevel.READ_ONLY)).state.isLocked)
        assertFalse(rig(PlannerContext(hasConfigurePermission = false)).state.isLocked)
        assertFalse(rig(PlannerContext(connectionName = null)).state.isLocked)
        assertFalse(PlannerRig(folderPath = null).also { closers += it::close }.state.isLocked)
    }

    @Test
    fun `a locked planner has no wizard and its save gate is not enabled`() {
        val state = rig(PlannerContext(unavailableReason = reason3x)).also { it.vm.openWizard() }.state
        assertEquals(WizardState.Closed, state.wizard)
        assertTrue(state.saveGate is SaveGate.Disabled)
    }

    // ---- canDismiss ----

    @Test
    fun `an empty plan cannot be dismissed`() = assertFalse(rig().state.canDismiss)

    @Test
    fun `a plan with a step can be dismissed`() = assertTrue(rig { queue("orders") }.state.canDismiss)

    @Test
    fun `a plan with only a description can be dismissed, because the description is kept as a draft`() {
        val r = rig { vm.setDescription("later") }
        assertTrue(r.state.canDismiss)
    }

    @Test
    fun `after the last step is removed and the description is empty, Dismiss is off again`() {
        val r = rig { queue("orders") }
        r.vm.removeStep(r.state.plan.steps.single().id)
        assertFalse(r.state.canDismiss)
    }

    // ---- grouped problems, notes, blocking count ----

    private fun stateWith(vararg problems: PlanProblem) = PlannerState(
        plan = TopologyPlan().append(listOf(sample, sample.copy(name = "b"), sample.copy(name = "c"))),
        problems = problems.toList(),
    )

    @Test
    fun `a problem of the whole plan is not a problem of any step`() {
        val s = stateWith(PlanProblem(null, "the parser refuses the plan", true), PlanProblem(2, "step two", true))
        assertEquals(listOf("the parser refuses the plan"), s.wholePlanProblems.map { it.message })
        assertEquals(emptyList(), s.stepProblems(1))
        assertEquals(listOf("step two"), s.stepProblems(2).map { it.message })
    }

    @Test
    fun `step problems keep the order of the validation and include the notes of the step`() {
        val s = stateWith(PlanProblem(2, "first", true), PlanProblem(3, "other", true), PlanProblem(2, "second, a note", false))
        assertEquals(listOf("first", "second, a note"), s.stepProblems(2).map { it.message })
    }

    @Test
    fun `notes are the problems that do not block, and the blocking count leaves them out`() {
        val s = stateWith(PlanProblem(2, "red", true), PlanProblem(3, "grey", false), PlanProblem(null, "red too", true))
        assertEquals(listOf("grey"), s.notes.map { it.message })
        assertEquals(2, s.blockingCount)
    }

    @Test
    fun `no problems gives zero, no notes and nothing grouped`() {
        val s = stateWith()
        assertEquals(0, s.blockingCount)
        assertEquals(emptyList(), s.notes)
        assertEquals(emptyList(), s.wholePlanProblems)
    }

    @Test
    fun `a note stays out of the Fix the N problems text of the save gate`() {
        val r = rig { queue("orders"); queue("orders") } // the same queue twice with the same settings: a note, not a problem
        assertEquals(listOf(PlannerTexts.declaredTwice("orders")), r.state.notes.map { it.message })
        assertEquals(0, r.state.blockingCount)
        assertEquals(SaveGate.Enabled, r.state.saveGate)
    }

    @Test
    fun `the blocking count is the number in the save gate reason`() {
        val r = rig { binding("events", "orders", "k"); queue("orders"); exchange("events") }
        assertEquals(2, r.state.blockingCount)
        assertEquals(SaveGate.Disabled(PlannerTexts.saveBlocked(2)), r.state.saveGate)
    }

    // ---- write-failed second line ----

    @Test
    fun `a failed write has a second line that says the plan is still a draft and nothing was changed`() {
        assertEquals("The plan is still here as a draft. Nothing was changed.", PlannerTexts.FILE_NOT_WRITTEN_DETAIL)
        val r = rig { queue("orders"); folder.failWrite(1) }
        r.vm.saveAsMigration()
        assertEquals(PlannerTexts.FILE_NOT_WRITTEN, r.state.errorMessage)
        assertEquals(PlannerTexts.FILE_NOT_WRITTEN_DETAIL, r.state.errorDetail)
    }

    @Test
    fun `no error, no detail`() = assertNull(rig().state.errorDetail)

    @Test
    fun `a draft that could not be saved has its own message and no second line (the plan is only in memory)`() {
        val r = rig(reopen = false) { store.refuseWrites = true; queue("orders") }
        assertEquals(PlannerTexts.DRAFT_NOT_SAVED, r.state.errorMessage)
        assertNull(r.state.errorDetail)
    }

    // ---- empty plan, Check, review texts ----

    @Test
    fun `an empty plan says No steps yet and nothing to review`() {
        assertEquals("No steps yet", PlannerTexts.NO_STEPS_YET)
        assertEquals("Nothing to review yet", PlannerTexts.NOTHING_TO_REVIEW)
        assertTrue(rig().state.isEmpty)
        assertFalse(rig { queue("orders") }.state.isEmpty)
    }

    @Test
    fun `Check is disabled until M9-06 and says why`() {
        assertEquals("Checking comes in a later step", PlannerTexts.CHECK_LATER)
        val s = rig { queue("orders") }.state
        assertFalse(s.checkEnabled)
        assertEquals(PlannerTexts.CHECK_LATER, s.checkDisabledReason)
    }

    // ---- the migration text (the JSON view) ----

    @Test
    fun `the migration text is what the plan would be saved as and the parser reads it back`() {
        val r = rig { vm.setDescription("Add orders"); queue("orders"); exchange("events"); binding("events", "orders", "order.created") }
        val parsed = assertIs<MigrationValidationResult.Valid>(MigrationParser.parse(r.state.migrationText)).migration
        assertEquals("Add orders", parsed.description)
        assertEquals(r.state.plan.steps.map { it.action }, parsed.up)
        assertTrue(r.state.migrationText.contains("\"action\": \"declareQueue\""), r.state.migrationText)
    }

    @Test
    fun `an empty plan has an empty list of actions in its text, never a crash`() {
        val parsed = MigrationParser.parse(rig().state.migrationText)
        // The parser may refuse an empty list; the text must still exist and name no action.
        assertFalse(rig().state.migrationText.contains("\"action\""))
        assertTrue(parsed is MigrationValidationResult.Valid || parsed is MigrationValidationResult.Invalid)
    }

    @Test
    fun `the migration text holds no password or secret field`() {
        val text = rig { queue("orders") }.state.migrationText
        assertFalse(text.contains("password", ignoreCase = true))
    }

    // ---- a binding problem as one message of the page (M9-01 review note 1) ----

    @Test
    fun `an empty binding form gives the first problem as the page message`() {
        val r = rig()
        r.vm.openWizard(); r.vm.chooseKind("Binding")
        val details = assertIs<WizardState.Details>(r.state.wizard)
        assertEquals(PlannerTexts.BINDING_EXCHANGE_MISSING, details.pageMessage)
    }

    @Test
    fun `a binding problem from the parser is the page message too, not only under the exchange field`() {
        val r = rig()
        r.vm.openWizard(); r.vm.chooseKind("Binding")
        r.vm.updateForm(BindingForm(exchange = "..", destination = "orders", destinationKind = BindingDestinationType.QUEUE))
        val details = assertIs<WizardState.Details>(r.state.wizard)
        assertEquals(details.problems.first().message, details.pageMessage)
        assertFalse(details.nextEnabled)
    }

    @Test
    fun `a valid binding form has no page message`() {
        val r = rig()
        r.vm.openWizard(); r.vm.chooseKind("Binding")
        r.vm.updateForm(BindingForm(exchange = "events", destination = "orders"))
        assertNull(assertIs<WizardState.Details>(r.state.wizard).pageMessage)
    }

    @Test
    fun `a queue form keeps its message under the field only, no page message`() {
        val r = rig()
        r.vm.openWizard(); r.vm.chooseKind("Queue")
        val details = assertIs<WizardState.Details>(r.state.wizard)
        assertEquals(PlannerTexts.QUEUE_NAME_MISSING, details.problems.single().message)
        assertNull(details.pageMessage)
    }

    // ---- the suggestion list of the binding form (the screen cannot build it: it has no FormContext) ----

    @Test
    fun `the binding form offers the exchanges and destinations of this plan first, then the broker, and asks the routing key`() {
        val known = KnownTopology(exchanges = mapOf("amq.topic" to "topic"), queues = setOf("audit"))
        val r = rig(PlannerContext(known = known)) { queue("orders"); exchange("events") }
        r.vm.openWizard(); r.vm.chooseKind("Binding")
        val details = assertIs<WizardState.Details>(r.state.wizard)
        assertEquals(
            listOf(Suggestion("events", SuggestionSource.PLAN), Suggestion("amq.topic", SuggestionSource.BROKER)),
            details.exchangeSuggestions,
        )
        assertEquals(
            listOf(Suggestion("orders", SuggestionSource.PLAN), Suggestion("audit", SuggestionSource.BROKER)),
            details.destinationSuggestions,
        )
        assertTrue(details.asksRoutingKey)
    }

    @Test
    fun `the routing key is not asked for a fanout exchange of this plan`() {
        val r = rig { exchange("events", "fanout") }
        r.vm.openWizard(); r.vm.chooseKind("Binding")
        r.vm.updateForm(BindingForm(exchange = "events", destination = ""))
        assertFalse(assertIs<WizardState.Details>(r.state.wizard).asksRoutingKey)
    }

    @Test
    fun `destination suggestions follow the kind of the destination`() {
        val r = rig { queue("orders"); exchange("events") }
        r.vm.openWizard(); r.vm.chooseKind("Binding")
        r.vm.updateForm(BindingForm(exchange = "", destination = "", destinationKind = BindingDestinationType.EXCHANGE))
        assertEquals(listOf("events"), assertIs<WizardState.Details>(r.state.wizard).destinationSuggestions.map { it.name })
    }

    @Test
    fun `a queue or exchange form has no suggestions`() {
        val r = rig()
        r.vm.openWizard(); r.vm.chooseKind("Queue")
        val details = assertIs<WizardState.Details>(r.state.wizard)
        assertEquals(emptyList(), details.exchangeSuggestions)
        assertEquals(emptyList(), details.destinationSuggestions)
    }
}
