package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-02: the wizard state machine of [PlannerViewModel] (Kind, Details(form), Summary(actions)), the step list operations, and the step-id rule
 * of the brief: the view model keeps only ids that are live in the current plan. `TopologyPlan.append` hands out max id + 1, so after the last
 * step is removed its id goes to the next appended step; a selection or an edit must never follow the id onto that other step.
 */
class PlannerViewModelWizardTest {
    private val rig = PlannerRig()
    private val vm get() = rig.vm

    @AfterTest
    fun close() = rig.close()

    // ---- the three pages ----

    @Test
    fun `a new planner has an empty plan, no wizard open and nothing selected`() {
        assertEquals(emptyList(), rig.state.plan.steps)
        assertEquals(WizardState.Closed, rig.state.wizard)
        assertNull(rig.state.selectedStepId)
    }

    @Test
    fun `the wizard opens on the page of the kinds`() {
        vm.openWizard()
        assertEquals(WizardState.Kind, rig.state.wizard)
    }

    @Test
    fun `choosing a kind opens its blank form with the problem it has`() {
        vm.openWizard()
        vm.chooseKind("Queue")
        val details = assertIs<WizardState.Details>(rig.state.wizard)
        assertEquals(QueueForm(name = ""), details.form)
        assertEquals(listOf("Give the queue a name"), details.problems.map { it.message })
        assertTrue(!details.nextEnabled)
        assertNull(details.editingStepId)
    }

    @Test
    fun `each kind of this task opens its own form`() {
        listOf("Queue" to QueueForm::class, "Exchange" to ExchangeForm::class, "Binding" to BindingForm::class).forEach { (kind, type) ->
            vm.openWizard()
            vm.chooseKind(kind)
            assertEquals(type, assertIs<WizardState.Details>(rig.state.wizard).form::class, kind)
            vm.cancelWizard()
        }
    }

    @Test
    fun `a kind that is not built yet leaves the wizard on the kinds and adds nothing`() {
        vm.openWizard()
        vm.chooseKind("Stream")
        assertEquals(WizardState.Kind, rig.state.wizard)
        vm.chooseKind("Not a kind at all")
        assertEquals(WizardState.Kind, rig.state.wizard)
    }

    @Test
    fun `Next is refused while the form has a problem`() {
        vm.openWizard()
        vm.chooseKind("Queue")
        vm.next()
        assertIs<WizardState.Details>(rig.state.wizard)
        assertEquals(emptyList(), rig.state.plan.steps)
    }

    @Test
    fun `Next with a good form shows the summary of what would be added and the plan is unchanged`() {
        vm.openWizard()
        vm.chooseKind("Queue")
        vm.updateForm(QueueForm("orders"))
        vm.next()
        val summary = assertIs<WizardState.Summary>(rig.state.wizard)
        assertEquals(listOf("Declare quorum queue orders"), summary.actions.map { it.description })
        assertEquals(emptyList(), rig.state.plan.steps)
    }

    @Test
    fun `Back returns from the summary to the form as it was and from the form to the kinds`() {
        vm.openWizard()
        vm.chooseKind("Queue")
        vm.updateForm(QueueForm("orders", type = "classic"))
        vm.next()
        vm.back()
        assertEquals(QueueForm("orders", type = "classic"), assertIs<WizardState.Details>(rig.state.wizard).form)
        vm.back()
        assertEquals(WizardState.Kind, rig.state.wizard)
    }

    @Test
    fun `Add to plan appends one step, closes the wizard and the step has the next id`() {
        rig.queue("orders")
        assertEquals(listOf("Declare quorum queue orders"), rig.titles())
        assertEquals(WizardState.Closed, rig.state.wizard)
        rig.exchange("events")
        assertEquals(listOf(1, 2), rig.ids())
    }

    @Test
    fun `Add to plan is refused before the summary is reached`() {
        vm.openWizard()
        vm.chooseKind("Queue")
        vm.updateForm(QueueForm("orders"))
        vm.addToPlan()
        assertEquals(emptyList(), rig.state.plan.steps, "Add to plan belongs to the summary page")
    }

    @Test
    fun `Cancel adds nothing from any page and closes the wizard`() {
        vm.openWizard(); vm.cancelWizard()
        vm.openWizard(); vm.chooseKind("Queue"); vm.cancelWizard()
        vm.openWizard(); vm.chooseKind("Queue"); vm.updateForm(QueueForm("orders")); vm.next(); vm.cancelWizard()
        assertEquals(emptyList(), rig.state.plan.steps)
        assertEquals(WizardState.Closed, rig.state.wizard)
        assertEquals(0, rig.store.savedTexts.size, "a cancelled wizard leaves no draft")
    }

    @Test
    fun `a binding built in the wizard is for the plan's virtual host`() {
        rig.restart(PlannerContext(vhost = "/orders"))
        rig.add(BindingForm("events", "orders", BindingDestinationType.QUEUE, "k"))
        assertEquals("/orders", rig.state.plan.steps.single().action.vhost)
    }

    // ---- hints and problems ----

    @Test
    fun `a name that does not follow the naming style shows a hint and never blocks Next`() {
        vm.openWizard()
        vm.chooseKind("Queue")
        vm.updateForm(QueueForm("Orders_EU"))
        val details = assertIs<WizardState.Details>(rig.state.wizard)
        assertEquals(listOf("name"), details.hints.map { it.field })
        assertTrue(details.nextEnabled)
        vm.next()
        assertIs<WizardState.Summary>(rig.state.wizard)
    }

    @Test
    fun `the hint follows the naming style and the steps do not`() {
        rig.queue("orders-eu")
        vm.setNamingStyle(NamingStyle.SNAKE)
        assertEquals(NamingStyle.SNAKE, rig.state.namingStyle)
        assertEquals(listOf("Declare quorum queue orders-eu"), rig.titles(), "changing the style renames nothing")
        vm.openWizard(); vm.chooseKind("Queue"); vm.updateForm(QueueForm("orders-eu"))
        assertEquals(listOf("name"), assertIs<WizardState.Details>(rig.state.wizard).hints.map { it.field })
    }

    @Test
    fun `the plan's problems are in the state, a binding before its queue blocks`() {
        rig.exchange("events")
        rig.binding("events", "orders", "k")
        rig.queue("orders")
        val blocking = rig.state.problems.filter { it.blocking }
        assertEquals(1, blocking.size, "the binding is above the queue it needs: ${rig.state.problems}")
        assertEquals(rig.state.plan.steps[1].id, blocking.single().stepId)
    }

    // ---- the step list ----

    @Test
    fun `a step moves up and down by its id and stays selected`() {
        rig.queue("a"); rig.queue("b"); rig.queue("c")
        val b = rig.state.plan.steps[1].id
        vm.selectStep(b)
        vm.moveStepUp(b)
        assertEquals(listOf("b", "a", "c"), rig.state.plan.steps.map { (it.action as MigrationAction.DeclareQueue).name })
        vm.moveStepDown(b)
        vm.moveStepDown(b)
        assertEquals(listOf("a", "c", "b"), rig.state.plan.steps.map { (it.action as MigrationAction.DeclareQueue).name })
        assertEquals(b, rig.state.selectedStepId)
    }

    @Test
    fun `selecting a step that is not in the plan selects nothing`() {
        rig.queue("a")
        vm.selectStep(99)
        assertNull(rig.state.selectedStepId)
    }

    // ---- editing a step ----

    @Test
    fun `editing a step opens Details with the form of its action and Add to plan replaces it in place`() {
        rig.queue("a"); rig.queue("b"); rig.queue("c")
        val b = rig.state.plan.steps[1].id
        vm.editStep(b)
        val details = assertIs<WizardState.Details>(rig.state.wizard)
        assertEquals(QueueForm("b"), details.form)
        assertEquals(b, details.editingStepId)
        vm.updateForm(QueueForm("b2", type = "classic"))
        vm.next()
        vm.addToPlan()
        assertEquals(listOf("Declare quorum queue a", "Declare classic queue b2", "Declare quorum queue c"), rig.titles())
        assertEquals(b, rig.state.plan.steps[1].id, "the step keeps its id and its place")
        assertEquals(WizardState.Closed, rig.state.wizard)
    }

    @Test
    fun `a step no form can hold is not opened`() {
        rig.store.putDraft("", listOf(MigrationAction.DeleteQueue("old")))
        rig.restart()
        val id = rig.state.plan.steps.single().id
        vm.editStep(id)
        assertEquals(WizardState.Closed, rig.state.wizard, "StepForms.formOf is null for a delete: the wizard does not open")
    }

    @Test
    fun `editing an unknown step id opens nothing`() {
        rig.queue("a")
        vm.editStep(42)
        assertEquals(WizardState.Closed, rig.state.wizard)
    }

    // ---- step ids across a removal (the rule of the brief) ----

    @Test
    fun `removing the selected step clears the selection, and the id given again to a new step is not selected`() {
        rig.queue("a"); rig.queue("b")
        val last = rig.state.plan.steps.last().id
        vm.selectStep(last)
        vm.removeStep(last)
        assertNull(rig.state.selectedStepId, "the selected step is gone")
        rig.queue("c")
        assertEquals(last, rig.state.plan.steps.last().id, "precondition: TopologyPlan.append gives the freed id to the new step")
        assertNull(rig.state.selectedStepId, "the new step must not inherit the selection of the removed one")
    }

    @Test
    fun `removing another step keeps the selection on the same step`() {
        rig.queue("a"); rig.queue("b"); rig.queue("c")
        val (a, _, c) = rig.state.plan.steps.map { it.id }
        vm.selectStep(c)
        vm.removeStep(a)
        assertEquals(c, rig.state.selectedStepId)
        assertEquals("c", (rig.state.plan.steps.single { it.id == c }.action as MigrationAction.DeclareQueue).name)
    }

    @Test
    fun `removing the step being edited closes the wizard, and a later Add to plan cannot replace the step that got its id`() {
        rig.queue("a"); rig.queue("b")
        val last = rig.state.plan.steps.last().id
        vm.editStep(last)
        vm.removeStep(last)
        assertEquals(WizardState.Closed, rig.state.wizard, "the edit named a step that is gone")
        vm.addToPlan()
        rig.queue("c")
        assertEquals(listOf("Declare quorum queue a", "Declare quorum queue c"), rig.titles())
        assertEquals(last, rig.state.plan.steps.last().id)
        vm.addToPlan()
        assertEquals(listOf("Declare quorum queue a", "Declare quorum queue c"), rig.titles(), "nothing is left to complete")
    }

    @Test
    fun `an edit in flight on a step that is removed does not land on the step that reuses its id`() {
        rig.queue("a"); rig.queue("b")
        val b = rig.state.plan.steps.last().id
        vm.editStep(b)
        vm.updateForm(QueueForm("b-edited"))
        vm.next()
        vm.removeStep(b) // the summary was open; the step goes away
        rig.queue("c") // gets the id b had
        assertEquals(listOf("Declare quorum queue a", "Declare quorum queue c"), rig.titles())
        vm.addToPlan() // must not replace c with the edit meant for b
        assertEquals(listOf("Declare quorum queue a", "Declare quorum queue c"), rig.titles())
    }

    // ---- locked ----

    @Test
    fun `when the connection cannot run migrations the wizard does not open`() {
        rig.restart(PlannerContext(unavailableReason = "This broker runs RabbitMQ 3.x: it is a migration source only"))
        vm.openWizard()
        assertEquals(WizardState.Closed, rig.state.wizard)
        vm.chooseKind("Queue")
        assertEquals(WizardState.Closed, rig.state.wizard)
    }

    @Test
    fun `the wizard does not open while the draft file cannot be read, so the file is never written over`() {
        rig.store.putUnreadable()
        rig.restart()
        vm.openWizard()
        assertEquals(WizardState.Closed, rig.state.wizard)
        assertEquals(emptyList(), rig.store.savedTexts)
    }
}
