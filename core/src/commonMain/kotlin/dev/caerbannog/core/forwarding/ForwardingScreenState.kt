package dev.caerbannog.core.forwarding

/** One queue of the held analysis. */
data class QueueRow(val vhost: String, val name: String)

/** The Remove shovel button: shown, and enabled only at Full control; [reason] is the text under a disabled one. */
data class RemoveButton(val enabled: Boolean, val reason: String?)

/** The held 4.x connection the shovel is created on. */
data class DestinationConnection(val profile: dev.caerbannog.core.connections.ConnectionProfile)

sealed interface ForwardingScreenState {
    data object Closed : ForwardingScreenState

    /** [forwardDisabledReason] is non-null when no queue can be forwarded now (no 4.x connection, or locked). */
    data class Pick(val query: String, val rows: List<QueueRow>, val forwardDisabledReason: String?) : ForwardingScreenState
    data class Preparing(val queue: String) : ForwardingScreenState

    /** A plan with blocks: no confirm form. [facts] null when the gate blocked (no request was sent). [remove] only for an existing shovel. */
    data class Blocked(val queue: String, val destination: String, val blocks: List<ForwardingBlock>, val warnings: List<ForwardingWarning>, val facts: DryRunResult?, val remove: RemoveButton?) : ForwardingScreenState

    /** A fixed text: a failed dry run, a refused or failed start. */
    data class Problem(val queue: String, val text: String) : ForwardingScreenState
    data class Confirm(val queue: String, val destination: String, val lines: List<String>, val warnings: List<ForwardingWarning>, val facts: DryRunResult, val form: ForwardingForm, val canConfirm: Boolean) : ForwardingScreenState
    data class Starting(val queue: String) : ForwardingScreenState

    /** Running, Finished or Failed; [texts] are the lines to show. [remove] is null after Finished (D4). */
    data class Progress(val queue: String, val state: ForwardingState, val texts: List<String>, val remove: RemoveButton?) : ForwardingScreenState
}
