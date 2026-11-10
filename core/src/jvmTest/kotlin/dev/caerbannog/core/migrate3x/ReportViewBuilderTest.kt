package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-06a: grouping, chips, loss block and lazy rows. Brief: docs/tasks/M8-06a-file-source-and-report.md. */
class ReportViewBuilderTest {
    private val held = ScreenFiles.held()

    private fun ReportView.openAll(): ReportView = ReportViewBuilder.build(held, openGroups = groups.map { it.key }.toSet())

    @Test
    fun `the groups cover every finding once, key is rule plus severity, and the order is loss, blocker, warning, info, rule`() {
        val view = ReportViewBuilder.build(held)
        assertEquals(held.report.findings.size, view.groups.sumOf { it.count }, "every finding sits in exactly one group")
        assertEquals(view.groups.size, view.groups.map { it.key }.toSet().size, "group keys are unique")
        view.groups.forEach { g ->
            assertTrue(g.key.contains(g.severity.name, ignoreCase = true), "key ${g.key} names the severity ${g.severity}")
        }
        val order = view.groups.map { Triple(g(it), it.severity.ordinal, it.key) }
        assertEquals(order.sortedWith(compareBy({ it.first }, { it.second }, { it.third })), order, "order of ${view.groups.map { it.key }}")
        assertTrue(view.groups.size >= 3, "the mixed file has several rules, got ${view.groups.map { it.key }}")
    }

    private fun g(group: GroupView): Int = if (group.findingClass == FindingClass.MessageLoss) 0 else 1

    @Test
    fun `a group starts closed unless it is in the open set`() {
        val view = ReportViewBuilder.build(held)
        assertTrue(view.groups.none { it.open && it.findingClass != FindingClass.MessageLoss }, "only the message-loss group may start open: ${view.groups}")
        val key = view.groups.first { it.findingClass != FindingClass.MessageLoss }.key
        assertTrue(ReportViewBuilder.build(held, openGroups = setOf(key)).groups.first { it.key == key }.open)
    }

    @Test
    fun `the loss block is built for the queues at risk and never filtered away`() {
        for (selected in listOf(Severity.entries.toSet(), setOf(Severity.Info), emptySet())) {
            val loss = ReportViewBuilder.build(held, selected).lossBlock
            assertNotNull(loss, "filter $selected must not remove the message-loss block")
            assertTrue(loss.title.contains("silently empty"), loss.title)
            assertEquals(held.report.summary.messageLoss, loss.queues.size, "one name per at-risk queue")
        }
    }

    @Test
    fun `no loss block when no queue is at risk`() {
        val quorum = ScreenFiles.held(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("q", type = "quorum"))))
        assertEquals("3.13.7", ReportViewBuilder.build(quorum).sourceVersion, "the premise: the view is built")
        assertNull(ReportViewBuilder.build(quorum).lossBlock)
    }

    @Test
    fun `the loss block has three fixes and only the new broker with a Shovel is recommended`() {
        val fixes = assertNotNull(ReportViewBuilder.build(held).lossBlock).fixes
        assertEquals(3, fixes.size, "$fixes")
        val recommended = fixes.single { it.recommended }
        assertTrue(recommended.title.contains("new 4.x broker", ignoreCase = true) || recommended.text.contains("new 4.x broker", ignoreCase = true), "$recommended")
        assertTrue(recommended.text.contains("Shovel"), "$recommended")
        assertTrue(fixes.filter { !it.recommended }.none { it.title.contains("new 4.x broker", ignoreCase = true) }, "$fixes")
    }

    @Test
    fun `chips show the three counts of the whole file whatever is selected`() {
        val s = held.report.summary
        for (selected in listOf(Severity.entries.toSet(), setOf(Severity.Blocker), emptySet())) {
            val chips = ReportViewBuilder.build(held, selected).chips
            assertEquals(listOf(Severity.Blocker, Severity.Warning, Severity.Info), chips.map { it.severity })
            assertEquals(listOf(s.blockers, s.warnings, s.infos), chips.map { it.count }, "counts ignore the filter $selected")
            assertEquals(Severity.entries.map { it in selected }, chips.map { it.selected })
        }
    }

    @Test
    fun `the filter removes groups of the other severities but not the message-loss group`() {
        val view = ReportViewBuilder.build(held, setOf(Severity.Blocker))
        assertTrue(view.groups.isNotEmpty())
        assertTrue(view.groups.filter { it.findingClass != FindingClass.MessageLoss }.all { it.severity == Severity.Blocker }, "${view.groups}")
    }

    @Test
    fun `the header line carries the file label, version, document counts and the summary`() {
        val view = ReportViewBuilder.build(held)
        assertEquals("legacy.json", view.fileLabel)
        assertEquals("3.13.7", view.sourceVersion)
        assertNull(view.singleVirtualHost)
        assertEquals(DocumentCounts(held.document.virtualHosts.size, 4, 2, 1), view.counts, "counted from the document, not from findings")
        assertEquals(held.report.summary, view.summary)
        val s = held.report.summary
        assertTrue(view.summaryLine.contains("${s.compatibleItems}") && view.summaryLine.contains("compatible as they are"), view.summaryLine)
        assertTrue(view.summaryLine.contains("${s.needDecision}") && view.summaryLine.contains("need a decision"), view.summaryLine)
    }

    @Test
    fun `items without a finding are listed nowhere`() {
        val rows = ReportViewBuilder.build(held).let { v -> v.openAll().let { open -> open.groups.flatMap { grp -> open.rows(grp.key).let { r -> (0 until r.size).map { r.at(it) } } } } }
        assertTrue(rows.none { it.kind == ItemKind.Exchange || it.kind == ItemKind.Binding }, "the exchanges and the binding are compatible: $rows")
        assertTrue(rows.none { it.name == "modern" }, "the quorum queue is compatible")
        assertEquals(held.report.findings.size, rows.size)
    }

    @Test
    fun `a closed group builds no rows and an open group builds a row only when asked`() {
        var built = 0
        val counting: (Finding) -> RowView = { built++; FindingRowFormatter.format(it) }
        val closed = ReportViewBuilder.build(held, rowFormatter = counting)
        closed.groups.forEach { assertEquals(0, closed.rows(it.key).size, "closed group ${it.key} holds no rows") }
        assertEquals(0, built, "nothing is built for closed groups")

        val target = closed.groups.first { it.findingClass != FindingClass.MessageLoss && it.count > 0 }
        val open = ReportViewBuilder.build(held, openGroups = setOf(target.key), rowFormatter = counting)
        val rows = open.rows(target.key)
        assertEquals(target.count, rows.size)
        assertEquals(0, built, "opening a group builds nothing yet")
        rows.at(0)
        assertEquals(1, built, "one row per at()")
    }

    @Test
    fun `240000 findings are grouped and the rows of one open group are read in the default heap`() {
        val base = ScreenFiles.held(DefinitionsFiles.classicQueues(2_000))
        val one = base.report.findings
        val copies = 240_000 / one.size + 1
        val findings = (0 until copies).flatMap { n -> one.map { it.copy(id = "${it.id}#$n") } }.take(240_000)
        val big = HeldAnalysis("big.json", base.document, base.report.copy(findings = findings))
        val view = ReportViewBuilder.build(big)
        assertEquals(240_000, view.groups.sumOf { it.count })
        val largest = view.groups.maxBy { it.count }
        val open = ReportViewBuilder.build(big, openGroups = setOf(largest.key))
        val rows = open.rows(largest.key)
        assertEquals(largest.count, rows.size)
        rows.at(0)
        rows.at(rows.size / 2)
        rows.at(rows.size - 1)
        assertTrue(open.groups.filter { it.key != largest.key && !it.open }.all { open.rows(it.key).size == 0 })
    }
}
