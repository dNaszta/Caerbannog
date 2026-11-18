package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.HistoryUnreadable
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationsWording

/** What the planner works on: a new plan (the draft of this machine) or a pending migration file opened as steps ("Edit steps", M9-12). */
sealed interface PlannerMode {
    data object NewPlan : PlannerMode

    /** [baseChecksum] is the checksum of the file as it was when the user chose "Edit steps"; a save refuses when the file on disk no longer has it. */
    data class EditFile(val version: String, val baseChecksum: String) : PlannerMode
}

/** Whether a migration file may be opened as steps. */
sealed interface PlanOpeningDecision {
    data object Openable : PlanOpeningDecision

    /**
     * [reason] is a fixed sentence and never holds file content. [detail] is the second line of the applied case. [canCopy] is true for the applied
     * case only: the steps of a file with a password hash would put the hash in the draft (A8), and those of a file with keys the planner does not know
     * would lose them without a word (A2).
     */
    data class NotOffered(val reason: String, val detail: String? = null, val canCopy: Boolean = false) : PlanOpeningDecision
}

/** The rules that decide whether a pending file becomes steps, and the plan it becomes. Pure: nothing is read or written here. */
object PlanOpening {
    /**
     * The first refusal that applies, in this order: a password hash, any other broker-level action, keys the planner does not know ([rawKeysKnown]
     * false), an unreadable applied history ([historyProblem]), the file being applied on a connection ([appliedOn], names in the caller's order).
     */
    fun decide(
        file: MigrationFile,
        rawKeysKnown: Boolean,
        appliedOn: List<String>,
        historyProblem: HistoryUnreadable? = null,
    ): PlanOpeningDecision = when {
        file.up.any { it is MigrationAction.DeclareUser } ->
            PlanOpeningDecision.NotOffered(MigrationsWording.HASH_FILE_NO_EDIT)
        file.up.any { it.isBrokerLevel } -> PlanOpeningDecision.NotOffered(PlannerTexts.ACCESS_FILE_NOT_PLANNED)
        !rawKeysKnown -> PlanOpeningDecision.NotOffered(PlannerTexts.UNKNOWN_ENTRIES)
        historyProblem != null -> PlanOpeningDecision.NotOffered(PlannerTexts.historyUnreadableSoUnknown(file.version))
        appliedOn.isNotEmpty() -> PlanOpeningDecision.NotOffered(
            reason = PlannerTexts.appliedElsewhere(file.version, appliedOn.first()),
            detail = PlannerTexts.APPLIED_DETAIL,
            canCopy = true,
        )
        else -> PlanOpeningDecision.Openable
    }

    /**
     * One step per action of [file], ids 1..n, with the file's description. Refuses a file with a broker-level action (it never reaches the planner,
     * and a `declareUser` would carry a password hash into a plan); the message names nothing of the file.
     */
    fun planOf(file: MigrationFile, defaultVhost: String): TopologyPlan {
        require(file.up.none { it.isBrokerLevel }) { "a file with virtual hosts, users or permissions is not turned into steps" }
        return TopologyPlan.of(file.description, file.up, defaultVhost)
    }
}
