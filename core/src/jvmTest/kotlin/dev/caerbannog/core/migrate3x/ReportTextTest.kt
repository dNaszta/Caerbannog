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
        assertTrue(blocks[0].startsWith("[BLOCKER] classic-index-v1 "), blocks[0])
        assertTrue(blocks[1].startsWith("[WARNING] classic-not-replicated "), blocks[1])
        assertTrue(blocks[2].startsWith("[INFO] classic-to-quorum-unmeasured "), blocks[2])
        assertTrue(blocks[0].lines().first().contains("orders") && blocks[1].lines().first().contains("jobs"), "the header names the item")
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
}
