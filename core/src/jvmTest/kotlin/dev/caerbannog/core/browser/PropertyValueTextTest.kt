package dev.caerbannog.core.browser

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * M9-00b-c: the one rule that turns a stream message property or annotation value into text. Pure, so no broker.
 * The rule (to be confirmed by the architect): text as is (also nested, unquoted); number, boolean and anything else by its
 * `toString()`; a list, an array (`Array<*>` and the primitive arrays) as `[a, b]`; a map as `{key: value, ...}` in the
 * iteration order of the map (the broker's wire order, never sorted); null is `""` at the top and `null` nested;
 * a `ByteArray` as lowercase hex pairs separated by one space (the scanner's existing hex text), never `[B@...`.
 * No length cap: long lists are not cut by this task.
 */
class PropertyValueTextTest {

    @Test
    fun `null at the top is the empty text`() {
        assertEquals("", PropertyValueText.of(null))
    }

    @Test
    fun `text is returned as it is, also empty text and text with brackets`() {
        assertEquals("orders", PropertyValueText.of("orders"))
        assertEquals("", PropertyValueText.of(""))
        assertEquals("[not a list]", PropertyValueText.of("[not a list]"))
    }

    @Test
    fun `numbers and booleans are their text`() {
        assertEquals("42", PropertyValueText.of(42))
        assertEquals("1700000000000", PropertyValueText.of(1_700_000_000_000L))
        assertEquals("1.5", PropertyValueText.of(1.5))
        assertEquals("true", PropertyValueText.of(true))
        assertEquals("false", PropertyValueText.of(false))
    }

    @Test
    fun `an empty list and an empty array are two brackets`() {
        assertEquals("[]", PropertyValueText.of(emptyList<Any?>()))
        assertEquals("[]", PropertyValueText.of(arrayOf<Any?>()))
    }

    @Test
    fun `a list is bracketed and comma separated with one space`() {
        assertEquals("[a, b, 3]", PropertyValueText.of(listOf("a", "b", 3)))
        assertEquals("[only]", PropertyValueText.of(listOf("only")))
    }

    @Test
    fun `an Array of objects, the client's raw list, reads like a list`() {
        val raw: Array<Any?> = arrayOf("a", 2, true)
        assertEquals("[a, 2, true]", PropertyValueText.of(raw))
        assertEquals(PropertyValueText.of(raw.toList()), PropertyValueText.of(raw))
    }

    @Test
    fun `primitive arrays other than ByteArray read like lists`() {
        assertEquals("[1, 2, 3]", PropertyValueText.of(intArrayOf(1, 2, 3)))
        assertEquals("[1, 2]", PropertyValueText.of(longArrayOf(1L, 2L)))
    }

    @Test
    fun `a map is braced with key colon value pairs in its own order`() {
        assertEquals("{queue: orders, reason: rejected, count: 1}", PropertyValueText.of(linkedMapOf("queue" to "orders", "reason" to "rejected", "count" to 1)))
        assertEquals("{b: 1, a: 2}", PropertyValueText.of(linkedMapOf("b" to 1, "a" to 2)), "the order is the map's order, not sorted")
        assertEquals("{}", PropertyValueText.of(emptyMap<String, Any?>()))
    }

    @Test
    fun `a death entry, the list of maps of x-opt-deaths, reads as text with the queue and the reason`() {
        val death = linkedMapOf<String, Any?>(
            "queue" to "orders", "reason" to "rejected", "count" to 1L, "exchange" to "",
            "routing-keys" to arrayOf<Any?>("orders"),
        )
        val text = PropertyValueText.of(arrayOf<Any?>(death))
        assertEquals("[{queue: orders, reason: rejected, count: 1, exchange: , routing-keys: [orders]}]", text)
        assertFalse(text.startsWith("[Ljava"))
    }

    @Test
    fun `two entries are separated by a comma and a space`() {
        val one = linkedMapOf<String, Any?>("queue" to "a", "reason" to "expired", "ttl" to 1000L)
        val two = linkedMapOf<String, Any?>("queue" to "b", "reason" to "rejected")
        assertEquals("[{queue: a, reason: expired, ttl: 1000}, {queue: b, reason: rejected}]", PropertyValueText.of(listOf(one, two)))
    }

    @Test
    fun `a list nested in a map and a map nested in a list are rendered the same way`() {
        assertEquals("{keys: [a, b], inner: {x: 1}}", PropertyValueText.of(linkedMapOf("keys" to listOf("a", "b"), "inner" to mapOf("x" to 1))))
        assertEquals("[[1, 2], [3]]", PropertyValueText.of(listOf(listOf(1, 2), listOf(3))))
        assertEquals("[[]]", PropertyValueText.of(listOf(emptyList<Any?>())))
    }

    @Test
    fun `null inside a list or a map is the word null`() {
        assertEquals("[a, null]", PropertyValueText.of(listOf("a", null)))
        assertEquals("{k: null}", PropertyValueText.of(mapOf("k" to null)))
    }

    @Test
    fun `a map with keys that are not text renders the keys with the same rule`() {
        assertEquals("{1: a, true: b}", PropertyValueText.of(linkedMapOf<Any?, Any?>(1 to "a", true to "b")))
        assertEquals("{[1, 2]: a}", PropertyValueText.of(mapOf<Any?, Any?>(listOf(1, 2) to "a")))
        assertEquals("{null: a}", PropertyValueText.of(mapOf<Any?, Any?>(null to "a")))
    }

    @Test
    fun `a ByteArray is hex pairs, never the array identity`() {
        val text = PropertyValueText.of(byteArrayOf(0x0a, 0xff.toByte(), 0x10))
        assertEquals("0a ff 10", text)
        assertFalse(text.startsWith("[B"))
        assertEquals("", PropertyValueText.of(ByteArray(0)))
    }

    @Test
    fun `a ByteArray inside a list or a map is hex too`() {
        assertEquals("[0a, 0b ff]", PropertyValueText.of(listOf(byteArrayOf(0x0a), byteArrayOf(0x0b, 0xff.toByte()))))
        assertEquals("{id: de ad}", PropertyValueText.of(mapOf("id" to byteArrayOf(0xde.toByte(), 0xad.toByte()))))
    }

    @Test
    fun `any other object is its toString`() {
        data class Odd(val n: Int)
        assertEquals("Odd(n=3)", PropertyValueText.of(Odd(3)))
    }

    @Test
    fun `a long list is not cut`() {
        val text = PropertyValueText.of((1..2000).toList())
        assertEquals(2000, text.removePrefix("[").removeSuffix("]").split(", ").size)
    }

    @Test
    fun `the same value always gives the same text`() {
        val value = linkedMapOf<String, Any?>("q" to "a", "l" to arrayOf<Any?>(1, 2))
        assertEquals(PropertyValueText.of(value), PropertyValueText.of(value))
    }
}
