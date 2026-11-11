package dev.caerbannog.desktopapp

import dev.caerbannog.core.migrate3x.DefinitionsFileChooser
import dev.caerbannog.core.migrate3x.ReportFileSaver
import dev.caerbannog.core.migrate3x.SaveOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/** Seam of [SwingDefinitionsFileChooser]: shows the open dialog on the thread it is called on; null on cancel. */
fun interface OpenFileDialog {
    fun pick(startIn: File): File?
}

/** Seam of [SwingReportFileSaver]: shows the save dialog on the thread it is called on; null on cancel. */
fun interface SaveFileDialog {
    fun pick(startIn: File, suggestedName: String): File?
}

/** The real open dialog: `JFileChooser` for one `.json` file. */
class JFileChooserOpenDialog : OpenFileDialog {
    override fun pick(startIn: File): File? {
        val chooser = JFileChooser(startIn).apply {
            dialogTitle = "Choose a RabbitMQ definitions file"
            fileFilter = FileNameExtensionFilter("JSON files", "json")
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
}

/** The real save dialog: `JFileChooser`; a name without an extension gets `.json`, and an existing file is replaced only after a question. */
class JFileChooserSaveDialog : SaveFileDialog {
    override fun pick(startIn: File, suggestedName: String): File? {
        val chooser = JFileChooser(startIn).apply {
            dialogTitle = "Save the compatibility report"
            selectedFile = File(startIn, suggestedName)
            fileFilter = FileNameExtensionFilter("JSON files", "json")
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
        val picked = withJsonExtension(chooser.selectedFile)
        val replace = !picked.exists() ||
            JOptionPane.showConfirmDialog(null, "${picked.name} already exists. Replace it?", "Replace file", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION
        return picked.takeIf { replace }
    }
}

internal fun withJsonExtension(file: File): File = if (file.name.contains('.')) file else File(file.parentFile, file.name + ".json")

private fun startFolder(): File = File(System.getProperty("user.home"))

/**
 * Runs [block] on the Swing thread and waits for it. The caller runs on a background dispatcher, because the composition thread is the
 * Swing thread itself and must not wait for its own queue (same reasoning as [SwingMigrationFolderChooser]).
 */
private fun <T> onSwingThread(block: () -> T): T {
    var result: T? = null
    SwingUtilities.invokeAndWait { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/** The native file chooser of the Migrate from 3.x screen (read only: it only returns a path). */
class SwingDefinitionsFileChooser(private val dialog: OpenFileDialog = JFileChooserOpenDialog()) : DefinitionsFileChooser {
    override suspend fun choose(): String? = withContext(Dispatchers.IO) {
        onSwingThread { dialog.pick(startFolder()) }?.path
    }
}

/**
 * The native save dialog and the write of the report. Where the file goes is the user's choice; the app never picks the migrations folder
 * for it. A failure gives a fixed reason: the exception text may carry a path or system words, so it is not shown.
 */
class SwingReportFileSaver(
    private val dialog: SaveFileDialog = JFileChooserSaveDialog(),
    private val write: (File, String) -> Unit = { file, text -> Files.writeString(file.toPath(), text, Charsets.UTF_8) },
) : ReportFileSaver {
    override suspend fun save(suggestedName: String, text: String, secret: Boolean): SaveOutcome = withContext(Dispatchers.IO) {
        val target = onSwingThread { dialog.pick(startFolder(), suggestedName) } ?: return@withContext SaveOutcome.Cancelled
        try {
            if (secret) restrictToOwner(target)
            write(target, text)
            SaveOutcome.Saved(target.path)
        } catch (_: IOException) {
            SaveOutcome.Failed(WRITE_FAILED)
        } catch (_: SecurityException) {
            SaveOutcome.Failed(WRITE_FAILED)
        }
    }

    /**
     * A file that holds password hashes is readable by its owner only (P13): created with mode 600, or set to it before the text is written over an
     * existing file. Best effort: a system without POSIX permissions keeps its default (not verified on Windows), and the save goes on.
     */
    private fun restrictToOwner(target: File) {
        val path = target.toPath()
        val ownerOnly = PosixFilePermissions.fromString("rw-------")
        try {
            if (Files.exists(path)) Files.setPosixFilePermissions(path, ownerOnly) else Files.createFile(path, PosixFilePermissions.asFileAttribute(ownerOnly))
        } catch (_: UnsupportedOperationException) {
            // No POSIX permissions here.
        } catch (_: IOException) {
            // The write below reports a real failure.
        }
    }

    companion object {
        const val WRITE_FAILED: String = "The file could not be written"
    }
}
