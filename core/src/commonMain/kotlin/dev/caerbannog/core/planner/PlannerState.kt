package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser

/**
 * What the planner knows about the connection, as plain values the window builds (shaped like `MigrationsInputs`, which lives in
 * `desktopApp`). The window rebuilds the view model when it changes. [connectionName] null means not connected; [unavailableReason] is why a
 * connection cannot run migrations (RabbitMQ 3.x, version unknown), null when it can. What the broker has is read by the view model
 * through a [KnownTopologyReader], so there is one source of broker names.
 */
data class PlannerContext(
    val connectionName: String? = "uat-eu",
    val vhost: String = "/",
    val controlLevel: TopologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    val hasConfigurePermission: Boolean = true,
    val unavailableReason: String? = null,
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
        /** True for a binding from a known headers exchange, or a form that already holds headers (an opened file): the screen shows the headers section. */
        val asksHeaders: Boolean = false,
        /** The response name a stream form suggests in the plan's naming style; empty for the other forms. The screen has no naming style of its own. */
        val suggestedResponseName: String = "",
        /** The names a dead-lettering form suggests in the plan's naming style (empty while no queue is chosen), and whether its queue offers At least once; the screen has no context of its own. */
        val suggestedExchangeName: String = "",
        val suggestedTargetName: String = "",
        val offersAtLeastOnce: Boolean = false,
        /** The queues a dead-lettering form can pick, this plan first, no stream (empty for the other forms). */
        val queueSuggestions: List<Suggestion> = emptyList(),
        /** Notes the form gets from a read of the broker (the policy that already applies to the queue); empty for the other forms and while nothing is known. */
        val formNotes: List<String> = emptyList(),
    ) : WizardState {
        /** A hint never disables Next (O11); a problem does. */
        val nextEnabled: Boolean get() = problems.isEmpty()
    }

    /** Page 3: what "Add to plan" would add. */
    data class Summary(
        val form: StepForm,
        val actions: List<dev.caerbannog.core.migrations.MigrationAction>,
        val editingStepId: Int?,
        /** One sentence under the list, or null; neutral on purpose so every kind can use the same place. */
        val note: String? = null,
    ) : WizardState
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
    val confirmLabel: String
    val cancelLabel: String

    data class Dismiss(val steps: Int) : PlannerQuestion {
        override val text: String get() = PlannerTexts.dismissQuestion(steps)
        override val confirmLabel: String get() = PlannerTexts.DISMISS_LABEL
        override val cancelLabel: String get() = "Keep the plan"
    }

    /** Leaving a file that has unsaved changes (M9-12). Nothing is written when it is confirmed: the file stays as it was. */
    data class DiscardChanges(val version: String) : PlannerQuestion {
        override val text: String get() = PlannerTexts.discardChangesQuestion(version)
        override val confirmLabel: String get() = PlannerTexts.DISCARD_CHANGES_LABEL
        override val cancelLabel: String get() = PlannerTexts.KEEP_EDITING_LABEL
    }

    /** Asked on the Migrations screen before the steps of [version] replace a draft that has steps (M9-12). */
    data class CopyOverDraft(val version: String) : PlannerQuestion {
        override val text: String get() = PlannerTexts.copyOverDraftQuestion(version)
        override val confirmLabel: String get() = PlannerTexts.COPY_OVER_DRAFT_CONFIRM_LABEL
        override val cancelLabel: String get() = "Keep the plan"
    }

    data class StartNewPlan(val asideFileName: String) : PlannerQuestion {
        override val text: String get() = PlannerTexts.startNewPlanQuestion(asideFileName)
        override val confirmLabel: String get() = "Move file and start"
        override val cancelLabel: String get() = "Cancel"
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

/** The check on the screen. One state at a time: a running check hides the result it replaces. */
sealed interface CheckState {
    data object None : CheckState

    /** The check is reading [step] (1-based) of [total] steps; requests are not counted, a binding step costs up to three. */
    data class Running(val step: Int, val total: Int) : CheckState

    /** [stale]: the plan changed after the check; the results are kept, matched to steps by id. */
    data class Done(val results: List<StepCheck>, val connectionName: String, val atMillis: Long, val stale: Boolean) : CheckState
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
    /** What the broker has for the plan's virtual host; null when nothing was read (no connection) or the read failed. */
    val known: KnownTopology? = null,
    /** True while the names of the plan's virtual host are being read: the graph then says nothing about what exists. */
    val knownPending: Boolean = false,
    /** The review graph: the plan, with the broker's existing ends when [known] is there. Derived on every change. */
    val graph: PlanGraph = planPreview(plan, known),
    val graphView: ReviewView = ReviewView.Graph,
    /** The check of the plan against the broker (M9-06). */
    val check: CheckState = CheckState.None,
    /** The held connection's name; null when not connected. */
    val connectionName: String? = null,
    /** Why the check cannot start, in the order of `PlanCheck.run`; null when it can. A hand-made state has no port, so it says so. */
    val checkDisabledReason: String? = PlanCheck.NO_CHECK,
    /** M9-12: a new plan, or a pending file opened as steps. */
    val mode: PlannerMode = PlannerMode.NewPlan,
    /** M9-12: in file mode, the steps or the description differ from the file as opened; always false for a new plan. */
    val hasChanges: Boolean = false,
) {
    private val fileVersion: String? get() = (mode as? PlannerMode.EditFile)?.version

    /** "Topology planner", or "Topology planner · 0005" on a file. */
    val title: String get() = PlannerTexts.titleOf(fileVersion)

    /** "Save as migration", or "Save 0005" on a file. */
    val saveLabel: String get() = PlannerTexts.saveLabelOf(fileVersion)

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
    val errorDetail: String?
        get() = when {
            // The draft sentence would be wrong for a file: its steps are not a draft, and the file may be as it was.
            errorMessage == PlannerTexts.FILE_NOT_WRITTEN -> if (mode is PlannerMode.NewPlan) PlannerTexts.FILE_NOT_WRITTEN_DETAIL else null
            fileVersion?.let { errorMessage == PlannerTexts.fileChangedOnDisk(it) } == true -> PlannerTexts.FILE_CHANGED_DETAIL
            else -> null
        }

    /** The button: usable when nothing says otherwise and no check runs. A running check has no reason; the progress banner is the explanation. */
    val checkEnabled: Boolean get() = checkDisabledReason == null && check !is CheckState.Running

    /** "Check against uat-eu", or "Check" while there is no connection. */
    val checkLabel: String get() = PlannerTexts.checkLabel(connectionName)

    /**
     * The two lines a plan action is shown with: the words of an MQTT route (its kind from this plan, then from the broker's names), else the
     * action's own description and detail. The step list, the summary page of the wizard and every other place that words a step call this.
     * [alongside] are actions that are not in the plan yet but come with [action] (the summary's own steps), so a route into a stream declared
     * next to it is worded "to stream ..." there as well.
     */
    fun wordsOf(action: MigrationAction, alongside: List<MigrationAction> = emptyList()): MqttRouteWords =
        MqttRoutes.words(action, plan.steps.map { it.action } + alongside, known ?: KnownTopology.None)
            ?: MqttRouteWords(action.description, action.detail.orEmpty())

    /** The title of step [stepId] as the planner shows it; empty for an id that is not in the plan. */
    fun stepTitle(stepId: Int): String =
        plan.steps.firstOrNull { it.id == stepId }?.let { wordsOf(it.action).title }.orEmpty()

    /** The second line of step [stepId], or null when it has none. */
    fun stepDetail(stepId: Int): String? {
        val action = plan.steps.firstOrNull { it.id == stepId }?.action ?: return null
        return if (MqttRoutes.routeOf(action) != null) wordsOf(action).detail else action.detail
    }

    /** "Response stream (DLN) of telemetry" for a step that [PlanPairs] recognises as a response stream; null for every other step. */
    fun responseStreamLine(stepId: Int): String? {
        val request = PlanPairs.responseOf(plan)[stepId] ?: return null
        val name = (plan.steps.firstOrNull { it.id == request }?.action as? MigrationAction.DeclareStream)?.name
        return PlannerTexts.responseStreamOf(name.orEmpty())
    }

    /** The result of [stepId] in the check that is shown; null when there is none (no check, running, or a step added after it). */
    fun stepCheck(stepId: Int): StepCheck? = (check as? CheckState.Done)?.results?.firstOrNull { it.stepId == stepId }

    /**
     * What the plan would be saved as, for the read-only JSON view. The version is not chosen yet (the next free one is picked at save),
     * so it reads as a draft; it holds exactly what the file will hold: a description and actions, no secret.
     */
    val migrationText: String
        get() = MigrationParser.exportToJson("draft", plan.description, plan.steps.map { it.action })
}
