package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M8-03 plain-text rendering: summary first line, one block per finding in report order. Structure, not exact whitespace. */
class ReportTextTest {
    private fun blocks(text: String): List<String> {
        val lines = text.lines()
        val starts = lines.indices.filter { Regex("^\\[(BLOCKER|WARNING|INFO)] ").containsMatchIn(lines[it]) }
        return starts.mapIndexed { i, s -> lines.subList(s, if (i + 1 < starts.size) starts[i + 1] else lines.size).joinToString("\n") }
    }

    @Test
    fun `the first line is the summary sentence`() {
        assertEquals("1 of 2 items are compatible as they are, 1 need a decision", CompatibilityReportText.render(smallReport()).lines().first())
    }

    @Test
    fun `the summary of the three-item document reads all compatible`() {
        val r = analyze(D.document(queues = listOf(D.quorumQueue("events")), exchanges = listOf(D.exchange("events.x")), bindings = listOf(D.binding("events.x", "events"))))
        assertEquals("3 of 3 items are compatible as they are, 0 need a decision", CompatibilityReportText.render(r).lines().first())
    }

    @Test
    fun `there is one block per finding in report order with the severity tag and the rule`() {
        val report = smallReport()
        val blocks = blocks(CompatibilityReportText.render(report))
        assertEquals(3, blocks.size)
        assertEquals("""[BLOCKER] classic-index-v1 queue "orders" (vhost "/")""", blocks[0].lines().first())
        assertEquals("""[WARNING] classic-not-replicated queue "jobs" (vhost "/")""", blocks[1].lines().first())
        assertEquals("[INFO] classic-to-quorum-unmeasured global", blocks[2].lines().first())
    }

    @Test
    fun `each block has message, why and the proposal`() {
        val blocks = blocks(CompatibilityReportText.render(smallReport()))
        assertTrue(blocks[0].contains("message one") && blocks[0].contains("why one"), blocks[0])
        assertTrue(blocks[0].contains("Use a new broker"), "the proposal text is shown: ${blocks[0]}")
        assertTrue(blocks[1].contains("message two") && blocks[1].contains("why two"), blocks[1])
    }

    @Test
    fun `only the unverified finding says unverified`() {
        val blocks = blocks(CompatibilityReportText.render(smallReport()))
        assertFalse(blocks[0].contains("unverified", ignoreCase = true), blocks[0])
        assertFalse(blocks[1].contains("unverified", ignoreCase = true), blocks[1])
        assertTrue(blocks[2].contains("unverified", ignoreCase = true), blocks[2])
    }

    @Test
    fun `the tick text is shown for the proposal that needs it and for no other`() {
        val blocks = blocks(CompatibilityReportText.render(smallReport()))
        assertTrue(blocks[1].contains("Ordering by priority may change"), blocks[1])
        assertFalse(blocks[0].contains("Ordering by priority may change") || blocks[2].contains("Ordering by priority may change"))
    }

    @Test
    fun `the known-password tick is shown for guest`() {
        val text = CompatibilityReportText.render(analyze(D.document(users = listOf(D.user("guest")))))
        assertContainsText(text, "This user has a publicly known password", "the text for guest")
    }

    @Test
    fun `an empty report renders its summary and no block`() {
        val empty = CompatibilityReport(ReportSource(null, null, false, null), ReportSummary(0, 0, 0, 0, 0, 0, 0), emptyList())
        val text = CompatibilityReportText.render(empty)
        assertEquals("0 of 0 items are compatible as they are, 0 need a decision", text.lines().first())
        assertEquals(0, blocks(text).size)
    }

    // ---- round 1 (F4): the header format ----

    private fun header(report: CompatibilityReport, rule: String, name: String? = null): String {
        val f = report.withRule(rule, name).single()
        val line = CompatibilityReportText.render(report).lines().first { it.startsWith("[") && it.contains(" $rule ") || it.endsWith(" $rule") }
        assertTrue(f.rule == rule)
        return line
    }

    @Test
    fun `header examples of the architect`() {
        val doc = D.document(
            policies = listOf(D.policy("ha-all", "^x", D.args("ha-mode" to "all"))),
            users = listOf(D.user("svc")),
            otherParameters = listOf(D.parameter("global", "cluster_name", vhost = null)),
        )
        val r = analyze(doc)
        assertEquals("""[BLOCKER] ha-policy-key policy "ha-all" (vhost "/")""", header(r, "ha-policy-key"))
        assertEquals("""[INFO] access-declare-user user "svc"""", header(r, "access-declare-user"))
        assertEquals("[INFO] clients-global-qos global", header(r, "clients-global-qos"))
        assertEquals("[INFO] not-covered-global-parameter global [cluster_name]", header(r, "not-covered-global-parameter"))
    }

    // ---- round 1 (F2): escaping ----

    private val lineStart = Regex("^\\s*\\[(BLOCKER|WARNING|INFO)] ")
    private val hostile = "a\n[BLOCKER] fake x"

    @Test
    fun `a queue name with a line feed cannot start a fake block`() {
        val r = analyze(D.document(queues = listOf(D.classicQueue(hostile))))
        val text = CompatibilityReportText.render(r)
        assertEquals(r.findings.size, text.lines().count { lineStart.containsMatchIn(it) }, "one header line per finding, none from the name:\n$text")
        assertTrue(text.contains("a\\u000A[BLOCKER] fake x"), "the line feed is written as \\u000A")
    }

    @Test
    fun `control characters, NEL and the unicode line separators are escaped as uppercase four digit sequences`() {
        val name = "a\rb\u001Bc\u0007d\u0085e\u2028f\u2029g\u007Fh\u0000i\u009Fj\tk"
        val r = analyze(D.document(queues = listOf(D.classicQueue(name))))
        val text = CompatibilityReportText.render(r)
        val expected = "a\\u000Db\\u001Bc\\u0007d\\u0085e\\u2028f\\u2029g\\u007Fh\\u0000i\\u009Fj\\u0009k"
        assertContainsText(text, expected, "the escaped name")
        text.forEach { c ->
            if (c != '\n') assertTrue(c.code >= 0x20 && c.code != 0x7F && c.code !in 0x80..0x9F && c != '\u2028' && c != '\u2029', "raw character U+%04X in the text".format(c.code))
        }
    }

    @Test
    fun `a backslash and a double quote in a name are escaped`() {
        val r = analyze(D.document(queues = listOf(D.classicQueue("a\\b\"c"))))
        assertContainsText(CompatibilityReportText.render(r), """queue "a\\b\"c" (vhost "/")""", "the header")
    }

    @Test
    fun `a hostile vhost and user name are escaped in the header too`() {
        val r = analyze(D.document(queues = listOf(D.classicQueue("q", vhost = "v\n[INFO] fake")), users = listOf(D.user("u\r\n[INFO] x"))))
        val text = CompatibilityReportText.render(r)
        assertEquals(r.findings.size, text.lines().count { lineStart.containsMatchIn(it) }, text)
        assertTrue(!text.contains('\r'))
    }

    @Test
    fun `only the renderer writes line breaks`() {
        val r = analyze(D.document(queues = listOf(D.classicQueue("a\nb"))))
        val text = CompatibilityReportText.render(r)
        // every line break is followed by a blank line, an indented line or a header: never in the middle of a data value
        assertTrue(text.lines().none { it.isNotEmpty() && !it.startsWith("[") && !it.startsWith("  ") && !it.contains(" of ") }, text)
    }
}
