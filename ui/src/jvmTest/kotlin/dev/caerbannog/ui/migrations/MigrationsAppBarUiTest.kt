package dev.caerbannog.ui.migrations

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.PlannerEntry
import dev.caerbannog.ui.layout.assertIntact
import dev.caerbannog.ui.planner.node
import dev.caerbannog.ui.streams.connected
import dev.caerbannog.ui.streams.noControlEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-03 step 4, round 2: the Migrations app bar WITH the connection chip (the screenshots of the entry draw it without), after the shared
 * AppBar spacing change. At 1280 x 800 the title, the five buttons and the chip are on one line even with the longest entry label
 * ("Continue plan"); at 1024 px the bar may wrap but every button and the chip stay on screen and the title is kept.
 */
@OptIn(ExperimentalTestApi::class)
class MigrationsAppBarUiTest {
    private val tags = listOf("migrations:newPlanBtn", "migrations:migrate3xBtn", "migrations:importBtn", "migrations:exportBtn", "migrations:applyPendingBtn", "connectionChip")

    private fun DesktopComposeUiTest.show(entry: PlannerEntry) {
        setContent {
            MigrationsScreen(
                state = dev.caerbannog.core.migrations.MigrationsScreenState(profileName = "uat-eu", isApplyPendingEnabled = true),
                onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {},
                onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {},
                control = connected, controlEvents = noControlEvents, plannerEntry = entry,
            )
        }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.tops(): List<Float> = tags.map { node(it).fetchSemanticsNode().boundsInRoot.top }

    @Test
    fun `at 1280 px the title, five buttons and the chip share one line, whatever the entry label`() = runDesktopComposeUiTest(1280, 800) {
        listOf(PlannerEntry.NewPlan, PlannerEntry.ContinuePlan(10, "x"), PlannerEntry.DraftUnreadable("/p")).forEach { entry ->
            show(entry)
            val tops = tops()
            assertTrue(tops.all { kotlin.math.abs(it - tops.first()) < 8f }, "the app bar wrapped for ${entry.label}: $tops")
            assertTrue(node("connectionChip").fetchSemanticsNode().boundsInRoot.right <= 1280f - 24f, "the chip ends past the 24 px page padding for ${entry.label}")
            assertIntact(tags.drop(1).dropLast(1), 1280, 800)
        }
    }

    @Test
    fun `at 1024 px every button and the chip stay on screen and the title is kept`() = runDesktopComposeUiTest(1024, 768) {
        show(PlannerEntry.ContinuePlan(10, "x"))
        tags.forEach { node(it).assertIsDisplayed() }
        node("migrations:title").assertIsDisplayed()
        assertEquals(1, onAllNodesWithTagCount("migrations:newPlanBtn"))
    }

    private fun DesktopComposeUiTest.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
}
