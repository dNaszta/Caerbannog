package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** M8-06a round 1: `escapeForText` hardened against control, bidi and invisible format characters and lone surrogates (architect ruling fb2f90d). */
class EscapeForTextTest {
    private fun hex(c: Int) = "\\u" + c.toString(16).uppercase().padStart(4, '0')

    private val escaped: List<Int> = buildList {
        addAll(0x0000..0x001F); addAll(0x007F..0x009F); add(0x00AD); add(0x061C); add(0x180E); addAll(0x200B..0x200F)
        add(0x2028); add(0x2029); addAll(0x202A..0x202E); addAll(0x2060..0x2064); addAll(0x2066..0x2069); add(0xFEFF)
    }

    @Test
    fun `every listed code point is written as backslash u and four uppercase hex digits`() {
        for (c in escaped) assertEquals("a${hex(c)}b", escapeForText("a" + c.toChar() + "b"), "U+${c.toString(16).uppercase()}")
    }

    @Test
    fun `ordinary text, accents, CJK and an emoji pair are unchanged`() {
        for (text in listOf("é", "日本語", " ", ".", "_", "-", "🐇", "orders.v2_new-1")) assertEquals(text, escapeForText(text), text)
    }

    @Test
    fun `backslash and quote behaviour is unchanged`() {
        assertEquals("a\\\\b", escapeForText("a\\b"))
        assertEquals("a\"b", escapeForText("a\"b"))
        assertEquals("a\\\"b", escapeForText("a\"b", escapeQuotes = true))
    }

    @Test
    fun `a lone high or low surrogate is escaped and a valid pair is not`() {
        assertEquals("a\\uD800b", escapeForText("a\uD800b"))
        assertEquals("a\\uDC00b", escapeForText("a\uDC00b"))
        assertEquals("a\\uD83D", escapeForText("a\uD83D"), "a high surrogate at the end is lone")
        assertEquals("\\uDC07\\uD83D", escapeForText("\uDC07\uD83D"), "a reversed pair is two lone surrogates")
        assertEquals("a🐇b", escapeForText("a🐇b"))
    }

    @Test
    fun `no output char of any of the 65536 chars is control, format, a line or paragraph separator, or a lone surrogate`() {
        val bad = setOf(Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR).map { it.toInt() }.toSet()
        for (code in 0..0xFFFF) {
            val out = escapeForText("a" + code.toChar() + "b")
            for ((i, ch) in out.withIndex()) {
                assertFalse(Character.getType(ch) in bad, "U+${code.toString(16).uppercase()} leaves a control or format char in \"$out\"")
                if (Character.isHighSurrogate(ch)) assertTrue(i + 1 < out.length && Character.isLowSurrogate(out[i + 1]), "lone high surrogate left for U+${code.toString(16)}")
                if (Character.isLowSurrogate(ch)) assertTrue(i > 0 && Character.isHighSurrogate(out[i - 1]), "lone low surrogate left for U+${code.toString(16)}")
            }
        }
    }

    @Test
    fun `end to end - a queue with a right-to-left override reaches no text export, row, loss name or view string raw`() {
        val raw = "a‮b"
        val held = ScreenFiles.held(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue(raw, durable = false), DefinitionsFiles.queue(raw))))
        val view = ReportViewBuilder.build(held, openGroups = ReportViewBuilder.build(held).groups.map { it.key }.toSet())
        val rows = view.groups.flatMap { g -> view.rows(g.key).let { r -> (0 until r.size).map { r.at(it) } } }
        val named = rows.filter { it.name?.startsWith("a") == true }
        assertTrue(named.isNotEmpty(), "the premise: rows for the queue exist")
        named.forEach { assertEquals("a\\u202Eb", it.name) }
        val text = CompatibilityReportText.render(held.report)
        assertTrue(text.contains("a\\u202Eb"), "the text export shows the escaped name")
        val loss = assertNotNull(view.lossBlock)
        val everything = listOf(text, view.toString(), loss.toString(), loss.copyText, loss.preview.joinToString(), rows.joinToString { it.toString() })
        everything.forEach { assertFalse(it.contains('‮'), "a raw U+202E reached: ${it.take(80)}") }
        assertEquals("a\\u202Eb", loss.queueAt(0))
    }
}
