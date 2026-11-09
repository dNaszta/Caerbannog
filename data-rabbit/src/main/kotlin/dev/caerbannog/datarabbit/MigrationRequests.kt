package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.json.ArgumentValueJson
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.datarabbit.ManagementRequests.Companion.encode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One write to the management API: [method] is `PUT` or `POST`, [path] starts with `/api` (the base URL is the caller's). */
internal data class ManagementWrite(val method: String, val path: String, val body: JsonObject?)

/**
 * The management API request that each declare action of a migration becomes. Pure: it builds, it sends nothing (the executor is M5-08).
 * Delete actions and streams give null here. Numbers stay JSON numbers through [ArgumentValueJson.encode].
 */
internal object MigrationRequests {

    fun requestFor(action: MigrationAction): ManagementWrite? = when (action) {
        is MigrationAction.DeclareQueue -> put("queues", action.vhost, action.name, queueBody(action))
        is MigrationAction.DeclareExchange -> put("exchanges", action.vhost, action.name, exchangeBody(action))
        is MigrationAction.DeclareBinding -> ManagementWrite("POST", bindingPath(action), bindingBody(action))
        is MigrationAction.DeclarePolicy ->
            put("policies", action.vhost, action.name, policyBody(action.pattern, action.definition, action.priority, action.applyTo))
        is MigrationAction.DeclareOperatorPolicy ->
            put("operator-policies", action.vhost, action.name, policyBody(action.pattern, action.definition, action.priority, action.applyTo))
        is MigrationAction.DeleteQueue,
        is MigrationAction.DeleteExchange,
        is MigrationAction.DeleteBinding,
        is MigrationAction.DeclareStream,
        is MigrationAction.DeleteStream,
        is MigrationAction.DeletePolicy,
        -> null
    }

    private fun put(collection: String, vhost: String, name: String, body: JsonObject) =
        ManagementWrite("PUT", "/api/$collection/${encode(vhost)}/${encode(name)}", body)

    private fun bindingPath(action: MigrationAction.DeclareBinding): String {
        val kind = if (action.destinationType == BindingDestinationType.EXCHANGE) "e" else "q"
        return "/api/bindings/${encode(action.vhost)}/e/${encode(action.exchange)}/$kind/${encode(action.queue)}"
    }

    private fun queueBody(action: MigrationAction.DeclareQueue): JsonObject = buildJsonObject {
        put("durable", action.durable)
        put("auto_delete", action.autoDelete)
        put("arguments", encodeAll(mapOf("x-queue-type" to ArgumentValue.Text(action.type)) + action.arguments))
    }

    private fun exchangeBody(action: MigrationAction.DeclareExchange): JsonObject = buildJsonObject {
        put("type", action.type)
        put("durable", action.durable)
        put("auto_delete", action.autoDelete)
        put("internal", action.internal)
        put("arguments", encodeAll(action.arguments))
    }

    private fun bindingBody(action: MigrationAction.DeclareBinding): JsonObject = buildJsonObject {
        put("routing_key", action.routingKey)
        put("arguments", encodeAll(action.arguments))
    }

    private fun policyBody(pattern: String, definition: Map<String, ArgumentValue>, priority: Int, applyTo: String): JsonObject = buildJsonObject {
        put("pattern", pattern)
        put("definition", encodeAll(definition))
        put("priority", JsonPrimitive(priority))
        put("apply-to", applyTo)
    }

    private fun encodeAll(entries: Map<String, ArgumentValue>): JsonObject =
        JsonObject(entries.mapValues { (_, value) -> ArgumentValueJson.encode(value) })
}
