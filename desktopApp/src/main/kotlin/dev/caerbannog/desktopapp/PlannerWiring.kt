package dev.caerbannog.desktopapp

import dev.caerbannog.core.migrations.FolderOpener
import dev.caerbannog.core.planner.NamingStyle
import dev.caerbannog.core.planner.PlannerContext
import dev.caerbannog.core.planner.PlannerEntry
import dev.caerbannog.core.planner.PlannerViewModel
import dev.caerbannog.core.planner.ReviewView
import dev.caerbannog.core.planner.SavedPlan
import dev.caerbannog.core.planner.StepForm
import dev.caerbannog.ui.planner.PlannerEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What the planner is built from. The planner takes the same inputs as the Migrations screen (one connection, one set of folder ports), so
 * the two screens cannot disagree about the folder, the level or the permission. Not connected is a state of the planner (it plans and saves),
 * never a lock and never "Read-only": the level and permission are neutral then, so the banner says "Not connected".
 */
fun plannerContextOf(inputs: MigrationsInputs): PlannerContext {
    val notConnected = inputs.connectionName.isBlank() || inputs.unavailableReason == NOT_CONNECTED
    if (notConnected) {
        // The defaults of PlannerContext are the neutral level and permission, so "Read-only" is never said about no connection.
        return PlannerContext(connectionName = null, vhost = inputs.vhost)
    }
    return PlannerContext(
        connectionName = inputs.connectionName,
        vhost = inputs.vhost,
        controlLevel = inputs.controlLevel,
        hasConfigurePermission = inputs.hasConfigurePermission,
        unavailableReason = inputs.unavailableReason,
    )
}

/**
 * The scope of a planner view model: `Dispatchers.IO` limited to one thread, with a [SupervisorJob]. The draft store does blocking file I/O
 * under a lock, which must never run on the UI dispatcher; one thread keeps the calls in order, and a failing call does not end the scope.
 */
fun newPlannerScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

/** The planner view model for [inputs]: the draft store of the app and the same folder ports as the Migrations screen. */
fun AppComposition.createPlannerViewModel(inputs: MigrationsInputs, scope: CoroutineScope = newPlannerScope()): PlannerViewModel =
    PlannerViewModel(
        draftStore = planDraftStore,
        folderPorts = migrationFolderPorts,
        settings = appSettings,
        context = plannerContextOf(inputs),
        scope = scope,
    )

/**
 * Follows [viewModel] for the navigation: a saved plan closes the planner and opens Migrations with that version waiting; a confirmed
 * Dismiss closes the planner. Each is reported once, however often the state is published.
 */
fun AppComposition.followPlanner(viewModel: PlannerViewModel, scope: CoroutineScope): Job = scope.launch {
    var reported: SavedPlan? = null
    var closed = false
    viewModel.state.collect { state ->
        val saved = state.saved
        if (saved != null && saved != reported) {
            reported = saved
            navigation.planSaved(saved)
        }
        if (state.dismissed && !closed) {
            closed = true
            navigation.closePlanner()
        }
    }
}

/** What the Migrations screen offers as its way into the planner, from the draft file (blocking file read: call it off the UI thread). */
suspend fun AppComposition.plannerEntry(): PlannerEntry = PlannerEntry.of(planDraftStore.load())

/** Maps the planner screen's events onto the [PlannerViewModel] (the screen knows no view model); "Show folder" goes through [folderOpener]. */
class PlannerEventsAdapter(
    private val viewModel: PlannerViewModel,
    private val folderOpener: FolderOpener = DesktopFolderOpener(),
) : PlannerEvents {
    override fun openWizard() = viewModel.openWizard()
    override fun chooseKind(kind: String) = viewModel.chooseKind(kind)
    override fun updateForm(form: StepForm) = viewModel.updateForm(form)
    override fun next() = viewModel.next()
    override fun back() = viewModel.back()
    override fun cancelWizard() = viewModel.cancelWizard()
    override fun addToPlan() = viewModel.addToPlan()
    override fun selectStep(id: Int) = viewModel.selectStep(id)
    override fun editStep(id: Int) = viewModel.editStep(id)
    override fun removeStep(id: Int) = viewModel.removeStep(id)
    override fun moveStepUp(id: Int) = viewModel.moveStepUp(id)
    override fun moveStepDown(id: Int) = viewModel.moveStepDown(id)
    override fun setDescription(text: String) = viewModel.setDescription(text)
    override fun setVirtualHost(vhost: String) = viewModel.setVirtualHost(vhost)
    override fun setNamingStyle(style: NamingStyle) = viewModel.setNamingStyle(style)
    override fun selectReviewView(view: ReviewView) = viewModel.selectReviewView(view)
    override fun chooseFolder() = viewModel.chooseFolder()
    override fun saveAsMigration() = viewModel.saveAsMigration()
    override fun dismiss() = viewModel.dismiss()
    override fun startNewPlan() = viewModel.startNewPlan()
    override fun confirm() = viewModel.confirm()
    override fun cancelQuestion() = viewModel.cancelQuestion()
    override fun retryDraft() = viewModel.retryDraft()
    override fun flush() = viewModel.flush()
    override fun showFolder(path: String) = folderOpener.show(path)
}
