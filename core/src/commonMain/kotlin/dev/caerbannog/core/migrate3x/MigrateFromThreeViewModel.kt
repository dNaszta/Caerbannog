package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrations.FOLDER_READ_ONLY_MESSAGE
import dev.caerbannog.core.migrations.FolderListing
import dev.caerbannog.core.migrations.MigrationFileRepository
import dev.caerbannog.core.migrations.MigrationFolderPorts
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.ActionsParseResult
import dev.caerbannog.core.migrations.MigrationValidationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MigrateFromThreeState {
    data object ChooseSource : MigrateFromThreeState
    data class Loading(val fileLabel: String) : MigrateFromThreeState

    /** [text] is one of the fixed texts, never file content. */
    data class Rejected(val fileLabel: String, val text: String) : MigrateFromThreeState

    /** [error] is `InvalidName.reason` as returned, or null. The field always starts empty: there is no default value. */
    data class NeedsVirtualHost(val fileLabel: String, val error: String?) : MigrateFromThreeState
    data class Report(val view: ReportView, val notice: ExportNotice?, val review: ReviewState) : MigrateFromThreeState

    /** M8-06c: the live read of [connectionName] runs. */
    data class LoadingLive(val connectionName: String) : MigrateFromThreeState

    /** M8-06c: the broker refused the export for this user; [text] is `LiveSourceTexts.CANNOT_EXPORT`. The screen offers the file import. */
    data class CannotExport(val connectionName: String, val text: String) : MigrateFromThreeState

    /** M8-06c: [text] is one of the fixed `LiveSourceTexts`, never a body, a URL or a credential. */
    data class LiveFailed(val connectionName: String, val text: String) : MigrateFromThreeState
}

/**
 * The Migrate from 3.x screen, file source. State in, events out. No gateway is injected, so nothing here can send a request to a broker.
 *
 * One [job] at a time (reading a file, analysing it, saving): a new event that starts work cancels the old one, and a result that arrives
 * after [cancelLoading], [cancelPrompt], [reset] or a newer file is dropped because its job was cancelled. The analysis is parsed and
 * analysed on [analysisDispatcher] so a large file does not block the UI thread. The held analysis lives in [holder], not here: the window
 * rebuilds this view model when its inputs change (for example a new connection) and the report must survive that.
 */
class MigrateFromThreeViewModel(
    private val loader: DefinitionsFileLoader,
    private val fileChooser: DefinitionsFileChooser,
    private val reportSaver: ReportFileSaver,
    private val holder: AnalysisHolder,
    private val folders: MigrationFolderPorts,
    private val scope: CoroutineScope,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** M8-06c: what may be read live now; the default is "nothing". Read only: it has no write method. */
    private val liveSource: LiveSourceProvider = LiveSourceProvider.None,
) {
    // Declared before `mutable`: the initial state reads them.
    private var job: Job? = null
    private var selected: Set<Severity> = Severity.entries.toSet()
    private var openGroups: Set<String> = emptySet()

    /** The open sheet's local edits; null when no sheet is open. Never a decision (closing the sheet drops it). */
    private var edit: SheetEdit? = null

    /** The Replace text typed per item, kept when the sheet is closed. Dropped with the analysis. */
    private val replaceDrafts: MutableMap<ReviewItem, String> = HashMap()
    private var generation: GenerationState = GenerationState.Idle

    /** The generated files of the last generation, kept only while its [GenerationState.Trouble] is shown (Try again, Export). */
    private var plan: SavePlan? = null

    /** Counts analyses and resets: a save that finishes after one may not touch the new state. */
    private var epoch: Int = 0

    /** The save job is apart from [job]: a report export or a new prompt must not cancel it half way. */
    private var generationJob: Job? = null

    private val mutable = MutableStateFlow(initialState())
    val state: StateFlow<MigrateFromThreeState> = mutable.asStateFlow()

    /** The per-virtual-host export that waits for a name, while the state is [MigrateFromThreeState.NeedsVirtualHost]. */
    private var pending: Pending? = null

    private class Pending(val label: String, val draft: UnassignedDefinitions)

    /** The files of one generation. [saved] is always a prefix of [generated]'s migrations: they are written in order and the first refusal stops. */
    private class SavePlan(val folder: String, val generated: GenerationResult.Generated, val saved: List<SavedFile>)

    // The decisions live in the held analysis, so a new window view model shows the same counts (ruling 12), with no sheet and an idle generation.
    private fun initialState(): MigrateFromThreeState =
        holder.held.value?.let { MigrateFromThreeState.Report(viewOf(it), null, ReviewViewBuilder.state(it.review, null, GenerationState.Idle)) }
            ?: MigrateFromThreeState.ChooseSource

    /** The kind the user chose in the picker. It falls back to File when the live connection goes, and stays File when it comes back. */
    private val chosenKind = MutableStateFlow(SourceKind.File)

    /** What the picker shows; follows the connection while the screen is open (the user may connect from the chip popup). */
    val picker: StateFlow<SourcePicker> = combine(liveSource.current, chosenKind, ::pickerOf)
        .stateIn(scope, SharingStarted.Eagerly, pickerOf(liveSource.current.value, chosenKind.value))

    init {
        scope.launch {
            liveSource.current.collect { source ->
                if (source == null) chosenKind.value = SourceKind.File
                rebuild()
            }
        }
    }

    /**
     * Selects the kind of the picker. From a refusal or a failure it also goes back to ChooseSource. Ignored while work runs, at the virtual
     * host question and with a report on screen (the report stays); Live is ignored while no live source is offered.
     */
    fun chooseSource(kind: SourceKind) {
        if (kind == SourceKind.Live && liveSource.current.value == null) return
        when (mutable.value) {
            MigrateFromThreeState.ChooseSource -> Unit
            is MigrateFromThreeState.Rejected, is MigrateFromThreeState.CannotExport, is MigrateFromThreeState.LiveFailed ->
                mutable.value = MigrateFromThreeState.ChooseSource
            else -> return
        }
        chosenKind.value = kind
    }

    /** Reads and analyses the held 3.x connection, GET requests only. Ignored while a live read runs and while no live source is offered. */
    fun analyseLive() {
        val source = liveSource.current.value ?: return
        if (mutable.value is MigrateFromThreeState.LoadingLive) return
        val name = source.connectionName
        job?.cancel()
        pending = null
        dropReviewLocals()
        mutable.value = MigrateFromThreeState.LoadingLive(name)
        job = scope.launch {
            val outcome = analyseLive(source, analysisDispatcher)
            ensureActive()
            when (outcome) {
                is LiveAnalysisOutcome.Analysed -> present(outcome.held)
                // The texts are the fixed ones of LiveSourceTexts: the broker's own words never reach the state.
                is LiveAnalysisOutcome.OfferFileImport -> mutable.value = MigrateFromThreeState.CannotExport(name, outcome.text)
                is LiveAnalysisOutcome.Unreachable -> mutable.value = MigrateFromThreeState.LiveFailed(name, LiveSourceTexts.unreachable(outcome.connectionName))
                is LiveAnalysisOutcome.Failed -> mutable.value = MigrateFromThreeState.LiveFailed(name, outcome.text)
            }
        }
    }

    private fun pickerOf(source: DefinitionsSource?, chosen: SourceKind): SourcePicker =
        if (source == null) SourcePicker(SourceKind.File, listOf(SourceOption.File, SourceOption.LiveDisabled(MigrateFromThreeTexts.LIVE_NOT_AVAILABLE)))
        else SourcePicker(chosen, listOf(SourceOption.File, SourceOption.Live(source.connectionName)))

    private fun viewOf(held: HeldAnalysis, filter: Set<Severity> = Severity.entries.toSet(), groups: Set<String> = emptySet()): ReportView =
        ReportViewBuilder.build(held, filter, groups, liveConnectionName = liveSource.current.value?.connectionName)

    /** Opens the file dialog; a cancelled dialog changes nothing. */
    fun chooseFile() {
        scope.launch {
            val path = fileChooser.choose() ?: return@launch
            open(path)
        }
    }

    /** Reads and analyses the file at [path]; shows Loading at once. A second file replaces the first. */
    fun open(path: String) {
        val label = fileLabel(path)
        job?.cancel()
        pending = null
        dropReviewLocals()
        mutable.value = MigrateFromThreeState.Loading(label)
        job = scope.launch { read(path, label) }
    }

    private suspend fun read(path: String, label: String) {
        when (val loaded = loader.load(path)) {
            is FileLoadResult.Loaded -> analyse(loaded.text, label)
            is FileLoadResult.TooLarge -> mutable.value = MigrateFromThreeState.Rejected(label, MigrateFromThreeTexts.FILE_TOO_LARGE)
            // The loader's reason may carry a path or system text: the screen shows a fixed text only.
            is FileLoadResult.Unreadable -> mutable.value = MigrateFromThreeState.Rejected(label, MigrateFromThreeTexts.FILE_UNREADABLE)
        }
    }

    private suspend fun analyse(text: String, label: String) {
        when (val parsed = withContext(analysisDispatcher) { DefinitionsParser.parse(text) }) {
            is DefinitionsParseResult.Parsed -> showReport(label, parsed.document)
            is DefinitionsParseResult.NeedsVirtualHost -> {
                pending = Pending(label, parsed.draft)
                mutable.value = MigrateFromThreeState.NeedsVirtualHost(label, null)
            }
            is DefinitionsParseResult.Rejected -> mutable.value = MigrateFromThreeState.Rejected(label, parsed.reason.message)
        }
    }

    private suspend fun showReport(label: String, document: DefinitionsDocument) {
        present(withContext(analysisDispatcher) { HeldAnalysis(label, document, CompatibilityAnalyzer.analyze(document)) })
    }

    /** The one step that shows an analysis, for a file and for a live read: holds it and starts with every severity shown. */
    private fun present(held: HeldAnalysis) {
        pending = null
        selected = Severity.entries.toSet()
        openGroups = emptySet()
        dropReviewLocals()
        holder.hold(held)
        mutable.value = MigrateFromThreeState.Report(viewOf(held), null, ReviewViewBuilder.state(held.review, null, GenerationState.Idle))
    }

    /** Answers the virtual host prompt. Trims the text, then asks the core; a refusal stays on the prompt. Ignored when no prompt is shown. */
    fun giveVirtualHost(text: String) {
        val waiting = pending ?: return
        if (mutable.value !is MigrateFromThreeState.NeedsVirtualHost) return
        job?.cancel()
        job = scope.launch {
            when (val result = withContext(analysisDispatcher) { waiting.draft.assignVirtualHost(text.trim()) }) {
                is VirtualHostAssignment.Assigned -> showReport(waiting.label, result.document)
                is VirtualHostAssignment.InvalidName -> mutable.value = MigrateFromThreeState.NeedsVirtualHost(waiting.label, result.reason)
            }
        }
    }

    /** Cancels the virtual host prompt (back to ChooseSource). Does nothing in any other state. */
    fun cancelPrompt() {
        if (mutable.value !is MigrateFromThreeState.NeedsVirtualHost) return
        job?.cancel()
        pending = null
        mutable.value = MigrateFromThreeState.ChooseSource
    }

    /** Cancels a running Loading or LoadingLive (the job, back to ChooseSource; a late result is ignored; a held report is not changed). */
    fun cancelLoading() {
        if (mutable.value !is MigrateFromThreeState.Loading && mutable.value !is MigrateFromThreeState.LoadingLive) return
        job?.cancel()
        mutable.value = MigrateFromThreeState.ChooseSource
    }

    /** The UI calls this after it copied [reportText] to the clipboard: the notice "Report copied as text". */
    fun reportCopied() = setNotice(ExportNotice(MigrateFromThreeTexts.REPORT_COPIED, failed = false))

    fun toggleSeverity(severity: Severity) {
        if (mutable.value !is MigrateFromThreeState.Report) return
        selected = if (severity in selected) selected - severity else selected + severity
        rebuild()
    }

    fun toggleGroup(key: String) {
        if (mutable.value !is MigrateFromThreeState.Report) return
        openGroups = if (key in openGroups) openGroups - key else openGroups + key
        rebuild()
    }

    /** The one place that rebuilds the report view and the review state, so every event ends the same way. */
    private fun rebuild() {
        val held = holder.held.value ?: return
        val view = viewOf(held, selected, openGroups)
        val review = ReviewViewBuilder.state(held.review, edit, generation)
        mutable.update { if (it is MigrateFromThreeState.Report) it.copy(view = view, review = review) else it }
    }

    /** Forgets the sheet, the drafts and the generation of the old analysis; a save still running will find its epoch gone. */
    private fun dropReviewLocals() {
        edit = null
        replaceDrafts.clear()
        generation = GenerationState.Idle
        plan = null
        epoch++
        generationJob?.cancel()
        generationJob = null
    }

    /** Hands the JSON export of the whole report to the saver and shows where it went. A cancelled dialog shows nothing. */
    fun exportReportJson() {
        val held = holder.held.value ?: return
        if (mutable.value !is MigrateFromThreeState.Report) return
        job?.cancel()
        job = scope.launch {
            val json = withContext(analysisDispatcher) { CompatibilityReportJson.encode(held.report) }
            when (val outcome = reportSaver.save(suggestedName(held.fileLabel), json)) {
                is SaveOutcome.Saved -> setNotice(ExportNotice(MigrateFromThreeTexts.reportSaved(outcome.path), failed = false))
                SaveOutcome.Cancelled -> Unit
                is SaveOutcome.Failed -> setNotice(ExportNotice("${MigrateFromThreeTexts.SAVE_FAILED_PREFIX}: ${outcome.reason}", failed = true))
            }
        }
    }

    /** The text export of the whole report (whatever the chips show); the UI puts it on the clipboard and then calls [reportCopied]. */
    suspend fun reportText(): String = withContext(analysisDispatcher) { holder.held.value?.let { CompatibilityReportText.render(it.report) }.orEmpty() }

    fun dismissNotice() = setNotice(null)

    /** Drops the held analysis and goes back to ChooseSource; work in flight is cancelled and its result ignored. */
    fun reset() {
        job?.cancel()
        pending = null
        selected = Severity.entries.toSet()
        openGroups = emptySet()
        dropReviewLocals()
        holder.clear()
        mutable.value = MigrateFromThreeState.ChooseSource
    }

    // ---- review: the sheet and the decisions. Every event is a no-op outside the report and ends with one rebuild(). ----

    private fun reportHeld(): HeldAnalysis? = if (mutable.value is MigrateFromThreeState.Report) holder.held.value else null

    /** The decisions are not touched while files are generated: the generation reads the review on another dispatcher. */
    private fun canDecide(): Boolean = generation != GenerationState.Saving

    /** Opens the sheet of [item], with the choices of its stored decision when it has one. Not a review item: nothing happens. */
    fun openItem(item: ReviewItem) {
        val review = reportHeld()?.review ?: return
        if (review.refusalOf(item, ItemDecision.Skip) != null) return
        edit = ReviewViewBuilder.editOf(review, item, review.items.indexOf(item) + 1)
        rebuild()
    }

    /** Closes the sheet. The edits of the sheet are dropped, a stored decision and a Replace draft are not. */
    fun closeItem() {
        if (reportHeld() == null) return
        edit = null
        rebuild()
    }

    /** [index] is the position in `[proposal] + alternatives` of the finding; an unknown finding or a position that does not exist changes nothing. */
    fun chooseAlternative(findingId: String, index: Int) {
        val review = reportHeld()?.review ?: return
        val current = edit ?: return
        val finding = review.findingsOf(current.item).firstOrNull { it.id == findingId } ?: return
        if (index !in 0..finding.alternatives.size) return
        edit = current.copy(chosen = if (index == 0) current.chosen - findingId else current.chosen + (findingId to index))
        rebuild()
    }

    fun setTick(ack: Acknowledgement, on: Boolean) {
        val review = reportHeld()?.review ?: return
        val current = edit ?: return
        if (ack !in requiredTicks(review, current)) return
        edit = current.copy(ticks = if (on) current.ticks + ack else current.ticks - ack)
        rebuild()
    }

    private fun requiredTicks(review: ConversionReview, current: SheetEdit): Set<Acknowledgement> =
        ProposalChoice.resolve(review.findingsOf(current.item), current.chosen).mapNotNull { it.proposal.acknowledgement }.toSet()

    /** Sends the sheet's choices to the core. On Decided the sheet closes; a refusal keeps it open and shows as the Accept block. */
    fun accept() {
        val review = reportHeld()?.review ?: return
        val current = edit ?: return
        if (current.replaceText != null || !canDecide()) return
        if (review.decide(current.item, ReviewViewBuilder.acceptOf(review, current)) == DecisionResult.Decided) edit = null
        rebuild()
    }

    fun skip() {
        val review = reportHeld()?.review ?: return
        val current = edit ?: return
        if (!canDecide()) return
        if (review.decide(current.item, ItemDecision.Skip) == DecisionResult.Decided) edit = null
        rebuild()
    }

    /** Puts a skipped item back as undecided. Any other item is left as it is. */
    fun unskip(item: ReviewItem) {
        val review = reportHeld()?.review ?: return
        if (!canDecide() || review.decisionOf(item) != ItemDecision.Skip) return
        review.undecide(item)
        rebuild()
    }

    /** Shows the Replace sheet: the draft of this item, else the actions the app would write now, as migration JSON. */
    fun startReplace() {
        val review = reportHeld()?.review ?: return
        val current = edit ?: return
        val text = replaceDrafts[current.item] ?: MigrationParser.actionsToJson(ReviewViewBuilder.prefill(review, current))
        edit = current.copy(replaceText = text, replaceError = null)
        rebuild()
    }

    /** Keeps the typed text as the draft of the item, so closing the sheet and reopening it does not lose it. */
    fun setReplaceText(text: String) {
        val current = edit ?: return
        if (reportHeld() == null) return
        replaceDrafts[current.item] = text
        if (current.replaceText != null) edit = current.copy(replaceText = text)
        rebuild()
    }

    /** Reads [text] as migration actions and sends them as a replacement; nothing is stored on any refusal and the text stays. */
    fun submitReplace(text: String) {
        val review = reportHeld()?.review ?: return
        val current = edit ?: return
        if (!canDecide()) return
        replaceDrafts[current.item] = text
        val refusal: String? = when (val parsed = MigrationParser.parseActions(text)) {
            is ActionsParseResult.Invalid -> MigrateFromThreeTexts.replaceRefused(parsed.reason)
            is ActionsParseResult.Parsed -> when (val result = review.decide(current.item, ItemDecision.Replace(parsed.actions))) {
                DecisionResult.Decided -> null
                is DecisionResult.Refused -> MigrateFromThreeTexts.replaceRefusalText(result.reason)
            }
        }
        edit = if (refusal == null) null else current.copy(replaceText = text, replaceError = refusal)
        rebuild()
    }

    /** Leaves the Replace sheet for the proposal; nothing is stored and the draft stays. */
    fun backToProposal() {
        val current = edit ?: return
        if (reportHeld() == null) return
        edit = current.copy(replaceText = null, replaceError = null)
        rebuild()
    }

    /** Opens the first undecided item, if any. */
    fun showNextUndecided() {
        val review = reportHeld()?.review ?: return
        review.items.firstOrNull { review.decisionOf(it) == null }?.let(::openItem)
    }

    // ---- generate and save: the migrations folder. The files are what `generate` returned, written one by one, never overwritten. ----

    /** Generates and saves once every item is decided. See `docs/tasks/M8-06b-review-generate-save.md` "Generate and save". */
    fun generate() {
        val held = reportHeld() ?: return
        if (generation == GenerationState.Saving || generation == GenerationState.ConfirmAgain) return
        begin(held, confirmed = false)
    }

    /** The answer Generate again to the question, or after a Trouble: a new generation with new versions. No other state accepts it. */
    fun generateAgain() {
        val held = reportHeld() ?: return
        if (generation != GenerationState.ConfirmAgain && generation !is GenerationState.Trouble) return
        begin(held, confirmed = true)
    }

    fun cancelGenerateAgain() {
        if (reportHeld() == null || generation != GenerationState.ConfirmAgain) return
        generation = GenerationState.Idle
        rebuild()
    }

    private fun begin(held: HeldAnalysis, confirmed: Boolean) {
        val review = held.review
        if (review.items.any { review.decisionOf(it) == null }) {
            generation = GenerationState.Idle
            showNextUndecided()
            rebuild()
            return
        }
        if (!confirmed && holder.saved.value) {
            generation = GenerationState.ConfirmAgain
            rebuild()
            return
        }
        launchSave { token -> folderFor(token)?.let { saveInto(held, it, token) } }
    }

    /** Asks the folder dialog, starting in the folder that is chosen now; a cancel changes nothing, a pick is stored and the flow restarts at the folder step. */
    fun chooseFolder() {
        val held = reportHeld() ?: return
        if (generation !is GenerationState.Trouble) return
        val token = epoch
        generationJob?.cancel()
        generationJob = scope.launch {
            val picked = folders.chooser.choose(folders.store.folder) ?: return@launch
            if (token != epoch) return@launch
            folders.store.folder = picked
            generation = GenerationState.Saving
            plan = null
            rebuild()
            saveInto(held, picked, token)
        }
    }

    /** Saves the files that were not saved yet, with the same versions. A version taken meanwhile is refused by the folder, never overwritten. */
    fun retrySave() {
        val held = reportHeld() ?: return
        val current = plan ?: return
        if (generation !is GenerationState.Trouble) return
        launchSave { token -> write(held, token, current.folder, current.generated, current.saved) }
    }

    private fun launchSave(block: suspend (token: Int) -> Unit) {
        generation = GenerationState.Saving
        plan = null
        rebuild()
        val token = epoch
        generationJob?.cancel()
        generationJob = scope.launch { block(token) }
    }

    /** The stored folder, else the dialog; null when the user cancelled (the Trouble is shown then) or the analysis changed meanwhile. */
    private suspend fun folderFor(token: Int): String? {
        folders.store.folder?.let { return it }
        val picked = folders.chooser.choose(null)
        if (token != epoch) return null
        if (picked == null) {
            finish(token, trouble(SaveProblem.NoFolderChosen))
            return null
        }
        folders.store.folder = picked
        return picked
    }

    private suspend fun saveInto(held: HeldAnalysis, folder: String, token: Int) {
        val repository = folders.repositoryFor(folder)
        val existing: Set<String> = when (val listing = repository.load()) {
            is FolderListing.Unavailable -> return finish(token, trouble(SaveProblem.Folder(escapeForText(listing.path), listing.problem.message)))
            is FolderListing.Listed -> {
                if (listing.readOnly) return finish(token, trouble(SaveProblem.Folder(escapeForText(folder), FOLDER_READ_ONLY_MESSAGE)))
                // An invalid file named like a version still occupies it: the folder refuses to overwrite it, so the numbering must pass it.
                (listing.files.map { it.version } + listing.invalid.mapNotNull { versionOfFileName(it.fileName) }).toSet()
            }
        }
        when (val generated = generateFiles(held, existing)) {
            null -> finish(token, trouble(SaveProblem.NoFreeVersion))
            is GenerationResult.Incomplete -> finish(token, GenerationState.Idle)
            is GenerationResult.Generated -> write(held, token, folder, generated, emptyList())
        }
    }

    private fun trouble(problem: SaveProblem): GenerationState.Trouble = GenerationState.Trouble(problem, emptyList(), emptyList())

    private fun versionOfFileName(fileName: String): String? =
        fileName.removeSuffix(".json").takeIf { it.isNotEmpty() && it != fileName && it.all { c -> c in '0'..'9' } }

    /** The core's generation on the analysis dispatcher; null when a version would pass 9999 (the one exception caught here). */
    private suspend fun generateFiles(held: HeldAnalysis, existing: Set<String>): GenerationResult? =
        withContext(analysisDispatcher) {
            try {
                held.review.generate(GenerationRequest(existing, held.fileLabel))
            } catch (_: IllegalStateException) {
                null
            }
        }

    /**
     * Writes the migrations after [alreadySaved] one by one through the repository. Runs to the end even when a new event cancels the job, and
     * records its result in the same block: a cancelled save never leaves half a state behind. Saved files are never deleted or rolled back.
     */
    private suspend fun write(held: HeldAnalysis, token: Int, folder: String, generated: GenerationResult.Generated, alreadySaved: List<SavedFile>) {
        val repository: MigrationFileRepository = folders.repositoryFor(folder)
        withContext(NonCancellable) {
            val saved = alreadySaved.toMutableList()
            var refused: SaveProblem.FileRefused? = null
            for (migration in generated.migrations.drop(alreadySaved.size)) {
                val version = migration.file.version
                when (val result = repository.importFile(migration.file.rawJson)) {
                    is MigrationValidationResult.Valid -> saved += GenerationReportBuilder.fileOf(migration, escapeForText(savedPath(folder, version)))
                    is MigrationValidationResult.Invalid -> {
                        refused = SaveProblem.FileRefused(version, result.reason)
                        break
                    }
                }
            }
            if (token != epoch) return@withContext
            if (saved.isNotEmpty()) holder.recordSaved()
            if (refused == null) {
                finish(token, GenerationState.Done(GenerationReportBuilder.report(How.Saved, saved, generated, held.document.virtualHosts.size)))
            } else {
                plan = SavePlan(folder, generated, saved)
                val unsaved = generated.migrations.drop(saved.size).map { GenerationReportBuilder.fileOf(it, "") }
                finish(token, GenerationState.Trouble(refused, saved, unsaved))
            }
        }
    }

    private fun finish(token: Int, result: GenerationState) {
        if (token != epoch) return
        generation = result
        rebuild()
    }

    /**
     * Saves the files that are not in the folder through the save dialog, one file per dialog, named `<version>.json`. Used from a folder problem
     * (nothing generated yet: numbered from 0001, the folder is not read) and from a partial save (the files that were not saved). Never
     * touches the migrations folder. A cancelled dialog stops quietly; a failure shows a notice.
     */
    fun exportFiles() {
        val held = reportHeld() ?: return
        val current = generation as? GenerationState.Trouble ?: return
        val token = epoch
        val kept = plan
        generationJob?.cancel()
        generationJob = scope.launch {
            val generated = kept?.generated ?: (generateFiles(held, emptySet()) as? GenerationResult.Generated) ?: return@launch
            val alreadySaved = kept?.saved.orEmpty()
            val exported = mutableListOf<SavedFile>()
            for (migration in generated.migrations.drop(alreadySaved.size)) {
                val name = "${migration.file.version}.json"
                when (val outcome = reportSaver.save(name, migration.file.rawJson)) {
                    is SaveOutcome.Saved -> exported += GenerationReportBuilder.fileOf(migration, escapeForText(outcome.path))
                    SaveOutcome.Cancelled -> return@launch
                    is SaveOutcome.Failed -> {
                        if (token == epoch) setNotice(ExportNotice("${MigrateFromThreeTexts.EXPORT_FAILED_PREFIX}: ${outcome.reason}", failed = true))
                        return@launch
                    }
                }
            }
            if (token != epoch) return@launch
            val notes = buildList {
                if (kept == null && current.problem is SaveProblem.Folder) add(MigrateFromThreeTexts.NUMBERING_FROM_ONE)
                if (alreadySaved.isNotEmpty()) add("Already saved in the migrations folder: ${alreadySaved.joinToString(", ") { it.version }}.")
            }
            plan = null
            finish(token, GenerationState.Done(GenerationReportBuilder.report(How.Exported, exported, generated, held.document.virtualHosts.size, notes)))
        }
    }

    /** Closes the result or the problem panel; the decisions and the question for a second generation stay. */
    fun dismissResult() {
        if (reportHeld() == null) return
        if (generation is GenerationState.Done || generation is GenerationState.Trouble) {
            generation = GenerationState.Idle
            plan = null
            rebuild()
        }
    }

    private fun setNotice(notice: ExportNotice?) {
        mutable.update { if (it is MigrateFromThreeState.Report) it.copy(notice = notice) else it }
    }

    private fun fileLabel(path: String): String = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

    private fun suggestedName(label: String): String = "${label.removeSuffix(".json")}-compatibility-report.json"
}
