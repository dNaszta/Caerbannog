package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.TopologyControlLevel

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
}
