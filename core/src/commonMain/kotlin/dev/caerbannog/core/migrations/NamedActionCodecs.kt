package dev.caerbannog.core.migrations

import kotlinx.serialization.json.JsonObject

/** A codec for an action that has only a required `name` (and the vhost), such as `deleteQueue` or `declareStream`. */
internal inline fun <reified A : MigrationAction> namedCodec(
    name: String,
    missingNameMessage: String,
    crossinline create: (name: String, vhost: String) -> A,
    crossinline nameOf: (A) -> String,
): ActionCodec = codec<A>(
    name = name,
    read = { entry ->
        val actionName = entry.identifier("name")
        if (actionName.isNullOrBlank()) refused(missingNameMessage) else accepted(create(actionName, entry.vhost()))
    },
    write = { action, out -> out.putText("name", nameOf(action)) },
)

internal val deleteQueueCodec = namedCodec("deleteQueue", "queue name is required", MigrationAction::DeleteQueue, MigrationAction.DeleteQueue::name)
internal val deleteExchangeCodec = namedCodec("deleteExchange", "exchange name is required", MigrationAction::DeleteExchange, MigrationAction.DeleteExchange::name)
internal val declareStreamCodec = namedCodec("declareStream", "stream name is required", MigrationAction::DeclareStream, MigrationAction.DeclareStream::name)
internal val deleteStreamCodec = namedCodec("deleteStream", "stream name is required", MigrationAction::DeleteStream, MigrationAction.DeleteStream::name)
internal val deletePolicyCodec = namedCodec("deletePolicy", "policy name is required", MigrationAction::DeletePolicy, MigrationAction.DeletePolicy::name)
