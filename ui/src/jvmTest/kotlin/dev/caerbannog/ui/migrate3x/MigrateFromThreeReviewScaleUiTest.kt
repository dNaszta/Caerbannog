package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrate3x.Acknowledgement
import dev.caerbannog.core.migrate3x.ItemDecision
import dev.caerbannog.core.migrate3x.SKIPPED_GROUP_KEY
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import dev.caerbannog.ui.migrate3x.ReviewScreenFiles.NOT_DURABLE_GROUP
import dev.caerbannog.ui.migrate3x.ReviewScreenFiles.ORDERS_GROUP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-06b: 60,000 items, and a 400 dp window. Rows stay lazy (`RowSource`), the bar and the buttons of the sheets stay reachable. */
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeReviewScaleUiTest {
    private val tickTag = "migrate3x-tick-${Acknowledgement.OrderingByPriorityMayChange.name}"
    private val sixty = DefinitionsFilesBig.sixtyThousand

    private fun androidx.compose.ui.test.DesktopComposeUiTest.drawnRows(prefix: String, upTo: Int = 600) = (0 until upTo).count { nodes("$prefix$it").isNotEmpty() }

    @Test
    fun `60000 pending items - the bar counts them, a group draws a handful of rows and the first sheet opens`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(sixty)
        showLive(rig)
        assertTextHas("migrate3x-pending-count", "60,000 items need a decision")
        assertEnabled("migrate3x-generate", false)
        val key = ORDERS_GROUP
        revealGroup(rig.report.view, key)
        click("migrate3x-group-$key")
        revealRow(rig.report.view, key, 0)
        val rows = drawnRows("migrate3x-row-$key-")
        assertTrue(rows in 1..200, "a lazy list draws what is visible, drew $rows of 60,000")
        val buttons = drawnRows("migrate3x-review-$key-")
        assertTrue(buttons in 1..200, "drew $buttons Review buttons")
        click("migrate3x-review-$key-0")
        assertDrawn("migrate3x-review-sheet")
        assertTextHas("migrate3x-review-sheet", "item 1 of 60,000")
    }

    @Test
    fun `60000 skipped items - the Skipped group counts them and draws a handful of rows when opened`() = runDesktopComposeUiTest(1280, 900) {
        val first = LiveRig(sixty)
        first.core.items.forEach { first.core.decide(it, ItemDecision.Skip) }
        val rig = LiveRig(sixty, holder = first.holder, open = false)
        showLive(rig)
        assertEquals(SKIPPED_GROUP_KEY, rig.report.view.groups.last().key)
        revealGroup(rig.report.view, SKIPPED_GROUP_KEY)
        assertTrue(textOf("migrate3x-group-$SKIPPED_GROUP_KEY").contains("60,000 items"), textOf("migrate3x-group-$SKIPPED_GROUP_KEY"))
        click("migrate3x-group-$SKIPPED_GROUP_KEY")
        revealRow(rig.report.view, SKIPPED_GROUP_KEY, 0)
        val rows = drawnRows("migrate3x-row-$SKIPPED_GROUP_KEY-")
        assertTrue(rows in 1..200, "drew $rows of 60,000")
        assertTrue(drawnRows("migrate3x-unskip-$SKIPPED_GROUP_KEY-") in 1..200)
    }

    @Test
    fun `a result with 60000 left-out rows is a lazy list`() = runDesktopComposeUiTest(1280, 900) {
        val first = LiveRig(sixty)
        first.core.items.forEach { first.core.decide(it, ItemDecision.Skip) }
        val rig = LiveRig(sixty, FakeMigrationFolders("/migrations"), holder = first.holder, open = false)
        showLive(rig)
        click("migrate3x-generate")
        assertDrawn("migrate3x-result")
        assertDrawn("migrate3x-result-left-out")
        val drawn = onAllNodesWithText("skipped by you", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue(drawn in 1..400, "a lazy result draws what is visible, drew $drawn of 60,000 rows")
    }

    // ---- narrow window ----

    @Test
    fun `at 400 dp the bar and the buttons of the sheet stay reachable`() = runDesktopComposeUiTest(400, 800) {
        val rig = LiveRig(ReviewScreenFiles.mixed)
        showLive(rig)
        assertReachable("migrate3x-pending-count")
        assertReachable("migrate3x-generate")
        clickReview(rig, NOT_DURABLE_GROUP, "temp")
        listOf("migrate3x-accept", "migrate3x-skip", "migrate3x-replace", "migrate3x-sheet-close").forEach { assertReachable(it) }
        assertReachable("migrate3x-accept-block")
    }

    @Test
    fun `at 400 dp the Replace sheet keeps its text, its error and its buttons reachable`() = runDesktopComposeUiTest(400, 800) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-replace")
        listOf("migrate3x-replace-text", "migrate3x-replace-save", "migrate3x-replace-back").forEach { assertReachable(it) }
        onNodeWithTag("migrate3x-replace-text").performTextReplacement("not json")
        click("migrate3x-replace-save")
        assertReachable("migrate3x-replace-error")
    }

    @Test
    fun `at 400 dp the result and the folder problem keep their buttons reachable`() = runDesktopComposeUiTest(400, 800) {
        val folders = FakeMigrationFolders("/migrations").also { it.folder("/migrations").readOnly = true }
        val rig = LiveRig(ReviewScreenFiles.orders, folders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag); click("migrate3x-accept"); click("migrate3x-generate")
        assertReachable("migrate3x-trouble")
        listOf("migrate3x-trouble-choose-folder", "migrate3x-trouble-export").forEach { assertReachable(it) }
        click("migrate3x-trouble-export")
        assertReachable("migrate3x-result")
    }
}

internal object DefinitionsFilesBig {
    val sixtyThousand: String = dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles.classicQueues(60_000)
}
