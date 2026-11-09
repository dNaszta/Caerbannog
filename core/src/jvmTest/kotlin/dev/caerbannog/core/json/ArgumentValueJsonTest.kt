package dev.caerbannog.core.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-04: the typed value conversion that moved to the neutral package (rules of M8-01: F1 depth, F2/F3 finite numbers only). */
class ArgumentValueJsonTest {

    private fun el(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun nested(levels: Int) = "[".repeat(levels) + "1" + "]".repeat(levels)

    @Test
    fun `scalars parse to their types`() {
        assertEquals(ArgumentValue.Text("a"), ArgumentValueJson.parse(el("\"a\"")))
        assertEquals(ArgumentValue.Whole(5), ArgumentValueJson.parse(el("5")))
        assertEquals(ArgumentValue.Decimal(5.5), ArgumentValueJson.parse(el("5.5")))
        assertEquals(ArgumentValue.Decimal(1000.0), ArgumentValueJson.parse(el("1e3")))
        assertEquals(ArgumentValue.Decimal(9223372036854775808.0), ArgumentValueJson.parse(el("9223372036854775808")))
        assertEquals(ArgumentValue.Flag(true), ArgumentValueJson.parse(el("true")))
        assertEquals(ArgumentValue.Nothing, ArgumentValueJson.parse(JsonNull))
    }

    @Test
    fun `lists and objects nest`() {
        assertEquals(
            ArgumentValue.Table(mapOf("a" to ArgumentValue.Items(listOf(ArgumentValue.Whole(1), ArgumentValue.Nothing)))),
            ArgumentValueJson.parse(el("""{"a":[1,null]}""")),
        )
    }

    @Test
    fun `encode gives back the same JSON type and a number never becomes a string`() {
        val whole = ArgumentValueJson.encode(ArgumentValue.Whole(5)) as JsonPrimitive
        assertFalse(whole.isString, "Whole must be a JSON number")
        assertEquals(5L, whole.longOrNull)
        assertEquals("5", whole.content)
        val decimal = ArgumentValueJson.encode(ArgumentValue.Decimal(0.5)) as JsonPrimitive
        assertFalse(decimal.isString)
        assertEquals(0.5, decimal.doubleOrNull)
        val big = ArgumentValueJson.encode(ArgumentValue.Whole(Long.MAX_VALUE)) as JsonPrimitive
        assertFalse(big.isString)
        assertEquals(Long.MAX_VALUE, big.longOrNull)
        assertTrue((ArgumentValueJson.encode(ArgumentValue.Flag(false)) as JsonPrimitive).let { !it.isString && it.content == "false" })
        assertEquals(JsonNull, ArgumentValueJson.encode(ArgumentValue.Nothing))
        val text = ArgumentValueJson.encode(ArgumentValue.Text("5")) as JsonPrimitive
        assertTrue(text.isString && text.content == "5")
    }

    @Test
    fun `encode then parse is the identity for every value type`() {
        val all = ArgumentValue.Table(
            mapOf(
                "t" to ArgumentValue.Text("x"), "w" to ArgumentValue.Whole(-7), "d" to ArgumentValue.Decimal(2.25), "f" to ArgumentValue.Flag(true),
                "n" to ArgumentValue.Nothing, "l" to ArgumentValue.Items(listOf(ArgumentValue.Whole(1), ArgumentValue.Table(emptyMap()))),
            ),
        )
        assertEquals(all, ArgumentValueJson.parse(ArgumentValueJson.encode(all)))
        assertTrue(ArgumentValueJson.encode(all) is JsonObject)
        assertTrue(ArgumentValueJson.encode(ArgumentValue.Items(emptyList())) is JsonArray)
    }

    @Test
    fun `depth 32 passes and depth 33 fails`() {
        assertNotNull(ArgumentValueJson.parse(el(nested(32))))
        assertNull(ArgumentValueJson.parse(el(nested(33))))
        val objects33 = "{\"a\":".repeat(33) + "1" + "}".repeat(33)
        assertNull(ArgumentValueJson.parse(el(objects33)))
        assertEquals(32, MAX_ARGUMENT_DEPTH)
    }

    @Test
    fun `the depth limit is a parameter`() {
        assertNotNull(ArgumentValueJson.parse(el(nested(3)), maxDepth = 3))
        assertNull(ArgumentValueJson.parse(el(nested(4)), maxDepth = 3))
    }

    @Test
    fun `non-finite numbers, bare words and huge numbers are refused`() {
        for (bad in listOf("NaN", "Infinity", "-Infinity", "abc", "1" + "0".repeat(399))) {
            assertNull(ArgumentValueJson.parse(el(bad)), "for $bad")
            assertNull(ArgumentValueJson.parse(el("[$bad]")), "inside a list: $bad")
            assertNull(ArgumentValueJson.parse(el("""{"k":$bad}""")), "inside an object: $bad")
        }
    }
}
