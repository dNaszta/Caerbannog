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
}
