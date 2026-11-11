package dev.caerbannog.core.forwarding

sealed interface ForwardingState {
    data class Started(val absentPolls: Int = 0) : ForwardingState
    data class Running(val forwarded: Long, val remaining: Long?) : ForwardingState
    data object Finished : ForwardingState
    data class Failed(val reason: String, val cleanedUp: Boolean) : ForwardingState
}

object ForwardingProgress {
    fun next(previous: ForwardingState, polled: StatusOutcome): ForwardingState = previous // M8-07 STUB
}
