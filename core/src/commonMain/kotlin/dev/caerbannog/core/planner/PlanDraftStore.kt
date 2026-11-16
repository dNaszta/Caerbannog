package dev.caerbannog.core.planner

/** What the draft file of this machine holds. [Unreadable.reason] is a fixed text and never holds file content. */
sealed interface DraftLoad {
    data object None : DraftLoad

    data class Loaded(val plan: TopologyPlan) : DraftLoad

    /** The file exists and cannot be used as a plan; it is kept as it is. [path] is where it is, for the message. */
    data class Unreadable(val path: String, val reason: String) : DraftLoad
}

sealed interface DraftMoveResult {
    data class Moved(val fileName: String) : DraftMoveResult

    data class Failed(val reason: String) : DraftMoveResult
}

/**
 * The one draft of this machine, in the app's own data (spec A3). Never the migrations folder: a draft is not a migration until it is saved
 * as one. A draft holds no secret (A8), so a file that holds a password hash is [DraftLoad.Unreadable], never loaded and never written over.
 */
interface PlanDraftStore {
    suspend fun load(): DraftLoad

    /** Writes [plan] as a whole or not at all; false, with the old file as it was, when the write fails or the file there cannot be read. */
    suspend fun save(plan: TopologyPlan): Boolean

    /** Removes a readable draft (after Dismiss and after a successful save); an unreadable file is never removed. */
    suspend fun clear()

    /** The name [moveAside] would give the file now: `planner-draft.json.unreadable-<yyyyMMdd-HHmmss>`. */
    fun asideFileName(): String

    /** Renames an unreadable draft; never deletes and never overwrites. Failed when the draft is readable or missing. */
    suspend fun moveAside(): DraftMoveResult
}

/** What the Migrations screen offers as the way into the planner, from what the draft store holds (the button is drawn in M9-03). */
sealed interface PlannerEntry {
    val label: String

    data object NewPlan : PlannerEntry {
        override val label: String get() = "New plan"
    }

    data class ContinuePlan(val stepCount: Int, val description: String = "") : PlannerEntry {
        override val label: String get() = "Continue plan"

        /** The line of the draft card under the description. */
        val stepsText: String get() = PlannerTexts.draftCardSteps(stepCount)
    }

    /** The draft cannot be read: the planner opens on its report (neither New nor Continue). */
    data class DraftUnreadable(val path: String) : PlannerEntry {
        override val label: String get() = "Open planner"

        /** The line of the draft card for a file that cannot be read. */
        val cardText: String get() = PlannerTexts.DRAFT_UNREADABLE
    }

    companion object {
        fun of(load: DraftLoad): PlannerEntry = when (load) {
            DraftLoad.None -> NewPlan
            is DraftLoad.Loaded -> ContinuePlan(load.plan.steps.size, load.plan.description)
            is DraftLoad.Unreadable -> DraftUnreadable(load.path)
        }
    }
}
