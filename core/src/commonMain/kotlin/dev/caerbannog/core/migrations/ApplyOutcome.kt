package dev.caerbannog.core.migrations

/** What the screen shows while an apply runs: the action being sent (1-based [index] of [total] actions of migration [version]). */
data class ApplyProgress(val version: String, val index: Int, val total: Int)

/** How the last apply ended, when it did not succeed. */
sealed interface ApplyOutcome {
    data class Failed(val version: String, val index: Int, val total: Int, val reason: String) : ApplyOutcome
    /** [resource] is the plain name of the failed action's queue, exchange, stream or policy, or null (a binding has none); the screen says "resource" then. */
    data class Conflict(val version: String, val index: Int, val total: Int, val reason: String, val resource: String? = null) : ApplyOutcome
    data class NotRecorded(val version: String, val reason: String) : ApplyOutcome
}

/** The outcome of a finished apply, or null when it succeeded or was blocked before anything was sent. */
internal fun MigrationApplyResult.toOutcome(): ApplyOutcome? = when (this) {
    is MigrationApplyResult.ActionFailed ->
        if (kind == FailureKind.Conflict) ApplyOutcome.Conflict(version, index, total, reason, action.resourceName()?.takeIf(::echoableName))
        else ApplyOutcome.Failed(version, index, total, reason)
    is MigrationApplyResult.NotRecorded -> ApplyOutcome.NotRecorded(version, reason)
    is MigrationApplyResult.Success, is MigrationApplyResult.Blocked -> null
}

/** The name a conflict is about, taken from the action itself (never parsed from a broker text); null for a binding. The caller echoes it only when [echoableName] allows it, as the executor does. */
private fun MigrationAction.resourceName(): String? = when (this) {
    is MigrationAction.DeclareQueue -> name
    is MigrationAction.DeleteQueue -> name
    is MigrationAction.DeclareExchange -> name
    is MigrationAction.DeleteExchange -> name
    is MigrationAction.DeclareStream -> name
    is MigrationAction.DeleteStream -> name
    is MigrationAction.DeclarePolicy -> name
    is MigrationAction.DeclareOperatorPolicy -> name
    is MigrationAction.DeletePolicy -> name
    is MigrationAction.DeclareBinding, is MigrationAction.DeleteBinding -> null
    is MigrationAction.DeclareVhost -> name
    is MigrationAction.DeclareUser -> name
    is MigrationAction.SetPermissions -> user
    is MigrationAction.SetTopicPermissions -> user
}
