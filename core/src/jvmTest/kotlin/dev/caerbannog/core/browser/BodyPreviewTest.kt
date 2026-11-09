package dev.caerbannog.core.browser

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BodyPreviewTest {

    @Test
    fun `cell flattens newlines in text`() = assertEquals("a b", BodyPreview.cell("a\nb".encodeToByteArray()))

    @Test
    fun `cell shows control bytes as hex`() = assertEquals("00 ff 41", BodyPreview.cell(byteArrayOf(0, -1, 0x41)))

    @Test
    fun `cell and tooltip agree on invalid utf8 being binary`() {
        val bad = byteArrayOf(0x41, 0xC3.toByte())
        assertEquals("41 c3", BodyPreview.cell(bad))
        assertEquals("41 c3", BodyPreview.tooltip(bad))
    }

    @Test
    fun `short text is returned whole without a note`() = assertEquals("hello", BodyPreview.tooltip("hello".encodeToByteArray()))

    @Test
    fun `empty payload gives empty text`() = assertEquals("", BodyPreview.tooltip(ByteArray(0)))

    @Test
    fun `long text is cut at the limit with a truncation note`() {
        val out = BodyPreview.tooltip("x".repeat(1500).encodeToByteArray())
        assertTrue(out.startsWith("x".repeat(BodyPreview.TOOLTIP_TEXT_BYTES) + "\n"))
        assertTrue(out.endsWith("(truncated, full length: 1500 bytes)"))
    }

    @Test
    fun `a multi-byte character split by the limit stays text and is dropped whole`() {
        val payload = ("a".repeat(BodyPreview.TOOLTIP_TEXT_BYTES - 1) + "é" + "tail").encodeToByteArray()
        val out = BodyPreview.tooltip(payload)
        assertFalse(out.contains('�'))
        assertTrue(out.startsWith("a".repeat(BodyPreview.TOOLTIP_TEXT_BYTES - 1) + "\n"))
    }

    @Test
    fun `large binary is capped with a note`() {
        val out = BodyPreview.tooltip(ByteArray(5_000))
        assertEquals(BodyPreview.TOOLTIP_HEX_BYTES, out.substringBefore(" …").split(" ").size)
        assertTrue(out.endsWith("(truncated, full length: 5000 bytes)"))
    }
}
