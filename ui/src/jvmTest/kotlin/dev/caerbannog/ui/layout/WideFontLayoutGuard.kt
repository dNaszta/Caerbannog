package dev.caerbannog.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.PlannerEntry
import dev.caerbannog.core.planner.PlannerMode
import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.ui.PinnedFontTheme
import dev.caerbannog.ui.migrations.MigrationsScreen
import dev.caerbannog.ui.planner.EditFileUiFixtures
import dev.caerbannog.ui.planner.PlannerOver
import dev.caerbannog.ui.planner.UiRig
import dev.caerbannog.ui.planner.node
import dev.caerbannog.ui.streams.connected
import dev.caerbannog.ui.streams.noControlEvents
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex

/**
 * M2-21 scenarios of the five layout tests that fail on the Linux CI runner, each run with the font a Linux machine draws with (DejaVu Sans, [dev.caerbannog.ui.PinnedFont])
 * or with the machine's own default font ([pinned] = false, used only to print the Mac headroom). Every function returns what it measured: [Run.problems] is
 * empty when the promise holds, [Run.facts] are numbers for the report. See `WideFontLayoutGuardUiTest` for which promise each one asserts.
 */
internal class Run(val facts: MutableList<String> = mutableListOf(), val problems: MutableList<String> = mutableListOf())

@Composable
private fun Fonted(pinned: Boolean, content: @Composable () -> Unit) = if (pinned) PinnedFontTheme(content) else content()

private fun r1(f: Float) = (Math.round(f * 10f) / 10f).toString()

@OptIn(ExperimentalTestApi::class)
internal object WideFontScenarios {
    /** 1. `MigrationsAppBarUiTest` at 1280: title, five buttons and the chip on one line, for every entry label. */
    fun appBar(pinned: Boolean): Run = Run().also { run ->
        val tags = listOf("migrations:title", "migrations:newPlanBtn", "migrations:migrate3xBtn", "migrations:importBtn", "migrations:exportBtn", "migrations:applyPendingBtn", "connectionChip")
        runDesktopComposeUiTest(1280, 800) {
            listOf(PlannerEntry.NewPlan, PlannerEntry.ContinuePlan(10, "x"), PlannerEntry.DraftUnreadable("/p")).forEach { entry ->
                setContent {
                    Fonted(pinned) {
                        MigrationsScreen(
                            state = MigrationsScreenState(profileName = "uat-eu", isApplyPendingEnabled = true),
                            onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {},
                            onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {},
                            control = connected, controlEvents = noControlEvents, plannerEntry = entry,
                        )
                    }
                }
                waitForIdle()
                val b = tags.associateWith { node(it).fetchSemanticsNode().boundsInRoot }
                val bar = tags.drop(1).map { b.getValue(it) }
                val widthSum = bar.sumOf { it.width.toDouble() }.toFloat()
                // The bar's own title is the text node "Migrations" at the top of the window (the page title below it is tagged migrations:title).
                val barTitle = onAllNodes(androidx.compose.ui.test.hasText("Migrations"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.minByOrNull { it.top }!!
                val title = barTitle
                val firstLeft = bar.minOf { it.left }
                run.facts += "app bar ${entry.label}: bar title=${title.left.toInt()}..${title.right.toInt()} (top ${title.top.toInt()}), leftmost button left=${r1(firstLeft)}, gap from the bar title to the first button=${r1(firstLeft - title.right)} px (the row spacing is 8, so a gap above 8 is headroom while the line is not wrapped), " +
                    "sum of widths of 5 buttons and chip=${r1(widthSum)} px, chip=${b.getValue("connectionChip")}, tops=${bar.map { it.top }}"
                val tops = bar.map { it.top }
                if (!tops.all { Math.abs(it - tops.first()) < 8f }) run.problems += "the app bar wrapped for ${entry.label}: $tops"
                val chip = b.getValue("connectionChip")
                if (chip.right > 1280f - 24f) run.problems += "the chip of ${entry.label} ends at ${chip.right}, past the 24 px page padding"
                // Headroom: the slack left on the line between the bar title and the first button, after the 8 dp row spacing (buttons sit at the end);
                // M2-21 D1 asks for at least 8 px under DejaVu Sans, so that a small font change does not wrap the chip again.
                val headroom = firstLeft - title.right - 8f
                run.facts += "app bar ${entry.label}: headroom=${r1(headroom)} px"
                if (pinned && headroom < 8f) run.problems += "the app bar of ${entry.label} has only ${r1(headroom)} px of headroom, 8 are asked"
                run.problems += intactProblems(tags.drop(1).dropLast(1), 1280, 800)
            }
        }
    }

    private val file = MigrationFile("0005", "d", listOf(MigrationAction.DeclareQueue("orders")), """{ "version": "0005" }""", "abc0005")
    private val migrationsState = MigrationsScreenState(
        entries = listOf(MigrationEntry(file, MigrationStatus.Pending)), selectedVersion = "0005", selectedFile = file, profileName = "uat-eu",
        dryRun = MigrationDryRun("0005", file.up.map { ActionDryRun(it, allowed = true) }, canApply = true),
    )

    /** 2. `MigrationsEditStepsUiTest` at font scale 1.5, 1280 wide: the four buttons of the action row on screen. */
    fun actionRow(pinned: Boolean, width: Int = 1280): Run = Run().also { run ->
        val tags = listOf("migrations:editStepsBtn", "migrations:editFileBtn", "migrations:validateBtn", "migrations:applySingleBtn")
        runDesktopComposeUiTest(width, 800) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    Fonted(pinned) { MigrationsScreen(migrationsState, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, plannerEntry = PlannerEntry.NewPlan) }
                }
            }
            waitForIdle()
            tags.forEach { tag ->
                val n = node(tag).fetchSemanticsNode()
                val bd = n.boundsInRoot
                run.facts += "action row $width wide, scale 1.5: $tag left=${r1(bd.left)} right=${r1(bd.right)} top=${r1(bd.top)} size=${bd.width.toInt()}x${bd.height.toInt()}"
            }
            run.problems += intactProblems(tags, width, 800, scroll = true)
        }
    }

    /** 3. `PlannerEditFileNarrowExploratoryUiTest` at 900 wide, font scale 1.5: the file bar, the pills and the buttons on screen. */
    fun editFileBar(pinned: Boolean): Run = Run().also { run ->
        runDesktopComposeUiTest(900, 800) {
            val raw = EditFileUiFixtures.rawOf(EditFileUiFixtures.threeSteps)
            val r = UiRig(mode = PlannerMode.EditFile("0005", MigrationParser.computeChecksum(raw)), rawFiles = listOf(raw))
            setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) { Fonted(pinned) { PlannerOver(r) } } }
            waitForIdle()
            r.queue("invoices")
            waitForIdle()
            listOf("planner:saveBtn", "planner:discardBtn", "planner:editPill", "planner:unsavedPill").forEach { tag ->
                val bd = node(tag).fetchSemanticsNode().boundsInRoot
                run.facts += "file bar 900 wide, scale 1.5: $tag left=${r1(bd.left)} right=${r1(bd.right)} top=${r1(bd.top)} size=${bd.width.toInt()}x${bd.height.toInt()}"
            }
            run.problems += intactProblems(listOf("planner:saveBtn", "planner:discardBtn"), 900, 800)
            run.problems += intactProblems(listOf("planner:editPill", "planner:unsavedPill"), 900, 800, minWidthPx = 0f)
            r.close()
        }
    }

    /** Scrolls the review card into view and asserts that it then ends inside the 800 px window. */
    private fun DesktopComposeUiTest.reachByScroll(run: Run, where: String) {
        node("planner:reviewCard").performScrollTo()
        waitForIdle()
        val card = node("planner:reviewCard").fetchSemanticsNode()
        val bottom = card.positionInRoot.y + card.size.height
        run.facts += "$where: after scrolling to the card its bottom is $bottom"
        if (bottom > 801f || card.positionInRoot.y < 0f) run.problems += "$where: the review card cannot be scrolled into the window (top ${card.positionInRoot.y}, bottom $bottom)"
    }

    private fun DesktopComposeUiTest.pageScrollbar() =
        onAllNodesWithTagCount("planner:pageScrollbar")

    private fun DesktopComposeUiTest.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    /**
     * 4. `PlannerCheckExploratoryUiTest` at 1280 x 800: a checked small plan, the review card against the window. Returns the overshoot as a fact; the problem is raised
     * only when the card is out of the window and the stacked page has no scrollbar (the page scrolls by design when steps and card floor do not fit).
     */
    fun checkedCard(pinned: Boolean): Run = Run().also { run ->
        runDesktopComposeUiTest(1280, 800) {
            val port = FakeDeclareCheck()
            val r = UiRig(declareCheck = port) { threeSteps() }
            r.state.plan.steps.forEach { s ->
                when (val a = s.action) {
                    is MigrationAction.DeclareQueue -> port.answerNamed(a.name, BrokerFinding.Absent)
                    is MigrationAction.DeclareExchange -> port.answerNamed(a.name, BrokerFinding.Same)
                    else -> port.answer(a, BrokerFinding.Absent)
                }
            }
            setContent { Fonted(pinned) { PlannerOver(r) } }
            waitForIdle()
            node("planner:checkBtn").performClick(); waitForIdle()
            val card = node("planner:reviewCard").fetchSemanticsNode()
            val bottom = card.positionInRoot.y + card.size.height
            val bar = pageScrollbar() > 0
            run.facts += "checked card at 1280x800: top=${card.positionInRoot.y} height=${card.size.height} bottom=$bottom (window 800, over by ${bottom - 800f}); page scrollbar drawn=$bar"
            if (bottom > 801f && !bar) run.problems += "the review card is cut off by the window ($bottom > 800) and the page has no scrollbar"
            if (bottom > 801f) reachByScroll(run, "checked card at 1280x800")
            r.close()
        }
    }

    /** 5. `PlanPairLayoutExploratoryUiTest`: stacked card against the window at 1025 x 800 for 3, 7, 12 and 30 steps. */
    fun stackedCard(pinned: Boolean): Run = Run().also { run ->
        for (n in listOf(1, 3, 7, 12, 30)) runDesktopComposeUiTest(1025, 800) {
            val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) { (1..n).forEach { i -> if (i % 3 == 0) stream("s$i", withResponse = false) else queue("q$i") } }
            setContent { Fonted(pinned) { PlannerOver(r) } }
            waitForIdle()
            val card = node("planner:reviewCard").fetchSemanticsNode()
            val list = node("planner:stepList").fetchSemanticsNode()
            val bottom = card.positionInRoot.y + card.size.height
            val stacked = card.positionInRoot.y >= list.positionInRoot.y + list.size.height - 1f
            val bar = pageScrollbar() > 0
            run.facts += "1025x800, $n steps: stacked=$stacked card bottom=$bottom (over by ${bottom - 800f}); page scrollbar drawn=$bar"
            if (stacked && bottom > 801f && !bar) run.problems += "1025 x 800, $n steps: the stacked card ends at $bottom, below the window, and the page has no scrollbar"
            if (stacked && bottom > 801f) reachByScroll(run, "1025x800, $n steps")
            node("planner:stepList").performScrollToIndex(n - 1)
            waitForIdle()
            r.close()
        }
    }

    /** 6. `MigrationsEditStepsNarrowExploratoryUiTest` at 1000 wide, font scale 1.5: the four buttons with Edit steps (F2). */
    fun narrowActionRow(pinned: Boolean): Run = Run().also { run ->
        runDesktopComposeUiTest(1000, 800) {
            val s = migrationsState.copy(planOpening = dev.caerbannog.core.planner.PlanOpeningDecision.Openable)
            setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) { Fonted(pinned) { MigrationsScreen(s, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}) } } }
            waitForIdle()
            val tags = listOf("migrations:editStepsBtn", "migrations:editFileBtn", "migrations:validateBtn", "migrations:applySingleBtn")
            tags.forEach { run.facts += "action row 1000 wide, scale 1.5: $it ${node(it).fetchSemanticsNode().boundsInRoot}" }
            run.problems += intactProblems(tags, 1000, 800, scroll = true)
        }
    }

    /**
     * 7. M2-21b: the Migrations page behind a drawer stand-in ([WithDrawerStandIn]), Openable file, at [width] and font [scale]: the four buttons of the detail panel are intact.
     * [drawer] = false shows the screen alone (the whole window, as the older tests do). NOTE: 1000 wide WITH the drawer (screen 696 dp, detail 284 px side by side) makes
     * `waitForIdle` never return while scrolling to `migrations:validateBtn` (measured 2026-11-23: no failure, a hang), so that case is not in the guard; finding for the architect.
     */
    fun detailBehindDrawer(pinned: Boolean, width: Int, scale: Float, drawer: Boolean = true): Run = Run().also { run ->
        val tags = listOf("migrations:editStepsBtn", "migrations:editFileBtn", "migrations:validateBtn", "migrations:applySingleBtn")
        runDesktopComposeUiTest(width, 800) {
            val s = migrationsState.copy(planOpening = dev.caerbannog.core.planner.PlanOpeningDecision.Openable)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    Fonted(pinned) { if (drawer) WithDrawerStandIn { MigrationsScreen(s, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}) } else MigrationsScreen(s, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}) }
                }
            }
            waitForIdle()
            tags.forEach { run.facts += "detail $width wide, drawer=$drawer, scale $scale: $it ${node(it).fetchSemanticsNode().boundsInRoot}" }
            run.problems += intactProblems(tags, width, 800, scroll = true)
        }
    }
}
