package dev.caerbannog.core.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Conversion between untrusted JSON and the typed [ArgumentValue]. Pure; used by the 3.x reader and the migration format. */
object ArgumentValueJson {

    /**
     * Converts [element]; a container passed here is nesting level 1, so [maxDepth] containers inside each other pass and one
     * more fails. Returns null when the nesting is too deep or when a primitive is not a string, a boolean or a finite number
     * (the tree reader also accepts bare words such as `abc` or `NaN` as non-string primitives).
     */
    fun parse(element: JsonElement, maxDepth: Int = MAX_ARGUMENT_DEPTH): ArgumentValue? = convert(element, 1, maxDepth)

    /** Writes [value] back as JSON; a number stays a number and is never turned into a string. */
    fun encode(value: ArgumentValue): JsonElement = when (value) {
        is ArgumentValue.Text -> JsonPrimitive(value.value)
        is ArgumentValue.Whole -> JsonPrimitive(value.value)
        is ArgumentValue.Decimal -> JsonPrimitive(value.value)
        is ArgumentValue.Flag -> JsonPrimitive(value.value)
        is ArgumentValue.Items -> JsonArray(value.items.map(::encode))
        is ArgumentValue.Table -> JsonObject(value.entries.mapValues { (_, item) -> encode(item) })
        ArgumentValue.Nothing -> JsonNull
    }

    private fun convert(element: JsonElement, level: Int, maxDepth: Int): ArgumentValue? = when (element) {
        is JsonNull -> ArgumentValue.Nothing
        is JsonPrimitive -> scalar(element)
        is JsonArray -> if (level > maxDepth) null else convertAll(element, level, maxDepth)?.let(ArgumentValue::Items)
        is JsonObject -> if (level > maxDepth) null else convertEntries(element, level, maxDepth)?.let(ArgumentValue::Table)
    }

    private fun convertAll(items: List<JsonElement>, level: Int, maxDepth: Int): List<ArgumentValue>? {
        val converted = items.map { convert(it, level + 1, maxDepth) ?: return null }
        return converted
    }

    private fun convertEntries(entries: Map<String, JsonElement>, level: Int, maxDepth: Int): Map<String, ArgumentValue>? {
        val converted = LinkedHashMap<String, ArgumentValue>(entries.size)
        for ((key, item) in entries) converted[key] = convert(item, level + 1, maxDepth) ?: return null
        return converted
    }

    private fun scalar(primitive: JsonPrimitive): ArgumentValue? = when {
        primitive.isString -> ArgumentValue.Text(primitive.content)
        primitive.content == "true" -> ArgumentValue.Flag(true)
        primitive.content == "false" -> ArgumentValue.Flag(false)
        else -> primitive.content.toLongOrNull()?.let { ArgumentValue.Whole(it) }
            ?: primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { ArgumentValue.Decimal(it) }
    }
}
