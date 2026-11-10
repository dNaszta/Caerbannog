package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import dev.caerbannog.core.migrate3x.ReportView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalClipboard
import dev.caerbannog.core.migrate3x.CompatibilityAnalyzer
import dev.caerbannog.core.migrate3x.CompatibilityReportText
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.DefinitionsParser
import dev.caerbannog.core.migrate3x.ExportNotice
import dev.caerbannog.core.migrate3x.HeldAnalysis
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.ReportViewBuilder
import dev.caerbannog.core.migrate3x.Severity
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import kotlinx.serialization.json.JsonPrimitive
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.assertTrue

/** M8-06a: states, events and helpers shared by the Migrate from 3.x screen tests. Events are recorded as short strings. */
@OptIn(ExperimentalTestApi::class)
internal class MigrateCalls {
    val events = mutableListOf<String>()
    val copied = mutableListOf<String>()
}

internal object MigrateScreenFiles {
    const val SENTINEL_HASH = "SENTINEL-HASH"
    const val SENTINEL_PASSWORD = "SENTINEL-PASSWORD"

    val mixed: String = DefinitionsFiles.file(
        queues = listOf(
            DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("invoices"),
            DefinitionsFiles.queue("temp", durable = false),
            DefinitionsFiles.queue("jobs", arguments = mapOf("x-max-priority" to JsonPrimitive(5))),
            DefinitionsFiles.queue("modern", type = "quorum"),
        ),
        exchanges = listOf(DefinitionsFiles.exchange("events-exchange")),
        bindings = listOf(DefinitionsFiles.binding("events-exchange", "modern", "k")),
        policies = listOf(
            DefinitionsFiles.policy("ha-all", "^orders", mapOf("ha-mode" to JsonPrimitive("all"))),
            DefinitionsFiles.policy("lookahead", "^(?!amq\\.).*", mapOf("max-length" to JsonPrimitive(10))),
        ),
    )

    val withUser: String get() = DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER)

    fun held(text: String = mixed, label: String = "legacy.json"): HeldAnalysis {
        val doc = (DefinitionsParser.parse(text) as? DefinitionsParseResult.Parsed)?.document ?: error("the test file was not parsed")
        return HeldAnalysis(label, doc, CompatibilityAnalyzer.analyze(doc))
    }

    fun report(
        text: String = mixed,
        label: String = "legacy.json",
        selected: Set<Severity> = Severity.entries.toSet(),
        notice: ExportNotice? = null,
        open: (HeldAnalysis) -> Set<String> = { emptySet() },
    ): MigrateFromThreeState.Report {
        val held = held(text, label)
        return MigrateFromThreeState.Report(ReportViewBuilder.build(held, selected, open(held)), notice, dev.caerbannog.core.migrate3x.ReviewState.NONE)
    }

    /** Opens the first group whose rule is [rule] (the key starts with the rule id). */
    fun openRule(held: HeldAnalysis, rule: String): Set<String> =
        ReportViewBuilder.build(held).groups.filter { it.key.startsWith(rule) }.map { it.key }.toSet()

    fun allGroups(held: HeldAnalysis): Set<String> = ReportViewBuilder.build(held).groups.map { it.key }.toSet()

    fun text(held: HeldAnalysis): String = CompatibilityReportText.render(held.report)
}

/** Records what was copied; the app never reads the clipboard. */
internal class RecordingClipboard : Clipboard {
    val copied = mutableListOf<String?>()
    override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("fake")
    override suspend fun getClipEntry(): ClipEntry? = null
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        copied += clipEntry?.let { (it.nativeClipEntry as Transferable).getTransferData(DataFlavor.stringFlavor) as String }
    }
}

internal fun SemanticsNode.allText(): String {
    val own = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
    return (listOf(own, description) + children.map { it.allText() }).filter { it.isNotEmpty() }.joinToString(" ")
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.show(state: MigrateFromThreeState, calls: MigrateCalls = MigrateCalls(), text: String = "", clipboard: RecordingClipboard = RecordingClipboard()) {
    setContent {
        CompositionLocalProvider(LocalClipboard provides clipboard) {
            MigrateFromThreeScreen(
                state = state,
                reportText = { calls.copied += text; text },
                onChooseFile = { calls.events += "chooseFile" },
                onGiveVirtualHost = { calls.events += "vhost:$it" },
                onCancelPrompt = { calls.events += "cancel" },
                onToggleSeverity = { calls.events += "severity:$it" },
                onToggleGroup = { calls.events += "group:$it" },
                onExport = { calls.events += "export" },
                onDismissNotice = { calls.events += "dismissNotice" },
            )
        }
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.nodes(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.textOf(tag: String): String {
    val found = nodes(tag)
    assertTrue(found.isNotEmpty(), "nothing is drawn with the test tag $tag")
    return found.first().allText()
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.assertDrawn(tag: String) = assertTrue(nodes(tag).isNotEmpty(), "nothing is drawn with the test tag $tag")

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.assertNotDrawn(tag: String) = assertTrue(nodes(tag).isEmpty(), "$tag should not be drawn but ${nodes(tag).size} node(s) are")

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.assertTextHas(tag: String, vararg parts: String) {
    val text = textOf(tag)
    for (part in parts) assertTrue(text.contains(part), "the node $tag should say \"$part\" but says \"$text\"")
}

/** Every node of the screen, as text: texts and content descriptions, for the "no secret on the screen" checks. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.wholeScreenText(): String =
    onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().joinToString("\n") { it.allText() } // a tooltip is a popup: a second root

// ---- single lazy column (owner decision 2026-11-10): banner, summary and chips are the first items of ONE lazy list, tagged `migrate3x-report-list` ----

internal const val REPORT_LIST_TAG = "migrate3x-report-list"

/** Items before the first group: the loss banner (only when there is a loss block), the summary and the chips. */
internal fun ReportView.leadingItems(): Int = (if (lossBlock != null) 1 else 0) + 2

/** Index in the lazy list of the header of [key]; each open group adds its rows right after its header. */
internal fun ReportView.indexOfGroup(key: String): Int {
    var index = leadingItems()
    for (g in groups) { if (g.key == key) return index; index += 1 + rows(g.key).size }
    error("no group $key")
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.scrollList(index: Int) { onNodeWithTag(REPORT_LIST_TAG).performScrollToIndex(index); waitForIdle() }

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.revealTop(): Unit = scrollList(0)
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.revealGroup(view: ReportView, key: String): Unit = scrollList(view.indexOfGroup(key))
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.revealRow(view: ReportView, key: String, row: Int): Unit = scrollList(view.indexOfGroup(key) + 1 + row)
