package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.json.ArgumentValueJson
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
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
        is MigrationAction.DeclareVhost, is MigrationAction.DeclareUser, is MigrationAction.SetPermissions, is MigrationAction.SetTopicPermissions ->
            AccessRequests.requestFor(action)
    }

    /** A path segment; one made only of dots is written as `%2E` per dot so no `.` or `..` segment can reach the URL. */
    fun encode(value: String): String =
        if (value.isNotEmpty() && value.all { it == '.' }) "%2E".repeat(value.length) else ManagementRequests.encode(value)

    private fun put(collection: String, vhost: String, name: String, body: JsonObject) =
        ManagementWrite("PUT", collectionPath(collection, vhost, name), body)

    /** `/api/{collection}/{vhost}/{name}` for queues, exchanges, policies and operator policies: the only shape with a name in it. */
    fun collectionPath(collection: String, vhost: String, name: String): String =
        "/api/$collection/${encode(vhost)}/${encode(name)}"

    /** `/api/bindings/{vhost}/e/{source}/{q|e}/{destination}`: the bindings between one source and one destination. */
    fun bindingPath(vhost: String, source: String, destinationType: BindingDestinationType, destination: String): String {
        val kind = if (destinationType == BindingDestinationType.EXCHANGE) "e" else "q"
        return "/api/bindings/${encode(vhost)}/e/${encode(source)}/$kind/${encode(destination)}"
    }

    private fun bindingPath(action: MigrationAction.DeclareBinding): String =
        bindingPath(action.vhost, action.exchange, action.destinationType, action.queue)

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
