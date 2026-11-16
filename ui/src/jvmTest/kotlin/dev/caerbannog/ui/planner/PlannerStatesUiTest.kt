package dev.caerbannog.ui.planner

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.FolderProblem
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.PlannerBanner
import dev.caerbannog.core.planner.PlannerContext
import dev.caerbannog.core.planner.PlannerTexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-03 step 2: one test per banner of mockup section 12 (the first that applies, one banner under the info line), 1280 x 800, and what each
 * state draws or locks. The text of a banner comes from the banner of the state ([PlannerBanner.title], `detail`), which comes from
 * [PlannerTexts]; the scenarios of `19` pin the literal for three of them (3.x, Read-only, no configure permission, not connected), asserted in
 * the acceptance suite. Every state is built by the real view model over fakes, so the screen is checked against what the app will give it.
 */
@OptIn(ExperimentalTestApi::class)
class PlannerStatesUiTest {
    private val reason3x = "This broker runs RabbitMQ 3.x: it is a migration source only"

    private fun rig(context: PlannerContext = PlannerContext(), folder: String? = FOLDER, prepare: UiRig.() -> Unit = {}) =
        UiRig(folderPath = folder, context = context, prepare = prepare)

    private fun DesktopComposeUiTest.bannerSays(r: UiRig) {
        val banner = r.state.banner ?: error("the state has no banner")
        val text = textOf("planner:banner")
        assertTrue(text.contains(banner.title), "banner is \"$text\", expected the title \"${banner.title}\"")
        banner.detail?.let { assertTrue(text.contains(it), "banner is \"$text\", expected the detail \"$it\"") }
    }

    private fun DesktopComposeUiTest.assertNothingToEdit() {
        assertNotDrawn("planner:addStepBtn")
        assertNotDrawn("planner:saveBtn")
        assertNotDrawn("planner:checkBtn")
        assertNotDrawn("planWizard")
    }

    // ---- no banner ----

    @Test
    fun `when everything is fine there is no banner`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        showPlanner(r)
        assertNotDrawn("planner:banner")
        r.close()
    }

    // ---- 12a: 3.x ----

    @Test
    fun `12a a 3x connection shows the planner is not offered, with the reason, and no wizard, no check, no save`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig(PlannerContext(unavailableReason = reason3x))
        showPlanner(r)
        assertIs<PlannerBanner.NotOffered>(r.state.banner)
        bannerSays(r)
        assertTrue(textOf("planner:banner").contains("The planner is not offered on this connection"))
        assertTrue(textOf("planner:banner").contains(reason3x))
        assertNothingToEdit()
        r.close()
    }

    @Test
    fun `12a a draft that exists is kept and not drawn as a step list while the planner is not offered`() = runDesktopComposeUiTest(1280, 800) {
        val store = dev.caerbannog.core.planner.fakes.FakePlanDraftStore()
        store.putDraft("kept", listOf(MigrationAction.DeclareQueue("a"), MigrationAction.DeclareQueue("b"), MigrationAction.DeclareQueue("c")))
        val before = store.rawText
        val r = UiRig(context = PlannerContext(unavailableReason = reason3x), store = store)
        showPlanner(r)
        assertNotDrawn("planStep:0")
        assertNothingToEdit()
        assertEquals(before, store.rawText, "the draft was touched")
        assertEquals(emptyList(), store.savedTexts)
        r.close()
    }

    @Test
    fun `12a an unreadable version is the same state with its own reason`() = runDesktopComposeUiTest(1280, 800) {
        val text = "The broker version could not be read, so migrations are not offered"
        val r = rig(PlannerContext(unavailableReason = text))
        showPlanner(r)
        assertTrue(textOf("planner:banner").contains(text))
        assertNothingToEdit()
        r.close()
    }

    @Test
    fun `12a the 3x state never writes a migration file`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig(PlannerContext(unavailableReason = reason3x))
        showPlanner(r)
        assertNotDrawn("planner:saveBtn")
        assertEquals(emptyList(), r.folder.written)
        r.close()
    }

    // ---- 12b: unreadable draft ----

    private fun unreadable(): UiRig = rig { store.putUnreadable() }

    @Test
    fun `12b an unreadable draft shows its path and reason, with three ways out and no editing`() = runDesktopComposeUiTest(1280, 800) {
        val r = unreadable()
        showPlanner(r)
        assertIs<PlannerBanner.DraftUnreadable>(r.state.banner)
        bannerSays(r)
        assertTrue(textOf("planner:banner").contains(r.store.path), textOf("planner:banner"))
        node("planner:retryDraftBtn").assertIsDisplayed().assertIsEnabled()
        node("planner:startNewPlanBtn").assertIsDisplayed().assertIsEnabled()
        assertNothingToEdit()
        r.close()
    }

    @Test
    fun `12b Try again reads the file again`() = runDesktopComposeUiTest(1280, 800) {
        val r = unreadable()
        showPlanner(r)
        r.store.putDraft("fixed by hand", listOf(MigrationAction.DeclareQueue("orders")))
        node("planner:retryDraftBtn").performClick()
        assertNotDrawn("planner:banner")
        assertTrue(textOf("planStep:0").contains("orders"))
        r.close()
    }

    @Test
    fun `12b Start a new plan asks first, with the real name, and moves the file aside only on confirm`() = runDesktopComposeUiTest(1280, 800) {
        val r = unreadable()
        showPlanner(r)
        val bytes = r.store.rawText
        node("planner:startNewPlanBtn").performClick()
        assertTrue(textOf("planner:question").contains(r.store.asideFileName()), textOf("planner:question"))
        assertEquals(bytes, r.store.rawText, "the file was moved before the user confirmed")
        node("planner:questionConfirmBtn").performClick()
        assertEquals(bytes, r.store.asideFiles.values.single())
        assertNotDrawn("planner:banner")
        node("planner:addStepBtn").assertIsEnabled()
        r.close()
    }

    @Test
    fun `12b cancelling the question leaves the file where it was`() = runDesktopComposeUiTest(1280, 800) {
        val r = unreadable()
        showPlanner(r)
        node("planner:startNewPlanBtn").performClick()
        node("planner:questionCancelBtn").performClick()
        assertNotDrawn("planner:question")
        assertTrue(r.store.asideFiles.isEmpty())
        assertDrawn("planner:banner")
        r.close()
    }

    @Test
    fun `12b a failed move shows the error and keeps the banner`() = runDesktopComposeUiTest(1280, 800) {
        val r = unreadable()
        r.store.refuseMoveAside = true
        showPlanner(r)
        node("planner:startNewPlanBtn").performClick()
        node("planner:questionConfirmBtn").performClick()
        assertTrue(textOf("planner:error").contains(PlannerTexts.DRAFT_NOT_MOVED))
        assertDrawn("planner:banner")
        r.close()
    }

    // ---- 12c to 12e ----

    @Test
    fun `12c Read-only level says applying is locked and everything else works`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig(PlannerContext(controlLevel = TopologyControlLevel.READ_ONLY)) { queue("orders") }
        showPlanner(r)
        assertIs<PlannerBanner.ReadOnlyLevel>(r.state.banner)
        assertEquals(PlannerTexts.READ_ONLY_LEVEL, textOf("planner:banner").trim())
        node("planner:addStepBtn").assertIsEnabled()
        node("planner:saveBtn").assertIsEnabled()
        node("planner:checkBtn").assertIsDisplayed()
        r.close()
    }

    @Test
    fun `12d no configure permission names the user's connection and says the plan can be applied elsewhere`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig(PlannerContext(hasConfigurePermission = false, connectionName = "prod-eu")) { queue("orders") }
        showPlanner(r)
        assertIs<PlannerBanner.NoConfigurePermission>(r.state.banner)
        assertTrue(textOf("planner:banner").contains(PlannerTexts.noConfigurePermission("prod-eu")), textOf("planner:banner"))
        node("planner:addStepBtn").assertIsEnabled()
        node("planner:saveBtn").assertIsEnabled()
        r.close()
    }

    @Test
    fun `12e not connected still plans and saves, and Check cannot be used`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig(PlannerContext(connectionName = null)) { queue("orders") }
        showPlanner(r)
        assertIs<PlannerBanner.NotConnected>(r.state.banner)
        assertEquals(PlannerTexts.NOT_CONNECTED, textOf("planner:banner").trim())
        node("planner:saveBtn").assertIsEnabled()
        node("planner:checkBtn").assertIsNotEnabled()
        r.close()
    }

    // ---- 12f: folder ----

    @Test
    fun `12f no folder chosen offers Choose folder and disables Save with the reason`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig(folder = null) { queue("orders") }
        showPlanner(r)
        assertTrue(textOf("planner:banner").contains(PlannerTexts.NO_FOLDER_BANNER), textOf("planner:banner"))
        node("planner:chooseFolderBtn").assertIsDisplayed().assertIsEnabled()
        node("planner:saveBtn").assertIsNotEnabled()
        assertEquals(PlannerTexts.NO_FOLDER, textOf("planner:saveReason"))
        node("planner:addStepBtn").assertIsEnabled()
        r.close()
    }

    @Test
    fun `12f Choose folder sends the event`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig(folder = null)
        showPlanner(r)
        node("planner:chooseFolderBtn").performClick()
        assertTrue("chooseFolder" in r.events.calls)
        r.close()
    }

    @Test
    fun `12f a read-only folder says so, shows its path, and Save says why it is off`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { queue("orders"); folder.readOnly = true }
        r.folders.store.folder = FOLDER
        showPlanner(r)
        bannerSays(r)
        node("planner:saveBtn").assertIsNotEnabled()
        assertEquals((r.state.saveGate as dev.caerbannog.core.planner.SaveGate.Disabled).reason, textOf("planner:saveReason"))
        r.close()
    }

    @Test
    fun `12f a missing folder shows its reason and path`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { queue("orders"); folder.unavailable = FolderProblem.MISSING }
        showPlanner(r)
        bannerSays(r)
        node("planner:saveBtn").assertIsNotEnabled()
        r.close()
    }

    // ---- 12g: another virtual host ----

    @Test
    fun `12g a plan for another virtual host says it can be saved here and applied elsewhere`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { vm.setVirtualHost("/orders"); queue("orders") }
        showPlanner(r)
        assertIs<PlannerBanner.VhostMismatch>(r.state.banner)
        bannerSays(r)
        node("planner:saveBtn").assertIsEnabled()
        r.close()
    }

    // ---- order of banners ----

    @Test
    fun `only one banner is drawn, the first that applies`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig(PlannerContext(unavailableReason = reason3x, controlLevel = TopologyControlLevel.READ_ONLY, connectionName = null)) { store.putUnreadable() }
        showPlanner(r)
        assertIs<PlannerBanner.NotOffered>(r.state.banner)
        assertTrue(!textOf("planner:banner").contains(PlannerTexts.READ_ONLY_LEVEL))
        assertTrue(!textOf("planner:banner").contains(PlannerTexts.NOT_CONNECTED))
        r.close()
    }

    @Test
    fun `no state draws a connect, apply or publish control`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { queue("orders") }
        showPlanner(r)
        listOf("planner:applyBtn", "planner:applyPendingBtn", "planner:publishBtn").forEach { assertNotDrawn(it) }
        r.close()
    }
}
