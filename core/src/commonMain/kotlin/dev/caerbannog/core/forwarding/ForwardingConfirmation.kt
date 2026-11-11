package dev.caerbannog.core.forwarding

/** [requiredText] is the source queue name the user types; [lines] never hold a password. */
data class ForwardingConfirmation(val lines: List<String>, val requiredText: String, val notEncrypted: Boolean)

object ForwardingConfirmations {
    fun of(request: ForwardingRequest, plan: ForwardingPlan): ForwardingConfirmation {
        val source = request.source
        val notEncrypted = !source.address.tls
        val lines = buildList {
            add(ForwardingTexts.source(source.connectionName, source.vhost, source.queue, source.address.toString()))
            add(ForwardingTexts.destination(request.destinationConnection, request.destinationVhost, request.destinationQueue))
            add(ForwardingTexts.MOVED)
            add(ForwardingTexts.SOURCE_KEPT)
            add(ForwardingTexts.KEEPS_CREDENTIALS)
            add(ForwardingTexts.CLEAR_TEXT)
            if (ForwardingWarning.KeepsShovelAndCredentials in plan.warnings) add(ForwardingTexts.KEEPS_RUNNING)
            if (notEncrypted) add(ForwardingTexts.NOT_ENCRYPTED)
        }
        return ForwardingConfirmation(lines, source.queue, notEncrypted)
    }
}
