package dev.caerbannog.core.connections

/** Whether the "Create stream" button of the stream list may be pressed, and if not, why (the tooltip of the disabled button). */
sealed interface CreateStreamGate {
    data object Enabled : CreateStreamGate

    data class Disabled(val reason: String) : CreateStreamGate
}

/**
 * COMPILE STUB (M2-05c step 2, test developer): no behaviour yet. The rule is in `CreateStreamGateTest`.
 */
fun createStreamGate(current: CurrentConnection?, link: LinkState): CreateStreamGate = TODO("M2-05c")
