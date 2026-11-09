package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads the broker's list of the bindings between one source and one destination (`GET /api/bindings/{v}/e/{s}/q|e/{d}`). Never throws. */
internal object BindingList {

    private const val MAX_BODY_DEPTH = 64

    /** The binding objects of [body], or null when it is not a list of objects. */
    fun entries(body: String): List<JsonObject>? {
        val root = (parseUntrustedJson(body, MAX_BODY_DEPTH) as? UntrustedJson.Parsed)?.element as? JsonArray ?: return null
        return root.map { it as? JsonObject ?: return null }
    }

    /** The entries a `DeleteBinding` names: its routing key and no arguments (a delete action has none). */
    fun plain(entries: List<JsonObject>, routingKey: String): List<JsonObject> =
        entries.filter { text(it, "routing_key") == routingKey && isEmpty(it["arguments"]) }

    /** The key under which the broker addresses the binding in a DELETE path, or null when it reports none as text. */
    fun propertiesKey(entry: JsonObject): String? = text(entry, "properties_key")

    private fun isEmpty(arguments: kotlinx.serialization.json.JsonElement?): Boolean =
        arguments == null || (arguments is JsonObject && arguments.isEmpty())

    private fun text(entry: JsonObject, key: String): String? = (entry[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
