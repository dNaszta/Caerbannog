package dev.caerbannog.desktopapp

import dev.caerbannog.core.migrations.FolderOpener
import java.awt.Desktop
import java.io.File

/** Seam of [DesktopFolderOpener] for `java.awt.Desktop`. */
interface DesktopPort {
    val isSupported: Boolean
    fun open(file: File)
}

/** The real one, over `java.awt.Desktop`. */
class AwtDesktopPort : DesktopPort {
    override val isSupported: Boolean get() = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
    override fun open(file: File) = Desktop.getDesktop().open(file)
}

/**
 * Opens a folder in the system file manager; a file path opens its parent. A missing path is not opened, and an unsupported or failing desktop
 * does nothing: "Show folder" is a convenience, never a reason for an error.
 */
class DesktopFolderOpener(private val desktop: DesktopPort = AwtDesktopPort()) : FolderOpener {
    override fun show(path: String) {
        val target = File(path).let { if (it.isDirectory) it else if (it.exists()) it.parentFile else null } ?: return
        try {
            if (desktop.isSupported) desktop.open(target)
        } catch (_: Exception) {
            // Nothing to tell the user: the folder simply does not open.
        }
    }
}
