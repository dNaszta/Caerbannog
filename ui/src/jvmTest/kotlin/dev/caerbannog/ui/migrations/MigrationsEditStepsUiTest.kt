package dev.caerbannog.ui.migrations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.core.planner.PlanOpeningDecision
import dev.caerbannog.core.planner.PlannerEntry
import dev.caerbannog.ui.planner.assertDrawn
import dev.caerbannog.ui.planner.assertNotDrawn
import dev.caerbannog.ui.planner.node
import dev.caerbannog.ui.planner.textOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-12 step 2: "Edit steps" on the action row of a selected valid file (mockup 13, row 2 of "1. Entry"). RED by compilation until
 * `MigrationsScreenState.planOpening` and the two parameters exist.
 *
 * Contract (the decision itself is `PlanOpening.decide`, made by the view model; the screen only draws it):
 *  - `MigrationsScreenState.planOpening: PlanOpeningDecision = PlanOpeningDecision.Openable` (for the selected file)
 *  - `MigrationsScreen(onEditSteps: (String) -> Unit = {}, onCopyIntoNewPlan: (String) -> Unit = {})`, both called with the version
 *  - tags: `migrations:editStepsBtn` (text "Edit steps", the main button, to the left of `migrations:editFileBtn` on the same row, enabled exactly when
 *    Edit file is), `migrations:planNotOffered` (the reason; for a hash file the existing `migrations:hashFileNoEdit` carries it and this tag is not drawn),
 *    `migrations:planNotOfferedDetail` (the detail line, drawn when there is one), `migrations:copyIntoPlanBtn` (text "Copy into a new plan", drawn when
 *    `canCopy`). A copy asks first, when a draft with steps exists, with the existing dialog (`migrations:draftDismissDialog`, `...ConfirmBtn`, `...CancelBtn`)
 *    and `PlannerQuestion.CopyOverDraft(version)`: "Dismiss the current plan and copy 0002 into a new plan?".
 */
@OptIn(ExperimentalTestApi::class)
class MigrationsEditStepsUiTest {
    private val raw = """{ "version": "0005" }"""
    private fun fileOf(version: String, vararg actions: MigrationAction) = MigrationFile(version, "d", actions.toList(), raw, "abc$version")
    private val queue = MigrationAction.DeclareQueue("orders")

    private fun state(
        file: MigrationFile = fileOf("0005", queue),
        decision: PlanOpeningDecision = PlanOpeningDecision.Openable,
        canWrite: Boolean = true,
        applying: Boolean = false,
    ) = MigrationsScreenState(
        entries = listOf(MigrationEntry(file, MigrationStatus.Pending)), selectedVersion = file.version, selectedFile = file, profileName = "uat-eu",
        dryRun = MigrationDryRun(file.version, file.up.map { ActionDryRun(it, allowed = true) }, canApply = true),
        planOpening = decision, isImportEnabled = canWrite, isApplying = applying,
    )

    private class Calls {
        val steps = mutableListOf<String>()
        val copies = mutableListOf<String>()
    }

    private fun DesktopComposeUiTest.show(state: MigrationsScreenState, entry: PlannerEntry = PlannerEntry.NewPlan, calls: Calls = Calls(), scale: Float = 1f) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                MigrationsScreen(
                    state = state, onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {},
                    onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {},
                    plannerEntry = entry,
                    onEditSteps = { calls.steps += it },
                    onCopyIntoNewPlan = { calls.copies += it },
                )
            }
        }
        waitForIdle()
    }

    private fun refusal(reason: String, detail: String? = null, canCopy: Boolean = false) = PlanOpeningDecision.NotOffered(reason, detail, canCopy)

    // ---- the button ----

    @Test
    fun `an openable file offers Edit steps as the main button beside Edit file`() = runDesktopComposeUiTest(1280, 800) {
        show(state())
        node("migrations:editStepsBtn").assertIsDisplayed().assertIsEnabled()
        assertEquals("Edit steps", textOf("migrations:editStepsBtn").trim())
        node("migrations:editFileBtn").assertIsDisplayed()
        assertEquals("Edit file", textOf("migrations:editFileBtn").trim())
        val steps = node("migrations:editStepsBtn").fetchSemanticsNode().boundsInRoot
        val file = node("migrations:editFileBtn").fetchSemanticsNode().boundsInRoot
        assertTrue(steps.right <= file.left + 1, "Edit steps ($steps) is not to the left of Edit file ($file)")
        assertTrue(kotlin.math.abs(steps.center.y - file.center.y) < 8, "the two buttons are not on one line: $steps $file")
        assertNotDrawn("migrations:planNotOffered")
        assertNotDrawn("migrations:copyIntoPlanBtn")
    }

    @Test
    fun `Edit steps sends the version and nothing else`() = runDesktopComposeUiTest(1280, 800) {
        val calls = Calls()
        show(state(file = fileOf("0006", queue)), calls = calls)
        node("migrations:editStepsBtn").performClick()
        assertEquals(listOf("0006"), calls.steps)
        assertEquals(emptyList(), calls.copies)
    }

    @Test
    fun `Edit steps is disabled exactly when Edit file is, in a read-only folder`() = runDesktopComposeUiTest(1280, 800) {
        show(state(canWrite = false))
        node("migrations:editStepsBtn").assertIsNotEnabled()
        node("migrations:editFileBtn").assertIsNotEnabled()
    }

    @Test
    fun `while an apply runs Edit steps is disabled`() = runDesktopComposeUiTest(1280, 800) {
        show(state(applying = true))
        node("migrations:editStepsBtn").assertIsNotEnabled()
        node("migrations:editFileBtn").assertIsNotEnabled()
    }

    // ---- the fixed texts in its place ----

    @Test
    fun `a file with password hashes shows the fixed text under the existing tag, no Edit steps, no Edit file, no copy`() = runDesktopComposeUiTest(1280, 800) {
        val user = MigrationAction.DeclareUser("svc", SecretValue.of("SENTINEL-HASH"), "rabbit_password_hashing_sha256")
        show(state(file = fileOf("0001", user), decision = refusal("This file holds password hashes. Edit it outside the app.")))
        assertEquals("This file holds password hashes. Edit it outside the app.", textOf("migrations:hashFileNoEdit").trim())
        assertNotDrawn("migrations:editStepsBtn")
        assertNotDrawn("migrations:editFileBtn")
        assertNotDrawn("migrations:copyIntoPlanBtn")
        assertNotDrawn("migrations:planNotOffered")
    }

    @Test
    fun `a file with virtual hosts or permissions shows its text and keeps Edit file`() = runDesktopComposeUiTest(1280, 800) {
        val text = "This file holds virtual hosts, users or permissions. The planner works on topology only."
        show(state(file = fileOf("0001", MigrationAction.DeclareVhost("orders")), decision = refusal(text)))
        assertEquals(text, textOf("migrations:planNotOffered").trim())
        assertNotDrawn("migrations:editStepsBtn")
        node("migrations:editFileBtn").assertIsDisplayed()
        assertNotDrawn("migrations:copyIntoPlanBtn")
    }

    @Test
    fun `a file with entries the planner does not know shows its text and keeps Edit file`() = runDesktopComposeUiTest(1280, 800) {
        val text = "This file holds entries the planner does not know. Edit it as JSON so nothing is lost."
        show(state(decision = refusal(text)))
        assertEquals(text, textOf("migrations:planNotOffered").trim())
        assertNotDrawn("migrations:editStepsBtn")
        node("migrations:editFileBtn").assertIsDisplayed()
        assertNotDrawn("migrations:copyIntoPlanBtn")
    }

    @Test
    fun `an applied file says where, adds its detail and offers the copy, and keeps Edit file`() = runDesktopComposeUiTest(1280, 800) {
        val detail = "Changing an applied file would mark it as modified there. Copy its steps into a new plan instead."
        show(state(file = fileOf("0002", queue), decision = refusal("0002 is applied on prod-eu, so it is not edited", detail, canCopy = true)))
        assertEquals("0002 is applied on prod-eu, so it is not edited", textOf("migrations:planNotOffered").trim())
        assertEquals(detail, textOf("migrations:planNotOfferedDetail").trim())
        assertNotDrawn("migrations:editStepsBtn")
        node("migrations:copyIntoPlanBtn").assertIsDisplayed().assertIsEnabled()
        assertEquals("Copy into a new plan", textOf("migrations:copyIntoPlanBtn").trim())
        node("migrations:editFileBtn").assertIsDisplayed()
    }

    @Test
    fun `a refusal without a detail draws no detail line`() = runDesktopComposeUiTest(1280, 800) {
        show(state(decision = refusal("x")))
        assertNotDrawn("migrations:planNotOfferedDetail")
    }

    // ---- the copy ----

    @Test
    fun `Copy into a new plan with no draft copies at once`() = runDesktopComposeUiTest(1280, 800) {
        val calls = Calls()
        show(state(file = fileOf("0002", queue), decision = refusal("r", "d", canCopy = true)), PlannerEntry.NewPlan, calls)
        node("migrations:copyIntoPlanBtn").performClick()
        assertEquals(listOf("0002"), calls.copies)
        assertNotDrawn("migrations:draftDismissDialog")
    }

    @Test
    fun `Copy into a new plan with a draft that has steps asks first and copies only after the answer`() = runDesktopComposeUiTest(1280, 800) {
        val calls = Calls()
        show(state(file = fileOf("0002", queue), decision = refusal("r", "d", canCopy = true)), PlannerEntry.ContinuePlan(3, "My draft"), calls)
        node("migrations:copyIntoPlanBtn").performClick()
        assertDrawn("migrations:draftDismissDialog")
        assertTrue(textOf("migrations:draftDismissDialog").contains("Dismiss the current plan and copy 0002 into a new plan?"), textOf("migrations:draftDismissDialog"))
        assertEquals(emptyList(), calls.copies, "copied before the answer")
        node("migrations:draftDismissConfirmBtn").performClick()
        assertEquals(listOf("0002"), calls.copies)
        assertNotDrawn("migrations:draftDismissDialog")
    }

    @Test
    fun `answering Keep the plan copies nothing`() = runDesktopComposeUiTest(1280, 800) {
        val calls = Calls()
        show(state(file = fileOf("0002", queue), decision = refusal("r", "d", canCopy = true)), PlannerEntry.ContinuePlan(3, ""), calls)
        node("migrations:copyIntoPlanBtn").performClick()
        node("migrations:draftDismissCancelBtn").performClick()
        assertEquals(emptyList(), calls.copies)
        assertNotDrawn("migrations:draftDismissDialog")
    }

    @Test
    fun `a draft with a description and no step is not asked about`() = runDesktopComposeUiTest(1280, 800) {
        val calls = Calls()
        show(state(file = fileOf("0002", queue), decision = refusal("r", "d", canCopy = true)), PlannerEntry.ContinuePlan(0, "only words"), calls)
        node("migrations:copyIntoPlanBtn").performClick()
        assertEquals(listOf("0002"), calls.copies)
    }

    // ---- layout ----

    @Test
    fun `at font scale 1_5 the four buttons of the action row are all on screen`() = runDesktopComposeUiTest(1280, 800) {
        show(state(), scale = 1.5f)
        listOf("migrations:editStepsBtn", "migrations:editFileBtn", "migrations:validateBtn", "migrations:applySingleBtn").forEach { node(it).assertIsDisplayed() }
    }

    @Test
    fun `on a short window the action row, with Edit steps, is still on screen`() = runDesktopComposeUiTest(1280, 520) {
        show(state())
        listOf("migrations:editStepsBtn", "migrations:editFileBtn", "migrations:applySingleBtn").forEach { node(it).assertIsDisplayed() }
    }

    @Test
    fun `the applied text and the copy button fit at font scale 1_5`() = runDesktopComposeUiTest(1280, 800) {
        show(state(decision = refusal("0002 is applied on prod-eu, so it is not edited", "Changing an applied file would mark it as modified there. Copy its steps into a new plan instead.", canCopy = true)), scale = 1.5f)
        listOf("migrations:planNotOffered", "migrations:copyIntoPlanBtn", "migrations:editFileBtn").forEach { node(it).assertIsDisplayed() }
    }
}
