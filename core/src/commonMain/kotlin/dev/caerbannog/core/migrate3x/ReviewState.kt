package dev.caerbannog.core.migrate3x

// M8-06b STUB: the types of "View model API" and "Generate and save" of docs/tasks/M8-06b-review-generate-save.md, exactly as the brief
// lists them, with no behaviour. The developer replaces the stub values and builds the real state in the view model.

data class ReviewState(
    val itemCount: Int,
    val pendingCount: Int,
    val acceptedCount: Int,
    val skippedCount: Int,
    val replacedCount: Int,
    val pendingLine: String,
    val decidedLine: String?,
    val canGenerate: Boolean,
    val sheet: ItemSheet?,
    val generation: GenerationState,
) {
    companion object {
        /** M8-06b STUB value: "no review built yet". */
        val NONE = ReviewState(0, 0, 0, 0, 0, "", null, false, null, GenerationState.Idle)
    }
}

data class ItemSheet(
    val item: ReviewItem,
    val itemNumber: Int,
    val itemTotal: Int,
    val findings: List<FindingChoice>,
    val ticks: List<TickView>,
    val manualSteps: List<ManualStepView>,
    val acceptBlock: DecisionRefusal?,
    val canAccept: Boolean,
    val replace: ReplaceEditor?,
    val decided: DecidedAs?,
)

data class FindingChoice(
    val findingId: String,
    val severity: Severity,
    val message: String,
    val why: String,
    val verified: Boolean,
    val options: List<ProposalOption>,
    val chosen: Int,
)

data class ProposalOption(val index: Int, val title: String, val isDefault: Boolean)
data class TickView(val acknowledgement: Acknowledgement, val text: String, val ticked: Boolean)
data class ManualStepView(val findingId: String, val title: String, val text: String)
data class ReplaceEditor(val text: String, val error: String?, val hint: String)

sealed interface DecidedAs {
    data class Accepted(val ticked: Boolean) : DecidedAs
    data class Replaced(val actionCount: Int) : DecidedAs
    data object Skipped : DecidedAs
}

sealed interface DecisionPill {
    data object Undecided : DecisionPill
    data class Accepted(val ticked: Boolean) : DecisionPill
    data class Replaced(val actionCount: Int) : DecisionPill
    data object Skipped : DecisionPill
}

sealed interface GenerationState {
    data object Idle : GenerationState
    data object ConfirmAgain : GenerationState
    data object Saving : GenerationState
    data class Done(val report: GenerationReport) : GenerationState
    data class Trouble(val problem: SaveProblem, val saved: List<SavedFile>, val unsaved: List<SavedFile>) : GenerationState
}

sealed interface SaveProblem {
    data object NoFolderChosen : SaveProblem
    data class Folder(val path: String, val text: String) : SaveProblem
    data class FileRefused(val version: String, val reason: String) : SaveProblem
    data object NoFreeVersion : SaveProblem
}

/** [path] is empty for an unsaved file. */
data class SavedFile(val version: String, val vhost: String, val description: String, val actionCount: Int, val path: String)

/** The brief says `how = Exported` and names no other value; [Saved] is the obvious one (the test pins both names). */
enum class How { Saved, Exported }

data class GenerationReport(
    val how: How,
    val headline: String,
    val files: List<SavedFile>,
    val leftOut: List<LeftOutRow>,
    val notes: List<String>,
    val vhostHint: String?,
)

data class LeftOutRow(val label: String, val text: String)

const val SKIPPED_GROUP_KEY: String = "skipped"

/** M8-06b STUB: folder plus `<version>.json`, joined with the separator the folder string already uses. */
fun savedPath(folder: String, version: String): String = ""
