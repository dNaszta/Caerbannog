package dev.caerbannog.core.migrate3x

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The JSON shape of each [Proposal]: `type` is the lower camel case name of the class, then its fields. */
internal object ProposalJson {
    fun encode(proposal: Proposal): JsonObject = buildJsonObject {
        put("type", typeOf(proposal))
        when (proposal) {
            is Proposal.ConvertQueueType -> {
                put("target", targetName(proposal.target))
                put("acknowledgement", proposal.acknowledgement?.text)
            }
            is Proposal.RemoveArgument -> put("argument", proposal.argument)
            is Proposal.SetArgument -> {
                put("argument", proposal.argument)
                put("value", scalar(proposal.value))
            }
            is Proposal.DropPolicyKeys -> put("keys", strings(proposal.keys))
            is Proposal.DeclareVirtualHost -> put("name", proposal.name)
            is Proposal.DeclareUser -> {
                put("name", proposal.name)
                put("tags", strings(proposal.tags))
                put("hashingAlgorithm", proposal.hashingAlgorithm)
                put("hasPasswordHash", proposal.hasPasswordHash)
                put("acknowledgement", proposal.acknowledgement?.text)
            }
            is Proposal.SetPermissions -> {
                put("vhost", proposal.vhost)
                put("user", proposal.user)
            }
            is Proposal.SetTopicPermissions -> {
                put("vhost", proposal.vhost)
                put("user", proposal.user)
                put("exchange", proposal.exchange)
            }
            is Proposal.Manual -> {
                put("title", proposal.title)
                put("text", proposal.text)
                put("recommended", proposal.recommended)
            }
            Proposal.KeepClassic, Proposal.ClearAutoDelete, Proposal.MakeDurable, Proposal.None -> Unit
        }
    }

    private fun typeOf(proposal: Proposal): String = when (proposal) {
        is Proposal.ConvertQueueType -> "convertQueueType"
        Proposal.KeepClassic -> "keepClassic"
        is Proposal.RemoveArgument -> "removeArgument"
        is Proposal.SetArgument -> "setArgument"
        Proposal.ClearAutoDelete -> "clearAutoDelete"
        Proposal.MakeDurable -> "makeDurable"
        is Proposal.DropPolicyKeys -> "dropPolicyKeys"
        is Proposal.DeclareVirtualHost -> "declareVirtualHost"
        is Proposal.DeclareUser -> "declareUser"
        is Proposal.SetPermissions -> "setPermissions"
        is Proposal.SetTopicPermissions -> "setTopicPermissions"
        is Proposal.Manual -> "manual"
        Proposal.None -> "none"
    }

    private fun targetName(kind: QueueKind): String = when (kind) {
        QueueKind.Classic -> "classic"
        QueueKind.Quorum -> "quorum"
        QueueKind.Stream -> "stream"
        is QueueKind.Other -> kind.name
    }

    /** A queue argument value as JSON: text, number or boolean; null for anything else (a table or a list is not proposed). */
    private fun scalar(value: ArgumentValue): JsonElement = when (value) {
        is ArgumentValue.Text -> JsonPrimitive(value.value)
        is ArgumentValue.Whole -> JsonPrimitive(value.value)
        is ArgumentValue.Decimal -> JsonPrimitive(value.value)
        is ArgumentValue.Flag -> JsonPrimitive(value.value)
        is ArgumentValue.Items, is ArgumentValue.Table, ArgumentValue.Nothing -> JsonNull
    }
}
