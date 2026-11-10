package dev.caerbannog.desktopapp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M5-08c1 scope 7: the native folder chooser. Skipped when the machine has no display. The real `JFileChooser` is never shown here: the
 * [FolderDialog] seam is a script, so the test checks what the chooser does around it (the Swing thread, the start folder, cancel, the result).
 */
class SwingMigrationFolderChooserTest {

    @TempDir
    lateinit var dir: File

    private class ScriptedDialog(private val answer: File?) : FolderDialog {
        var startedIn: File? = null
        var onEventThread: Boolean? = null
        var calls = 0
        override fun pick(startIn: File): File? {
            calls++
            startedIn = startIn
            onEventThread = SwingUtilities.isEventDispatchThread()
            return answer
        }
    }

    private fun needsDisplay() = assumeFalse(GraphicsEnvironment.isHeadless(), "no display: the Swing dialog thread cannot run")

    @Test
    fun `a cancelled dialog returns null`() {
        needsDisplay()
        val dialog = ScriptedDialog(null)

        val picked = runBlocking { SwingMigrationFolderChooser(dialog).choose(null) }

        assertNull(picked)
        assertEquals(1, dialog.calls)
    }

    @Test
    fun `a chosen folder returns its path`() {
        needsDisplay()
        val dialog = ScriptedDialog(dir)

        val picked = runBlocking { SwingMigrationFolderChooser(dialog).choose(null) }

        assertEquals(dir.path, picked)
    }

    @Test
    fun `the dialog runs on the Swing thread, whichever thread asks`() {
        needsDisplay()
        val dialog = ScriptedDialog(dir)
        assertFalse(SwingUtilities.isEventDispatchThread(), "precondition: the test thread is not the Swing thread")

        runBlocking { SwingMigrationFolderChooser(dialog).choose(null) }

        assertEquals(true, dialog.onEventThread, "a dialog on any other thread is not safe")
    }

    @Test
    fun `the dialog starts in the current folder when there is one`() {
        needsDisplay()
        val dialog = ScriptedDialog(null)

        runBlocking { SwingMigrationFolderChooser(dialog).choose(dir.path) }

        assertEquals(dir, dialog.startedIn)
    }

    @Test
    fun `the dialog starts in the user's home when there is no current folder or it is gone`() {
        needsDisplay()
        val home = File(System.getProperty("user.home"))
        val first = ScriptedDialog(null)
        runBlocking { SwingMigrationFolderChooser(first).choose(null) }
        assertEquals(home, first.startedIn)

        val second = ScriptedDialog(null)
        runBlocking { SwingMigrationFolderChooser(second).choose(File(dir, "does-not-exist").path) }
        assertTrue(second.startedIn == home, "a folder that is gone must not be the start: ${second.startedIn}")
    }
}
