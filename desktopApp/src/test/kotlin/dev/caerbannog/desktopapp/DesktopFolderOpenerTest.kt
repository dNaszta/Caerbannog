package dev.caerbannog.desktopapp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M5-08c1 scope 7: "Show folder" opens the folder in the system file manager, through a fake of `java.awt.Desktop`. It never throws. */
class DesktopFolderOpenerTest {

    @TempDir
    lateinit var dir: File

    private class FakeDesktop(override val isSupported: Boolean = true, private val failWith: Throwable? = null) : DesktopPort {
        val opened = mutableListOf<File>()
        override fun open(file: File) {
            failWith?.let { throw it }
            opened += file
        }
    }

    @Test
    fun `a folder is opened`() {
        val desktop = FakeDesktop()

        DesktopFolderOpener(desktop).show(dir.path)

        assertEquals(listOf(dir), desktop.opened)
    }

    @Test
    fun `a file opens its parent folder`() {
        val file = File(dir, "0001.json").also { it.writeText("{}") }
        val desktop = FakeDesktop()

        DesktopFolderOpener(desktop).show(file.path)

        assertEquals(listOf(dir), desktop.opened)
    }

    @Test
    fun `nothing happens and nothing is thrown when the desktop is not supported`() {
        val desktop = FakeDesktop(isSupported = false)

        DesktopFolderOpener(desktop).show(dir.path)

        assertTrue(desktop.opened.isEmpty())
    }

    @Test
    fun `a desktop that fails does not make Show folder throw`() {
        for (failure in listOf(IOException("no file manager"), UnsupportedOperationException("no open"), SecurityException("denied"), IllegalArgumentException("gone"))) {
            DesktopFolderOpener(FakeDesktop(failWith = failure)).show(dir.path)
        }
    }

    @Test
    fun `a folder that does not exist is not opened and does not throw`() {
        val desktop = FakeDesktop()

        DesktopFolderOpener(desktop).show(File(dir, "gone").path)

        assertTrue(desktop.opened.isEmpty(), "Desktop.open of a missing folder fails on some systems: nothing to open")
    }
}
