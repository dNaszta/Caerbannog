@file:OptIn(ExperimentalTestApi::class)

package dev.caerbannog.ui.planner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.migrations.DeclareCheck
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolder
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import dev.caerbannog.core.planner.BindingForm
import dev.caerbannog.core.planner.ExchangeForm
import dev.caerbannog.core.planner.KnownTopologyReader
import dev.caerbannog.core.planner.NamingStyle
import dev.caerbannog.core.planner.ReviewView
import dev.caerbannog.core.planner.PlannerContext
import dev.caerbannog.core.planner.PlannerState
import dev.caerbannog.core.planner.PlannerViewModel
import dev.caerbannog.core.planner.QueueForm
import dev.caerbannog.core.planner.StepForm
import dev.caerbannog.core.planner.fakes.FakePlanDraftStore
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.streams.connected
import dev.caerbannog.ui.streams.noControlEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * M9-03 test kit for the planner screens. The screen is stateless (state in, events out), so every test drives it with a REAL
 * [PlannerViewModel] over fakes (draft store, migrations folder, settings) on `Dispatchers.Unconfined`: an event has taken effect when the call
 * returns, and the screen is recomposed from the view model's state. No broker, no file, no dialog. The view model is already tested in `core`;
 * these tests are about what the screen draws and sends. State the view model cannot reach (a hand-made [PlannerState]) is passed in directly.
 *
 * Tag contract of the planner (fixed by the M9-03 tests; the brief lists the first group):
 *  - screen: `planner:title`, `planner:addStepBtn`, `planner:saveBtn`, `planner:dismissBtn`, `planner:checkBtn`, `planner:namingChip`,
 *    `planner:namingOption:<NamingStyle name>`, `planner:description` (editable), `planner:vhost`, `planner:draftPill`, `planner:saveReason`
 *    (the reason of a disabled Save, drawn only while it is disabled), `planner:banner` (title and detail), `planner:error` (errorMessage and
 *    errorDetail), `planner:emptyState`, `planner:reviewEmpty`, `planner:reviewCard`, `planner:jsonView`, `planner:jsonCopyBtn`,
 *    (M9-04) `planner:reviewGraph`, `planner:graphNotice`, `planner:reviewViewSwitch` with the options `planner:reviewViewSwitch:Graph` and
 *    `planner:reviewViewSwitch:Json` (radio, `selected` semantics), graph nodes `planGraphNode:<id>` (id `e:<name>` or `q:<name>`, content
 *    description = the accessible name) and `planGraphNew:<id>` (the "new" pill of a node, or of an edge: `planGraphNew:edge-<i>`); `planGraphNotInPlan:<id>` (the words "not in this plan" on a node that is);
 *    `planner:stepList` (the lazy list), `planner:wholePlanProblem:<i>`, `planner:question` (+ `planner:questionConfirmBtn`, `planner:questionCancelBtn`),
 *    banner buttons `planner:retryDraftBtn`, `planner:startNewPlanBtn`, `planner:chooseFolderBtn`.
 *  - step list (0-based index): `planStep:<i>` (row: title and detail as merged text, focusable), `planStepMenu:<i>` (the "more" button) with the
 *    items `planStepMenu:<i>:edit`, `:moveUp`, `:moveDown`, `:remove`, `planStepProblem:<i>` (blocking problems of the step, with the pill
 *    "Problem"), `planStepNote:<i>` (notes, neutral, no pill).
 *  - (M9-06) the check: `planner:checkSummary` (the summary line and the caveat), `planner:checkProgress`, `planner:checkStale` (the stale line) with its
 *    button `planner:checkAgainBtn`; per step `planStepResult:<i>` (the result pill; while the result is stale its semantics say `stateDescription` "stale"),
 *    `planStepBlocked:<i>` (the "Blocked" pill) and `planStepReason:<i>` (the reason line: the dry run's reason, the note, the missing/unreadable sentence).
 *  - wizard: `planWizard` (the dialog), `planWizard:kind:<Kind>` (radio, `selected` semantics), `planWizard:next`, `:back`, `:cancel`, `:add`,
 *    `planWizard:summary`, `planWizard:pageMessage`; forms `planForm:name`, `planForm:type:<value>`, `planForm:exchange`, `planForm:destination`,
 *    `planForm:destinationKind:queue|exchange`, `planForm:routingKey`, `planForm:hint:<field>`, `planForm:error:<field>`,
 *    `planForm:suggestionsBtn:<field>` and `planForm:suggestion:<field>:<name>`.
 */
internal const val FOLDER = "/work/migrations"

/** The time of every check of the UI tests: a fixed instant, so a summary says the same on every machine except for the zone (the tests format it as the screen does). */
internal const val CHECK_TIME = 1_700_000_000_000L

internal class UiRig(
    folderPath: String? = FOLDER,
    latest: String? = "0004",
    private val context: PlannerContext = PlannerContext(),
    val store: FakePlanDraftStore = FakePlanDraftStore(),
    /** M9-04: the broker's names; null is no connection (the graph says only the plan is drawn). */
    val reader: KnownTopologyReader? = null,
    /** M9-06: the connection's read-only check port; null is a connection without one. */
    val declareCheck: DeclareCheck? = null,
    /** M9-06: the time of a check, epoch milliseconds (the virtual clock of the view model). */
    var nowMillis: Long = CHECK_TIME,
    prepare: UiRig.() -> Unit = {},
) {
    val folders = FakeMigrationFolders(folderPath)
    val folder: FakeMigrationFolder = folders.folder(folderPath ?: FOLDER).allowExport().also { f -> latest?.let { f.addMigration(it) } }
    val settings = FakeAppSettings()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun build() = PlannerViewModel(
        store, folders.ports, settings, context, scope, descriptionDebounceMillis = 0, reader = reader,
        declareCheck = declareCheck, clock = RefreshClock { nowMillis },
    )

    private var current: PlannerViewModel = build()
    val vm: PlannerViewModel get() = current
    val events = RecordedPlannerEvents { current }
    val state: PlannerState get() = current.state.value

    init {
        prepare()
        // The window rebuilds the view model from the draft file; the screen then starts from what was saved.
        current = build()
    }

    fun close() = scope.cancel()

    fun add(form: StepForm) {
        vm.openWizard()
        vm.chooseKind(kindOf(form))
        vm.updateForm(form)
        vm.next()
        vm.addToPlan()
    }

    fun queue(name: String, type: String = "quorum") = add(QueueForm(name = name, type = type))
    fun exchange(name: String, type: String = "direct") = add(ExchangeForm(name = name, type = type))
    fun binding(exchange: String, queue: String, key: String = "") = add(BindingForm(exchange = exchange, destination = queue, routingKey = key))

    /** The mockup's plan, three steps: a queue, an exchange and a binding of the two. */
    fun threeSteps() {
        queue("orders"); exchange("events"); binding("events", "orders", "order.created")
    }

    private fun kindOf(form: StepForm) = when (form) {
        is QueueForm -> "Queue"
        is ExchangeForm -> "Exchange"
        is BindingForm -> "Binding"
    }
}

/** Every event sent to the view model, by name, in order; the view model does the work. */
internal class RecordedPlannerEvents(private val vm: () -> PlannerViewModel) : PlannerEvents {
    val calls = mutableListOf<String>()
    private fun <T> T.also(name: String): T { calls += name; return this }

    override fun openWizard() = vm().openWizard().also("openWizard")
    override fun chooseKind(kind: String) = vm().chooseKind(kind).also("chooseKind:$kind")
    override fun updateForm(form: StepForm) = vm().updateForm(form).also("updateForm")
    override fun next() = vm().next().also("next")
    override fun back() = vm().back().also("back")
    override fun cancelWizard() = vm().cancelWizard().also("cancelWizard")
    override fun addToPlan() = vm().addToPlan().also("addToPlan")
    override fun selectStep(id: Int) = vm().selectStep(id).also("selectStep:$id")
    override fun editStep(id: Int) = vm().editStep(id).also("editStep:$id")
    override fun removeStep(id: Int) = vm().removeStep(id).also("removeStep:$id")
    override fun moveStepUp(id: Int) = vm().moveStepUp(id).also("moveStepUp:$id")
    override fun moveStepDown(id: Int) = vm().moveStepDown(id).also("moveStepDown:$id")
    override fun setDescription(text: String) = vm().setDescription(text).also("setDescription:$text")
    override fun setVirtualHost(vhost: String) = vm().setVirtualHost(vhost).also("setVirtualHost:$vhost")
    override fun setNamingStyle(style: NamingStyle) = vm().setNamingStyle(style).also("setNamingStyle:$style")
    override fun chooseFolder() = vm().chooseFolder().also("chooseFolder")
    override fun saveAsMigration() = vm().saveAsMigration().also("saveAsMigration")
    override fun dismiss() = vm().dismiss().also("dismiss")
    override fun startNewPlan() = vm().startNewPlan().also("startNewPlan")
    override fun confirm() = vm().confirm().also("confirm")
    override fun cancelQuestion() = vm().cancelQuestion().also("cancelQuestion")
    override fun retryDraft() = vm().retryDraft().also("retryDraft")
    override fun flush() = vm().flush().also("flush")
    override fun selectReviewView(view: ReviewView) = vm().selectReviewView(view).also("selectReviewView:$view")
    override fun check() = vm().check().also("check")
}

/** The planner screen over [rig]; [onNavigate] receives the drawer and the app bar's navigation. */
@Composable
internal fun PlannerOver(rig: UiRig, onNavigate: (AppDestination) -> Unit = {}) {
    val state by rig.vm.state.collectAsState()
    PlannerScreen(state = state, events = rig.events, control = connected, controlEvents = noControlEvents, onNavigate = onNavigate)
}

internal fun DesktopComposeUiTest.showPlanner(rig: UiRig, onNavigate: (AppDestination) -> Unit = {}) {
    setContent { PlannerOver(rig, onNavigate) }
    waitForIdle()
}

/** A hand-made state, for what the view model cannot reach (a state the mockup draws that needs no real input). */
internal fun DesktopComposeUiTest.showState(state: PlannerState, events: PlannerEvents = RecordedPlannerEvents { error("no view model") }) {
    setContent { PlannerScreen(state = state, events = events, control = connected, controlEvents = noControlEvents, onNavigate = {}) }
    waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.node(tag: String): SemanticsNodeInteraction = onNodeWithTag(tag, useUnmergedTree = true)

/** All the text of a node and its children, joined; for "the banner says ...". */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.textOf(tag: String): String {
    val n = node(tag).fetchSemanticsNode()
    fun all(x: androidx.compose.ui.semantics.SemanticsNode): List<String> =
        x.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + x.children.flatMap { all(it) }
    return all(n).joinToString(" ")
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.assertDrawn(tag: String) = node(tag).assertExists("no node tagged $tag")

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.assertNotDrawn(tag: String) = node(tag).assertDoesNotExist()

/** True when the node can be typed into (a text field), false for plain text. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.isEditable(tag: String): Boolean {
    val c = node(tag).fetchSemanticsNode().config
    return c.getOrNull(SemanticsActions.SetText) != null || c.getOrNull(SemanticsProperties.EditableText) != null
}

/** The review card shows the graph first (M9-04); the JSON text is behind the switch. */
internal fun DesktopComposeUiTest.showJson() {
    node("planner:reviewViewSwitch:Json").performClick()
    waitForIdle()
}
