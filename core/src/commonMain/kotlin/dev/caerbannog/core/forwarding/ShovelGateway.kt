package dev.caerbannog.core.forwarding

/**
 * The only place the app touches a Shovel: exactly four operations, bound to the held 4.x connection.
 *
 * Spec rule ("Forwarding messages with a dynamic Shovel"): the one accepted exception to the queue message safety rule is that the app may
 * configure a broker-side dynamic Shovel that moves the messages of a queue; the broker moves them, the app never touches a message.
 * Allowed: creating, reading the status of and removing a dynamic Shovel through the management API, by this gateway only, with the safeguards.
 * Not allowed, always: any code in the app process (or the MCP server) that gets, consumes, acknowledges, rejects, deletes or purges queue
 * messages other than the requeue peek; deleting a queue as part of forwarding; a second read path; a Shovel with any `ack-mode` other than
 * `on-confirm`; starting a Shovel without the confirmation; creating or changing anything on a 3.x broker; a Shovel as a migration action;
 * an MCP tool that creates or removes a Shovel. So this interface has no get, consume, ack, purge, delete-queue and no list of parameters.
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
