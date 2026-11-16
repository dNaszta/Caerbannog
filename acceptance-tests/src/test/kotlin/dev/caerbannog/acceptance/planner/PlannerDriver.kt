package dev.caerbannog.acceptance.planner

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolder
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import dev.caerbannog.core.planner.BindingForm
import dev.caerbannog.core.planner.ExchangeForm
import dev.caerbannog.core.planner.FieldHint
import dev.caerbannog.core.planner.FieldProblem
import dev.caerbannog.core.planner.FormContext
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.NamingStyle
import dev.caerbannog.core.planner.PlanProblem
import dev.caerbannog.core.planner.PlanStep
import dev.caerbannog.core.planner.PlannerContext
import dev.caerbannog.core.planner.PlannerEntry
import dev.caerbannog.core.planner.PlannerQuestion
import dev.caerbannog.core.planner.PlannerState
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.PlannerViewModel
import dev.caerbannog.core.planner.QueueForm
import dev.caerbannog.core.planner.SaveGate
import dev.caerbannog.core.planner.StepForm
import dev.caerbannog.core.planner.TopologyPlan
import dev.caerbannog.core.planner.WizardState
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import dev.caerbannog.core.planner.fakes.FakePlanDraftStore
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * What the planner screen will do, in the glue, calling [PlannerViewModel] (M9-02 re-pointed it from the `core.planner` functions of M9-01).
 * It holds no rules of its own: every decision (problems, hints, texts, order, ids, the save gate, the banner) comes from `core`.
 *
 * The view model is built on first use from the fields below (the window rebuilds it when its context changes, so a field that changes
 * after that rebuilds it too). Whatever must outlive it is in the fakes, which a "restart" keeps: [store] (the draft file), [settings] and
 * [folders]. `executor` is the one door to a broker the planner could ever use; "nothing was sent" means it recorded nothing.
 */
class PlannerDriver {
    val executor = FakeTopologyMigrationExecutor()
    val store = FakePlanDraftStore()
    val settings = FakeAppSettings()
    val folders = FakeMigrationFolders(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    var profile = "uat-eu"
    var vhost = "/"
    var configurePermission = false

    /** Not connected: the planner is built with no connection name (mockup 12e). */
    var connected = true

    /** Why the connection cannot run migrations (a 3.x broker), as its gateway reports it; null for a 4.x broker. */
    var unavailableReason: String? = null
    var controlLevel = TopologyControlLevel.FULL_CONTROL
    var migrationsFolder = ""

    /** M9-05: what the Migrations screen applies with; the recording fake unless a scenario puts the real executor over the honest management API. */
    var migrationExecutor: dev.caerbannog.core.migrations.TopologyMigrationExecutor? = null
    var latestMigration = ""

    /** What the broker has; a change rebuilds the view model, as a new catalog read would. */
    var known = KnownTopology.None
        set(value) { field = value; restart() }

    /** False: the broker answers nothing the planner can use (the read gives null), as for a virtual host the user cannot see. */
    var brokerReadable = true

    /** The one read of the broker's names (M9-04): a fake that records what it was asked. Null when not connected, as the app gives no reader then. */
    val reader: FakeKnownTopologyReader? get() = currentReader

    private var currentReader: FakeKnownTopologyReader? = null

    /** The naming style on this machine (before the view model exists; the planner changes it with [chooseStyle]). */
    var style: NamingStyle
        get() = settings.plannerNamingStyle
        set(value) { settings.plannerNamingStyle = value; restart() }

    /** Every question the view model asked, in order (to show that an empty plan is dismissed without one). */
    val questions = mutableListOf<PlannerQuestion>()

    private var current: PlannerViewModel? = null
    val vm: PlannerViewModel
        get() = current ?: PlannerViewModel(store, folders.ports, settings, PlannerContext(
            connectionName = profile.takeIf { connected },
            unavailableReason = unavailableReason,
            vhost = vhost,
            controlLevel = controlLevel,
            hasConfigurePermission = configurePermission,
        ), scope, descriptionDebounceMillis = 0, reader = readerForNewViewModel()).also { created ->
            current = created
            scope.launch { created.state.collect { s -> s.question?.let { q -> if (questions.lastOrNull() != q) questions += q } } }
        }

    private fun readerForNewViewModel(): FakeKnownTopologyReader? =
        (if (connected && unavailableReason == null) FakeKnownTopologyReader(known.takeIf { brokerReadable }) else null).also { currentReader = it }

    val state: PlannerState get() = vm.state.value

    /** The app is closed and opened again: a new view model on the same draft file, settings and folders. */
    fun restart() { current = null }

    fun close() = scope.cancel()

    // ---- the folder ----

    val migrationFolder: FakeMigrationFolder get() = folders.folder(migrationsFolder)

    fun chooseMigrationsFolder(path: String, latest: String) {
        migrationsFolder = path
        latestMigration = latest
        migrationFolder.allowExport().addMigration(latest)
        folders.store.folder = path
    }

    // ---- the Migrations screen (the existing one, unchanged) ----

    fun migrationsScreen(): MigrationsViewModel = MigrationsViewModel(
        fileRepository = null,
        runner = MigrationRunner(folders.history, migrationExecutor ?: executor),
        profileName = profile,
        vhost = vhost,
        hasConfigurePermission = configurePermission,
        controlLevel = controlLevel,
        folderPorts = folders.ports,
        scope = scope,
    )

    /** The button of the Migrations screen, from what the draft store answers. */
    fun entry(): PlannerEntry = PlannerEntry.of(runBlocking { store.load() })

    // ---- the plan ----

    val plan: TopologyPlan get() = state.plan

    /** The wizard: the open form (null = closed or not on a form) and what the last fill produced. */
    var lastHints: List<FieldHint> = emptyList()

    val form: StepForm? get() = when (val w = state.wizard) {
        is WizardState.Details -> w.form
        is WizardState.Summary -> w.form
        else -> null
    }

    fun context() = FormContext(plan = plan, namingStyle = state.namingStyle, known = known)

    fun newPlan() { restart(); vm }

    fun openWizard() = vm.openWizard()

    /** Page 2 of the wizard: choose the kind of [form] and fill it. */
    fun fill(form: StepForm) {
        vm.openWizard()
        vm.chooseKind(kindOf(form))
        vm.updateForm(form)
        lastHints = (state.wizard as? WizardState.Details)?.hints.orEmpty()
    }

    /** "Next": the summary page, when the form has no problem. */
    fun next() = vm.next()

    fun formProblems(): List<FieldProblem> = (state.wizard as? WizardState.Details)?.problems.orEmpty()

    /** "Next" is enabled when the form has no problem; a hint never disables it (O11). */
    fun nextEnabled(): Boolean = (state.wizard as? WizardState.Details)?.nextEnabled == true

    fun summaryActions(): List<MigrationAction> = (state.wizard as? WizardState.Summary)?.actions.orEmpty()
    fun summaryTitle(): String = PlannerTexts.summaryTitle(summaryActions().size)

    fun addToPlan() = vm.addToPlan()

    fun cancelWizard() = vm.cancelWizard()

    /** From the form to the end, as the "Next" and "Add to plan" buttons do. */
    fun finishWizard() { vm.next(); vm.addToPlan() }

    /** The whole wizard in one go, for the steps that do not look at the pages. */
    fun addWithWizard(newForm: StepForm) {
        fill(newForm)
        finishWizard()
    }

    fun problems(): List<PlanProblem> = state.problems
    fun blockingCount(): Int = problems().count { it.blocking }

    fun saveGate(): SaveGate = state.saveGate
    fun saveEnabled(): Boolean = saveGate() == SaveGate.Enabled
    fun saveHint(): String? = (saveGate() as? SaveGate.Disabled)?.reason

    fun stepAt(position: Int): PlanStep = plan.steps[position - 1]
    fun titles(): List<String> = plan.steps.map { it.action.description }

    fun editStep(position: Int, change: (StepForm) -> StepForm) {
        vm.editStep(stepAt(position).id)
        val open = checkNotNull(form) { "step $position cannot be held by a form" }
        vm.updateForm(change(open))
        finishWizard()
    }

    fun moveUp(position: Int) = vm.moveStepUp(stepAt(position).id)
    fun moveDown(position: Int) = vm.moveStepDown(stepAt(position).id)
    fun removeByTitle(title: String) {
        val step = plan.steps.firstOrNull { it.action.description == title } ?: error("no step titled \"$title\" in ${titles()}")
        vm.removeStep(step.id)
    }

    fun chooseStyle(style: NamingStyle) = vm.setNamingStyle(style)

    // ---- forms ----

    fun queueForm(name: String, type: String?) = if (type == null) QueueForm(name = name) else QueueForm(name = name, type = type)
    fun exchangeForm(name: String, type: String) = ExchangeForm(name = name, type = type)
    fun bindingForm(exchange: String, destination: String, routingKey: String) = BindingForm(
        exchange = exchange,
        destination = destination,
        destinationKind = BindingDestinationType.QUEUE,
        routingKey = routingKey,
    )

    private fun kindOf(form: StepForm): String = when (form) {
        is QueueForm -> "Queue"
        is ExchangeForm -> "Exchange"
        is BindingForm -> "Binding"
    }
}
