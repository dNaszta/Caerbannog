package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.json.ArgumentValueJson
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

// Field access shared by the action codecs. The fixed refusal messages live here, in one place.

internal const val ARGUMENTS_INVALID = "the arguments are not valid or nested too deep"
internal const val QUEUE_TYPE_IN_ARGUMENTS = "the queue type belongs in \"type\""
internal const val APPLY_TO_UNSUPPORTED = "the policy apply-to is not supported"
internal const val DESTINATION_TYPE_UNSUPPORTED = "the binding destination type must be queue or exchange"
internal const val PRIORITY_NOT_WHOLE = "the policy priority must be a whole number"

private val APPLY_TO_VALUES = setOf("all", "queues", "classic_queues", "quorum_queues", "streams", "exchanges")

internal fun refused(reason: String): ActionReadResult = ActionReadResult.Refused(reason)

internal fun read(action: MigrationAction): ActionReadResult = ActionReadResult.Read(action)

internal fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

internal fun JsonObject.vhost(): String = text("vhost") ?: "/"

internal fun JsonObject.flag(key: String, default: Boolean): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: default

/** The key's value, or null when the key is absent or JSON null (same rule as the 3.x definitions reader). */
private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

/** Typed entries of the object under [key]: empty when absent or null, null when it is not an object or a value is refused. */
internal fun JsonObject.typedEntries(key: String): Map<String, ArgumentValue>? {
    val value = present(key) ?: return emptyMap()
    val entries = value as? JsonObject ?: return null
    val converted = LinkedHashMap<String, ArgumentValue>(entries.size)
    for ((name, element) in entries) converted[name] = ArgumentValueJson.parse(element) ?: return null
    return converted
}

private fun JsonObject.plainString(key: String): String? = (present(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The `applyTo` value, "all" when absent; null when it is not one of the known values. */
internal fun JsonObject.applyTo(): String? =
    if (present("applyTo") == null) "all" else plainString("applyTo")?.takeIf { it in APPLY_TO_VALUES }

/** The `destinationType` value, queue when absent; null when it is neither `queue` nor `exchange`. */
internal fun JsonObject.destinationType(): BindingDestinationType? =
    if (present("destinationType") == null) {
        BindingDestinationType.QUEUE
    } else {
        when (plainString("destinationType")) {
            "queue" -> BindingDestinationType.QUEUE
            "exchange" -> BindingDestinationType.EXCHANGE
            else -> null
        }
    }

/** The `priority` value, 0 when absent; null when it is not a whole number. */
internal fun JsonObject.priority(): Int? {
    val value = present("priority") ?: return 0
    return (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
}

internal fun JsonObjectBuilder.putTyped(key: String, entries: Map<String, ArgumentValue>) {
    if (entries.isNotEmpty()) put(key, JsonObject(entries.mapValues { (_, value) -> ArgumentValueJson.encode(value) }))
}

internal fun JsonObjectBuilder.putText(key: String, value: String) = put(key, JsonPrimitive(value))

internal fun JsonObjectBuilder.putFlagUnlessDefault(key: String, value: Boolean, default: Boolean) {
    if (value != default) put(key, JsonPrimitive(value))
}
