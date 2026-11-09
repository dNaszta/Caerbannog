package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.json.ArgumentValue

/** The tick the user must give before this proposal is applied, if any. */
internal val Proposal.acknowledgement: Acknowledgement?
    get() = when (this) {
        is Proposal.ConvertQueueType -> acknowledgement
        is Proposal.DeclareUser -> acknowledgement
        else -> null
    }

/** One line in plain words for the text rendering. */
internal fun Proposal.describe(): String = when (this) {
    is Proposal.ConvertQueueType -> "Convert the queue to type ${targetLabel(target)}"
    Proposal.KeepClassic -> "Keep the queue classic (it is not replicated on 4.x)"
    is Proposal.RemoveArgument -> "Remove the argument $argument"
    is Proposal.SetArgument -> "Set the argument $argument to ${valueLabel(value)}"
    Proposal.ClearAutoDelete -> "Clear auto-delete"
    Proposal.MakeDurable -> "Make the queue durable"
    is Proposal.DropPolicyKeys -> "Drop the keys ${keys.joinToString()}"
    is Proposal.DeclareVirtualHost -> "Declare the virtual host $name"
    is Proposal.DeclareUser -> "Declare the user $name" + if (hasPasswordHash) " with its password hash" else " without a password"
    is Proposal.SetPermissions -> "Set the permissions of $user on $vhost"
    is Proposal.SetTopicPermissions -> "Set the topic permissions of $user on $vhost for the exchange $exchange"
    is Proposal.Manual -> "$title: $text" + if (recommended) "" else " (secondary)"
    Proposal.None -> "Nothing to change"
}

private fun targetLabel(kind: QueueKind): String = when (kind) {
    QueueKind.Classic -> "classic"
    QueueKind.Quorum -> "quorum"
    QueueKind.Stream -> "stream"
    is QueueKind.Other -> kind.name
}

private fun valueLabel(value: ArgumentValue): String = when (value) {
    is ArgumentValue.Text -> value.value
    is ArgumentValue.Whole -> value.value.toString()
    is ArgumentValue.Decimal -> value.value.toString()
    is ArgumentValue.Flag -> value.value.toString()
    is ArgumentValue.Items, is ArgumentValue.Table, ArgumentValue.Nothing -> "(no value)"
}
