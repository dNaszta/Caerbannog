package dev.caerbannog.core.forwarding

sealed interface ForwardingState {
    /** Submitted; the PUT answers 201 without checking (S-F). [absentPolls] counts polls in a row that did not list the shovel. */
    data class Started(val absentPolls: Int = 0) : ForwardingState
    /** [remaining] null = unlimited (NEVER). */
    data class Running(val forwarded: Long, val remaining: Long?) : ForwardingState
    /** The shovel removed itself (S-J). */
    data object Finished : ForwardingState
    data class Failed(val reason: String, val cleanedUp: Boolean) : ForwardingState
}

object ForwardingProgress {
    /** Finished and Failed are final. A read that fails keeps the previous state. One absent poll right after Started is not yet Finished. */
    fun next(previous: ForwardingState, polled: StatusOutcome): ForwardingState {
        if (previous is ForwardingState.Finished || previous is ForwardingState.Failed) return previous
        return when (polled) {
            is StatusOutcome.Listed -> {
                val status = polled.status
                if (status.state == ShovelRunState.TERMINATED) ForwardingState.Failed(status.reason ?: ForwardingTexts.NO_REASON, cleanedUp = false)
                else ForwardingState.Running(status.forwarded ?: 0, status.remaining)
            }
            StatusOutcome.Absent ->
                if (previous is ForwardingState.Started && previous.absentPolls < 1) ForwardingState.Started(previous.absentPolls + 1) else ForwardingState.Finished
            else -> previous
        }
    }
}
