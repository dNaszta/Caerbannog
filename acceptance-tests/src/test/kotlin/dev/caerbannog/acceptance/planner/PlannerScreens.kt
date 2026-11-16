package dev.caerbannog.acceptance.planner

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import dev.caerbannog.acceptance.migrations.ScreenDriver
import dev.caerbannog.core.connections.ChipIcon
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.connections.RefreshSectionState
import dev.caerbannog.core.planner.NamingStyle
import dev.caerbannog.core.planner.PlannerViewModel
import dev.caerbannog.core.planner.ReviewView
import dev.caerbannog.core.planner.StepForm
import dev.caerbannog.core.topology.BindingsViewMode
import dev.caerbannog.core.topology.QueueDetailView
import dev.caerbannog.core.topology.QueueRowView
import dev.caerbannog.core.topology.QueueTypeFilter
import dev.caerbannog.core.topology.TopologyScreen as TopologyScreenKind
import dev.caerbannog.core.topology.TopologyState
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.ui.planner.PlannerEvents
import dev.caerbannog.ui.planner.PlannerScreen
import dev.caerbannog.ui.topology.TopologyEvents
import dev.caerbannog.ui.topology.TopologyScreen

/** M9-03: the real planner and Topology screens, drawn through the Compose UI test API and bound to the real view models (the pattern of M5-08c2). */

private val connectedChip = ConnectionControlState(
    chip = ConnectionChipState(ChipState.Connected, ChipIcon.Link, "uat-eu", null, null, null, "Connection uat-eu, Connected. Open connection menu", false),
    rows = emptyList(),
    emptyText = null,
    refresh = RefreshSectionState(RefreshInterval.Every5, enabled = true, note = "Not loaded yet"),
)

private val noControl = object : ConnectionControlEvents {
    override fun connect(name: String) = Unit
    override fun disconnect(name: String) = Unit
    override fun retry(name: String) = Unit
    override fun setRefreshInterval(interval: RefreshInterval) = Unit
    override fun refreshNow() = Unit
    override fun openConnections() = Unit
}

/** Every planner event goes to the view model; nothing else is in the way. */
internal class ViewModelPlannerEvents(private val vm: PlannerViewModel) : PlannerEvents {
    override fun openWizard() = vm.openWizard()
    override fun chooseKind(kind: String) = vm.chooseKind(kind)
    override fun updateForm(form: StepForm) = vm.updateForm(form)
    override fun next() = vm.next()
    override fun back() = vm.back()
    override fun cancelWizard() = vm.cancelWizard()
    override fun addToPlan() = vm.addToPlan()
    override fun selectStep(id: Int) = vm.selectStep(id)
    override fun editStep(id: Int) = vm.editStep(id)
    override fun removeStep(id: Int) = vm.removeStep(id)
    override fun moveStepUp(id: Int) = vm.moveStepUp(id)
    override fun moveStepDown(id: Int) = vm.moveStepDown(id)
    override fun setDescription(text: String) = vm.setDescription(text)
    override fun setVirtualHost(vhost: String) = vm.setVirtualHost(vhost)
    override fun setNamingStyle(style: NamingStyle) = vm.setNamingStyle(style)
    override fun chooseFolder() = vm.chooseFolder()
    override fun saveAsMigration() = vm.saveAsMigration()
    override fun dismiss() = vm.dismiss()
    override fun startNewPlan() = vm.startNewPlan()
    override fun confirm() = vm.confirm()
    override fun cancelQuestion() = vm.cancelQuestion()
    override fun retryDraft() = vm.retryDraft()
    override fun flush() = vm.flush()
    override fun selectReviewView(view: ReviewView) = vm.selectReviewView(view)
}

@OptIn(ExperimentalTestApi::class)
internal class PlannerScreenDriver(vm: PlannerViewModel) : ScreenDriver({
    val state by vm.state.collectAsState()
    PlannerScreen(state = state, events = ViewModelPlannerEvents(vm), control = connectedChip, controlEvents = noControl, onNavigate = {})
}) {
    private fun tagOf(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.TestTag)

    private fun DesktopComposeUiTest.nodesWithPrefix(prefix: String): List<SemanticsNode> =
        onAllNodes(SemanticsMatcher("tag starts with $prefix") { tagOf(it)?.startsWith(prefix) == true }, useUnmergedTree = true).fetchSemanticsNodes()

    /** The test tags drawn that start with [prefix], in the order the screen has them, without repeats. */
    fun tagsWithPrefix(prefix: String): List<String> = exec { waitForIdle(); nodesWithPrefix(prefix).mapNotNull { tagOf(it) }.distinct() }

    /** The content description of the node with [tag]; null when it is not drawn. */
    fun description(tag: String): String? = exec {
        waitForIdle()
        nodesWithPrefix(tag).firstOrNull { tagOf(it) == tag }?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    }

    /** Every node under the node tagged [tag] that offers an action that changes something: a click, a long click, a text. */
    fun actionsUnder(tag: String): List<String> = exec {
        waitForIdle()
        val acting = listOf(SemanticsActions.OnClick, SemanticsActions.OnLongClick, SemanticsActions.SetText, SemanticsActions.SetSelection)
        fun walk(n: SemanticsNode): List<String> =
            acting.filter { n.config.contains(it) }.map { "${it.name} on ${tagOf(n) ?: "an untagged node"}" } + n.children.flatMap { walk(it) }
        val root = nodesWithPrefix(tag).firstOrNull { tagOf(it) == tag } ?: error("nothing is drawn with the test tag $tag")
        walk(root)
    }

    /** Presses the keyboard's way through the nodes: each in turn takes focus; what a screen reader would say for each is returned in order. */
    fun focusInTurn(prefix: String): List<String> = exec {
        waitForIdle()
        val tags = nodesWithPrefix(prefix).mapNotNull { tagOf(it) }.distinct()
        tags.map { tag ->
            val node = onNodeWithTag(tag, useUnmergedTree = true)
            check(node.fetchSemanticsNode().config.contains(SemanticsActions.RequestFocus)) { "$tag cannot take focus, so Tab cannot reach it" }
            node.requestFocus()
            waitForIdle()
            val now = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
            check(now.getOrNull(SemanticsProperties.Focused) == true) { "$tag did not take focus" }
            now.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        }
    }

    /** True when the node can be typed into. */
    fun isEditable(tag: String): Boolean = exec {
        waitForIdle()
        val nodes = onAllNodes(SemanticsMatcher("tag $tag") { it.config.getOrNull(SemanticsProperties.TestTag) == tag }, useUnmergedTree = true).fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "nothing is drawn with the test tag $tag" }
        val c = nodes.first().config
        c.getOrNull(SemanticsActions.SetText) != null || c.getOrNull(SemanticsProperties.EditableText) != null
    }
}

/** The one planner screen of the running scenario, rebuilt when the view model is (a restart gives a new one). */
internal object PlannerScreens {
    private var drawn: Pair<PlannerViewModel, PlannerScreenDriver>? = null

    fun forViewModel(vm: PlannerViewModel): PlannerScreenDriver {
        drawn?.takeIf { it.first === vm }?.let { return it.second }
        drawn?.second?.dispose()
        return PlannerScreenDriver(vm).also { drawn = vm to it }
    }

    fun endScenario() {
        drawn?.second?.dispose()
        drawn = null
    }
}

private object NoTopologyEvents : TopologyEvents {
    override fun setShown(shown: Boolean) = Unit
    override fun refreshNow() = Unit
    override fun retry() = Unit
    override fun setTab(tab: TopologyTab) = Unit
    override fun setQueueFilter(filter: QueueTypeFilter) = Unit
    override fun setSearch(text: String) = Unit
    override fun selectVhost(name: String) = Unit
    override fun selectQueue(name: String?) = Unit
    override fun setShowDefaultExchange(show: Boolean) = Unit
    override fun setBindingsView(mode: BindingsViewMode) = Unit
    override fun nextPage() = Unit
    override fun previousPage() = Unit
}

/** The real Topology screen with one queue selected, to look at the controls it draws. */
@OptIn(ExperimentalTestApi::class)
internal class TopologyScreenDriver(queue: String) : ScreenDriver({
    TopologyScreen(
        state = TopologyState(
            screen = TopologyScreenKind.Ready, message = null, tab = TopologyTab.Queues, queueFilter = QueueTypeFilter.Quorum, vhost = "/", vhosts = listOf("/"),
            queues = listOf(QueueRowView(queue, "quorum", "0", "0", "0", "rabbit@node-1", "3 of 3", false)),
            selectedQueue = QueueDetailView(
                name = queue, typeText = "Quorum queue", leader = "rabbit@node-1", members = "node-1", membersOnline = "3 of 3", readyUnacked = "0 / 0",
                consumers = "0", policy = "none", bindings = emptyList(), memberOffline = false,
            ),
            total = 1, rangeText = "1 to 1 of 1",
        ),
        nowMillis = 0, events = NoTopologyEvents, control = connectedChip, controlEvents = noControl,
    )
}) {
    /** The words (lower case, whole words) of every control the screen draws: its text, description and tag. */
    fun controlWords(): Set<String> = exec {
        waitForIdle()
        fun words(n: SemanticsNode): List<String> =
            (n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                listOfNotNull(n.config.getOrNull(SemanticsProperties.TestTag))).joinToString(" ").lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        fun deep(n: SemanticsNode): List<String> = words(n) + n.children.flatMap { deep(it) }
        onAllNodes(SemanticsMatcher("a control") { it.config.contains(SemanticsActions.OnClick) }, useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { deep(it) }.toSet()
    }
}
