package dev.caerbannog.core.migrate3x

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * M8-06a STUB: the state stays [MigrateFromThreeState.ChooseSource] and no event does anything yet.
 */
class MigrateFromThreeViewModel(
    private val loader: DefinitionsFileLoader,
    private val fileChooser: DefinitionsFileChooser,
    private val reportSaver: ReportFileSaver,
    private val holder: AnalysisHolder,
    private val scope: CoroutineScope,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutable = MutableStateFlow<MigrateFromThreeState>(MigrateFromThreeState.ChooseSource)
    val state: StateFlow<MigrateFromThreeState> = mutable.asStateFlow()

    fun chooseFile() {}
    fun open(path: String) {}
    fun giveVirtualHost(text: String) {}

    /** Also cancels a running Loading (back to ChooseSource; a late result is ignored). */
    fun cancelPrompt() {}
    fun toggleSeverity(severity: Severity) {}
    fun toggleGroup(key: String) {}
    fun exportReportJson() {}
    fun reportText(): String = ""
    fun dismissNotice() {}
    fun reset() {}
}
