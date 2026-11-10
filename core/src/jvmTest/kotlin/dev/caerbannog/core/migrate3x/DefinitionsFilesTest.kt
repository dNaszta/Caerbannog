package dev.caerbannog.core.migrate3x

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-06a: the test file builder produces files the real parser accepts, with the 3.x shape (no x-queue-type on a classic queue). */
class DefinitionsFilesTest {
    @Test
    fun `the mixed test file parses and holds every severity and a message-loss finding`() {
        val held = ScreenFiles.held()
        assertEquals(4, held.document.queues.size)
        assertEquals(2, held.document.exchanges.size)
        val report = held.report
        assertTrue(report.summary.blockers > 0, "the premise: the mixed file has a blocker")
        assertTrue(report.summary.warnings > 0, "the premise: the mixed file has a warning")
        assertTrue(report.summary.infos > 0, "the premise: the mixed file has an info")
        assertTrue(report.summary.messageLoss > 0, "the premise: the mixed file has message loss")
        assertTrue(report.findings.any { it.rule == "policy-pattern-unreadable" }, "the lookahead pattern is unreadable")
    }

    @Test
    fun `a per-virtual-host file has no vhost on its entries`() {
        val text = dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles.file(queues = listOf(dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles.queue("q", vhost = null)), vhosts = null)
        assertTrue(DefinitionsParser.parse(text) is DefinitionsParseResult.NeedsVirtualHost, "got ${DefinitionsParser.parse(text)}")
    }
}
