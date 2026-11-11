package dev.caerbannog.core.forwarding

data class ForwardingConfirmation(val lines: List<String>, val requiredText: String, val notEncrypted: Boolean)

object ForwardingConfirmations {
    fun of(request: ForwardingRequest, plan: ForwardingPlan): ForwardingConfirmation = ForwardingConfirmation(emptyList(), "", false) // M8-07 STUB
}
