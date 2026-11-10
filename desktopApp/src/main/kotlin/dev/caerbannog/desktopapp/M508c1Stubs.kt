package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.ConnectionsState
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.FolderOpener
import dev.caerbannog.core.migrations.MigrationFolderChooser
import dev.caerbannog.core.migrations.MigrationFolderPorts
import dev.caerbannog.core.migrations.MigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.topology.TopologyCatalog
import kotlinx.coroutines.CoroutineScope
import java.io.File

// M5-08c1 STUBS (step 2, tests first). Signatures only: every body throws NotImplementedError, so the tests compile and are RED for the right
// reason. The senior developer replaces this file (adapters in their own files, the wiring in CompositionRoot.kt and Main.kt). The seams
// (FolderDialog, DesktopPort, MigrationsInputs and the AppComposition members) are the test developer's choices; see the step 2 notes in
// docs/tasks/M5-08c1-migrations-wiring.md.

/** Seam of [SwingMigrationFolderChooser]: shows the folder dialog, on the thread it is called on (the chooser calls it on the Swing thread); null on cancel. */
fun interface FolderDialog {
    fun pick(startIn: File): File?
}

/** The real dialog: `JFileChooser` with `DIRECTORIES_ONLY`. */
class JFileChooserFolderDialog : FolderDialog {
    override fun pick(startIn: File): File? = throw NotImplementedError("M5-08c1 step 3")
}

/** Native folder chooser: shows [dialog] through `SwingUtilities.invokeAndWait` (never on the composition thread), starting in the current folder or the home. */
class SwingMigrationFolderChooser(private val dialog: FolderDialog = JFileChooserFolderDialog()) : MigrationFolderChooser {
    override suspend fun choose(startIn: String?): String? = throw NotImplementedError("M5-08c1 step 3")
}

/** Seam of [DesktopFolderOpener] for `java.awt.Desktop`. */
interface DesktopPort {
    val isSupported: Boolean
    fun open(file: File)
}

/** The real one over `java.awt.Desktop`. */
class AwtDesktopPort : DesktopPort {
    override val isSupported: Boolean get() = throw NotImplementedError("M5-08c1 step 3")
    override fun open(file: File): Unit = throw NotImplementedError("M5-08c1 step 3")
}

/** Opens a folder in the system file manager; a file path opens its parent; does nothing, and never throws, when unsupported or failing. */
class DesktopFolderOpener(private val desktop: DesktopPort = AwtDesktopPort()) : FolderOpener {
    override fun show(path: String): Unit = throw NotImplementedError("M5-08c1 step 3")
}

/**
 * What one Migrations screen is built from. The window rebuilds the view model when this value changes (so it must be a value: equal inputs are
 * equal); the executor is compared by identity (a new connection is a new executor).
 */
data class MigrationsInputs(
    val connectionName: String,
    val vhost: String,
    val hasConfigurePermission: Boolean,
    val controlLevel: TopologyControlLevel,
    val executor: TopologyMigrationExecutor?,
    /** Why there is no executor (the connection's own fixed text, or a fixed "connect first" text when nothing is connected); null when there is one. */
    val unavailableReason: String?,
    val topology: TopologyCatalog?,
)

/** The one history repository of the app (the very instance given to `ConnectionsViewModel` as `historyKeys`). */
val AppComposition.migrationHistory: MigrationHistoryRepository
    get() = throw NotImplementedError("M5-08c1 step 3")

/** The ports of the Migrations screen: the settings file as the folder store, the chooser, folder repositories, and [migrationHistory]. */
val AppComposition.migrationFolderPorts: MigrationFolderPorts
    get() = throw NotImplementedError("M5-08c1 step 3")

/** The inputs from the connection state: the profile's own level, the connection's vhost, the permission check's answer, its executor and reason. */
fun AppComposition.migrationsInputs(connections: ConnectionsState): MigrationsInputs = throw NotImplementedError("M5-08c1 step 3")

/** The Migrations view model for [inputs]: real runner over [migrationHistory] and the connection's executor (or a refusing one when there is none). */
fun AppComposition.createMigrationsViewModel(inputs: MigrationsInputs, scope: CoroutineScope): MigrationsViewModel =
    throw NotImplementedError("M5-08c1 step 3")
