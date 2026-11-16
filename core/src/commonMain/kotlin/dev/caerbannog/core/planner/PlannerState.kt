package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.MigrationParser

/**
 * What the planner knows about the connection, as plain values the window builds (shaped like `MigrationsInputs`, which lives in
 * `desktopApp`). The window rebuilds the view model when it changes. [connectionName] null means not connected; [unavailableReason] is why a
 * connection cannot run migrations (RabbitMQ 3.x, version unknown), null when it can; [known] is what the broker has, read by the caller.
 */
data class PlannerContext(
    val connectionName: String? = "uat-eu",
    val vhost: String = "/",
    val controlLevel: TopologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    val hasConfigurePermission: Boolean = true,
    val unavailableReason: String? = null,
    val known: KnownTopology = KnownTopology.None,
)

/** A draft file that cannot be used as a plan: where it is and a fixed reason (never its content). */
data class DraftUnreadable(val path: String, val reason: String)

/** The wizard as the screen draws it. Page 3 and the form pages are derived from the current plan, so they never show a stale answer. */
sealed interface WizardState {
    data object Closed : WizardState

    /** Page 1: the kind of step. */
    data object Kind : WizardState

    /** Page 2: the form. [editingStepId] is the step being changed, null for a new one. */
    data class Details(
        val form: StepForm,
        val problems: List<FieldProblem>,
        val hints: List<FieldHint>,
        val editingStepId: Int?,
        /** A binding problem as one message of the page (the parser does not say which field caused it); null for the other forms. */
        val pageMessage: String? = null,
        /** The exchanges of this plan, then the broker's (a binding form only; empty for the others). */
        val exchangeSuggestions: List<Suggestion> = emptyList(),
        /** The destinations that fit the destination kind of a binding form (empty for the others). */
        val destinationSuggestions: List<Suggestion> = emptyList(),
        /** False for a fanout or headers exchange whose type is known: the routing key is not asked. */
        val asksRoutingKey: Boolean = true,
    ) : WizardState {
        /** A hint never disables Next (O11); a problem does. */
        val nextEnabled: Boolean get() = problems.isEmpty()
    }

    /** Page 3: what "Add to plan" would add. */
    data class Summary(val form: StepForm, val actions: List<dev.caerbannog.core.migrations.MigrationAction>, val editingStepId: Int?) : WizardState
}

/** One verdict for "Save as migration": enabled, or disabled with the first reason that applies. */
sealed interface SaveGate {
    data object Enabled : SaveGate

    data class Disabled(val reason: String) : SaveGate
}

/** A plan that was written as a migration file. [fileName] is `<version>.json` in the folder of the store (a [dev.caerbannog.core.migrations.MigrationFile] has no path). */
data class SavedPlan(val version: String, val fileName: String) {
    val message: String get() = PlannerTexts.planSaved(version)
}

/** A question that waits for Confirm or Cancel. */
sealed interface PlannerQuestion {
    val text: String

    data class Dismiss(val steps: Int) : PlannerQuestion {
        override val text: String get() = PlannerTexts.dismissQuestion(steps)
    }

    data class StartNewPlan(val asideFileName: String) : PlannerQuestion {
        override val text: String get() = PlannerTexts.startNewPlanQuestion(asideFileName)
    }
}

/** The one banner of the planner, the first that applies. [title] is the sentence, [detail] a second line or null. */
sealed interface PlannerBanner {
    val title: String
    val detail: String?

    data class NotOffered(override val detail: String) : PlannerBanner {
        override val title: String get() = PlannerTexts.NOT_OFFERED
    }

    data class DraftUnreadable(val path: String, override val detail: String) : PlannerBanner {
        override val title: String get() = PlannerTexts.DRAFT_UNREADABLE
    }

    data object ReadOnlyLevel : PlannerBanner {
        override val title: String get() = PlannerTexts.READ_ONLY_LEVEL
        override val detail: String? get() = null
    }

    data class NoConfigurePermission(val connection: String?) : PlannerBanner {
        override val title: String get() = PlannerTexts.noConfigurePermission(connection)
        override val detail: String? get() = null
    }

    data object NotConnected : PlannerBanner {
        override val title: String get() = PlannerTexts.NOT_CONNECTED
        override val detail: String? get() = null
    }

    /** [detail] is the folder path when there is one. */
    data class FolderProblem(override val title: String, override val detail: String?) : PlannerBanner

    data class VhostMismatch(val planVhost: String, val connection: String, val connectionVhost: String) : PlannerBanner {
        override val title: String get() = PlannerTexts.vhostMismatch(planVhost, connection, connectionVhost)
        override val detail: String? get() = null
    }
}

/** Everything the planner screen draws. Built from one snapshot, so a state is never half of two changes. */
data class PlannerState(
    val plan: TopologyPlan,
    val wizard: WizardState = WizardState.Closed,
    val selectedStepId: Int? = null,
    val namingStyle: NamingStyle = NamingStyle.KEBAB,
    /** What [PlanValidation] says about [plan]. A problem with a null step id is about the plan as a whole: the screen shows it at the top. */
    val problems: List<PlanProblem> = emptyList(),
    val saveGate: SaveGate = SaveGate.Disabled(PlannerTexts.ADD_A_STEP_FIRST),
    val banner: PlannerBanner? = null,
    val draftUnreadable: DraftUnreadable? = null,
    val question: PlannerQuestion? = null,
    val saved: SavedPlan? = null,
    val errorMessage: String? = null,
    /** True after Dismiss: the screen goes back to the Migrations screen. */
    val dismissed: Boolean = false,
    /** False until the draft file has been read once. */
    val isLoaded: Boolean = false,
) {
    val description: String get() = plan.description

    /** The plan is not a migration until it is saved (spec A3). */
    val draftLabel: String get() = PlannerTexts.DRAFT_LABEL

    /** The 3.x connection or an unreadable draft: the screen draws no wizard, no add and no save. */
    val isLocked: Boolean get() = banner is PlannerBanner.NotOffered || draftUnreadable != null

    val isEmpty: Boolean get() = plan.steps.isEmpty()

    /** A plan with no step and no description is nothing to dismiss; a description alone is kept as a draft, so it can be dismissed. */
    val canDismiss: Boolean get() = !isLocked && (plan.steps.isNotEmpty() || plan.description.isNotEmpty())

    /** Problems about the plan as a whole (shown at the top of the list). */
    val wholePlanProblems: List<PlanProblem> get() = problems.filter { it.stepId == null }

    fun stepProblems(stepId: Int): List<PlanProblem> = problems.filter { it.stepId == stepId }

    /** The problems that do not block: shown in the neutral style and left out of [blockingCount]. */
    val notes: List<PlanProblem> get() = problems.filterNot { it.blocking }

    val blockingCount: Int get() = problems.count { it.blocking }

    /** The second line of the error banner, for the one error that has it. */
    val errorDetail: String? get() = if (errorMessage == PlannerTexts.FILE_NOT_WRITTEN) PlannerTexts.FILE_NOT_WRITTEN_DETAIL else null

    /** Checking is built in M9-06; until then the button is drawn disabled with a reason. */
    val checkEnabled: Boolean get() = false

    val checkDisabledReason: String? get() = PlannerTexts.CHECK_LATER

    /**
     * What the plan would be saved as, for the read-only JSON view. The version is not chosen yet (the next free one is picked at save),
     * so it reads as a draft; it holds exactly what the file will hold: a description and actions, no secret.
     */
    val migrationText: String
        get() = MigrationParser.exportToJson("draft", plan.description, plan.steps.map { it.action })
}
