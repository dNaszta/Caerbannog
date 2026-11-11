package dev.caerbannog.core.migrate3x

// The state of the review, generate and save part of the Migrate from 3.x screen (docs/tasks/M8-06b-review-generate-save.md). Plain data:
// no secret, no hash, and every string that came from a file is escaped by the builders before it gets here.

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
        /** "No review built yet"; the view model replaces it as soon as a report is held. */
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

/** Where the files went: into the migrations folder, or out through a save dialog. */
enum class How { Saved, Exported }

data class GenerationReport(
    val how: How,
    val headline: String,
    val files: List<SavedFile>,
    val leftOut: List<LeftOutRow>,
    val notes: List<String>,
    val vhostHint: String?,
    /** M8-12b: "Access: 1 virtual host, 4 users, ..."; null without an access file. */
    val accessLine: String? = null,
    val usersNotMigrated: List<LeftOutRow> = emptyList(),
    val permissionsLeftOut: List<LeftOutRow> = emptyList(),
    /** M8-12b: the secret-file warning (D3), set when any generated file holds a `declareUser`. */
    val secretWarning: String? = null,
)

data class LeftOutRow(val label: String, val text: String)

const val SKIPPED_GROUP_KEY: String = "skipped"

/** Folder plus `<version>.json`, joined with the separator the folder string already uses (nothing is resolved, trimmed or escaped). */
fun savedPath(folder: String, version: String): String {
    val separator = if ('\\' in folder) '\\' else '/'
    val base = if (folder.endsWith('/') || folder.endsWith('\\')) folder else folder + separator
    return "$base$version.json"
}
