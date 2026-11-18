package dev.caerbannog.core.migrations

import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.serialization.json.JsonObject

private const val BINDING_INCOMPLETE = "binding requires exchange and queue"

/** The destination name: `queue`, or its synonym `destination`. */
private fun JsonObject.destinationName(): String? = identifier("queue") ?: identifier("destination")

internal val declareBindingCodec = codec<MigrationAction.DeclareBinding>(
    name = "declareBinding",
    keys = setOf("exchange", "queue", "destination", "routingKey", "destinationType", "arguments"),
    read = { entry ->
        val exchange = entry.identifier("exchange")
        val destination = entry.destinationName()
        val destinationType = entry.destinationType()
        val arguments = entry.typedEntries("arguments")
        when {
            exchange.isNullOrBlank() || destination.isNullOrBlank() -> refused(BINDING_INCOMPLETE)
            destinationType == null -> refused(DESTINATION_TYPE_UNSUPPORTED)
            arguments == null -> refused(ARGUMENTS_INVALID)
            else -> accepted(
                MigrationAction.DeclareBinding(
                    exchange = exchange,
                    queue = destination,
                    routingKey = entry.string("routingKey") ?: "",
                    vhost = entry.vhost(),
                    destinationType = destinationType,
                    arguments = arguments,
                ),
            )
        }
    },
    write = { action, out ->
        out.putText("exchange", action.exchange)
        out.putText("queue", action.queue)
        if (action.routingKey.isNotEmpty()) out.putText("routingKey", action.routingKey)
        if (action.destinationType == BindingDestinationType.EXCHANGE) out.putText("destinationType", "exchange")
        out.putTyped("arguments", action.arguments)
    },
)

internal val deleteBindingCodec = codec<MigrationAction.DeleteBinding>(
    name = "deleteBinding",
    keys = setOf("exchange", "queue", "destination", "routingKey"),
    read = { entry ->
        val exchange = entry.identifier("exchange")
        val destination = entry.destinationName()
        if (exchange.isNullOrBlank() || destination.isNullOrBlank()) {
            refused(BINDING_INCOMPLETE)
        } else {
            accepted(MigrationAction.DeleteBinding(exchange, destination, entry.string("routingKey") ?: "", entry.vhost()))
        }
    },
    write = { action, out ->
        out.putText("exchange", action.exchange)
        out.putText("queue", action.queue)
        if (action.routingKey.isNotEmpty()) out.putText("routingKey", action.routingKey)
    },
)
