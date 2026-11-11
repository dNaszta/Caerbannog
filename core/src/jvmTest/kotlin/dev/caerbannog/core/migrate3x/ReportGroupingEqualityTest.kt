package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M8-06e items 1 and 6: the view the builder makes from the group index equals the view of the old logic ([ReferenceGrouping], a copy of M8-06a to 06c)
 * on a mixed fixture of a few thousand findings: groups (key, title, severity, class, count, open), chips, loss block, every row of every open group,
 * the Skipped group and the pills. Green before the change (it characterizes the old result) and must stay green after it.
 */
class ReportGroupingEqualityTest {
    private val all = Severity.entries.toSet()

    private fun held(): HeldAnalysis = GroupingFixtures.mixed().also {
        assertTrue(it.report.findings.size > 2_500, "the premise: a few thousand findings, got ${it.report.findings.size}")
        assertTrue(it.report.findings.map { f -> f.rule }.toSet().size >= 8, "the premise: several rules")
        assertTrue(it.report.findings.map { f -> f.severity }.toSet() == all, "the premise: every severity")
        assertTrue(it.review.items.size > 500, "the premise: review items, got ${it.review.items.size}")
    }

    private fun skip(held: HeldAnalysis, items: List<ReviewItem>) =
        items.forEach { assertEquals(DecisionResult.Decided, held.review.decide(it, ItemDecision.Skip), "skip $it") }

    /** Builds with the marker formatter and compares everything with the reference. */
    private fun assertSame(held: HeldAnalysis, selected: Set<Severity> = all, open: Set<String> = emptySet(), what: String) {
        val view = ReportViewBuilder.build(held, selected, open, rowFormatter = ReferenceGrouping.marker)
        val expected = ReferenceGrouping.expected(held, selected, open)
        assertEquals(expected.groups, view.groups, "$what: groups")
        assertEquals(expected.chips, view.chips, "$what: chips")
        for (group in expected.groups) {
            val rows = view.rows(group.key)
            if (!group.open) { assertEquals(0, rows.size, "$what: closed group ${group.key} holds no rows"); continue }
            val want = expected.rows.getValue(group.key)
            assertEquals(want.size, rows.size, "$what: row count of ${group.key}")
            for (i in want.indices) assertEquals(want[i], rows.at(i), "$what: row $i of ${group.key}")
        }
        val names = expected.lossNames
        val loss = view.lossBlock
        if (names == null) { assertNull(loss, "$what: no loss block"); return }
        assertNotNull(loss, "$what: loss block")
        assertEquals(MigrateFromThreeTexts.lossTitle(names.size), loss.title, "$what: loss title")
        assertEquals(names.size, loss.queueCount, "$what: loss count")
        assertEquals(names.take(LOSS_PREVIEW_COUNT), loss.preview, "$what: loss preview")
        for (i in names.indices) assertEquals(names[i], loss.queueAt(i), "$what: loss name $i")
        assertEquals(names.joinToString("\n"), loss.copyText, "$what: loss copy text")
    }

    @Test
    fun `nothing skipped, all groups closed and all groups open`() {
        val held = held()
        assertSame(held, what = "closed")
        assertSame(held, open = ReportViewBuilder.build(held).groups.map { it.key }.toSet(), what = "all open")
    }

    @Test
    fun `some items skipped - the groups shrink, the Skipped group lists them in report order, the pills follow`() {
        val held = held()
        skip(held, held.review.items.filterIndexed { i, _ -> i % 7 == 0 })
        val keys = ReportViewBuilder.build(held).groups.map { it.key }.toSet()
        assertTrue(SKIPPED_GROUP_KEY in keys)
        assertSame(held, what = "closed")
        assertSame(held, open = keys, what = "all open")
        assertSame(held, open = setOf(SKIPPED_GROUP_KEY), what = "only the Skipped group open")
    }

    @Test
    fun `an accepted and a replaced item keep their group and show their pill`() {
        val held = held()
        val review = held.review
        // an item with a manual-only fix cannot be accepted ("temp"); take the first 5 that can
        val accepted = review.items.filter { review.decide(it, ReviewViewBuilder.acceptOf(review, SheetEdit(it, 1, ticks = Acknowledgement.entries.toSet()))) == DecisionResult.Decided }.take(5)
        assertEquals(5, accepted.size, "the premise: five accepted items")
        skip(held, review.items.filter { review.decisionOf(it) == null }.take(3))
        assertSame(held, open = ReportViewBuilder.build(held).groups.map { it.key }.toSet(), what = "accepted and skipped")
    }

    @Test
    fun `all items of a group skipped - the group disappears and the other groups are unchanged`() {
        val held = held()
        val view = ReportViewBuilder.build(held)
        // a group whose findings are all review findings that need a decision: every item of it is skipped, nothing else is left
        val target = view.groups.first { g ->
            g.severity != null && g.findingClass != FindingClass.MessageLoss &&
                held.report.findings.filter { "${it.rule}:${it.severity.name.lowercase()}" == g.key }.all { ReviewRules.isReviewFinding(it) }
        }
        val items = held.report.findings.filter { "${it.rule}:${it.severity.name.lowercase()}" == target.key }
            .map { ReviewItem(it.kind, it.vhost, it.name) }.distinct().filter { it in held.review.items }
        assertTrue(items.isNotEmpty(), "the premise: the group ${target.key} holds review items")
        skip(held, items)
        val after = ReportViewBuilder.build(held)
        assertTrue(after.groups.none { it.key == target.key }, "the group ${target.key} is gone: ${after.groups.map { it.key }}")
        assertSame(held, open = after.groups.map { it.key }.toSet(), what = "group ${target.key} emptied")
    }

    @Test
    fun `filter off for each severity, with and without skipped items and open groups`() {
        val held = held()
        for (severity in Severity.entries) assertSame(held, selected = all - severity, what = "without $severity")
        skip(held, held.review.items.filterIndexed { i, _ -> i % 5 == 0 })
        val keys = ReportViewBuilder.build(held).groups.map { it.key }.toSet()
        for (severity in Severity.entries) assertSame(held, selected = all - severity, open = keys, what = "without $severity, skipped, open")
        assertSame(held, selected = emptySet(), open = keys, what = "no severity")
    }

    @Test
    fun `the loss block ignores the filter and the skipped items, and its names are escaped`() {
        val held = held()
        skip(held, held.review.items.filterIndexed { i, _ -> i % 2 == 0 })
        val loss = assertNotNull(ReportViewBuilder.build(held, selected = emptySet()).lossBlock)
        assertTrue(loss.copyText.lines().any { it.contains("odd\\u000Aname\\u202E-") }, "the odd name is escaped: ${loss.copyText.take(200)}")
        assertSame(held, selected = emptySet(), what = "no severity")
    }

    @Test
    fun `an un-skipped item is back in its group`() {
        val held = held()
        val first = held.review.items.first()
        skip(held, listOf(first))
        held.review.undecide(first)
        assertTrue(ReportViewBuilder.build(held).groups.none { it.key == SKIPPED_GROUP_KEY })
        assertSame(held, open = ReportViewBuilder.build(held).groups.map { it.key }.toSet(), what = "after un-skip")
    }

    @Test
    fun `copyText equals the old string when it is read after the build`() {
        val held = held()
        val view = ReportViewBuilder.build(held, openGroups = setOf("nothing"))
        val loss = assertNotNull(view.lossBlock)
        val old = held.report.findings.filter { it.findingClass == FindingClass.MessageLoss }.joinToString("\n") { escapeForText(it.name.orEmpty()) }
        assertEquals(old, loss.copyText)
        assertEquals(old, loss.copyText, "a second read gives the same text")
    }
}
