package dev.caerbannog.core.planner

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** M9-01b: the UTF-8 byte length of well-formed text, counted over code points (the 255-byte rule of a queue name, S-H). A lone surrogate is out of scope. */
class Utf8LengthTest {
    private fun cp(value: Int) = StringBuilder().appendCodePoint(value).toString()

    @Test
    fun `the empty text has no byte`() = assertEquals(0, Utf8Length.of(""))

    @Test
    fun `a code point takes one to four bytes at the borders`() {
        val table = mapOf(
            0x00 to 1, 0x41 to 1, 0x7F to 1,
            0x80 to 2, 0xE9 to 2, 0x7FF to 2,
            0x800 to 3, 0x20AC to 3, 0xFFFF to 3,
            0x10000 to 4, 0x1F600 to 4, 0x10FFFF to 4,
        )
        for ((codePoint, bytes) in table) assertEquals(bytes, Utf8Length.of(cp(codePoint)), "U+${codePoint.toString(16).uppercase()}")
    }

    @Test
    fun `a surrogate pair is one four-byte character, not two`() {
        val smile = "\uD83D\uDE00"
        assertEquals(2, smile.length)
        assertEquals(4, Utf8Length.of(smile))
    }

    @Test
    fun `the length of a text is the sum of its characters`() {
        assertEquals(5, Utf8Length.of("hello"))
        assertEquals(1 + 2 + 3 + 4, Utf8Length.of("a" + "é" + "€" + "\uD83D\uDE00"))
        assertEquals(256, Utf8Length.of("é".repeat(128)))
        assertEquals(255, Utf8Length.of("é".repeat(127) + "a"))
        assertEquals(255, Utf8Length.of("€".repeat(85)))
        assertEquals(10_000, Utf8Length.of("a".repeat(10_000)))
    }

    @Test
    fun `the byte length is never smaller than the character count and never more than four times it`() {
        for (text in listOf("abc", "äöü", "日本語", "a\uD83D\uDE00b")) {
            val bytes = Utf8Length.of(text)
            val chars = text.codePointCount(0, text.length)
            assertEquals(true, bytes >= chars && bytes <= 4 * chars, "\"$text\" is $bytes bytes in $chars characters")
        }
    }
}
