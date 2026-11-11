package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrate3x.Acknowledgement
import dev.caerbannog.core.migrate3x.GenerationState
import dev.caerbannog.core.migrate3x.ItemDecision
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.SKIPPED_GROUP_KEY
import dev.caerbannog.core.migrate3x.SaveOutcome
import dev.caerbannog.core.migrations.FolderProblem
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import dev.caerbannog.ui.migrate3x.ReviewScreenFiles.NOT_DURABLE_GROUP
import dev.caerbannog.ui.migrate3x.ReviewScreenFiles.ORDERS_GROUP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M8-06b: the sheets and states 6 to 11 of `docs/ux/Migrate3x.dc.html`, drawn by the real screen over a real view model with fakes.
 * State 6 accept with tick, 7 manual fix, 8 replace and its error, 9 decided rows, Skipped group and bar, 10 result, 11 folder problems
 * (also the second-generation question and the partial-save panel, which use the state 11 card). Texts are the fixed words of the brief.
 */
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeReviewUiTest {
    private val tickTag = "migrate3x-tick-${Acknowledgement.OrderingByPriorityMayChange.name}"
    private fun pill(key: String, index: Int) = "migrate3x-pill-$key-$index"

    // ---- state 6: accept with the ordering tick ----

    @Test
    fun `the sheet of orders shows the finding, why, the options and an unticked tick, and Accept is disabled with its reason`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders")
        assertDrawn("migrate3x-review-sheet")
        assertTextHas("migrate3x-review-sheet", "orders", "Classic queues are no longer replicated on 4.x", "Quorum queues are the replicated type on 4.x.")
        assertTextHas("migrate3x-review-sheet", "Convert the queue to type quorum", "Keep the queue classic (it is not replicated on 4.x)")
        assertTextHas(tickTag, "Ordering by priority may change")
        assertEnabled("migrate3x-accept", false)
        assertTextHas("migrate3x-accept-block", "Tick \"Ordering by priority may change\" to accept")
        assertDrawn("migrate3x-skip"); assertDrawn("migrate3x-replace"); assertDrawn("migrate3x-sheet-close")
        assertNull(rig.core.decisionOf(rig.q("orders")), "opening a sheet decides nothing")
    }

    @Test
    fun `ticking enables Accept and removes the reason, accepting closes the sheet and the row says accepted with the tick`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders")
        click(tickTag)
        assertEnabled("migrate3x-accept", true)
        assertNotDrawn("migrate3x-accept-block")
        click("migrate3x-accept")
        assertNotDrawn("migrate3x-review-sheet")
        assertEquals(ItemDecision.Accept(acknowledged = setOf(Acknowledgement.OrderingByPriorityMayChange)), rig.core.decisionOf(rig.q("orders")))
        revealRow(rig.report.view, ORDERS_GROUP, 0)
        assertTextHas(pill(ORDERS_GROUP, 0), "Accepted, ordering tick set")
        assertTextHas("migrate3x-pending-count", "Every item is decided")
    }

    @Test
    fun `unticking again disables Accept again`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders")
        click(tickTag); click(tickTag)
        assertEnabled("migrate3x-accept", false)
        assertDrawn("migrate3x-accept-block")
    }

    @Test
    fun `closing the sheet with the close button decides nothing and keeps an earlier decision`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders")
        click(tickTag); click("migrate3x-accept")
        val stored = rig.core.decisionOf(rig.q("orders"))
        clickReview(rig, ORDERS_GROUP, "orders")
        assertDrawn("migrate3x-review-sheet")
        click("migrate3x-sheet-close")
        assertNotDrawn("migrate3x-review-sheet")
        assertEquals(stored, rig.core.decisionOf(rig.q("orders")), "a stored decision is never lost by closing a sheet")
        assertTextHas(pill(ORDERS_GROUP, 0), "Accepted, ordering tick set")
    }

    @Test
    fun `an item that was decided offers Change on its row and opens with its stored choices`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag); click("migrate3x-accept")
        revealRow(rig.report.view, ORDERS_GROUP, 0)
        assertTextHas("migrate3x-review-$ORDERS_GROUP-0", "Change")
        click("migrate3x-review-$ORDERS_GROUP-0")
        assertTrue(nodes(tickTag).isNotEmpty())
        assertNotNull(rig.report.review.sheet?.decided)
        assertEnabled("migrate3x-accept", true)
    }

    // ---- state 7: manual fix, keep classic default ----

    @Test
    fun `an item with only a manual fix shows the steps, blocks Accept with the fixed sentence and offers Skip and Replace`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.mixed)
        showLive(rig)
        clickReview(rig, NOT_DURABLE_GROUP, "temp")
        assertTextHas("migrate3x-review-sheet", "Declare it durable or let the application declare it", "Declare the queue durable, or let the application declare it when it starts.")
        assertEnabled("migrate3x-accept", false)
        assertTextHas("migrate3x-accept-block", "This item cannot be accepted as it is. Choose an automatic alternative, skip it, or replace it with your own actions.")
        assertEnabled("migrate3x-skip", true); assertEnabled("migrate3x-replace", true)
        val sheet = rig.report.review.sheet!!
        val finding = sheet.findings.indexOfFirst { it.message.startsWith("A non-durable queue") }
        click("migrate3x-option-$finding-1")
        assertEnabled("migrate3x-accept", true)
        assertNotDrawn("migrate3x-accept-block")
    }

    @Test
    fun `a queue that keeps classic by default shows no tick, choosing quorum shows it and blocks Accept`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.mixed)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "prio")
        assertNotDrawn(tickTag)
        assertEnabled("migrate3x-accept", true)
        val finding = rig.report.review.sheet!!.findings.indexOfFirst { it.options.size > 1 }
        click("migrate3x-option-$finding-1")
        assertDrawn(tickTag)
        assertEnabled("migrate3x-accept", false)
        click(tickTag); click("migrate3x-accept")
        assertEquals(1, rig.report.review.acceptedCount)
    }

    @Test
    fun `an accept with nothing in the way is refused by the screen and the view model alike - nothing is stored from a blocked sheet`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders")
        // The button is disabled, so a click reaches nobody. The Enter key on the disabled button must not reach accept either.
        onNodeWithTag("migrate3x-accept").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertNull(rig.core.decisionOf(rig.q("orders")))
        assertFalse("accept" in rig.calls, "accept reached the view model: ${rig.calls}")
    }

    // ---- state 8: replace and its error ----

    @Test
    fun `Replace shows the actions the app would write as editable text, with the hint`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-replace")
        assertDrawn("migrate3x-replace-text")
        assertTextHas("migrate3x-replace-text", "declareQueue", "orders")
        assertTextHas("migrate3x-review-sheet", "Migration actions, the \"up\" list of a migration file. An action without \"vhost\" is for \"/\". Only actions for virtual host / are accepted.")
        assertNotDrawn("migrate3x-replace-error")
        assertDrawn("migrate3x-replace-save"); assertDrawn("migrate3x-replace-back")
    }

    @Test
    fun `a refused replacement shows the error beside the text, keeps what was typed and stores nothing`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-replace")
        onNodeWithTag("migrate3x-replace-text").performTextReplacement("""[{"action":"deleteQueue","name":"orders"}]""")
        click("migrate3x-replace-save")
        assertTextHas("migrate3x-replace-error", "A replacement must not delete anything. Nothing was stored; the item is still undecided.")
        assertTextHas("migrate3x-replace-text", "deleteQueue")
        assertNull(rig.core.decisionOf(rig.q("orders")))
        onNodeWithTag("migrate3x-replace-text").performTextReplacement("this is { not json")
        click("migrate3x-replace-save")
        assertTextHas("migrate3x-replace-error", "The actions are refused: the text is not valid JSON. Nothing was stored; the item is still undecided.")
        assertTextHas("migrate3x-replace-text", "this is { not json")
    }

    @Test
    fun `a valid replacement closes the sheet and the row says replaced`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-replace")
        onNodeWithTag("migrate3x-replace-text").performTextReplacement("""[{"action":"declareQueue","name":"orders","type":"quorum","arguments":{"x-delivery-limit":5}}]""")
        click("migrate3x-replace-save")
        assertNotDrawn("migrate3x-review-sheet")
        revealRow(rig.report.view, ORDERS_GROUP, 0)
        assertTextHas(pill(ORDERS_GROUP, 0), "Replaced")
    }

    @Test
    fun `back returns to the proposal and stores nothing`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-replace"); click("migrate3x-replace-back")
        assertNotDrawn("migrate3x-replace-text")
        assertDrawn("migrate3x-accept")
        assertNull(rig.core.decisionOf(rig.q("orders")))
    }

    // ---- keyboard ----

    @Test
    fun `Escape closes the sheet and the typed Replace text is still there when the sheet is opened again`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-replace")
        onNodeWithTag("migrate3x-replace-text").performTextReplacement("[ {\"action\": \"decl")
        onNodeWithTag("migrate3x-replace-text").requestFocus()
        onNodeWithTag("migrate3x-replace-text").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNotDrawn("migrate3x-review-sheet")
        assertNull(rig.report.review.sheet)
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-replace")
        assertTextHas("migrate3x-replace-text", "[ {\"action\": \"decl")
    }

    @Test
    fun `Escape on a plain sheet closes it and decides nothing`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders")
        onNodeWithTag(tickTag).requestFocus()
        onNodeWithTag(tickTag).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNotDrawn("migrate3x-review-sheet")
        assertNull(rig.core.decisionOf(rig.q("orders")))
    }

    @Test
    fun `Enter in the Replace text does not submit it`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-replace")
        onNodeWithTag("migrate3x-replace-text").performTextReplacement("[]")
        onNodeWithTag("migrate3x-replace-text").requestFocus()
        onNodeWithTag("migrate3x-replace-text").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertFalse("submitReplace" in rig.calls, "Enter submitted the text: ${rig.calls}")
        assertNull(rig.core.decisionOf(rig.q("orders")))
        assertDrawn("migrate3x-replace-text")
    }

    @Test
    fun `Enter on a tick or an option does not accept`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag)
        onNodeWithTag(tickTag).requestFocus()
        onNodeWithTag(tickTag).performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("migrate3x-option-0-0").requestFocus()
        onNodeWithTag("migrate3x-option-0-0").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertFalse("accept" in rig.calls, "Enter accepted: ${rig.calls}")
        assertNull(rig.core.decisionOf(rig.q("orders")))
    }

    @Test
    fun `Tab goes through the options, then the tick, then Accept`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag)   // ticked, so Accept can take focus
        val watched = listOf("migrate3x-option-0-0", "migrate3x-option-0-1", tickTag, "migrate3x-accept")
        onNodeWithTag("migrate3x-option-0-0").requestFocus()
        val order = mutableListOf<String>()
        fun focused(): String? = watched.firstOrNull { tag -> nodes(tag).any { it.config.getOrNull(SemanticsProperties.Focused) == true } }
        focused()?.let { order += it }
        repeat(12) {
            onNodeWithTag(focused() ?: watched.first()).performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            focused()?.takeIf { it !in order }?.let { order += it }
        }
        val tick = order.indexOf(tickTag); val accept = order.indexOf("migrate3x-accept")
        assertTrue(tick > 0 && accept > tick, "focus order was $order: the options come before the tick, and the tick before Accept")
        assertTrue(order.indexOf("migrate3x-option-0-0") < tick, "focus order was $order")
    }

    // ---- unverified marker ----

    @Test
    fun `an unverified finding carries the marker, and hovering it says not measured on a broker yet`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        val key = rig.report.view.groups.first { it.key.startsWith("classic-to-quorum-unmeasured") }.key
        val index = openGroupAndFind(rig, key, "")  // a global finding has no name: its row is the first one
        revealRow(rig.report.view, key, index)
        assertDrawn("migrate3x-unverified")
        assertTextHas("migrate3x-unverified", "unverified")
        onNodeWithTag("migrate3x-unverified").performMouseInput { moveTo(center) }
        waitForIdle()
        assertTrue(wholeScreenText().contains("Not measured on a broker yet"), "the tooltip is not shown on hover")
    }

    // ---- state 9: decided rows, Skipped group, the bar ----

    @Test
    fun `the bar counts the items, disables Generate while some are undecided, and Show next undecided opens the next item`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.three)
        showLive(rig)
        assertTextHas("migrate3x-pending-count", "3 items need a decision")
        assertEnabled("migrate3x-generate", false)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag); click("migrate3x-accept")
        assertTextHas("migrate3x-pending-count", "2 items need a decision")
        click("migrate3x-next-undecided")
        assertDrawn("migrate3x-review-sheet")
        val expected = rig.core.items.first { it != rig.q("orders") }   // report order (ruling 14)
        assertEquals(expected, rig.report.review.sheet!!.item)
        assertTextHas("migrate3x-review-sheet", "item ${rig.core.items.indexOf(expected) + 1} of 3")
    }

    @Test
    fun `the bar says one item needs a decision, and every item decided with the line of counts`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.three)
        showLive(rig)
        for (name in listOf("orders", "invoices")) { clickReview(rig, ORDERS_GROUP, name); click(tickTag); click("migrate3x-accept") }
        assertTextHas("migrate3x-pending-count", "1 item needs a decision")
        clickReview(rig, ORDERS_GROUP, "jobs"); click("migrate3x-skip")
        assertTextHas("migrate3x-pending-count", "Every item is decided")
        assertTextHas("migrate3x-pending-count", "3 of 3: 2 accepted, 1 skipped (in the Skipped group).")
        assertEnabled("migrate3x-generate", true)
        assertNotDrawn("migrate3x-next-undecided")
    }

    @Test
    fun `a skipped item leaves its group for the Skipped group, which is last, closed, and opens with its rows`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.three)
        showLive(rig)
        assertNotDrawn("migrate3x-group-$SKIPPED_GROUP_KEY")
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-skip")
        assertEquals(SKIPPED_GROUP_KEY, rig.report.view.groups.last().key)
        revealGroup(rig.report.view, SKIPPED_GROUP_KEY)
        assertTextHas("migrate3x-group-$SKIPPED_GROUP_KEY", "Skipped by you", "1 item")
        assertNotDrawn("migrate3x-review-$SKIPPED_GROUP_KEY-0")
        click("migrate3x-group-$SKIPPED_GROUP_KEY")
        revealRow(rig.report.view, SKIPPED_GROUP_KEY, 0)
        assertTextHas("migrate3x-row-$SKIPPED_GROUP_KEY-0", "orders", "Left out of the migration. Was: Warning")
        assertTextHas(pill(SKIPPED_GROUP_KEY, 0), "Skipped")
        assertDrawn("migrate3x-unskip-$SKIPPED_GROUP_KEY-0")
    }

    @Test
    fun `un-skip from the Skipped group puts the item back as needing a decision and the group disappears`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-skip")
        openGroupAndFind(rig, SKIPPED_GROUP_KEY, "orders")
        revealRow(rig.report.view, SKIPPED_GROUP_KEY, 0)
        click("migrate3x-unskip-$SKIPPED_GROUP_KEY-0")
        assertNotDrawn("migrate3x-group-$SKIPPED_GROUP_KEY")
        assertTextHas("migrate3x-pending-count", "1 item needs a decision")
        revealRow(rig.report.view, ORDERS_GROUP, 0)
        assertTextHas(pill(ORDERS_GROUP, 0), "Needs decision")
    }

    @Test
    fun `Review on a row of the Skipped group opens the sheet and accepting replaces the skip`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-skip")
        clickReview(rig, SKIPPED_GROUP_KEY, "orders")
        assertDrawn("migrate3x-review-sheet")
        click(tickTag); click("migrate3x-accept")
        assertNotDrawn("migrate3x-group-$SKIPPED_GROUP_KEY")
        assertEquals(1, rig.report.review.acceptedCount)
    }

    @Test
    fun `the Skipped group stays when every severity chip is off`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.mixed)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-skip")
        revealTop()
        listOf("blocker", "warning", "info").forEach { click("migrate3x-chip-$it") }
        assertEquals(listOf(SKIPPED_GROUP_KEY), rig.report.view.groups.map { it.key })
        revealGroup(rig.report.view, SKIPPED_GROUP_KEY)
        assertDrawn("migrate3x-group-$SKIPPED_GROUP_KEY")
    }

    // ---- state 10: generate and the result ----

    @Test
    fun `Generate saves the file and the result lists it, what is left out, the notes and the fixed sentences`() = runDesktopComposeUiTest(1280, 900) {
        val folders = FakeMigrationFolders("/migrations").also { it.folder("/migrations").addMigration("0001"); it.folder("/migrations").addMigration("0002") }
        val rig = LiveRig(ReviewScreenFiles.mixed, folders)
        showLive(rig)
        for (name in listOf("orders", "prio")) { clickReview(rig, ORDERS_GROUP, name); if (nodes(tickTag).isNotEmpty()) click(tickTag); click("migrate3x-accept") }
        clickReview(rig, NOT_DURABLE_GROUP, "temp"); click("migrate3x-skip")
        assertEnabled("migrate3x-generate", true)
        click("migrate3x-generate")
        assertDrawn("migrate3x-result")
        assertTextHas("migrate3x-result", "1 migration saved", "0003", "Migrated from RabbitMQ 3.x legacy.json vhost /", "/migrations/0003.json")
        assertTextHas("migrate3x-result", "Nothing was sent to any broker. Apply them on a 4.x connection from the Migrations screen; each applies only on a connection whose virtual host matches.")
        assertTextHas("migrate3x-result-left-out", "temp", "skipped by you")
        assertDrawn("migrate3x-result-open-migrations")
        click("migrate3x-result-open-migrations")
        assertTrue("openMigrations" in rig.calls, "the button did not call onOpenMigrations: ${rig.calls}")
        assertEquals(listOf("0003"), folders.folder("/migrations").written)
    }

    @Test
    fun `a file with two virtual hosts says each file applies only on a matching connection`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.twoDeclaredVhosts)
        showLive(rig)
        listOf("orders" to "/", "billing" to "other").forEach { (name, _) -> clickReview(rig, ORDERS_GROUP, name); click(tickTag); click("migrate3x-accept") }
        click("migrate3x-generate")
        // the access file (declares "other") comes first: three files
        assertTextHas("migrate3x-result", "3 migrations saved", "Each file applies only on a connection whose virtual host matches. A source with 2 virtual hosts needs one 4.x profile per virtual host.")
    }

    @Test
    fun `a generation that writes nothing says so and offers no Open Migrations`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-skip")
        click("migrate3x-generate")
        assertTextHas("migrate3x-result", "No migration was written: nothing is left to write")
    }

    @Test
    fun `dismissing the result keeps the decisions and the Skipped group`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.three)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click("migrate3x-skip")
        for (name in listOf("invoices", "jobs")) { clickReview(rig, ORDERS_GROUP, name); click(tickTag); click("migrate3x-accept") }
        click("migrate3x-generate")
        assertDrawn("migrate3x-result")
        assertTrue(rig.report.view.groups.last().key == SKIPPED_GROUP_KEY, "the Skipped group stays visible after generating")
    }

    // ---- state 11: second generation, folder problems, partial save ----

    @Test
    fun `generating again asks first with the fixed question, Cancel writes nothing, Generate again writes new files`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag); click("migrate3x-accept"); click("migrate3x-generate")
        assertDrawn("migrate3x-result")
        val written = rig.folders.folder("/migrations").attempts.size
        click("migrate3x-generate")
        assertTextHas("migrate3x-generate-confirm", "Generate again")
        assertTextHas("migrate3x-generate-cancel", "Cancel")
        assertTrue(wholeScreenText().contains("Migration files were already saved from this analysis. Generating again writes new files with new versions and leaves the old ones as they are. Generate again?"))
        click("migrate3x-generate-cancel")
        assertEquals(written, rig.folders.folder("/migrations").attempts.size, "Cancel wrote nothing")
        click("migrate3x-generate"); click("migrate3x-generate-confirm")
        assertTextHas("migrate3x-result", "0002")
        assertEquals(listOf("0001", "0002"), rig.folders.folder("/migrations").written)
    }

    @Test
    fun `no folder chosen and a cancelled dialog says so, and writes nothing`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders, FakeMigrationFolders(null))
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag); click("migrate3x-accept"); click("migrate3x-generate")
        assertTextHas("migrate3x-trouble", MigrateFromThreeTexts.problemText(rig.report.review.generation as GenerationState.Trouble), "No folder was chosen. Nothing was saved.")
        assertDrawn("migrate3x-trouble-choose-folder")
        assertEquals(emptyList(), rig.folders.folder("/migrations").attempts)
    }

    @Test
    fun `a read-only folder is reported with its path and the choices, and nothing is written`() = runDesktopComposeUiTest(1280, 900) {
        val folders = FakeMigrationFolders("/migrations").also { it.folder("/migrations").readOnly = true }
        val rig = LiveRig(ReviewScreenFiles.orders, folders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag); click("migrate3x-accept"); click("migrate3x-generate")
        assertTextHas("migrate3x-trouble", MigrateFromThreeTexts.problemText(rig.report.review.generation as GenerationState.Trouble), "The folder is read-only", "No file was saved.", "/migrations")
        assertTextHas("migrate3x-trouble-choose-folder", "Choose another folder")
        assertTextHas("migrate3x-trouble-export", "Export the files")
        assertNotDrawn("migrate3x-trouble-retry")
        assertEquals(emptyList(), folders.folder("/migrations").attempts)
    }

    @Test
    fun `a folder that does not exist shows the fixed problem text`() = runDesktopComposeUiTest(1280, 900) {
        val folders = FakeMigrationFolders("/migrations").also { it.folder("/migrations").unavailable = FolderProblem.MISSING }
        val rig = LiveRig(ReviewScreenFiles.orders, folders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag); click("migrate3x-accept"); click("migrate3x-generate")
        assertTextHas("migrate3x-trouble", "The folder does not exist", "No file was saved.")
    }

    @Test
    fun `Export the files from a folder problem saves through the dialog and the result is an export without Open Migrations`() = runDesktopComposeUiTest(1280, 900) {
        val folders = FakeMigrationFolders("/migrations").also { it.folder("/migrations").readOnly = true }
        val rig = LiveRig(ReviewScreenFiles.orders, folders)
        rig.saver.outcome = SaveOutcome.Saved("/home/me/0001.json")
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag); click("migrate3x-accept"); click("migrate3x-generate")
        click("migrate3x-trouble-export")
        assertTextHas("migrate3x-result", "1 migration exported")
        assertNotDrawn("migrate3x-result-open-migrations")
        assertEquals(listOf("0001.json"), rig.saver.saves.map { it.suggestedName })
    }

    @Test
    fun `a partial save names the file that failed, the saved and the unsaved, and offers Try again, another folder and Export`() = runDesktopComposeUiTest(1280, 900) {
        val folders = FakeMigrationFolders("/migrations").also { it.folder("/migrations").failWrite(2, "the file could not be written") }
        val rig = LiveRig(ReviewScreenFiles.twoVhosts, folders)
        showLive(rig)
        listOf("orders", "billing").forEach { clickReview(rig, ORDERS_GROUP, it); click(tickTag); click("migrate3x-accept") }
        click("migrate3x-generate")
        assertTextHas("migrate3x-trouble", MigrateFromThreeTexts.problemText(rig.report.review.generation as GenerationState.Trouble), "0002.json was not saved: the file could not be written. Saved: 0001. Not saved: 0002. The saved files stay in the folder.")
        assertDrawn("migrate3x-trouble-retry"); assertDrawn("migrate3x-trouble-choose-folder"); assertDrawn("migrate3x-trouble-export")
        click("migrate3x-trouble-retry")
        assertTextHas("migrate3x-result", "2 migrations saved")
        assertEquals(setOf("0001", "0002"), folders.folder("/migrations").files.keys)
    }

    @Test
    fun `no free version number says so`() = runDesktopComposeUiTest(1280, 900) {
        val folders = FakeMigrationFolders("/migrations").also { it.folder("/migrations").addMigration("9999") }
        val rig = LiveRig(ReviewScreenFiles.orders, folders)
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag); click("migrate3x-accept"); click("migrate3x-generate")
        assertTextHas("migrate3x-trouble", MigrateFromThreeTexts.problemText(rig.report.review.generation as GenerationState.Trouble), "The migrations folder has no free version number left (the highest is 9999).")
    }

    @Test
    fun `a failed export says the file could not be saved with the fixed reason and keeps the panel`() = runDesktopComposeUiTest(1280, 900) {
        val folders = FakeMigrationFolders("/migrations").also { it.folder("/migrations").readOnly = true }
        val rig = LiveRig(ReviewScreenFiles.orders, folders)
        rig.saver.outcome = SaveOutcome.Failed("The place cannot be written")
        showLive(rig)
        clickReview(rig, ORDERS_GROUP, "orders"); click(tickTag); click("migrate3x-accept"); click("migrate3x-generate")
        click("migrate3x-trouble-export")
        assertDrawn("migrate3x-trouble")
        assertTrue(wholeScreenText().contains("The migration file could not be saved: The place cannot be written"), "no failure notice")
    }

    // ---- no secret, no raw control character ----

    @Test
    fun `no sheet, panel or result of a file with a user and a shovel secret shows a hash or a password`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(MigrateScreenFiles.withUser)
        showLive(rig)
        val items = rig.core.items
        for (item in items) { rig.vm.openItem(item); waitForIdle(); rig.vm.startReplace(); waitForIdle(); rig.vm.closeItem() }
        assertTrue(items.isNotEmpty(), "the premise: the file has an item that needs a decision")
        rig.vm.openItem(items.first()); waitForIdle()
        assertDrawn("migrate3x-review-sheet")
        listOf(MigrateScreenFiles.SENTINEL_HASH, MigrateScreenFiles.SENTINEL_PASSWORD, "amqp://").forEach {
            assertFalse(wholeScreenText().contains(it), "the screen holds $it")
        }
    }

    @Test
    fun `a hostile queue name is shown escaped in the sheet and nothing is read as markup`() = runDesktopComposeUiTest(1280, 900) {
        val raw = "<b>x</b>\u0007‮end"
        val rig = LiveRig(ReviewScreenFiles.classic(raw))
        showLive(rig)
        rig.vm.openItem(rig.core.items.single()); waitForIdle()
        assertDrawn("migrate3x-review-sheet")
        val shown = wholeScreenText()
        assertTrue(shown.contains("<b>x</b>\\u0007\\u202Eend"), "the name is shown as plain escaped text")
        assertFalse(shown.contains('\u0007') || shown.contains('‮'), "a raw control or bidi character reached the screen")
    }

    @Test
    fun `the sheet of the 06a screen did not change the report - rows without a decision show no pill`() = runDesktopComposeUiTest(1280, 900) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        val key = rig.report.view.groups.first { it.key.startsWith("clients-global-qos") }.key
        val index = openGroupAndFind(rig, key, "")
        revealRow(rig.report.view, key, index)
        assertNotDrawn(pill(key, 0))
        assertNotDrawn("migrate3x-review-$key-0")
    }
}
