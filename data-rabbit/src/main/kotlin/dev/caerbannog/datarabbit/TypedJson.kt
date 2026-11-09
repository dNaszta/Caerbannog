package dev.caerbannog.datarabbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * Equality of two JSON values for the executor's comparisons (policy conflict check, read-back): typed, so the number 5 is not the text "5";
 * numbers compare by value (5 equals 5.0 and 5E0, a whole number beyond 2^53 is exact); object key order does not matter, array order does.
 */
internal object TypedJson {

    /** Longer numbers are not read as numbers (BigDecimal with a huge exponent is a way to burn memory); they compare as text. */
    private const val MAX_NUMBER_LENGTH = 64

    fun equal(a: JsonElement, b: JsonElement): Boolean = when (a) {
        is JsonNull -> b is JsonNull
        is JsonObject -> b is JsonObject && a.keys == b.keys && a.all { (key, value) -> equal(value, b.getValue(key)) }
        is JsonArray -> b is JsonArray && a.size == b.size && a.indices.all { equal(a[it], b[it]) }
        is JsonPrimitive -> b is JsonPrimitive && b !is JsonNull && primitivesEqual(a, b)
    }

    private fun primitivesEqual(a: JsonPrimitive, b: JsonPrimitive): Boolean {
        if (a.isString || b.isString) return a.isString && b.isString && a.content == b.content
        val x = number(a)
        val y = number(b)
        return if (x != null && y != null) x.compareTo(y) == 0 else a.content == b.content
    }

    private fun number(primitive: JsonPrimitive): BigDecimal? =
        if (primitive.content.length > MAX_NUMBER_LENGTH) null else primitive.content.toBigDecimalOrNull()
}
