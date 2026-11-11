package dev.caerbannog.core.forwarding

/** The sentences of the confirmation (spec "Forwarding messages with a dynamic Shovel", safeguards 1, 5, 7, 8). Never a password. */
object ForwardingTexts {
    /** The reason of a terminated shovel that gave none. */
    const val NO_REASON: String = "The shovel stopped without giving a reason"

    const val MOVED = "The messages are moved: they leave the source queue when the destination confirms. The app cannot undo it."
    const val SOURCE_KEPT = "The source queue itself is not deleted."
    const val KEEPS_CREDENTIALS = "The shovel keeps credentials on the destination broker until it finishes or is removed."
    const val CLEAR_TEXT = "The source password is stored there in clear text, and any administrator of that broker can read it until the shovel finishes or is removed."
    const val KEEPS_RUNNING = "The shovel and its credentials stay until you remove the shovel."
    const val NOT_ENCRYPTED = "Not encrypted: the shovel reaches the source over a plain connection."

    fun source(connection: String, vhost: String, queue: String, address: String) = "From: $connection, virtual host $vhost, queue $queue ($address)"
    fun destination(connection: String, vhost: String, queue: String) = "To: $connection, virtual host $vhost, queue $queue"

    // ---- M8-09 STUB: the texts of the screen and the failure texts moved from ForwardingService. Empty until built; the exact wording is owner decision D2 and D3. ----
    const val BROKER_UNREACHABLE: String = "" // M8-09 STUB: "The broker could not be reached"
    const val CREATE_NOT_ALLOWED: String = "" // M8-09 STUB: "The broker did not allow creating the shovel"
    const val REMOVE_NOT_ALLOWED: String = "" // M8-09 STUB: "The broker did not allow removing the shovel"
    const val CONNECT_FIRST: String = "" // M8-09 STUB
    const val NEEDS_FULL_CONTROL: String = "" // M8-09 STUB
    const val FINISHED: String = "" // M8-09 STUB
    const val FINISHED_NONE_STAYED: String = "" // M8-09 STUB
    const val CLEANED_UP: String = "" // M8-09 STUB
    const val BUTTON_FORWARD: String = "" // M8-09 STUB
    const val LABEL_TYPED: String = "" // M8-09 STUB
    const val LABEL_ANOTHER_USER: String = "" // M8-09 STUB
    const val LABEL_KEEP_RUNNING: String = "" // M8-09 STUB

    fun block(block: ForwardingBlock, destination: String, queue: String): String = "" // M8-09 STUB
    fun warning(warning: ForwardingWarning): String = "" // M8-09 STUB
    fun probeFailed(outcome: DryRunOutcome): String = "" // M8-09 STUB
    fun amqpLabel(destination: String): String = "" // M8-09 STUB
    fun moved(forwarded: Long, ready: Long): String = "" // M8-09 STUB
    fun movedKeepRunning(forwarded: Long): String = "" // M8-09 STUB
    fun finishedStayed(count: Long): String = "" // M8-09 STUB
    fun failed(reason: String): String = "" // M8-09 STUB
    fun notCleanedUp(destination: String): String = "" // M8-09 STUB
}
