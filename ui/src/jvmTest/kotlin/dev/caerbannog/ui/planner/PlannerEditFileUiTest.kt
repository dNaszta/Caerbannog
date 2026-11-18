package dev.caerbannog.ui.planner

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.planner.PlannerMode
import dev.caerbannog.core.planner.PlannerQuestion
import dev.caerbannog.ui.appframe.AppDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-12 step 2: the planner screen on a file ("Edit steps", mockup 13). RED by compilation until `PlannerMode` and the file mode of the view model exist.
 * The screen is driven by a REAL view model over fakes (`UiRig(mode, rawFiles)`).
 *
 * Tag contract (new): `planner:editPill` ("Editing 0005.json"), `planner:unsavedPill` ("Unsaved changes", drawn only while `state.hasChanges`),
 * `planner:discardBtn` (replaces `planner:dismissBtn` in file mode; text "Discard changes" while there are changes, "Close" while there are none; sends `dismiss`),
 * `planner:saveBtn` carries `state.saveLabel` ("Save 0005"), `planner:draftPill` is NOT drawn in file mode. The back arrow (`planner:backBtn`) in file mode
 * sends `dismiss` (so a change is asked about) and navigates only through the state (`dismissed`), never straight to the Migrations screen while there are
 * changes. The title (`planner:title`) reads "Topology planner · 0005". The error of a file changed on disk is `planner:error` (message and detail).
 * Everything else of the planner screen is unchanged and is not repeated here.
 */
@OptIn(ExperimentalTestApi::class)
class PlannerEditFileUiTest {
    private val threeSteps = EditFileUiFixtures.threeSteps
    private fun open(raw: String = EditFileUiFixtures.rawOf(threeSteps), version: String = "0005") =
        UiRig(mode = PlannerMode.EditFile(version, MigrationParser.computeChecksum(raw)), rawFiles = listOf(raw))

    @Test
    fun `an opened file shows its pill, its title and Save 0005 disabled with the reason, and no draft pill`() = runDesktopComposeUiTest(1280, 800) {
        val r = open()
        showPlanner(r)
        assertEquals("Editing 0005.json", textOf("planner:editPill").trim())
        assertTrue(textOf("planner:title").contains("Topology planner · 0005"), textOf("planner:title"))
        assertEquals("Save 0005", textOf("planner:saveBtn").trim())
        node("planner:saveBtn").assertIsNotEnabled()
        assertDrawn("planner:saveReason")
        assertNotDrawn("planner:draftPill")
        assertNotDrawn("planner:unsavedPill")
        assertNotDrawn("planner:dismissBtn")
        assertEquals("Close", textOf("planner:discardBtn").trim())
        node("planner:checkBtn").assertIsDisplayed()
        r.close()
    }

    @Test
    fun `the three steps of the file are listed as steps`() = runDesktopComposeUiTest(1280, 800) {
        val r = open()
        showPlanner(r)
        assertEquals(listOf("planStep:0", "planStep:1", "planStep:2"), listOf(0, 1, 2).map { "planStep:$it" }.filter { runCatching { assertDrawn(it) }.isSuccess })
        assertTrue(textOf("planStep:0").contains("Declare quorum queue orders"), textOf("planStep:0"))
        assertTrue(textOf("planStep:2").contains("Bind events to orders with order.created"), textOf("planStep:2"))
        r.close()
    }

    @Test
    fun `after a change the pill appears, Save is enabled and the button says Discard changes`() = runDesktopComposeUiTest(1280, 800) {
        val r = open()
        showPlanner(r)
        r.queue("invoices")
        waitForIdle()
        assertEquals("Unsaved changes", textOf("planner:unsavedPill").trim())
        node("planner:saveBtn").assertIsEnabled()
        assertEquals("Save 0005", textOf("planner:saveBtn").trim())
        assertEquals("Discard changes", textOf("planner:discardBtn").trim())
        r.close()
    }

    @Test
    fun `Save sends saveAsMigration and the file is rewritten under the same version`() = runDesktopComposeUiTest(1280, 800) {
        val r = open()
        showPlanner(r)
        r.queue("invoices")
        waitForIdle()
        node("planner:saveBtn").performClick()
        assertEquals(listOf("saveAsMigration"), r.events.calls.filter { it == "saveAsMigration" })
        assertEquals(listOf("0005"), r.folder.updated)
        r.close()
    }

    @Test
    fun `Discard changes asks the question with its two buttons and writes nothing`() = runDesktopComposeUiTest(1280, 800) {
        val r = open()
        showPlanner(r)
        r.queue("invoices")
        waitForIdle()
        node("planner:discardBtn").performClick()
        assertEquals(PlannerQuestion.DiscardChanges("0005"), r.state.question)
        assertTrue(textOf("planner:question").contains("Discard the changes to 0005?"), textOf("planner:question"))
        assertEquals("Discard changes", textOf("planner:questionConfirmBtn").trim())
        assertEquals("Keep editing", textOf("planner:questionCancelBtn").trim())
        assertEquals(emptyList(), r.folder.updates)
        node("planner:questionCancelBtn").performClick()
        assertTrue(!r.state.dismissed)
        assertDrawn("planner:unsavedPill")
        r.close()
    }

    @Test
    fun `confirming the discard closes the planner through the state`() = runDesktopComposeUiTest(1280, 800) {
        val r = open()
        showPlanner(r)
        r.queue("invoices")
        waitForIdle()
        node("planner:discardBtn").performClick()
        node("planner:questionConfirmBtn").performClick()
        assertTrue(r.state.dismissed)
        assertEquals(emptyList(), r.folder.updates)
        r.close()
    }

    @Test
    fun `the back arrow with unsaved changes asks and does not leave`() = runDesktopComposeUiTest(1280, 800) {
        val r = open()
        val navigated = mutableListOf<AppDestination>()
        showPlanner(r) { navigated += it }
        r.queue("invoices")
        waitForIdle()
        node("planner:backBtn").performClick()
        assertTrue("dismiss" in r.events.calls, "the back arrow did not send dismiss: ${r.events.calls}")
        assertEquals(PlannerQuestion.DiscardChanges("0005"), r.state.question)
        assertEquals(emptyList(), navigated, "the screen was left with unsaved changes")
        r.close()
    }

    @Test
    fun `leaving without a change asks nothing`() = runDesktopComposeUiTest(1280, 800) {
        val r = open()
        showPlanner(r)
        node("planner:discardBtn").performClick()
        assertEquals(null, r.state.question)
        assertTrue(r.state.dismissed)
        assertEquals(emptyList(), r.folder.updates)
        r.close()
    }

    @Test
    fun `a file changed on disk shows the error with its detail and nothing is overwritten`() = runDesktopComposeUiTest(1280, 800) {
        val r = open()
        showPlanner(r)
        r.queue("invoices")
        val theirs = EditFileUiFixtures.rawOf(listOf(MigrationAction.DeclareQueue("theirs")))
        r.folder.changeOnDisk("0005", theirs)
        waitForIdle()
        node("planner:saveBtn").performClick()
        val shown = textOf("planner:error")
        assertTrue(shown.contains("0005.json changed on disk since it was opened"), shown)
        assertTrue(shown.contains("It was not overwritten. Open it again to edit the new content."), shown)
        assertEquals(theirs, r.folder.files.getValue("0005").rawJson)
        r.close()
    }

    @Test
    fun `a step the wizard cannot build offers move and remove but no Edit`() = runDesktopComposeUiTest(1280, 800) {
        val actions = listOf<MigrationAction>(
            MigrationAction.DeclareQueue("jobs", arguments = mapOf("x-single-active-consumer" to ArgumentValue.Flag(true))),
            MigrationAction.DeleteQueue("legacy-orders"),
        )
        val r = open(EditFileUiFixtures.rawOf(actions, "0006"), "0006")
        showPlanner(r)
        node("planStepMenu:0").performClick()
        assertDrawn("planStepMenu:0:remove")
        node("planStepMenu:0:edit").assertIsNotEnabled()
        r.close()
    }

    @Test
    fun `a new plan is unchanged in this task`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        showPlanner(r)
        assertNotDrawn("planner:editPill")
        assertNotDrawn("planner:unsavedPill")
        assertNotDrawn("planner:discardBtn")
        assertEquals("Save as migration", textOf("planner:saveBtn").trim())
        assertDrawn("planner:draftPill")
        assertDrawn("planner:dismissBtn")
        r.close()
    }

    // ---- layout ----

    private fun androidx.compose.ui.test.DesktopComposeUiTest.exercise(scale: Float) {
        val r = open()
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { PlannerOver(r) } }
        waitForIdle()
        r.queue("invoices")
        waitForIdle()
        listOf("planner:saveBtn", "planner:discardBtn", "planner:checkBtn", "planner:editPill", "planner:unsavedPill").forEach { node(it).assertIsDisplayed() }
        r.close()
    }

    @Test
    fun `at font scale 1_5 the file bar, both pills and the buttons are on screen`() = runDesktopComposeUiTest(1280, 800) { exercise(1.5f) }

    @Test
    fun `on a short window the file bar and its buttons are on screen`() = runDesktopComposeUiTest(1280, 520) { exercise(1f) }
}

internal object EditFileUiFixtures {
    val threeSteps: List<MigrationAction> = listOf(
        MigrationAction.DeclareQueue("orders", "quorum"),
        MigrationAction.DeclareExchange("events", "topic"),
        MigrationAction.DeclareBinding("events", "orders", "order.created"),
    )

    fun rawOf(actions: List<MigrationAction>, version: String = "0005"): String = MigrationParser.exportToJson(version, "Orders", actions)
}
