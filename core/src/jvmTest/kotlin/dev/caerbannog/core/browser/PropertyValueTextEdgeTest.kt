package dev.caerbannog.core.browser

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-00b-c step 4: edges the step 2 tests did not pin. Measured at step 4 (not pinned here because a cap would change the decided
 * "no length cap" rule): a list nested 5,000 deep, a map or list that contains itself throw StackOverflowError in
 * `PropertyValueText.of`. The wire cannot deliver such a value: the stream client's decoder refuses nesting beyond about 30 levels
 * (see `StreamMessageMappingTest`, `a value nested beyond the decoder limit never reaches the renderer`).
 */
class PropertyValueTextEdgeTest {

    @Test
    fun `a set is rendered like a list, in its iteration order`() {
        assertEquals("[b, a]", PropertyValueText.of(linkedSetOf("b", "a")))
    }

    @Test
    fun `text with unicode, right to left script and markup is passed through unchanged, also nested`() {
        val text = "café שלום <b>bold</b> & [x] {y} \u0000 \n second line"
        assertEquals(text, PropertyValueText.of(text))
        assertEquals("[$text]", PropertyValueText.of(listOf(text)))
        assertEquals("{<k>: $text}", PropertyValueText.of(mapOf("<k>" to text)))
    }

    @Test
    fun `Char, Short, Byte, Float and unsigned like numbers use their text`() {
        assertEquals("c", PropertyValueText.of('c'))
        assertEquals("7", PropertyValueText.of(7.toShort()))
        assertEquals("-1", PropertyValueText.of((-1).toByte()))
        assertEquals("1.5", PropertyValueText.of(1.5f))
        assertEquals("18446744073709551615", PropertyValueText.of(ULong.MAX_VALUE))
    }

    @Test
    fun `the other primitive arrays read like lists`() {
        assertEquals("[1, 2]", PropertyValueText.of(shortArrayOf(1, 2)))
        assertEquals("[1.5]", PropertyValueText.of(doubleArrayOf(1.5)))
        assertEquals("[1.5]", PropertyValueText.of(floatArrayOf(1.5f)))
        assertEquals("[true, false]", PropertyValueText.of(booleanArrayOf(true, false)))
        assertEquals("[a, b]", PropertyValueText.of(charArrayOf('a', 'b')))
    }

    @Test
    fun `a ByteArray as a map key is hex too`() {
        assertEquals("{0a: v}", PropertyValueText.of(mapOf<Any?, Any?>(byteArrayOf(0x0a) to "v")))
    }

    @Test
    fun `hostile size, a list of a million elements and a string of 50 million characters, comes back in bounded time and unchanged in content`() {
        val millionList = PropertyValueText.of(List(1_000_000) { it })
        assertTrue(millionList.startsWith("[0, 1, 2") && millionList.endsWith("999999]"))
        val big = "x".repeat(50_000_000)
        assertEquals(50_000_000, PropertyValueText.of(big).length)
    }

    @Test
    fun `nesting of a thousand levels is rendered`() {
        var value: Any? = "x"
        repeat(1000) { value = listOf(value) }
        assertEquals(2001, PropertyValueText.of(value).length)
    }

    // --- the null value of an existing key (step 3 note: it now matches the empty filter text) ---

    private val scanner get() = StreamScanner(reader)
    private val reader = FakeStreamMessageReader()

    private suspend fun matches(criteria: MessageFilterCriteria): List<Long> =
        scanner.scan("/", "s", 0L, criteria, scanLimit = 100).toList().last().matches.map { it.offset }

    @Test
    fun `filter by an empty value matches a key that is present with an empty or null value and never a missing key`() = runTest {
        reader.seed("/", "s", listOf(
            StreamMessage(0L, 1L, "a".encodeToByteArray(), properties = mapOf("k" to "")),
            StreamMessage(1L, 2L, "b".encodeToByteArray(), properties = mapOf("k" to null)),
            StreamMessage(2L, 3L, "c".encodeToByteArray(), properties = mapOf("other" to "v")),
            StreamMessage(3L, 4L, "d".encodeToByteArray(), properties = mapOf("k" to "v")),
            StreamMessage(4L, 5L, "e".encodeToByteArray(), properties = mapOf("k" to emptyList<Any?>())),
        ))
        // what the screen shows for offsets 0 and 1 is an empty value; offset 4 shows [] and does not match ''
        assertEquals(listOf(0L, 1L), matches(MessageFilterCriteria.Property("k", "")))
        assertEquals(listOf(4L), matches(MessageFilterCriteria.Property("k", "[]")))
    }

    @Test
    fun `the application property filter treats null and missing the same way`() = runTest {
        reader.seed("/", "s", listOf(
            StreamMessage(0L, 1L, "a".encodeToByteArray(), applicationProperties = mapOf("k" to null)),
            StreamMessage(1L, 2L, "b".encodeToByteArray()),
        ))
        assertEquals(listOf(0L), matches(MessageFilterCriteria.ApplicationProperty("k", "")))
    }
}
