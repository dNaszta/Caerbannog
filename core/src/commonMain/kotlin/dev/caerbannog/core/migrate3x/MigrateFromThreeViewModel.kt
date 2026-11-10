package dev.caerbannog.core.migrate3x

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MigrateFromThreeState {
    data object ChooseSource : MigrateFromThreeState
    data class Loading(val fileLabel: String) : MigrateFromThreeState

    /** [text] is one of the fixed texts, never file content. */
    data class Rejected(val fileLabel: String, val text: String) : MigrateFromThreeState

    /** [error] is `InvalidName.reason` as returned, or null. The field always starts empty: there is no default value. */
    data class NeedsVirtualHost(val fileLabel: String, val error: String?) : MigrateFromThreeState
    data class Report(val view: ReportView, val notice: ExportNotice?) : MigrateFromThreeState
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
    private val scope: CoroutineScope,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutable = MutableStateFlow(initialState())
    val state: StateFlow<MigrateFromThreeState> = mutable.asStateFlow()

    private var job: Job? = null
    private var selected: Set<Severity> = Severity.entries.toSet()
    private var openGroups: Set<String> = emptySet()

    /** The per-virtual-host export that waits for a name, while the state is [MigrateFromThreeState.NeedsVirtualHost]. */
    private var pending: Pending? = null

    private class Pending(val label: String, val draft: UnassignedDefinitions)

    private fun initialState(): MigrateFromThreeState =
        holder.held.value?.let { MigrateFromThreeState.Report(ReportViewBuilder.build(it), null) } ?: MigrateFromThreeState.ChooseSource

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
        val held = withContext(analysisDispatcher) { HeldAnalysis(label, document, CompatibilityAnalyzer.analyze(document)) }
        pending = null
        selected = Severity.entries.toSet()
        openGroups = emptySet()
        holder.hold(held)
        mutable.value = MigrateFromThreeState.Report(ReportViewBuilder.build(held), null)
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

    /** Cancels a running Loading (the job, back to ChooseSource; a late result is ignored; a held report is not changed). */
    fun cancelLoading() {
        if (mutable.value !is MigrateFromThreeState.Loading) return
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

    private fun rebuild() {
        val held = holder.held.value ?: return
        val view = ReportViewBuilder.build(held, selected, openGroups)
        mutable.update { if (it is MigrateFromThreeState.Report) it.copy(view = view) else it }
    }

    /** Hands the JSON export of the whole report to the saver and shows where it went. A cancelled dialog shows nothing. */
    fun exportReportJson() {
        val held = holder.held.value ?: return
        if (mutable.value !is MigrateFromThreeState.Report) return
        job?.cancel()
        job = scope.launch {
            val json = withContext(analysisDispatcher) { CompatibilityReportJson.encode(held.report) }
            when (val outcome = reportSaver.save(suggestedName(held.fileLabel), json)) {
                is SaveOutcome.Saved -> setNotice(ExportNotice("Report saved to ${outcome.path}", failed = false))
                SaveOutcome.Cancelled -> Unit
                is SaveOutcome.Failed -> setNotice(ExportNotice("${MigrateFromThreeTexts.SAVE_FAILED_PREFIX}: ${outcome.reason}", failed = true))
            }
        }
    }

    /** The text export of the whole report (whatever the chips show); the UI puts it on the clipboard and then calls [reportCopied]. */
    fun reportText(): String = holder.held.value?.let { CompatibilityReportText.render(it.report) }.orEmpty()

    fun dismissNotice() = setNotice(null)

    /** Drops the held analysis and goes back to ChooseSource; work in flight is cancelled and its result ignored. */
    fun reset() {
        job?.cancel()
        pending = null
        selected = Severity.entries.toSet()
        openGroups = emptySet()
        holder.clear()
        mutable.value = MigrateFromThreeState.ChooseSource
    }

    private fun setNotice(notice: ExportNotice?) {
        mutable.update { if (it is MigrateFromThreeState.Report) it.copy(notice = notice) else it }
    }

    private fun fileLabel(path: String): String = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

    private fun suggestedName(label: String): String = "${label.removeSuffix(".json")}-compatibility-report.json"
}
