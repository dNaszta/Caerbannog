package dev.caerbannog.core.migrations

/** What the screen shows while an apply runs: the action being sent (1-based [index] of [total] actions of migration [version]). */
data class ApplyProgress(val version: String, val index: Int, val total: Int)

/** How the last apply ended, when it did not succeed. */
sealed interface ApplyOutcome {
    data class Failed(val version: String, val index: Int, val total: Int, val reason: String) : ApplyOutcome
    data class Conflict(val version: String, val index: Int, val total: Int, val reason: String) : ApplyOutcome
    data class NotRecorded(val version: String, val reason: String) : ApplyOutcome

    /** The text of the existing red banner until the screen draws the structured outcome. */
    fun bannerText(): String = when (this) {
        is Failed ->
            "Action $index of $total failed: $reason. Migration $version was not recorded. " +
                "Running it again is safe: actions that already succeeded change nothing the second time."
        is Conflict ->
            "Action $index of $total stopped: $reason. The existing resource was not changed. " +
                "Migration $version was not recorded. Running it again is safe."
        is NotRecorded -> reason
    }
}

/** The outcome of a finished apply, or null when it succeeded or was blocked before anything was sent. */
internal fun MigrationApplyResult.toOutcome(): ApplyOutcome? = when (this) {
    is MigrationApplyResult.ActionFailed ->
        if (kind == FailureKind.Conflict) ApplyOutcome.Conflict(version, index, total, reason)
        else ApplyOutcome.Failed(version, index, total, reason)
    is MigrationApplyResult.NotRecorded -> ApplyOutcome.NotRecorded(version, reason)
    is MigrationApplyResult.Success, is MigrationApplyResult.Blocked -> null
}
