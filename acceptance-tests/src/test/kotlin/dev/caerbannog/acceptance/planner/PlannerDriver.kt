package dev.caerbannog.acceptance.planner

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.BindingForm
import dev.caerbannog.core.planner.ExchangeForm
import dev.caerbannog.core.planner.FieldHint
import dev.caerbannog.core.planner.FieldProblem
import dev.caerbannog.core.planner.FormContext
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.NamingStyle
import dev.caerbannog.core.planner.PlanProblem
import dev.caerbannog.core.planner.PlanStep
import dev.caerbannog.core.planner.PlanValidation
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.QueueForm
import dev.caerbannog.core.planner.StepForm
import dev.caerbannog.core.planner.StepForms
import dev.caerbannog.core.planner.TopologyPlan
import dev.caerbannog.core.topology.BindingDestinationType

/**
 * M9-01: what the planner screen will do, in the glue, calling `core.planner` directly. M9-02 re-points it at `PlannerViewModel`.
 * It holds no rules of its own: every decision (problems, hints, texts, order, ids) comes from `core`.
 * `executor` is the one door to a broker the planner could ever use; "nothing was sent" means it recorded nothing.
 */
class PlannerDriver {
    val executor = FakeTopologyMigrationExecutor()

    var profile = "uat-eu"
    var vhost = "/"
    var configurePermission = false
    var controlLevel = TopologyControlLevel.FULL_CONTROL
    var migrationsFolder = ""
    var latestMigration = ""

    var plan = TopologyPlan()
    var style = NamingStyle.KEBAB
    var known = KnownTopology.None

    /** The wizard: the open form (null = closed) and what the last add or fill produced. */
    var form: StepForm? = null
    var lastHints: List<FieldHint> = emptyList()

    fun context() = FormContext(plan = plan, namingStyle = style, known = known)

    fun newPlan() {
        plan = TopologyPlan(vhost = vhost)
        form = null
        lastHints = emptyList()
    }

    fun openWizard() { form = null }

    /** Page 2 of the wizard. */
    fun fill(form: StepForm) {
        this.form = form
        lastHints = form.hints(context())
    }

    fun formProblems(): List<FieldProblem> = form?.problems(context()).orEmpty()

    /** "Next" is enabled when the form has no problem; a hint never disables it (O11). */
    fun nextEnabled(): Boolean = form != null && formProblems().isEmpty()

    fun summaryActions(): List<MigrationAction> = form?.actions(context()).orEmpty()
    fun summaryTitle(): String = PlannerTexts.summaryTitle(summaryActions().size)

    fun addToPlan() {
        val open = checkNotNull(form) { "no form is open in the wizard" }
        check(open.problems(context()).isEmpty()) { "Add to plan was reached with problems: ${open.problems(context())}" }
        plan = plan.append(open.actions(context()))
        form = null
    }

    fun cancelWizard() { form = null }

    /** The whole wizard in one go, for the steps that do not look at the pages. */
    fun addWithWizard(newForm: StepForm) {
        fill(newForm)
        addToPlan()
    }

    fun problems(): List<PlanProblem> = PlanValidation.problems(plan)
    fun blockingCount(): Int = problems().count { it.blocking }
    fun saveEnabled(): Boolean = blockingCount() == 0
    fun saveHint(): String? = if (blockingCount() == 0) null else PlannerTexts.saveBlocked(blockingCount())

    fun stepAt(position: Int): PlanStep = plan.steps[position - 1]
    fun positionOf(stepId: Int): Int = plan.steps.indexOfFirst { it.id == stepId } + 1
    fun titles(): List<String> = plan.steps.map { it.action.description }

    fun editStep(position: Int, change: (StepForm) -> StepForm) {
        val step = stepAt(position)
        val old = checkNotNull(StepForms.formOf(step.action)) { "step $position cannot be held by a form" }
        val updated = change(old)
        val actions = updated.actions(context())
        check(actions.size == 1) { "an edited form must build one action, it built ${actions.size}" }
        plan = plan.replace(step.id, actions.single())
    }

    fun moveUp(position: Int) { plan = plan.moveUp(stepAt(position).id) }
    fun moveDown(position: Int) { plan = plan.moveDown(stepAt(position).id) }
    fun removeByTitle(title: String) {
        val step = plan.steps.firstOrNull { it.action.description == title } ?: error("no step titled \"$title\" in ${titles()}")
        plan = plan.remove(step.id)
    }

    fun queueForm(name: String, type: String?) = if (type == null) QueueForm(name = name) else QueueForm(name = name, type = type)
    fun exchangeForm(name: String, type: String) = ExchangeForm(name = name, type = type)
    fun bindingForm(exchange: String, destination: String, routingKey: String) = BindingForm(
        exchange = exchange,
        destination = destination,
        destinationKind = BindingDestinationType.QUEUE,
        routingKey = routingKey,
    )
}
