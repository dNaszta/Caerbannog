package dev.caerbannog.core.migrate3x

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Asks the user for a definitions file (native dialog in the app). Returns the path, or null when the user cancelled. Read only. */
fun interface DefinitionsFileChooser {
    suspend fun choose(): String?
}

/** How a save ended. [Failed.reason] is a fixed text from the adapter; it never holds report content. */
sealed interface SaveOutcome {
    data class Saved(val path: String) : SaveOutcome
    data object Cancelled : SaveOutcome
    data class Failed(val reason: String) : SaveOutcome
}

/** Asks where to save and writes [text] there (native save dialog in the app). Never writes into the migrations folder (that is M8-06b). */
fun interface ReportFileSaver {
    suspend fun save(suggestedName: String, text: String): SaveOutcome
}

/** What the screen holds after an analysis: the file label, the document (for the counts) and the report. */
class HeldAnalysis(val fileLabel: String, val document: DefinitionsDocument, val report: CompatibilityReport) {
    /** The review of this analysis (built once; the held analysis survives a new window view model, so decisions survive too). */
    val review: ConversionReview = ConversionReview.start(document, report)

    override fun toString(): String = "HeldAnalysis($fileLabel, ${report.findings.size} findings)"
}

/**
 * In memory, app lifetime (owner decision 4): the window rebuilds view models when its inputs change (for example a new connection), so the
 * held analysis cannot live in the view model. Never written to disk.
 */
class AnalysisHolder {
    private val state = MutableStateFlow<HeldAnalysis?>(null)
    val held: StateFlow<HeldAnalysis?> = state.asStateFlow()

    private val savedState = MutableStateFlow(false)

    /** True when migration files were saved from this analysis (also after a partial save); reset by [hold] and [clear]. */
    val saved: StateFlow<Boolean> = savedState.asStateFlow()

    fun hold(analysis: HeldAnalysis) {
        state.value = analysis
        savedState.value = false
    }

    fun clear() {
        state.value = null
        savedState.value = false
    }

    /** Records that at least one file was saved from the held analysis, so the next generation asks first. */
    fun recordSaved() {
        savedState.value = true
    }
}

/** The line under the report after an export: [failed] picks the style. [text] is fixed text plus, for a save, the chosen path. */
data class ExportNotice(val text: String, val failed: Boolean)
