package dev.caerbannog.core.migrations

/**
 * The words of the Migrations screen states, built from structured data (`index`, `total`, `reason`) in one place so a wording change touches
 * this file only. Every value is plain text; the screen never renders it as markup. No broker text, password or URL goes in here: the reasons
 * are the executor's fixed texts.
 */
object MigrationsWording {
    const val UNAVAILABLE_TITLE = "Migrations are not offered on this connection"
    const val HISTORY_TITLE = "The applied history cannot be read, so nothing can be applied"
    const val FOLDER_READ_ONLY_HINT = "You can read and apply the migrations; importing, exporting and editing need a writable folder."
    const val CHOOSE_FOLDER_TITLE = "Choose the folder for your migration files"
    const val CHOOSE_FOLDER_TEXT =
        "Migration files are plain JSON files that you keep where you like, for example in a git repository. " +
            "Nothing is read or written until you choose a folder. The list of applied migrations is not kept there: it stays with this app, per connection."

    fun applying(progress: ApplyProgress): String = "Applying ${progress.version}: action ${progress.index} of ${progress.total}"

    /** The first line of an outcome banner. */
    fun headline(outcome: ApplyOutcome): String = when (outcome) {
        is ApplyOutcome.Failed -> "Action ${outcome.index} of ${outcome.total} failed: ${outcome.reason}"
        is ApplyOutcome.Conflict -> "Action ${outcome.index} of ${outcome.total} stopped: ${outcome.reason}"
        is ApplyOutcome.NotRecorded -> outcome.reason
    }

    /** The second line of an outcome banner. */
    fun explanation(outcome: ApplyOutcome): String = when (outcome) {
        is ApplyOutcome.Failed ->
            "Migration ${outcome.version} was not recorded. Running it again is safe: actions that already succeeded change nothing the second time."
        is ApplyOutcome.Conflict ->
            "The existing ${outcome.resource?.let { "\"$it\"" } ?: "resource"} was not changed. Migration ${outcome.version} was not recorded. " +
                "To go on, change the file or the broker so that they agree, then apply again."
        is ApplyOutcome.NotRecorded ->
            "The broker has the changes. The list still shows ${outcome.version} as Pending until the history can be written."
    }

    /** The 0-based index of the dry-run row to mark "Failed", or null when the last apply did not fail on an action. */
    fun failedActionIndex(outcome: ApplyOutcome?, version: String?): Int? =
        (outcome as? ApplyOutcome.Failed)?.takeIf { it.version == version }?.index?.minus(1)
}
