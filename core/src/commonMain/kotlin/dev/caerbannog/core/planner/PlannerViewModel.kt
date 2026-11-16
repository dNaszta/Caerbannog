package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.MigrationFolderPorts
import dev.caerbannog.core.migrations.MigrationFolderState
import dev.caerbannog.core.migrations.MigrationWriteException
import dev.caerbannog.core.migrations.folderStateOf
import dev.caerbannog.core.streams.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Topology planner without a screen (spec "Topology planner (M9)"): the three wizard pages, the step list, the naming style, the one
 * draft of this machine and "Save as migration". It is a front end on migration files (A7): the only things it writes are the draft, through
 * [PlanDraftStore], and a new migration file, through the repository of the folder in use. It has no executor, no publisher and no secret.
 *
 * State in, events out: one [state], and every public method hops onto [scope] (so the fields below are read and written there only, and
 * [state] is published from there). Within the scope one [Mutex] makes each call whole, because a call can suspend in the draft store or the
 * folder; the effect of a call is visible in [state] after the scope has run it, not on return.
 *
 * The planner is locked (no wizard, no change, no draft write) while the connection cannot run migrations or the draft file cannot be read:
 * a plan typed over an unreadable file would replace it. The step ids the view model keeps (selection, the step being edited) are live in
 * the current plan: [TopologyPlan.append] gives a freed id to the next step, so an id is dropped the moment its step is removed.
 */
class PlannerViewModel(
    private val draftStore: PlanDraftStore,
    private val folderPorts: MigrationFolderPorts,
    private val settings: AppSettings,
    private val context: PlannerContext,
    private val scope: CoroutineScope,
    /** Quiet time before a typed description is written to the draft file; 0 writes at once. */
    private val descriptionDebounceMillis: Long = DESCRIPTION_DEBOUNCE_MILLIS,
) {
    /** The pages of the wizard; the screen sees [WizardState], derived from this and the current plan. */
    private sealed interface Page {
        data object Kinds : Page
        data class Form(val form: StepForm, val editing: Int?) : Page
        data class Summary(val form: StepForm, val editing: Int?) : Page
    }

    private val lock = Mutex()

    private var plan: TopologyPlan = TopologyPlan(vhost = context.vhost)
    private var page: Page? = null
    private var selected: Int? = null
    private var namingStyle: NamingStyle = settings.plannerNamingStyle
    private var folder: MigrationFolderState = MigrationFolderState.ChooseFolder
    private var unreadable: DraftUnreadable? = null
    private var question: PlannerQuestion? = null
    private var saved: SavedPlan? = null
    private var error: String? = null
    private var dismissed = false
    private var loaded = false

    /** True while the description in [plan] is newer than the draft file; [debounce] is the timer that writes it. */
    private var descriptionPending = false
    private var debounce: Job? = null

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<PlannerState> = _state.asStateFlow()

    init {
        // The folder collector first, so a folder chosen on the Migrations screen while the draft loads is not missed.
        scope.launch {
            folderPorts.store.changes.collect {
                lock.withLock {
                    readFolder()
                    publish()
                }
            }
        }
        act(allowedWhenLocked = true) {
            readDraft()
            readFolder()
            loaded = true
        }
    }

    // ---- the wizard ----

    fun openWizard() = act {
        question = null
        page = Page.Kinds
    }

    /** Page 1 to page 2 for [kind] (one of [PlannerTexts.WIZARD_KINDS]); a kind that has no form yet leaves the wizard where it is. */
    fun chooseKind(kind: String) = act {
        if (page != Page.Kinds) return@act
        val blank = when (kind) {
            "Queue" -> QueueForm(name = "")
            "Exchange" -> ExchangeForm(name = "")
            "Binding" -> BindingForm(exchange = "", destination = "")
            else -> return@act
        }
        page = Page.Form(blank, editing = null)
    }

    fun updateForm(form: StepForm) = act {
        val current = page as? Page.Form ?: return@act
        page = current.copy(form = form)
    }

    /** Page 2 to page 3; refused while the form has a problem. */
    fun next() = act {
        val current = page as? Page.Form ?: return@act
        if (current.form.problems(formContext()).isEmpty()) page = Page.Summary(current.form, current.editing)
    }

    fun back() = act {
        page = when (val current = page) {
            is Page.Summary -> Page.Form(current.form, current.editing)
            is Page.Form -> if (current.editing == null) Page.Kinds else null
            Page.Kinds, null -> null
        }
    }

    fun cancelWizard() = act(allowedWhenLocked = true) { page = null }

    /** Adds the summary's steps at the end, or replaces the step being edited in place; the wizard closes. */
    fun addToPlan() = act {
        val current = page as? Page.Summary ?: return@act
        val actions = current.form.actions(formContext())
        if (actions.isEmpty()) return@act
        val editing = current.editing
        val changed = when {
            editing == null -> plan.append(actions)
            plan.steps.none { it.id == editing } -> null
            else -> actions.singleOrNull()?.let { plan.replace(editing, it) }
        }
        page = null
        if (changed != null) changePlan(changed, refreshFolder = true)
    }

    // ---- the step list ----

    fun selectStep(id: Int) = act(allowedWhenLocked = true) { selected = id.takeIf { plan.steps.any { step -> step.id == it } } }

    /** Opens the wizard on the form of the step; a step no form can hold is not opened. */
    fun editStep(id: Int) = act {
        val step = plan.steps.firstOrNull { it.id == id } ?: return@act
        val form = StepForms.formOf(step.action) ?: return@act
        question = null
        selected = id
        page = Page.Form(form, editing = id)
    }

    fun removeStep(id: Int) = act { changePlan(plan.remove(id), refreshFolder = true) }

    fun moveStepUp(id: Int) = act { changePlan(plan.moveUp(id), refreshFolder = true) }

    fun moveStepDown(id: Int) = act { changePlan(plan.moveDown(id), refreshFolder = true) }

    // ---- the plan header and the style ----

    /**
     * The text is in the state at once; the draft file follows after [descriptionDebounceMillis] of quiet (a keystroke would otherwise
     * rewrite the file each time). Save, Dismiss, [flush] and any other change of the plan write it sooner.
     */
    fun setDescription(text: String) = act {
        if (descriptionDebounceMillis <= 0) {
            changePlan(plan.withDescription(text))
        } else {
            changePlan(plan.withDescription(text), writeDraft = false)
            descriptionPending = true
            debounce?.cancel()
            debounce = scope.launch {
                delay(descriptionDebounceMillis)
                lock.withLock {
                    writePending()
                    publish()
                }
            }
        }
    }

    /** Writes a description that is still waiting for its quiet time. The screen calls it when the user leaves. */
    fun flush() = act(allowedWhenLocked = true) { writePending() }

    fun setVirtualHost(vhost: String) = act { changePlan(plan.withVhost(vhost)) }

    /** The style is per machine and advice only: nothing in the plan or the draft is renamed or written. */
    fun setNamingStyle(style: NamingStyle) = act(allowedWhenLocked = true) {
        namingStyle = style
        settings.plannerNamingStyle = style
    }

    // ---- the folder ----

    /** Asks for a folder (the dialog is the Migrations screen's), remembers it in the shared store and reads it. */
    fun chooseFolder() {
        scope.launch {
            val picked = folderPorts.chooser.choose(folderPorts.store.folder) ?: return@launch
            lock.withLock {
                folderPorts.store.folder = picked
                readFolder()
                publish()
            }
        }
    }

    // ---- save and dismiss ----

    /** Writes the plan as the next migration of the folder in use. Refused, and nothing is asked of the folder, unless [PlannerState.saveGate] is enabled. */
    fun saveAsMigration() = act(allowedWhenLocked = true) {
        readFolder()
        if (gate() != SaveGate.Enabled) return@act
        val repository = folderPorts.store.folder?.let(folderPorts.repositoryFor) ?: return@act
        error = null
        val file = try {
            repository.exportTopology(repository.nextVersion(), plan.description, plan.steps.map { it.action })
        } catch (_: MigrationWriteException) {
            error = PlannerTexts.FILE_NOT_WRITTEN
            return@act
        }
        draftStore.clear()
        resetPlan()
        saved = SavedPlan(version = file.version, fileName = "${file.version}.json")
    }

    /** With a step the plan asks first; an empty plan goes at once. A locked planner just leaves: it never deletes a file it could not read. */
    fun dismiss() = act(allowedWhenLocked = true) {
        when {
            locked() -> dismissed = true
            plan.steps.isNotEmpty() -> question = PlannerQuestion.Dismiss(plan.steps.size)
            else -> discard()
        }
    }

    /** "Start a new plan" for an unreadable draft: asks first, with the real name the file will get. */
    fun startNewPlan() = act(allowedWhenLocked = true) {
        if (unreadable == null || context.unavailableReason != null) return@act
        question = PlannerQuestion.StartNewPlan(draftStore.asideFileName())
    }

    fun confirm() = act(allowedWhenLocked = true) {
        when (question) {
            null -> Unit
            is PlannerQuestion.Dismiss -> if (locked()) question = null else discard()
            is PlannerQuestion.StartNewPlan -> moveDraftAside()
        }
    }

    fun cancelQuestion() = act(allowedWhenLocked = true) { question = null }

    /** Reads the unreadable draft file again (the user may have fixed it by hand). */
    fun retryDraft() = act(allowedWhenLocked = true) {
        if (unreadable != null) {
            error = null
            readDraft()
        }
    }

    // ---- inside the scope ----

    /** One whole call on the scope: skipped when the planner is locked unless [allowedWhenLocked], then published once. */
    private fun act(allowedWhenLocked: Boolean = false, block: suspend () -> Unit) {
        scope.launch {
            lock.withLock {
                if (allowedWhenLocked || !locked()) block()
                publish()
            }
        }
    }

    private fun locked(): Boolean = context.unavailableReason != null || unreadable != null

    private fun formContext() = FormContext(plan = plan, namingStyle = namingStyle, known = context.known)

    /**
     * The one place the plan changes: the notice of a saved plan and any waiting question belong to the old plan, and ids that are gone are
     * dropped. [refreshFolder] reads the folder again, so the save gate does not rest on a listing from before the folder changed under us;
     * a keystroke in the description does not ask for it, because a listing reads every migration file.
     */
    private suspend fun changePlan(changed: TopologyPlan, refreshFolder: Boolean = false, writeDraft: Boolean = true) {
        if (changed == plan) return
        plan = changed
        question = null
        saved = null
        error = null
        val ids = plan.steps.map { it.id }
        if (selected !in ids) selected = null
        val editing = when (val p = page) {
            is Page.Form -> p.editing
            is Page.Summary -> p.editing
            else -> null
        }
        if (editing != null && editing !in ids) page = null
        if (writeDraft) persist()
        if (refreshFolder) readFolder()
    }

    /** Never writes while locked: an unreadable file must stay as it is. */
    private suspend fun writePending() {
        if (descriptionPending && !locked()) persist()
        descriptionPending = false
    }

    private fun dropPending() {
        descriptionPending = false
        debounce?.cancel()
        debounce = null
    }

    /** A plan with a step or a description is kept as the draft; an empty one clears it. */
    private suspend fun persist() {
        descriptionPending = false
        val kept = plan.steps.isNotEmpty() || plan.description.isNotEmpty()
        if (kept) {
            if (!draftStore.save(plan)) error = PlannerTexts.DRAFT_NOT_SAVED
        } else {
            draftStore.clear()
        }
    }

    private suspend fun discard() {
        draftStore.clear()
        resetPlan()
        dismissed = true
    }

    private fun resetPlan() {
        dropPending()
        plan = TopologyPlan(vhost = context.vhost)
        page = null
        selected = null
        question = null
        saved = null
        error = null
    }

    private suspend fun moveDraftAside() {
        question = null
        when (draftStore.moveAside()) {
            is DraftMoveResult.Moved -> {
                unreadable = null
                resetPlan()
            }
            is DraftMoveResult.Failed -> error = PlannerTexts.DRAFT_NOT_MOVED
        }
    }

    private suspend fun readDraft() {
        page = null
        selected = null
        question = null
        when (val result = draftStore.load()) {
            DraftLoad.None -> {
                unreadable = null
                plan = TopologyPlan(vhost = context.vhost)
            }
            is DraftLoad.Loaded -> {
                unreadable = null
                // The vhost of a draft without steps is not kept: it reads as the connection's.
                plan = if (result.plan.steps.isEmpty()) result.plan.withVhost(context.vhost) else result.plan
            }
            is DraftLoad.Unreadable -> {
                unreadable = DraftUnreadable(result.path, result.reason)
                plan = TopologyPlan(vhost = context.vhost)
            }
        }
    }

    private suspend fun readFolder() {
        val path = folderPorts.store.folder
        folder = folderStateOf(path?.let { folderPorts.repositoryFor(it).load() }, path)
    }

    private fun gate(): SaveGate {
        val blocking = PlanValidation.problems(plan).count { it.blocking }
        val reason = when {
            context.unavailableReason != null -> context.unavailableReason
            plan.steps.isEmpty() -> PlannerTexts.ADD_A_STEP_FIRST
            blocking > 0 -> PlannerTexts.saveBlocked(blocking)
            else -> when (val f = folder) {
                MigrationFolderState.ChooseFolder -> PlannerTexts.NO_FOLDER
                is MigrationFolderState.FolderUnavailable -> f.reason
                is MigrationFolderState.FolderReadOnly -> f.reason
                is MigrationFolderState.Ready -> null
            }
        }
        return if (reason == null) SaveGate.Enabled else SaveGate.Disabled(reason)
    }

    /** The first banner that applies, in the order of the mockup (states 12a to 12g). */
    private fun banner(): PlannerBanner? {
        val connection = context.connectionName
        return when {
            context.unavailableReason != null -> PlannerBanner.NotOffered(context.unavailableReason)
            unreadable != null -> unreadable?.let { PlannerBanner.DraftUnreadable(it.path, it.reason) }
            context.controlLevel == TopologyControlLevel.READ_ONLY -> PlannerBanner.ReadOnlyLevel
            !context.hasConfigurePermission -> PlannerBanner.NoConfigurePermission(connection)
            connection == null -> PlannerBanner.NotConnected
            else -> folderBanner() ?: if (plan.vhost != context.vhost) PlannerBanner.VhostMismatch(plan.vhost, connection, context.vhost) else null
        }
    }

    private fun folderBanner(): PlannerBanner? = when (val f = folder) {
        MigrationFolderState.ChooseFolder -> PlannerBanner.FolderProblem(PlannerTexts.NO_FOLDER_BANNER, null)
        is MigrationFolderState.FolderUnavailable -> PlannerBanner.FolderProblem(f.reason, f.path)
        is MigrationFolderState.FolderReadOnly -> PlannerBanner.FolderProblem(f.reason, f.path)
        is MigrationFolderState.Ready -> null
    }

    private fun wizardState(): WizardState = when (val p = page) {
        null -> WizardState.Closed
        Page.Kinds -> WizardState.Kind
        is Page.Form -> {
            val ctx = formContext()
            detailsOf(p.form, p.editing, ctx)
        }
        is Page.Summary -> WizardState.Summary(p.form, p.form.actions(formContext()), p.editing)
    }

    private fun detailsOf(form: StepForm, editing: Int?, ctx: FormContext): WizardState.Details {
        val problems = form.problems(ctx)
        val binding = form as? BindingForm
        return WizardState.Details(
            form = form,
            problems = problems,
            hints = form.hints(ctx),
            editingStepId = editing,
            pageMessage = if (binding != null) problems.firstOrNull()?.message else null,
            exchangeSuggestions = if (binding != null) ctx.exchangeSuggestions() else emptyList(),
            destinationSuggestions = binding?.let { ctx.destinationSuggestions(it.destinationKind) }.orEmpty(),
            asksRoutingKey = binding?.let { ctx.asksRoutingKey(it.exchange) } ?: true,
        )
    }

    private fun snapshot(): PlannerState = PlannerState(
        plan = plan,
        wizard = if (locked()) WizardState.Closed else wizardState(),
        selectedStepId = selected,
        namingStyle = namingStyle,
        problems = PlanValidation.problems(plan),
        saveGate = gate(),
        banner = banner(),
        draftUnreadable = unreadable,
        question = question,
        saved = saved,
        errorMessage = error,
        dismissed = dismissed,
        isLoaded = loaded,
    )

    private fun publish() {
        _state.value = snapshot()
    }

    companion object {
        /** About half a second of quiet before a typed description is written to the draft file. */
        const val DESCRIPTION_DEBOUNCE_MILLIS: Long = 500
    }
}
