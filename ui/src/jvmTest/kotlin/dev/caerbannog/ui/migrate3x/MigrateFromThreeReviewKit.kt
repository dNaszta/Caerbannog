package dev.caerbannog.ui.migrate3x

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.caerbannog.core.migrate3x.Acknowledgement
import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.ConversionReview
import dev.caerbannog.core.migrate3x.FileLoadResult
import dev.caerbannog.core.migrate3x.ItemKind
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.MigrateFromThreeViewModel
import dev.caerbannog.core.migrate3x.ReviewItem
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive

/**
 * M8-06b: a real view model (fakes behind it, `Dispatchers.Unconfined`, so every event has run when the call returns) shown by the real
 * screen. The tests click tags and read the screen and the view model, so the screen and the events are proved together.
 */
internal class LiveRig(
    text: String,
    val folders: FakeMigrationFolders = FakeMigrationFolders("/migrations"),
    label: String = "legacy.json",
    val holder: AnalysisHolder = AnalysisHolder(),
    /** False: do not open the file; the view model starts from [holder], like a window that was rebuilt (the held analysis and its decisions survive). */
    open: Boolean = true,
) {
    val path = "/data/$label"
    val saver = FakeReportFileSaver()
    private val loader = FakeDefinitionsFileLoader(mapOf(path to FileLoadResult.Loaded(text)))
    val vm = MigrateFromThreeViewModel(loader, FakeDefinitionsFileChooser(), saver, holder, folders.ports, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
    val calls = mutableListOf<String>()

    init { if (open) vm.open(path) }

    val report: MigrateFromThreeState.Report get() = vm.state.value as? MigrateFromThreeState.Report ?: error("expected the report, the state is ${vm.state.value}")
    val core: ConversionReview get() = holder.held.value!!.review
    fun q(name: String) = ReviewItem(ItemKind.Queue, "/", name)
}

internal object ReviewScreenFiles {
    const val ORDERS_GROUP = "classic-not-replicated:warning"
    const val NOT_DURABLE_GROUP = "queue-not-durable:blocker"

    fun classic(vararg names: String): String = DefinitionsFiles.file(queues = names.map { DefinitionsFiles.queue(it) })
    val orders: String = classic("orders")
    val three: String = classic("orders", "invoices", "jobs")
    val mixed: String = DefinitionsFiles.file(
        queues = listOf(
            DefinitionsFiles.queue("orders"),
            DefinitionsFiles.queue("prio", arguments = mapOf("x-max-priority" to JsonPrimitive(5))),
            DefinitionsFiles.queue("temp", durable = false),
            DefinitionsFiles.queue("modern", type = "quorum"),
        ),
    )
    val twoVhosts: String = DefinitionsFiles.file(
        queues = listOf(DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("billing", vhost = "other")),
        vhosts = listOf("/", "other"),
    )
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.showLive(rig: LiveRig, clipboard: RecordingClipboard = RecordingClipboard()) {
    val vm = rig.vm
    val events = object : ReviewEvents {
        override fun openItem(item: ReviewItem) = vm.openItem(item)
        override fun closeItem() = vm.closeItem()
        override fun chooseAlternative(findingId: String, index: Int) = vm.chooseAlternative(findingId, index)
        override fun setTick(ack: Acknowledgement, on: Boolean) = vm.setTick(ack, on)
        override fun accept() { rig.calls += "accept"; vm.accept() }
        override fun skip() = vm.skip()
        override fun unskip(item: ReviewItem) = vm.unskip(item)
        override fun startReplace() = vm.startReplace()
        override fun setReplaceText(text: String) = vm.setReplaceText(text)
        override fun submitReplace(text: String) { rig.calls += "submitReplace"; vm.submitReplace(text) }
        override fun backToProposal() = vm.backToProposal()
        override fun showNextUndecided() = vm.showNextUndecided()
        override fun generate() { rig.calls += "generate"; vm.generate() }
        override fun generateAgain() { rig.calls += "generateAgain"; vm.generateAgain() }
        override fun cancelGenerateAgain() = vm.cancelGenerateAgain()
        override fun chooseFolder() = vm.chooseFolder()
        override fun retrySave() = vm.retrySave()
        override fun exportFiles() = vm.exportFiles()
        override fun dismissResult() = vm.dismissResult()
    }
    setContent {
        val state by vm.state.collectAsState()
        CompositionLocalProvider(LocalClipboard provides clipboard) {
            MigrateFromThreeScreen(
                state = state,
                reportText = vm::reportText,
                onChooseFile = vm::chooseFile,
                onGiveVirtualHost = vm::giveVirtualHost,
                onCancelPrompt = { vm.cancelLoading(); vm.cancelPrompt() },
                onToggleSeverity = vm::toggleSeverity,
                onToggleGroup = vm::toggleGroup,
                onExport = vm::exportReportJson,
                onDismissNotice = vm::dismissNotice,
                onReportCopied = vm::reportCopied,
                review = events,
            )
        }
    }
    waitForIdle()
}

/** Opens [key] by clicking its header when it is closed, and returns the index of the row of [name] in it. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.openGroupAndFind(rig: LiveRig, key: String, name: String): Int {
    if (rig.report.view.groups.first { it.key == key }.open.not()) {
        revealGroup(rig.report.view, key)
        onNodeWithTag("migrate3x-group-$key").performClick(); waitForIdle()
    }
    val rows = rig.report.view.rows(key)
    return (0 until rows.size).first { rows.at(it).name.orEmpty() == name }
}

/** Clicks the Review (or Change) button of the row of [name] in the group [key]. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.clickReview(rig: LiveRig, key: String, name: String) {
    val index = openGroupAndFind(rig, key, name)
    revealRow(rig.report.view, key, index)
    onNodeWithTag("migrate3x-review-$key-$index").performClick(); waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.assertEnabled(tag: String, enabled: Boolean) {
    if (enabled) onNodeWithTag(tag).assertIsEnabled() else onNodeWithTag(tag).assertIsNotEnabled()
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.click(tag: String) { onNodeWithTag(tag).performClick(); waitForIdle() }

/** Reachable: shown, or shown after scrolling the container it sits in (a sheet or panel that scrolls is fine; a button that never appears is not). */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.assertReachable(tag: String) {
    val node = onNodeWithTag(tag)
    try { node.performScrollTo() } catch (_: AssertionError) { /* not inside a scrollable container: it must be displayed as it is */ }
    waitForIdle()
    node.assertIsDisplayed()
}
