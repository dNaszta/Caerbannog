package dev.caerbannog.ui.migrations

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.MigrationsScreenState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-14 step 4 (round 1): the one line under the export result (`MigrationsScreenState.exportNote`, tag `migrations-export-note`) had no UI test.
 * Shown when set, absent when null, still on screen with long and hostile names and at 1024 px. Writes `ui/build/screenshots/migrations-export-note-*.png`
 * as a side effect (cheap, so no opt-in tag) for the owner's look.
 */
@OptIn(ExperimentalTestApi::class)
class MigrationsExportNoteUiTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    private fun DesktopComposeUiTest.show(note: String?) {
        setContent {
            MigrationsScreen(
                state = MigrationsScreenState(profileName = "uat-eu", exportNote = note),
                onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {},
                onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {},
            )
        }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.shot(name: String) {
        outDir.mkdirs()
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "migrations-export-note-$name.png")))
    }

    @Test
    fun `a note is drawn with its text, no note draws nothing`() = runDesktopComposeUiTest(1280, 800) {
        show(null)
        onAllNodesWithTagCount("migrations-export-note").let { assertEquals(0, it) }
        show("Left out: retention of stream audit; exclusive queue session-1")
        onNodeWithTag("migrations-export-note").assertIsDisplayed()
        shot("short")
    }

    @Test
    fun `a long and hostile note stays inside the window at 1280 and at 1024 px`() {
        val hostile = "Left out: " + (1..12).joinToString("; ") { "retention of stream <b>orders</b>‮-$it-" + "n".repeat(200) }
        for ((w, h) in listOf(1280 to 800, 1024 to 768)) {
            runDesktopComposeUiTest(w, h) {
                show(hostile)
                val bounds = onNodeWithTag("migrations-export-note").fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.left >= 0f && bounds.right <= w.toFloat() + 1f, "the note leaves the window at $w px: $bounds")
                shot("long-$w")
            }
        }
    }

    private fun DesktopComposeUiTest.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
