package dev.caerbannog.core.migrate3x

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Internal control flow of the parser: a section of the file is not shaped as expected. Never leaves the parser. */
internal class MalformedSectionException(val section: String) : RuntimeException(null, null, false, false)

/**
 * Typed access to one JSON object of a definitions file. Required keys, defaults and type checks live here so
 * the section readers do not repeat null checks. Every violation names only the [section], never the content.
 */
internal class EntryReader(val section: String, private val json: JsonObject) {

    fun has(key: String): Boolean = key in json

    fun text(key: String): String = textOrNull(key) ?: malformed()

    fun text(key: String, default: String): String = textOrNull(key) ?: default

    fun textOrNull(key: String): String? = when (val value = present(key)) {
        null -> null
        is JsonPrimitive -> if (value.isString) value.content else malformed()
        else -> malformed()
    }

    fun flag(key: String, default: Boolean): Boolean = when (val value = present(key)) {
        null -> default
        is JsonPrimitive -> if (value.isString) malformed() else value.booleanOrNull ?: malformed()
        else -> malformed()
    }

    fun whole(key: String, default: Int): Int = when (val value = present(key)) {
        null -> default
        is JsonPrimitive -> if (value.isString) malformed() else value.intOrNull ?: malformed()
        else -> malformed()
    }

    /** A nested object, or null when the key is absent or JSON null. */
    fun objectOrNull(key: String): EntryReader? = when (val value = present(key)) {
        null -> null
        is JsonObject -> EntryReader(section, value)
        else -> malformed()
    }

    /** A list of strings, or a comma-separated string (older exports, unverified); null when absent. */
    fun tagsOrNull(key: String): List<String>? = when (val value = present(key)) {
        null -> null
        is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: malformed() }
        is JsonPrimitive -> if (value.isString) value.content.split(",").map(String::trim).filter(String::isNotEmpty) else malformed()
        else -> malformed()
    }

    /** An object of typed values, empty when absent. */
    fun arguments(key: String): Map<String, ArgumentValue> = when (val value = present(key)) {
        null -> emptyMap()
        is JsonObject -> value.mapValues { (_, element) -> element.toArgument(section, level = 1) }
        else -> malformed()
    }

    fun malformed(): Nothing = throw MalformedSectionException(section)

    private fun present(key: String): JsonElement? = json[key]?.takeUnless { it is JsonNull }
}

/** Converts a JSON value; [level] is the nesting level of a list or table (a container directly in an argument map is level 1). */
private fun JsonElement.toArgument(section: String, level: Int): ArgumentValue = when (this) {
    is JsonNull -> ArgumentValue.Nothing
    is JsonPrimitive -> toScalarArgument(section)
    is JsonArray -> {
        if (level > MAX_ARGUMENT_DEPTH) throw MalformedSectionException(section)
        ArgumentValue.Items(map { it.toArgument(section, level + 1) })
    }
    is JsonObject -> {
        if (level > MAX_ARGUMENT_DEPTH) throw MalformedSectionException(section)
        ArgumentValue.Table(mapValues { (_, element) -> element.toArgument(section, level + 1) })
    }
}

/**
 * The tree reader also accepts bare words (`abc`, `NaN`) as non-string primitives, so a number must be checked:
 * anything that is not a finite number makes the section malformed. No non-finite [ArgumentValue.Decimal] can exist.
 */
private fun JsonPrimitive.toScalarArgument(section: String): ArgumentValue = when {
    isString -> ArgumentValue.Text(content)
    content == "true" -> ArgumentValue.Flag(true)
    content == "false" -> ArgumentValue.Flag(false)
    else -> content.toLongOrNull()?.let { ArgumentValue.Whole(it) }
        ?: ArgumentValue.Decimal(content.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw MalformedSectionException(section))
}
