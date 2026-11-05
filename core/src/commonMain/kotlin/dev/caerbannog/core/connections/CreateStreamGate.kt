package dev.caerbannog.core.connections

import dev.caerbannog.core.streams.StreamListTexts

/** Whether the "Create stream" button of the stream list may be pressed, and if not, why (the tooltip of the disabled button). */
sealed interface CreateStreamGate {
    data object Enabled : CreateStreamGate

    data class Disabled(val reason: String) : CreateStreamGate
}

/**
 * Enabled only while the link is Connected and the permission check says topology is unlocked (confirmed or declared, the same
 * rule as the pill). The reason of a disabled gate is the pill text, so the tooltip and the pill agree (spec "Rules").
 * The self-constraint level is not part of the gate until M3 (architect ruling, 05c).
 */
fun createStreamGate(current: CurrentConnection?, link: LinkState): CreateStreamGate {
    if (current == null || link !is LinkState.Connected) return CreateStreamGate.Disabled(StreamListTexts.NOT_CONNECTED)
    val done = current.status as? PermissionCheckStatus.Done
    if (done != null && done.result.topologyUnlocked) return CreateStreamGate.Enabled
    return CreateStreamGate.Disabled(permissionPill(current.status).text)
}
