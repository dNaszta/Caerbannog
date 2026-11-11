package dev.caerbannog.ui.migrate3x

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrate3x.LiveSourceTexts
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.migrate3x.SourceOption
import dev.caerbannog.core.migrate3x.SourcePicker
import dev.caerbannog.ui.connections.AppIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M8-06e item 7: one `FailureBanner` (error container, icon, bold title, content) for Rejected, CannotExport and LiveFailed, and the picker and button icons of
 * the mockup `docs/ux/Migrate3x.dc.html`. Tags and texts are unchanged (the other UI tests check them). The colours are checked by eye in the screenshots.
 * Rejected and LiveFailed already show `icon:error` before the change (characterization); the other icons fail first.
 */
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeFailureBannerUiTest {
    private val liveOffered = SourcePicker(SourceKind.File, listOf(SourceOption.File, SourceOption.Live("legacy-eu")))

    /** The names of the icons (`icon:<name>` tags) drawn inside the node tagged [tag]. */
    private fun DesktopComposeUiTest.iconsIn(tag: String): Set<String> =
        ICONS.filter { icon -> onAllNodes(hasTestTag("icon:$icon") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }.toSet()

    private fun DesktopComposeUiTest.assertIcon(tag: String, icon: String, why: String) {
        val found = iconsIn(tag)
        assertTrue(icon in found, "$why: expected icon:$icon inside $tag, found $found")
    }

    @Test
    fun `the component draws its icon, its bold title and its content`() = runDesktopComposeUiTest(1280, 800) {
        setContent { FailureBanner(AppIcon.LockPerson, "The title") { Text("a second line", modifier = Modifier.testTag("banner-content")) } }
        assertDrawn("icon:lock_person")
        assertTextHas("banner-content", "a second line")
        assertTrue(onAllNodesWithText("The title").fetchSemanticsNodes().size == 1, "the title is drawn once")
    }

    @Test
    fun `a rejected file shows the error icon in its banner`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateFromThreeState.Rejected("notes.json", "The file is not a definitions file"))
        assertIcon("migrate3x-state-rejected", "error", "Rejected")
        assertTextHas("migrate3x-state-rejected", "The file is not a definitions file", "notes.json", MigrateFromThreeTexts.NOTHING_ANALYSED)
    }

    @Test
    fun `a broker that refuses the export shows the lock_person icon, not the error icon`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateFromThreeState.CannotExport("legacy-eu", LiveSourceTexts.CANNOT_EXPORT), picker = liveOffered.copy(selected = SourceKind.Live))
        assertIcon("migrate3x-state-cannot-export", "lock_person", "CannotExport")
        assertEquals(setOf("lock_person", "upload_file"), iconsIn("migrate3x-state-cannot-export"), "the banner icon and the choose-file button icon are the only icons")
    }

    @Test
    fun `a failed live read shows the error icon in its banner`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateFromThreeState.LiveFailed("legacy-eu", LiveSourceTexts.READ_FAILED), picker = liveOffered.copy(selected = SourceKind.Live))
        assertIcon("migrate3x-state-live-failed", "error", "LiveFailed")
        assertTextHas("migrate3x-state-live-failed", LiveSourceTexts.READ_FAILED, MigrateFromThreeTexts.NOTHING_ANALYSED)
    }

    @Test
    fun `the Analyse live connection button shows travel_explore, not link`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateFromThreeState.ChooseSource, picker = liveOffered.copy(selected = SourceKind.Live))
        assertEquals(setOf("travel_explore"), iconsIn("migrate3x-analyse-live"), "icons of the button")
    }

    @Test
    fun `an unselected live option shows travel_explore and the selected file option shows check`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateFromThreeState.ChooseSource, picker = liveOffered)
        assertEquals(setOf("check"), iconsIn("migrate3x-source-file"))
        assertEquals(setOf("travel_explore"), iconsIn("migrate3x-source-live"))
    }

    @Test
    fun `an unselected file option shows upload_file and the selected live option shows check`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateFromThreeState.ChooseSource, picker = liveOffered.copy(selected = SourceKind.Live))
        assertEquals(setOf("upload_file"), iconsIn("migrate3x-source-file"))
        assertEquals(setOf("check"), iconsIn("migrate3x-source-live"))
    }

    @Test
    fun `a disabled live option keeps the lock`() = runDesktopComposeUiTest(1280, 800) {
        show(MigrateFromThreeState.ChooseSource)
        assertEquals(setOf("check"), iconsIn("migrate3x-source-file"))
        assertEquals(setOf("lock"), iconsIn("migrate3x-source-live"))
    }

    private companion object {
        val ICONS = AppIcon.entries.map { it.mockupName }
    }
}
