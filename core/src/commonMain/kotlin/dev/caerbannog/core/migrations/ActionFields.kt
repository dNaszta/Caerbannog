package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.json.ArgumentValueJson
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// Field access shared by the action codecs. The fixed refusal messages live here, in one place.

internal const val ARGUMENTS_INVALID = "the arguments are not valid or nested too deep"
internal const val QUEUE_TYPE_IN_ARGUMENTS = "the queue type belongs in \"type\""
internal const val APPLY_TO_UNSUPPORTED = "the policy apply-to is not supported"
internal const val DESTINATION_TYPE_UNSUPPORTED = "the binding destination type must be queue or exchange"
internal const val PRIORITY_NOT_WHOLE = "the policy priority must be a whole number"
internal const val DOT_NAME = "a name must not be \".\" or \"..\""

private val APPLY_TO_VALUES = setOf("all", "queues", "classic_queues", "quorum_queues", "streams", "exchanges")

internal fun refused(reason: String): ActionReadResult = ActionReadResult.Refused(reason)

internal fun accepted(action: MigrationAction): ActionReadResult = ActionReadResult.Read(action)

/**
 * Control flow of the reader: a field of an untrusted file cannot be accepted. Never leaves [MigrationParser.parse], which turns it into
 * a refusal. The [reason] is a fixed text from this code; nothing of the input is ever put in it.
 */
internal open class FieldRefusal(val reason: String) : RuntimeException(null, null, false, false)

/** The value under [key] has the wrong JSON type. [key] is a fixed identifier from the code, never input. */
internal class WrongTypeException(val key: String) : FieldRefusal("the value of \"$key\" has the wrong type")

private fun dotName(): Nothing = throw FieldRefusal(DOT_NAME)

/** The key's value, or null when the key is absent or JSON null (same rule as the 3.x definitions reader). */
private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

/** The text under [key]: null when absent or JSON null; any other type than a JSON string is a [WrongTypeException]. */
internal fun JsonObject.string(key: String): String? = when (val value = present(key)) {
    null -> null
    is JsonPrimitive -> if (value.isString) value.content else throw WrongTypeException(key)
    else -> throw WrongTypeException(key)
}

/** Like [string] for a name (entity, vhost): `.` and `..` are refused, they are path segments for a URL. */
internal fun JsonObject.identifier(key: String): String? = string(key)?.also { if (it == "." || it == "..") dotName() }

internal fun JsonObject.vhost(): String = identifier("vhost") ?: "/"

/**
 * The root `version`: a string of letters, digits, `_` and `-`, or a whole number (older files; its text); null when absent or JSON null.
 * The version becomes a file name, so nothing else may pass; the refusal never echoes the value.
 */
internal fun JsonObject.versionText(): String? = when (val value = present("version")) {
    null -> null
    is JsonPrimitive -> value.versionOrNull() ?: throw WrongTypeException("version")
    else -> throw WrongTypeException("version")
}

private val VERSION_TEXT = Regex("[A-Za-z0-9_-]+")

private fun JsonPrimitive.versionOrNull(): String? = when {
    // A blank text stays and is reported as "no version" by the parser.
    isString -> content.takeIf { it.isBlank() || VERSION_TEXT.matches(it) }
    else -> content.toLongOrNull()?.toString()
}

/** A JSON boolean or the text `true` / `false`; [default] when absent or JSON null. */
internal fun JsonObject.flag(key: String, default: Boolean): Boolean = when (val value = present(key)) {
    null -> default
    is JsonPrimitive -> value.content.takeIf { it == "true" || it == "false" }?.toBooleanStrict() ?: throw WrongTypeException(key)
    else -> throw WrongTypeException(key)
}

/** Typed entries of the object under [key]: empty when absent or null, null when it is not an object or a value is refused. */
internal fun JsonObject.typedEntries(key: String): Map<String, ArgumentValue>? {
    val value = present(key) ?: return emptyMap()
    val entries = value as? JsonObject ?: return null
    val converted = LinkedHashMap<String, ArgumentValue>(entries.size)
    for ((name, element) in entries) converted[name] = ArgumentValueJson.parse(element) ?: return null
    return converted
}

/** A JSON string (an object, array, number or boolean is a [WrongTypeException]), or null when absent. */
private fun JsonObject.plainString(key: String): String? = string(key)

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
internal fun JsonObject.priority(): Int? = when (val value = present("priority")) {
    null -> 0
    is JsonPrimitive -> value.takeUnless { it.isString }?.intOrNull
    else -> throw WrongTypeException("priority")
}

internal fun JsonObjectBuilder.putTyped(key: String, entries: Map<String, ArgumentValue>) {
    if (entries.isNotEmpty()) put(key, JsonObject(entries.mapValues { (_, value) -> ArgumentValueJson.encode(value) }))
}

internal fun JsonObjectBuilder.putText(key: String, value: String) = put(key, JsonPrimitive(value))

internal fun JsonObjectBuilder.putFlagUnlessDefault(key: String, value: Boolean, default: Boolean) {
    if (value != default) put(key, JsonPrimitive(value))
}
