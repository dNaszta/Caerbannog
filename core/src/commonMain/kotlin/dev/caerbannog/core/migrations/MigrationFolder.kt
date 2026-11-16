package dev.caerbannog.core.migrations

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** The folder the user chose for migration files, kept per machine (`settings.json`, key `migrationsFolder`); null means "ask". */
interface MigrationFolderStore {
    var folder: String?

    /**
     * The folder each time it is set. The window rebuilds its view model when the inputs change, so the view model that asked for a folder may
     * not be the one the user looks at; every view model reloads from this. A store that cannot tell keeps the empty default.
     */
    val changes: Flow<String?> get() = emptyFlow()
}

/** Asks the user for a folder (a desktop dialog in the real app, scripted in tests); null when the user cancels. Receives no secret. */
fun interface MigrationFolderChooser {
    suspend fun choose(startIn: String?): String?
}

/** Shows a folder in the system file manager (desktop only). Shared with M1-13: whoever lands first keeps this one. */
fun interface FolderOpener {
    fun show(path: String)
}

/** Time source for the timestamp of a moved-aside history file. */
fun interface NowProvider {
    fun nowMillis(): Long
}

/** A file in the migrations folder that is not a valid migration: listed, never changed or deleted. [reason] never holds file content. */
data class InvalidMigrationFile(val fileName: String, val reason: String)

/** Why a folder cannot be used; [message] is the fixed text shown to the user. */
enum class FolderProblem(val message: String) {
    MISSING("The folder does not exist"),
    NOT_A_FOLDER("The path is not a folder"),
    UNREADABLE("The folder cannot be read"),
}

const val FOLDER_READ_ONLY_MESSAGE: String = "The folder is read-only"

/** What a migrations folder holds right now. */
sealed interface FolderListing {
    /** [readOnly]: listing works but import and export do not. */
    data class Listed(
        val files: List<MigrationFile>,
        val invalid: List<InvalidMigrationFile> = emptyList(),
        val readOnly: Boolean = false,
    ) : FolderListing

    data class Unavailable(val path: String, val problem: FolderProblem) : FolderListing
}

/** The state of the folder part of the Migrations screen. */
sealed interface MigrationFolderState {
    /** No folder chosen yet (or the user cancelled): nothing is read or written. */
    data object ChooseFolder : MigrationFolderState
    data class FolderUnavailable(val path: String, val problem: FolderProblem) : MigrationFolderState {
        /** The fixed text of [problem]. */
        val reason: String get() = problem.message
    }
    data class FolderReadOnly(val path: String, val reason: String = FOLDER_READ_ONLY_MESSAGE) : MigrationFolderState
    /** The folder lists and writes; [path] is null only for a view model built without folder ports (the old in-memory mode). */
    data class Ready(val path: String?) : MigrationFolderState
}

/**
 * The folder verdict of a listing, shared by the Migrations screen and the planner so they cannot disagree. [path] is the folder in the
 * store; a read-only listing without a path stays [MigrationFolderState.Ready] (the old in-memory mode).
 */
fun folderStateOf(listing: FolderListing?, path: String?): MigrationFolderState = when {
    listing == null -> MigrationFolderState.ChooseFolder
    listing is FolderListing.Unavailable -> MigrationFolderState.FolderUnavailable(listing.path, listing.problem)
    listing is FolderListing.Listed && listing.readOnly && path != null -> MigrationFolderState.FolderReadOnly(path)
    else -> MigrationFolderState.Ready(path)
}

data class HistoryUnreadable(val path: String, val reason: String)

/** The ports the view model needs once the migrations live in a user folder. */
class MigrationFolderPorts(
    val store: MigrationFolderStore,
    val chooser: MigrationFolderChooser,
    /** Builds the file repository for a folder; the view model never touches files itself. */
    val repositoryFor: (folder: String) -> MigrationFileRepository,
    val history: MigrationHistoryRepository,
)
