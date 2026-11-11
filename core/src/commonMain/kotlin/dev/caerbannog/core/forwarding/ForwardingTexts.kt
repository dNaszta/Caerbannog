package dev.caerbannog.core.forwarding

/** The sentences of the confirmation (spec "Forwarding messages with a dynamic Shovel", safeguards 1, 5, 7, 8). Never a password. */
object ForwardingTexts {
    // M8-14b STUB: empty until the developer writes the texts pinned by ForwardingHelpTextsTest.
    const val ADVICE_TOP: String = ""
    const val ADVICE_PICK: String = ""
    const val ADVICE_MOVE: String = ""
    const val ADVICE_LEAVE: String = ""
    val HELP_TIPS: List<dev.caerbannog.core.help.HelpText> = emptyList()
    fun tip(id: String): String = HELP_TIPS.firstOrNull { it.id == id }?.text.orEmpty() // M8-14b STUB
    fun failureAdvice(cleanedUp: Boolean): String = ""

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

    // ---- the failure texts of ForwardingService and the texts of the forwarding screen (owner decisions D2 and D3, pinned in ForwardingTextsTest) ----
    const val BROKER_UNREACHABLE: String = "The broker could not be reached"
    const val CREATE_NOT_ALLOWED: String = "The broker did not allow creating the shovel"
    const val REMOVE_NOT_ALLOWED: String = "The broker did not allow removing the shovel"
    const val NO_DESTINATION: String = "No 4.x connection is held"
    const val CONNECT_FIRST: String = "Connect to a RabbitMQ 4.x broker first"
    const val NEEDS_FULL_CONTROL: String = "Removing a shovel needs Full control"
    const val FINISHED: String = "Finished."
    const val FINISHED_NONE_STAYED: String = "Finished. No messages stayed on the source"
    const val CLEANED_UP: String = "The shovel was removed, so no stored credentials remain."
    const val SHOVEL_REMOVED: String = "The shovel was removed."
    const val STARTING: String = "Creating the shovel on the 4.x broker"
    const val PREPARING: String = "Reading both brokers to plan the forwarding"
    const val BUTTON_FORWARD: String = "Forward messages"
    const val LABEL_TYPED: String = "Type the source queue name to confirm"
    const val LABEL_ANOTHER_USER: String = "Use another user for this Shovel"
    const val LABEL_KEEP_RUNNING: String = "Keep the shovel running after the messages are moved"

    fun block(block: ForwardingBlock, destination: String, queue: String): String = when (block) {
        ForwardingBlock.TopologyLocked -> "Topology changes are locked on $destination: forwarding needs the level Declare and edit and the configure permission."
        ForwardingBlock.NoPolicymakerTag -> "The user of $destination needs the policymaker tag to create a shovel."
        ForwardingBlock.CannotReadStatus ->
            "The user of $destination cannot read the shovel status and needs the monitoring tag. Without it the app could not see a failed shovel or remove it."
        ForwardingBlock.ShovelPluginsOff -> "The shovel plugins are not enabled on $destination. Enable rabbitmq_shovel and rabbitmq_shovel_management."
        ForwardingBlock.DestinationMissing -> "The queue $queue does not exist on $destination. Apply the migration that declares it first."
        is ForwardingBlock.ShovelExists -> {
            val s = block.status
            "A shovel named ${s.ref.name} already exists on $destination (${s.state.name.lowercase()}, ${s.forwarded ?: 0} forwarded). The app never replaces a shovel."
        }
    }

    fun warning(warning: ForwardingWarning): String = when (warning) {
        is ForwardingWarning.DestinationNotQuorumOrStream -> "The destination queue is a ${warning.type.name.lowercase()} queue, not a quorum queue."
        ForwardingWarning.KeepsShovelAndCredentials -> KEEPS_RUNNING
        is ForwardingWarning.NotEncrypted -> NOT_ENCRYPTED
    }

    fun probeFailed(outcome: DryRunOutcome): String = when (outcome) {
        is DryRunOutcome.SourceUnreachable -> "${outcome.connectionName} could not be reached, so its queue could not be read."
        DryRunOutcome.SourceNotPermitted -> "The 3.x broker refused to show the queue, or the password of its connection is not stored."
        DryRunOutcome.DestinationUnreachable -> "The 4.x broker could not be reached."
        is DryRunOutcome.Failed -> "The 4.x broker did not answer the dry run as expected."
        is DryRunOutcome.Done -> ""
    }

    fun amqpLabel(destination: String): String = "AMQP host and port as $destination reaches them"
    fun moved(forwarded: Long, ready: Long): String = "Moved about $forwarded of $ready messages"
    fun movedKeepRunning(forwarded: Long): String = "Moved $forwarded messages; the shovel keeps running"
    fun finishedStayed(count: Long): String = "Finished. About $count messages stayed on the source because they were published meanwhile"
    fun failed(reason: String): String = "The forwarding failed: $reason"
    fun notCleanedUp(destination: String): String =
        "The shovel could not be removed here. It and its stored password remain on $destination; a user with the level Full control must remove it."
}
