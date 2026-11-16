package dev.caerbannog.ui.migrations

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.core.planner.PlannerEntry
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.ui.planner.assertDrawn
import dev.caerbannog.ui.planner.assertNotDrawn
import dev.caerbannog.ui.planner.node
import dev.caerbannog.ui.planner.textOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-03 step 2: the entry to the planner on the Migrations screen (mockup section 1, Q11), 1280 x 800. RED until `MigrationsScreen` takes
 * the new parameters. The only change to that screen in this task:
 *  - `plannerEntry: PlannerEntry = PlannerEntry.NewPlan`, `onOpenPlanner`, `onDismissDraft` (called after the question is confirmed),
 *    `plannerNotice: String?` with `onDismissPlannerNotice` (the banner "Plan saved as ...", a notice the window sets after a save);
 *  - tags `migrations:newPlanBtn` (the FIRST app bar button, its text is `entry.label`), `migrations:draftCard` with `migrations:draftTitle`,
 *    `migrations:draftPill`, `migrations:draftDescription` (not drawn when empty), `migrations:draftSteps`, `migrations:draftContinueBtn`,
 *    `migrations:draftDismissBtn`; the question `migrations:draftDismissDialog` with `migrations:draftDismissConfirmBtn`, `...CancelBtn`;
 *    `migrations:plannerNotice` and `migrations:plannerNoticeCloseBtn`.
 * Texts come from [PlannerEntry] and [PlannerTexts].
 */
@OptIn(ExperimentalTestApi::class)
class MigrationsNewPlanUiTest {

    private val file = MigrationFile("0001", "Create orders", listOf(MigrationAction.DeclareQueue("orders")), """{ "version": "0001" }""", "abc")
    private val withFiles = MigrationsScreenState(
        entries = listOf(MigrationEntry(file, MigrationStatus.Pending)), selectedVersion = "0001", selectedFile = file, profileName = "uat-eu",
    )

    private val continuePlan = PlannerEntry.ContinuePlan(stepCount = 10, description = "Orders with dead-lettering and a telemetry route")

    private fun DesktopComposeUiTest.show(
        entry: PlannerEntry = PlannerEntry.NewPlan,
        state: MigrationsScreenState = withFiles,
        notice: String? = null,
        opened: MutableList<String> = mutableListOf(),
    ) {
        setContent {
            MigrationsScreen(
                state = state, onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {},
                onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {},
                plannerEntry = entry,
                onOpenPlanner = { opened += "open" },
                onDismissDraft = { opened += "dismissDraft" },
                plannerNotice = notice,
                onDismissPlannerNotice = { opened += "closeNotice" },
            )
        }
        waitForIdle()
    }

    @Test
    fun `without a draft the first button reads New plan and there is no card`() = runDesktopComposeUiTest(1280, 800) {
        show()
        node("migrations:newPlanBtn").assertIsDisplayed().assertIsEnabled()
        assertEquals(PlannerEntry.NewPlan.label, textOf("migrations:newPlanBtn").trim())
        assertNotDrawn("migrations:draftCard")
    }

    @Test
    fun `New plan is the first button of the app bar`() = runDesktopComposeUiTest(1280, 800) {
        show()
        val first = node("migrations:newPlanBtn").fetchSemanticsNode().boundsInRoot
        listOf("migrations:migrate3xBtn", "migrations:importBtn", "migrations:exportBtn", "migrations:applyPendingBtn").forEach {
            val other = node(it).fetchSemanticsNode().boundsInRoot
            assertTrue(first.top < other.top || first.top == other.top && first.left < other.left, "New plan is not before $it")
        }
    }

    @Test
    fun `New plan opens the planner`() = runDesktopComposeUiTest(1280, 800) {
        val calls = mutableListOf<String>()
        show(opened = calls)
        node("migrations:newPlanBtn").performClick()
        assertEquals(listOf("open"), calls)
    }

    @Test
    fun `with a draft the button reads Continue plan and the card shows title, pill, description and step count`() = runDesktopComposeUiTest(1280, 800) {
        show(continuePlan)
        assertEquals(continuePlan.label, textOf("migrations:newPlanBtn").trim())
        assertEquals("Continue plan", continuePlan.label)
        assertEquals(PlannerTexts.DRAFT_CARD_TITLE, textOf("migrations:draftTitle").trim())
        assertEquals(PlannerTexts.DRAFT_CARD_PILL, textOf("migrations:draftPill").trim())
        assertEquals(continuePlan.description, textOf("migrations:draftDescription").trim())
        assertEquals(continuePlan.stepsText, textOf("migrations:draftSteps").trim())
        node("migrations:draftContinueBtn").assertIsDisplayed()
        node("migrations:draftDismissBtn").assertIsDisplayed()
    }

    @Test
    fun `an empty description draws no description line`() = runDesktopComposeUiTest(1280, 800) {
        show(PlannerEntry.ContinuePlan(stepCount = 2, description = ""))
        assertDrawn("migrations:draftCard")
        assertNotDrawn("migrations:draftDescription")
    }

    @Test
    fun `the card stands above the migration files and is not one of them`() = runDesktopComposeUiTest(1280, 800) {
        show(continuePlan)
        val card = node("migrations:draftCard").fetchSemanticsNode().boundsInRoot
        val firstFile = node("migrationCard:0001").fetchSemanticsNode().boundsInRoot
        assertTrue(card.bottom <= firstFile.top, "the draft card is not above the files")
        assertNotDrawn("migrationCard:draft")
    }

    @Test
    fun `Continue plan on the button and on the card both open the planner`() = runDesktopComposeUiTest(1280, 800) {
        val calls = mutableListOf<String>()
        show(continuePlan, opened = calls)
        node("migrations:newPlanBtn").performClick()
        node("migrations:draftContinueBtn").performClick()
        assertEquals(listOf("open", "open"), calls)
    }

    @Test
    fun `Dismiss on the card asks first and only Dismiss discards`() = runDesktopComposeUiTest(1280, 800) {
        val calls = mutableListOf<String>()
        show(continuePlan, opened = calls)
        node("migrations:draftDismissBtn").performClick()
        assertTrue(textOf("migrations:draftDismissDialog").contains(PlannerTexts.dismissQuestion(10)), textOf("migrations:draftDismissDialog"))
        assertEquals(emptyList(), calls, "the draft was discarded before the answer")
        node("migrations:draftDismissConfirmBtn").performClick()
        assertEquals(listOf("dismissDraft"), calls)
    }

    @Test
    fun `Cancel and Esc on the question keep the draft`() = runDesktopComposeUiTest(1280, 800) {
        val calls = mutableListOf<String>()
        show(continuePlan, opened = calls)
        node("migrations:draftDismissBtn").performClick()
        node("migrations:draftDismissCancelBtn").performClick()
        assertNotDrawn("migrations:draftDismissDialog")
        node("migrations:draftDismissBtn").performClick()
        node("migrations:draftDismissDialog").requestFocus()
        node("migrations:draftDismissDialog").performKeyInput { pressKey(Key.Escape) }
        assertNotDrawn("migrations:draftDismissDialog")
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `an unreadable draft reads Open planner, says it cannot be read, and offers no Dismiss`() = runDesktopComposeUiTest(1280, 800) {
        val entry = PlannerEntry.DraftUnreadable("/home/u/.caerbannog/planner-draft.json")
        show(entry)
        assertEquals(entry.label, textOf("migrations:newPlanBtn").trim())
        assertTrue(textOf("migrations:draftCard").contains(entry.cardText), textOf("migrations:draftCard"))
        assertNotDrawn("migrations:draftDismissBtn")
    }

    @Test
    fun `the saved notice is shown with its text and can be closed`() = runDesktopComposeUiTest(1280, 800) {
        val calls = mutableListOf<String>()
        val text = PlannerTexts.planSaved("0005")
        show(notice = text, opened = calls)
        assertTrue(textOf("migrations:plannerNotice").contains(text), textOf("migrations:plannerNotice"))
        node("migrations:plannerNoticeCloseBtn").performClick()
        assertEquals(listOf("closeNotice"), calls)
    }

    @Test
    fun `no notice, no banner`() = runDesktopComposeUiTest(1280, 800) {
        show()
        assertNotDrawn("migrations:plannerNotice")
    }

    @Test
    fun `at 1024 px the new button wraps with the others and the title stays`() = runDesktopComposeUiTest(1024, 768) {
        show(continuePlan)
        node("migrations:title").assertIsDisplayed()
        node("migrations:newPlanBtn").assertIsDisplayed()
        node("migrations:applyPendingBtn").assertIsDisplayed()
    }
}
