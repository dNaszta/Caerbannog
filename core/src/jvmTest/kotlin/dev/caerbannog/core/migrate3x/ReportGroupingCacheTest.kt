package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M8-06e items 1 and 6 (brief docs/tasks/M8-06e-report-polish.md): a chip, a group toggle, a sheet and a decision on a held analysis of 240,000
 * findings do not walk the findings again. The findings sit behind a [CountingList]; the count is reset after the held analysis is built (the review
 * and the group index walk the findings once there) and must stay 0. No production hook, no timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportGroupingCacheTest {
    private val notReplicated = GroupingFixtures.NOT_REPLICATED
    private val allSeverities = Severity.entries.toSet()

    private fun ticksFor(review: ConversionReview, item: ReviewItem): ItemDecision.Accept =
        ReviewViewBuilder.acceptOf(review, SheetEdit(item, 1, ticks = Acknowledgement.entries.toSet()))

    @Test
    fun `the premise - 240000 findings, two per queue, and the held analysis walks them at construction only`() {
        val (held, list) = GroupingFixtures.bigCounted()
        val groupSize = list.raw.count { GroupingFixtures.keyOf(it) == notReplicated }
        assertEquals(240_000, held.report.findings.size)
        assertTrue(held.review.items.size > 70_000, "the premise: one review item per queue, got ${held.review.items.size}")
        assertTrue(groupSize > 70_000, "the premise: a big group, got $groupSize")
        assertEquals(0, list.total, "reset after construction: ${list.describe()}")
        // the counter works at all: a pass over the findings is seen
        held.report.findings.count { it.rule == "x" }
        assertTrue(list.total > 0, "the CountingList did not see a pass over the findings")
    }

    @Test
    fun `builder - chip, group toggle, sheet, accept, skip and un-skip walk the findings 0 times`() {
        val (held, list) = GroupingFixtures.bigCounted()
        val groupSize = list.raw.count { GroupingFixtures.keyOf(it) == notReplicated }
        val review = held.review
        fun passes(step: String) = assertEquals(0, list.total, "after $step the findings were walked again: ${list.describe()}")

        val first = ReportViewBuilder.build(held)
        passes("the first build")
        val loss = first.groups.first { it.findingClass == FindingClass.MessageLoss }
        val group = first.groups.first { it.key == notReplicated }
        assertEquals(groupSize, group.count)

        ReportViewBuilder.build(held, selected = allSeverities - Severity.Warning); passes("chip Warning off")
        ReportViewBuilder.build(held, selected = allSeverities); passes("chip Warning on")

        val open = ReportViewBuilder.build(held, openGroups = setOf(notReplicated))
        assertEquals(groupSize, open.rows(notReplicated).size)
        open.rows(notReplicated).at(0); open.rows(notReplicated).at(groupSize - 1)
        passes("opening a group and drawing its first and last row")
        ReportViewBuilder.build(held, openGroups = emptySet()); passes("closing the group")
        ReportViewBuilder.build(held, openGroups = setOf(loss.key)); passes("opening the loss group")

        val item = review.items.first()
        ReviewViewBuilder.state(review, ReviewViewBuilder.editOf(review, item, 1), GenerationState.Idle); passes("opening the sheet")

        assertEquals(DecisionResult.Decided, review.decide(item, ticksFor(review, item)))
        val accepted = ReportViewBuilder.build(held, openGroups = setOf(notReplicated)); passes("accepting one item")
        assertEquals(groupSize, accepted.groups.first { it.key == notReplicated }.count, "an accepted item stays in its group")
        assertEquals(DecisionPill.Accepted(ticked = true), accepted.rows(notReplicated).at(0).decision)

        val second = review.items[1]
        assertEquals(DecisionResult.Decided, review.decide(second, ItemDecision.Skip))
        val skipped = ReportViewBuilder.build(held); passes("skipping one item")
        val left = review.findingsOf(second).count { GroupingFixtures.keyOf(it) == notReplicated }
        assertEquals(1, left, "the premise: the item has one finding in the group")
        assertEquals(groupSize - 1, skipped.groups.first { it.key == notReplicated }.count, "the skipped item left its group")
        assertEquals(SKIPPED_GROUP_KEY, skipped.groups.last().key)
        assertEquals(1, skipped.groups.last().count)

        review.undecide(second)
        val back = ReportViewBuilder.build(held); passes("un-skipping it")
        assertEquals(groupSize, back.groups.first { it.key == notReplicated }.count)
        assertTrue(back.groups.none { it.key == SKIPPED_GROUP_KEY }, "no Skipped group once nothing is skipped")

        val third = review.items[2]
        assertEquals(DecisionResult.Decided, review.decide(third, ItemDecision.Skip))
        val withOpenGroup = ReportViewBuilder.build(held, openGroups = setOf(notReplicated)); passes("a second skip while a group is open")
        val rows = withOpenGroup.rows(notReplicated)
        assertEquals(groupSize - 1, rows.size)
        assertEquals(item, rows.at(0).item, "the first row is still the accepted item")
        assertEquals(review.items[1], rows.at(1).item, "the second row is the un-skipped item")
        assertEquals(review.items[3], rows.at(2).item, "the skipped third item is not in the open group")
        rows.at(rows.size - 1)
        passes("drawing the rows of the group that has a removal")
    }

    @Test
    fun `builder - the loss block and its copy text need no pass over the findings`() {
        val (held, list) = GroupingFixtures.bigCounted()
        val loss = assertNotNull(ReportViewBuilder.build(held).lossBlock)
        assertEquals(list.raw.count { it.findingClass == FindingClass.MessageLoss }, loss.queueCount)
        assertTrue(loss.queueCount > 70_000, "the premise: a big loss block, got ${loss.queueCount}")
        assertEquals(10, loss.preview.size)
        loss.queueAt(0); loss.queueAt(loss.queueCount - 1)
        assertEquals(0, list.total, "the loss block walked the findings: ${list.describe()}")
        assertEquals(loss.queueCount, loss.copyText.lines().size)
        assertEquals(0, list.total, "the copy text walked the findings: ${list.describe()}")
    }

    // ---- view model: item 6, a decision event only re-filters by the skipped set and recounts ----

    private class Rig(test: TestScope) {
        val saver = FakeReportFileSaver()
        val holder = AnalysisHolder()
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val held: HeldAnalysis
        val list: CountingList<Finding>
        val vm: MigrateFromThreeViewModel

        init {
            val (h, l) = GroupingFixtures.bigCounted()
            held = h; list = l
            holder.hold(held)
            vm = MigrateFromThreeViewModel(
                FakeDefinitionsFileLoader(), FakeDefinitionsFileChooser(), saver, holder, FakeMigrationFolders().ports, CoroutineScope(dispatcher), dispatcher,
            )
        }

        val report: MigrateFromThreeState.Report get() = assertIs(vm.state.value, "the state is ${vm.state.value}")
    }

    @Test
    fun `view model - the events of a chip, a group, the sheet and the decisions walk the findings 0 times`() = runTest {
        val r = Rig(this)
        val groupSize = r.list.raw.count { GroupingFixtures.keyOf(it) == notReplicated }
        fun passes(step: String) = assertEquals(0, r.list.total, "after $step the findings were walked again: ${r.list.describe()}")
        passes("the first state")
        assertEquals(groupSize, r.report.view.groups.first { it.key == notReplicated }.count)
        val items = r.held.review.items.size
        assertEquals(items, r.report.review.itemCount)

        r.vm.toggleSeverity(Severity.Warning); advanceUntilIdle(); passes("chip off")
        r.vm.toggleSeverity(Severity.Warning); advanceUntilIdle(); passes("chip on")
        r.vm.toggleGroup(notReplicated); advanceUntilIdle(); passes("group open")
        assertEquals(groupSize, r.report.view.rows(notReplicated).size)
        r.vm.toggleGroup(notReplicated); advanceUntilIdle(); passes("group closed")

        val accepted = r.held.review.items[0]
        r.vm.openItem(accepted); advanceUntilIdle(); passes("open the sheet")
        assertEquals(accepted, r.report.review.sheet?.item)
        r.report.review.sheet!!.ticks.forEach { r.vm.setTick(it.acknowledgement, true) }
        r.vm.accept(); advanceUntilIdle(); passes("accept")
        assertEquals(1, r.report.review.acceptedCount)
        assertEquals(items - 1, r.report.review.pendingCount)

        r.vm.toggleGroup(notReplicated); advanceUntilIdle()
        val skipped = r.held.review.items[1]
        r.vm.openItem(skipped); r.vm.skip(); advanceUntilIdle(); passes("skip (group open)")
        val afterSkip = r.report
        assertEquals(1, afterSkip.review.skippedCount)
        assertEquals(items - 2, afterSkip.review.pendingCount)
        val skippedGroup = afterSkip.view.groups.last()
        assertEquals(SKIPPED_GROUP_KEY to MigrateFromThreeTexts.SKIPPED_GROUP_TITLE, skippedGroup.key to skippedGroup.title)
        assertEquals(1, skippedGroup.count)
        assertEquals(groupSize - 1, afterSkip.view.groups.first { it.key == notReplicated }.count)
        r.vm.toggleGroup(SKIPPED_GROUP_KEY); advanceUntilIdle()
        val pill = r.report.view.rows(SKIPPED_GROUP_KEY).at(0)
        assertEquals(skipped to DecisionPill.Skipped, pill.item to pill.decision)
        assertEquals(DecisionPill.Accepted(ticked = true), r.report.view.rows(notReplicated).at(0).decision, "the accepted item's pill")
        passes("drawing the pills")

        r.vm.unskip(skipped); advanceUntilIdle(); passes("un-skip")
        assertEquals(0, r.report.review.skippedCount)
        assertEquals(items - 1, r.report.review.pendingCount)
        assertTrue(r.report.view.groups.none { it.key == SKIPPED_GROUP_KEY })
        assertEquals(groupSize, r.report.view.groups.first { it.key == notReplicated }.count)

        r.vm.openItem(r.held.review.items[2]); r.vm.skip(); advanceUntilIdle(); passes("a second skip with the group open")
        assertEquals(groupSize - 1, r.report.view.groups.first { it.key == notReplicated }.count)
    }
}
