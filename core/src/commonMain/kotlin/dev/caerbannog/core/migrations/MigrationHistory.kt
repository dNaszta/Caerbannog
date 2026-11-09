package dev.caerbannog.core.migrations

/** The typed answer of the history repository. */
sealed interface HistoryResult {
    data class Loaded(val applied: Map<String, String>) : HistoryResult
    data class Unreadable(val path: String, val reason: String) : HistoryResult
}

/** The answer of moving an unreadable history file aside. */
sealed interface HistoryMoveResult {
    /** [fileName] is the new name inside the same folder, for example `migration-history.json.unreadable-20261109-143005`. */
    data class Moved(val fileName: String) : HistoryMoveResult
    data class Failed(val reason: String) : HistoryMoveResult
}

/**
 * Keeps the applied history in step with the profile list: a rename moves the entries (REPLACING any leftover already under the new name),
 * a delete removes them. The history is changed FIRST: when it cannot be, the caller leaves the profile as it is.
 */
interface ProfileHistoryKeys {
    fun rename(oldName: String, newName: String): HistoryKeyResult
    fun delete(profileName: String): HistoryKeyResult
}

/** The answer of a history key change; [Failed.reason] is fixed text. An unreadable history file counts as [Done]. */
sealed interface HistoryKeyResult {
    data object Done : HistoryKeyResult
    data class Failed(val reason: String) : HistoryKeyResult
}

/** A migration file could not be written; [reason] is fixed text, never file content or a system message. */
class MigrationWriteException(val reason: String) : Exception(reason)

