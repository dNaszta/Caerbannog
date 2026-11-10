package dev.caerbannog.desktopapp

import dev.caerbannog.core.migrations.MigrationFolderChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/** Seam of [SwingMigrationFolderChooser]: shows the folder dialog on the thread it is called on; null on cancel. */
fun interface FolderDialog {
    fun pick(startIn: File): File?
}

/** The real dialog: `JFileChooser` showing folders only. */
class JFileChooserFolderDialog : FolderDialog {
    override fun pick(startIn: File): File? {
        val chooser = JFileChooser(startIn).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose the migrations folder"
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
}

/**
 * The native folder chooser. The dialog is shown on the Swing thread through `invokeAndWait` from a background dispatcher, because the caller
 * runs on the composition thread, which is the Swing thread itself and must not wait for its own queue. It starts in the current folder, or in
 * the user's home when there is none or it is gone, and returns null when the user cancels.
 */
class SwingMigrationFolderChooser(private val dialog: FolderDialog = JFileChooserFolderDialog()) : MigrationFolderChooser {
    override suspend fun choose(startIn: String?): String? = withContext(Dispatchers.IO) {
        val start = startIn?.let(::File)?.takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))
        var picked: File? = null
        SwingUtilities.invokeAndWait { picked = dialog.pick(start) }
        picked?.path
    }
}
