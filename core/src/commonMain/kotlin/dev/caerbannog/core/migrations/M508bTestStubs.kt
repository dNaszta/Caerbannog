package dev.caerbannog.core.migrations

// M5-08b STUB FILE (test-first compile stubs, written by the test developer). The senior developer replaces every body, moves the
// types to their proper files and DELETES this file; M508bStubGuardTest stays red until then. Nothing here is behaviour.

/** The folder the user chose for migration files, kept per machine (`settings.json`, key `migrationsFolder`); null means "ask". */
interface MigrationFolderStore {
    var folder: String?
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

/** The typed answer of the history repository. */
sealed interface HistoryResult {
    data class Loaded(val applied: Map<String, String>) : HistoryResult
    data class Unreadable(val path: String, val reason: String) : HistoryResult
}

sealed interface HistoryMoveResult {
    /** [fileName] is the new name inside the same folder, for example `migration-history.json.unreadable-20261109-143005`. */
    data class Moved(val fileName: String) : HistoryMoveResult
    data class Failed(val reason: String) : HistoryMoveResult
}

/**
 * Keeps the applied history in step with the profile list: a rename moves the entries (REPLACING any leftover already under the new name),
 * a delete removes them.
 */
interface ProfileHistoryKeys {
    fun rename(oldName: String, newName: String)
    fun delete(profileName: String)
}

/** A migration file could not be written; [reason] is fixed text, never file content or a system message. */
class MigrationWriteException(val reason: String) : Exception(reason)

/** The state of the folder part of the Migrations screen. */
sealed interface MigrationFolderState {
    /** No folder chosen yet (or the user cancelled): nothing is read or written. */
    data object ChooseFolder : MigrationFolderState
    data class FolderUnavailable(val path: String, val reason: String) : MigrationFolderState
    data class FolderReadOnly(val path: String, val reason: String = FOLDER_READ_ONLY_MESSAGE) : MigrationFolderState
    /** The folder lists and writes; [path] is null only for a view model built without folder ports (the old in-memory mode). */
    data class Ready(val path: String?) : MigrationFolderState
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
