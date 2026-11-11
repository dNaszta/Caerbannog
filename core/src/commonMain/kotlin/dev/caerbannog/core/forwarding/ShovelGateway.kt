package dev.caerbannog.core.forwarding

/**
 * Exactly four operations, bound to the held 4.x connection. M8-07 STUB: the KDoc of the spec rule is the developer's (brief "Gateway").
 */
interface ShovelGateway {
    suspend fun dryRun(request: ForwardingRequest): DryRunOutcome
    suspend fun start(request: ForwardingRequest): StartOutcome
    suspend fun status(shovel: ShovelRef): StatusOutcome
    suspend fun remove(shovel: ShovelRef): RemoveOutcome
}

data class QueueCounts(val ready: Long, val unacknowledged: Long)

sealed interface DestinationQueue {
    data object Missing : DestinationQueue
    data class Exists(val type: DestinationQueueType) : DestinationQueue
}

enum class ShovelRunState { STARTING, RUNNING, TERMINATED, OTHER }

data class ShovelStatus(val ref: ShovelRef, val state: ShovelRunState, val reason: String?, val forwarded: Long?, val remaining: Long?)

data class DryRunResult(
    val source: QueueCounts,
    val destination: DestinationQueue,
    val shovelPluginsOn: Boolean,
    val userTags: Set<String>,
    val existingShovel: ShovelStatus?,
)

sealed interface DryRunOutcome {
    data class Done(val result: DryRunResult) : DryRunOutcome
    data class SourceUnreachable(val connectionName: String) : DryRunOutcome
    data object SourceNotPermitted : DryRunOutcome
    data object DestinationUnreachable : DryRunOutcome
    data class Failed(val reason: String) : DryRunOutcome
}

sealed interface StartOutcome {
    data object Submitted : StartOutcome
    data class Refused(val reason: String) : StartOutcome
    data object NotPermitted : StartOutcome
    data object Unreachable : StartOutcome
    data class Failed(val reason: String) : StartOutcome
}

sealed interface StatusOutcome {
    data class Listed(val status: ShovelStatus) : StatusOutcome
    data object Absent : StatusOutcome
    data object NotPermitted : StatusOutcome
    data object Unreachable : StatusOutcome
    data class Failed(val reason: String) : StatusOutcome
}

sealed interface RemoveOutcome {
    data object Removed : RemoveOutcome
    data object NotFound : RemoveOutcome
    data object NotPermitted : RemoveOutcome
    data object Unreachable : RemoveOutcome
    data class Failed(val reason: String) : RemoveOutcome
}
