package dev.caerbannog.core.connections

import dev.caerbannog.core.streams.StreamListTexts

/**
 * Gate determining whether the "Delete stream" action may be invoked.
 * Enabled only when connected and the user has configure permission (topology unlocked).
 */
sealed interface DeleteStreamGate {
    data object Enabled : DeleteStreamGate
    data class Disabled(val reason: String) : DeleteStreamGate
}

/**
 * Evaluates the gate for deleting streams based on current connection and link state.
 */
fun deleteStreamGate(current: CurrentConnection?, link: LinkState): DeleteStreamGate {
    if (current == null || link !is LinkState.Connected) return DeleteStreamGate.Disabled(StreamListTexts.NOT_CONNECTED)
    val done = current.status as? PermissionCheckStatus.Done
    if (done != null && done.result.topologyUnlocked) return DeleteStreamGate.Enabled
    return DeleteStreamGate.Disabled("Topology is locked because the user has no configure permission.")
}
