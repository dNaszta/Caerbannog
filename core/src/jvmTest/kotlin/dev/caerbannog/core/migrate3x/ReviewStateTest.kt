package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.ReviewFiles.q
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M8-06b: `ReviewState` and the sheet, through the view model on virtual time. Counts and lines, next undecided, a decision changed
 * later, a sheet closed without losing a decision, ticks and Accept, the manual item, message loss that never blocks, the Replace draft.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewStateTest {
    private val orders = q("orders")
    private val invoices = q("invoices")
    private val jobs = q("jobs")
    private val prio = q("prio")
    private val temp = q("temp")
    private val tick = Acknowledgement.OrderingByPriorityMayChange

    private fun TestScope.rig(text: String = ReviewFiles.three) = ReviewRig(this, text)

    // ---- counts and lines ----

    @Test
    fun `a report with three items that need a decision says so, with nothing decided`() = runTest {
        val r = rig()
        assertEquals(3, r.review.itemCount)
        assertEquals(3, r.review.pendingCount)
        assertEquals(listOf(0, 0, 0), listOf(r.review.acceptedCount, r.review.skippedCount, r.review.replacedCount))
        assertEquals("3 items need a decision", r.review.pendingLine)
        assertNull(r.review.decidedLine)
        assertFalse(r.review.canGenerate, "Generate is disabled while items are undecided")
        assertNull(r.review.sheet)
        assertEquals(GenerationState.Idle, r.generation)
    }

    @Test
    fun `accepting the default proposal of an item brings the count down`() = runTest {
        val r = rig()
        r.accept(orders)
        assertEquals("2 items need a decision", r.review.pendingLine)
        assertEquals(listOf(1, 2), listOf(r.review.acceptedCount, r.review.pendingCount))
        assertFalse(r.review.canGenerate)
    }

    @Test
    fun `the line and the button for one, none and all`() = runTest {
        val r = rig()
        r.accept(orders); r.accept(invoices)
        assertEquals("1 item needs a decision", r.review.pendingLine)
        assertFalse(r.review.canGenerate)
        r.accept(jobs)
        assertEquals("Every item is decided", r.review.pendingLine)
        assertTrue(r.review.canGenerate)
        assertEquals("3 of 3: 3 accepted.", r.review.decidedLine)
    }

    @Test
    fun `the decided line names accepted, skipped and replaced`() = runTest {
        val r = rig()
        r.accept(orders); r.skip(invoices)
        r.replace(jobs, """[{"action":"declareQueue","name":"jobs","type":"quorum"}]""")
        assertEquals("3 of 3: 1 accepted, 1 skipped (in the Skipped group), 1 replaced.", r.review.decidedLine)
        assertEquals(listOf(1, 1, 1, 0), listOf(r.review.acceptedCount, r.review.skippedCount, r.review.replacedCount, r.review.pendingCount))
    }

    @Test
    fun `a file with nothing to decide says so and can generate`() = runTest {
        val r = rig(dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles.file(queues = listOf(dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles.queue("modern", type = "quorum"))))
        assertEquals(0, r.review.itemCount)
        assertEquals("No item needs a decision", r.review.pendingLine)
        assertNull(r.review.decidedLine, "decidedLine is for itemCount > 0")
        assertTrue(r.review.canGenerate)
    }

    @Test
    fun `a count above one thousand uses the same plain number`() = runTest {
        val r = ReviewRig(this, ReviewFiles.classic(*Array(1500) { "q-$it" }))
        assertEquals(1500, r.review.itemCount)
        assertEquals("1,500 items need a decision", r.review.pendingLine, "numbers go through MigrateFromThreeTexts.count (thousands comma)")
        r.vm.showNextUndecided(); r.settle()
        assertEquals(listOf(1, 1500), listOf(r.sheet.itemNumber, r.sheet.itemTotal))
    }

    // ---- next undecided, a later change ----

    @Test
    fun `show next undecided opens the first pending item, with its place in the list`() = runTest {
        val r = rig()
        r.accept(orders)
        val expected = r.core.items.first { it != orders }   // items are in REPORT order, not file order (ruling 14)
        r.vm.showNextUndecided(); r.settle()
        assertEquals(expected, r.sheet.item)
        assertEquals(listOf(r.core.items.indexOf(expected) + 1, 3), listOf(r.sheet.itemNumber, r.sheet.itemTotal))
    }

    @Test
    fun `show next undecided with nothing pending opens nothing`() = runTest {
        val r = rig(ReviewFiles.orders)
        r.accept(orders)
        r.vm.showNextUndecided(); r.settle()
        assertNull(r.review.sheet)
    }

    @Test
    fun `a decision changed later replaces the first one and the counts follow`() = runTest {
        val r = rig()
        r.accept(orders)
        r.skip(orders)
        assertEquals(listOf(0, 1, 2), listOf(r.review.acceptedCount, r.review.skippedCount, r.review.pendingCount))
        assertEquals(ItemDecision.Skip, r.core.decisionOf(orders))
        r.open(orders)
        assertEquals(DecidedAs.Skipped, r.sheet.decided)
        r.sheet.ticks.forEach { r.vm.setTick(it.acknowledgement, true) }
        r.vm.accept(); r.settle()
        assertEquals(listOf(1, 0), listOf(r.review.acceptedCount, r.review.skippedCount))
        assertIs<ItemDecision.Accept>(r.core.decisionOf(orders))
    }

    // ---- sheets are local, decisions are kept ----

    @Test
    fun `closing a sheet loses no stored decision and drops the edits that were not accepted`() = runTest {
        val r = rig()
        r.accept(orders)
        val stored = r.core.decisionOf(orders)
        r.open(orders)
        assertEquals(DecidedAs.Accepted(ticked = true), r.sheet.decided, "opening a decided item pre-fills its stored choices")
        assertTrue(r.sheet.ticks.single().ticked)
        r.vm.setTick(tick, false)
        r.vm.chooseAlternative(r.sheet.findings.first().findingId, 1); r.settle()
        r.vm.closeItem(); r.settle()
        assertNull(r.review.sheet)
        assertEquals(stored, r.core.decisionOf(orders), "closing the sheet is not a decision")
        assertEquals(listOf(1, 2), listOf(r.review.acceptedCount, r.review.pendingCount))
        r.open(orders)
        assertTrue(r.sheet.ticks.single().ticked, "the edits of the closed sheet are gone, the stored choice is back")
        assertEquals(0, r.sheet.findings.first().chosen)
    }

    @Test
    fun `a sheet that was edited but not accepted leaves the item undecided`() = runTest {
        val r = rig()
        r.open(orders)
        r.vm.setTick(tick, true); r.settle()
        r.vm.closeItem(); r.settle()
        assertNull(r.core.decisionOf(orders))
        assertEquals(3, r.review.pendingCount)
        r.open(orders)
        assertFalse(r.sheet.ticks.single().ticked, "the tick is local to the sheet")
        assertNull(r.sheet.decided)
    }

    @Test
    fun `the decisions survive a new view model on the same held analysis`() = runTest {
        val r = rig()
        r.accept(orders); r.skip(invoices)
        val second = r.newVm(); r.settle()
        val state = second.state.value as MigrateFromThreeState.Report
        assertEquals(listOf(1, 1, 1), listOf(state.review.acceptedCount, state.review.skippedCount, state.review.pendingCount))
    }

    @Test
    fun `reset and opening another file drop the sheet and the drafts`() = runTest {
        val r = rig()
        r.open(orders); r.vm.startReplace(); r.vm.setReplaceText("[1]"); r.settle()
        r.vm.reset(); r.settle()
        assertEquals(MigrateFromThreeState.ChooseSource, r.vm.state.value)
        r.vm.open(r.path); r.settle()
        assertNull(r.review.sheet)
        r.open(orders); r.vm.startReplace(); r.settle()
        assertFalse(r.sheet.replace!!.text == "[1]", "the draft of the old analysis is gone")
        r.vm.open(r.path); r.settle()
        assertNull(r.review.sheet, "opening a file closes the sheet")
        assertEquals(3, r.review.pendingCount, "and the decisions of the old analysis are not carried over")
    }

    // ---- the sheet: findings, options, ticks ----

    @Test
    fun `the sheet of orders shows the finding, why it matters, the default proposal first and the alternative`() = runTest {
        val r = rig(ReviewFiles.mixed)
        r.open(orders)
        val sheet = r.sheet
        assertEquals(orders, sheet.item)
        val finding = sheet.findings.first { it.message.startsWith("Classic queues are no longer replicated") }
        assertEquals(Severity.Warning, finding.severity)
        assertEquals("Quorum queues are the replicated type on 4.x.", finding.why)
        assertTrue(finding.verified)
        assertEquals(listOf(0, 1), finding.options.map { it.index })
        assertEquals(listOf("Convert the queue to type quorum", "Keep the queue classic (it is not replicated on 4.x)"), finding.options.map { it.title })
        assertEquals(listOf(true, false), finding.options.map { it.isDefault })
        assertEquals(0, finding.chosen)
        assertTrue(sheet.findings.any { it.severity == Severity.Warning && it.message.startsWith("A quorum queue on 4.x always orders") }, "a finding that applies to one outcome appears as information: ${sheet.findings.map { it.message }}")
        assertTrue(sheet.manualSteps.isEmpty())
        assertNull(sheet.decided)
        assertNull(sheet.replace)
    }

    @Test
    fun `the ordering tick is unticked and Accept is blocked until it is ticked`() = runTest {
        val r = rig(ReviewFiles.orders)
        r.open(orders)
        assertEquals(listOf(TickView(tick, "Ordering by priority may change", ticked = false)), r.sheet.ticks)
        assertFalse(r.sheet.canAccept)
        assertEquals(DecisionRefusal.TickMissing(setOf(tick)), r.sheet.acceptBlock)
        r.vm.setTick(tick, true); r.settle()
        assertTrue(r.sheet.ticks.single().ticked)
        assertTrue(r.sheet.canAccept)
        assertNull(r.sheet.acceptBlock)
        r.vm.setTick(tick, false); r.settle()
        assertFalse(r.sheet.canAccept, "untick blocks again")
        assertEquals(DecisionRefusal.TickMissing(setOf(tick)), r.sheet.acceptBlock)
    }

    @Test
    fun `accepting with the tick stores the default with its tick and the row says accepted, ordering tick set`() = runTest {
        val r = rig(ReviewFiles.orders)
        r.accept(orders)
        assertNull(r.review.sheet, "the sheet closes on Decided")
        assertEquals(ItemDecision.Accept(acknowledged = setOf(tick)), r.core.decisionOf(orders))
        val row = r.rowsOf("classic-not-replicated:warning").single { it.name == "orders" }
        assertEquals(orders, row.item)
        assertEquals(DecisionPill.Accepted(ticked = true), row.decision)
    }

    @Test
    fun `accept calls from a blocked sheet store nothing and the sheet stays with the refusal`() = runTest {
        val r = rig(ReviewFiles.orders)
        r.open(orders)
        r.vm.accept(); r.settle()
        assertNull(r.core.decisionOf(orders), "the tick is missing, so nothing may be stored")
        assertNotNull(r.review.sheet, "the sheet stays")
        assertEquals(DecisionRefusal.TickMissing(setOf(tick)), r.sheet.acceptBlock)
        assertEquals(1, r.review.pendingCount)
    }

    @Test
    fun `choosing the quorum alternative of a queue that stays classic by default asks for the tick`() = runTest {
        val r = rig(ReviewFiles.mixed)
        r.open(prio)
        val finding = r.sheet.findings.first { it.message.startsWith("Classic queues are no longer replicated") }
        assertEquals("Keep the queue classic (it is not replicated on 4.x)", finding.options.single { it.isDefault }.title)
        assertTrue(r.sheet.ticks.isEmpty(), "keeping it classic needs no tick")
        assertTrue(r.sheet.canAccept)
        r.vm.chooseAlternative(finding.findingId, 1); r.settle()
        assertEquals(1, r.sheet.findings.first { it.findingId == finding.findingId }.chosen)
        assertEquals(listOf(tick), r.sheet.ticks.map { it.acknowledgement }, "only the ticks the chosen proposals require")
        assertFalse(r.sheet.canAccept)
        r.vm.setTick(tick, true); r.vm.accept(); r.settle()
        assertEquals(ItemDecision.Accept(chosen = mapOf(finding.findingId to 1), acknowledged = setOf(tick)), r.core.decisionOf(prio))
    }

    @Test
    fun `an alternative that needs no tick can be accepted at once and the row says accepted without a tick`() = runTest {
        val r = rig(ReviewFiles.mixed)
        r.accept(prio)
        assertEquals(ItemDecision.Accept(acknowledged = emptySet()), r.core.decisionOf(prio))
        val row = r.rowsOf("classic-not-replicated:warning").single { it.name == "prio" }
        assertEquals(DecisionPill.Accepted(ticked = false), row.decision)
    }

    @Test
    fun `a manual fix cannot be accepted as it is - the steps are shown, Accept is blocked until an automatic alternative is chosen`() = runTest {
        val r = rig(ReviewFiles.mixed)
        r.open(temp)
        val sheet = r.sheet
        assertEquals(listOf("Declare it durable or let the application declare it"), sheet.manualSteps.map { it.title })
        assertEquals("Declare the queue durable, or let the application declare it when it starts.", sheet.manualSteps.single().text)
        assertFalse(sheet.canAccept)
        val block = assertIs<DecisionRefusal.ManualNeeded>(sheet.acceptBlock)
        val finding = sheet.findings.first { it.message.startsWith("A non-durable queue") }
        assertEquals(listOf(finding.findingId), block.findingIds)
        assertEquals(sheet.manualSteps.single().findingId, finding.findingId)
        r.vm.accept(); r.settle()
        assertNull(r.core.decisionOf(temp), "a refused accept stores nothing")
        r.vm.chooseAlternative(finding.findingId, 1); r.settle()
        assertTrue(r.sheet.canAccept, "the automatic alternative (make it durable) can be accepted")
        assertTrue(r.sheet.manualSteps.isEmpty(), "the manual steps are those of the chosen proposals")
    }

    @Test
    fun `message loss never blocks Accept and is not a review finding of the sheet`() = runTest {
        val r = rig(ReviewFiles.orders)
        r.open(orders)
        assertTrue(r.sheet.findings.none { it.message.contains("old classic queue index") }, "${r.sheet.findings.map { it.message }}")
        assertTrue(r.sheet.manualSteps.none { it.title.contains("Migrate to a new 4.x broker") })
        r.vm.setTick(tick, true); r.settle()
        assertTrue(r.sheet.canAccept, "the only block of this item is the tick; message loss is a block above the report, not a decision")
    }

    @Test
    fun `events that need a sheet do nothing when there is no sheet and none when the screen is not the report`() = runTest {
        val r = rig()
        r.vm.accept(); r.vm.skip(); r.vm.startReplace(); r.vm.setTick(tick, true); r.vm.closeItem(); r.vm.backToProposal(); r.settle()
        assertEquals(3, r.review.pendingCount)
        assertNull(r.review.sheet)
        r.vm.reset(); r.settle()
        val choose = r.vm
        choose.openItem(orders); choose.accept(); choose.skip(); choose.generate(); choose.generateAgain(); choose.retrySave(); choose.exportFiles(); choose.dismissResult()
        choose.setReplaceText("x"); choose.submitReplace("[]"); choose.showNextUndecided(); choose.unskip(orders); choose.chooseFolder(); r.settle()
        assertEquals(MigrateFromThreeState.ChooseSource, choose.state.value)
        assertEquals(emptyList(), r.folders.repositoriesAsked, "no folder is touched outside the report")
        assertEquals(emptyList(), r.folders.chooser.startedIn)
    }

    @Test
    fun `opening an item that is not a review item shows no sheet`() = runTest {
        val r = rig(ReviewFiles.mixed)
        r.open(q("modern"))
        assertNull(r.review.sheet)
        r.open(q("nope"))
        assertNull(r.review.sheet)
    }

    // ---- Replace ----

    @Test
    fun `start replace prefills the editor with the actions the app would write, as migration JSON, and shows the hint`() = runTest {
        val r = rig(ReviewFiles.orders)
        r.open(orders); r.vm.startReplace(); r.settle()
        val editor = assertNotNull(r.sheet.replace)
        val expected = dev.caerbannog.core.migrations.MigrationParser.actionsToJson(r.core.previewActions(orders))
        assertEquals(expected, editor.text)
        assertTrue(editor.text.contains("\"declareQueue\"") && editor.text.contains("\"orders\""), editor.text)
        assertNull(editor.error)
        assertEquals("Migration actions, the \"up\" list of a migration file. An action without \"vhost\" is for \"/\". Only actions for virtual host / are accepted.", editor.hint)
    }

    @Test
    fun `a valid replacement is stored, the sheet closes, the row says replaced and the generated file carries it`() = runTest {
        val r = rig(ReviewFiles.orders)
        r.open(orders); r.vm.startReplace(); r.settle()
        r.vm.submitReplace("""[{"action":"declareQueue","name":"orders","type":"quorum","arguments":{"x-delivery-limit":5}}]"""); r.settle()
        assertNull(r.review.sheet)
        assertEquals(1, r.review.replacedCount)
        assertEquals(DecisionPill.Replaced(1), r.rowsOf("classic-not-replicated:warning").single().decision)
        val files = (r.core.generate(GenerationRequest(emptySet(), "legacy.json")) as GenerationResult.Generated).migrations
        assertTrue(files.single().file.rawJson.contains("x-delivery-limit"), files.single().file.rawJson)
        r.open(orders)
        assertEquals(DecidedAs.Replaced(1), r.sheet.decided)
    }

    @Test
    fun `an empty replacement is allowed and counts as a replacement that writes nothing`() = runTest {
        val r = rig(ReviewFiles.orders)
        r.replace(orders, "[]")
        assertEquals(1, r.review.replacedCount)
        assertEquals(DecisionPill.Replaced(0), r.rowsOf("classic-not-replicated:warning").single().decision)
    }

    @Test
    fun `a replacement the format refuses shows its error, keeps the text and stores nothing`() = runTest {
        val cases = mapOf(
            """[{"action":"deleteQueue","name":"orders"}]""" to "A replacement must not delete anything. Nothing was stored; the item is still undecided.",
            """[{"action":"declareQueue","name":".."}]""" to "The actions are refused: ",
            // the core's own returned reason, not a literal (ruling 1)
            """[{"action":"declareQueue","name":"orders","vhost":"other"}]""" to coreReason(ItemDecision.Replace(listOf(dev.caerbannog.core.migrations.MigrationAction.DeclareQueue("orders", vhost = "other")))),
            "this is not json" to "The actions are refused: the text is not valid JSON. Nothing was stored; the item is still undecided.",
            """{"action":"declareQueue"}""" to "The actions are refused: the text is not a list of actions. Nothing was stored; the item is still undecided.",
        )
        for ((text, error) in cases) {
            val r = rig(ReviewFiles.orders)
            r.open(orders); r.vm.startReplace(); r.settle()
            r.vm.submitReplace(text); r.settle()
            val editor = assertNotNull(r.sheet.replace, "the replace sheet stays for: $text")
            assertTrue(editor.error.orEmpty().startsWith(error.removeSuffix(" Nothing was stored; the item is still undecided.")), "error for $text was ${editor.error}")
            if (error.endsWith("undecided.")) assertEquals(error, editor.error, "for $text")
            assertTrue(editor.error.orEmpty().endsWith("Nothing was stored; the item is still undecided."), "${editor.error}")
            assertEquals(text, editor.text, "what the user typed is kept")
            assertNull(r.core.decisionOf(orders), "nothing is stored for: $text")
            assertEquals(1, r.review.pendingCount)
        }
    }

    private fun coreReason(decision: ItemDecision.Replace): String {
        val refusal = ConversionReviewsRef.core(ReviewFiles.orders).refusalOf(orders, decision)
        val reason = assertIs<DecisionRefusal.ReplaceInvalid>(refusal, "the core must refuse $decision").reason
        return "The actions are refused: $reason. Nothing was stored; the item is still undecided."
    }

    @Test
    fun `a new view model on the same holder shows the same counts, lines and Generate, with no sheet and an idle generation`() = runTest {
        val r = rig()
        r.accept(orders); r.skip(invoices)
        r.open(jobs)    // an open sheet is not carried over
        val before = r.review
        val second = r.newVm(); r.settle()
        val after = (second.state.value as MigrateFromThreeState.Report).review
        assertEquals(
            listOf(before.itemCount, before.pendingCount, before.acceptedCount, before.skippedCount, before.replacedCount, before.pendingLine, before.decidedLine, before.canGenerate),
            listOf(after.itemCount, after.pendingCount, after.acceptedCount, after.skippedCount, after.replacedCount, after.pendingLine, after.decidedLine, after.canGenerate),
        )
        assertEquals("1 item needs a decision", after.pendingLine)
        assertNull(after.sheet); assertEquals(GenerationState.Idle, after.generation)
        assertEquals(SKIPPED_GROUP_KEY, (second.state.value as MigrateFromThreeState.Report).view.groups.last().key, "the Skipped group is built from the held review")
    }

    @Test
    fun `a refused replacement does not replace an earlier decision`() = runTest {
        val r = rig(ReviewFiles.orders)
        r.skip(orders)
        r.open(orders); r.vm.startReplace(); r.settle()
        r.vm.submitReplace("""[{"action":"deleteQueue","name":"orders"}]"""); r.settle()
        assertEquals(ItemDecision.Skip, r.core.decisionOf(orders), "the Skip stays")
    }

    @Test
    fun `the Replace draft survives closing the sheet, a failed submit and reopening`() = runTest {
        val r = rig(ReviewFiles.three)
        r.open(orders); r.vm.startReplace(); r.settle()
        r.vm.setReplaceText("[ MARKER-X"); r.settle()
        r.vm.closeItem(); r.settle()
        assertNull(r.review.sheet)
        r.open(orders); r.vm.startReplace(); r.settle()
        assertEquals("[ MARKER-X", r.sheet.replace!!.text, "Esc keeps the typed text")
        r.vm.submitReplace("[ MARKER-X"); r.settle()
        assertEquals("[ MARKER-X", r.sheet.replace!!.text)
        r.open(invoices); r.vm.startReplace(); r.settle()
        assertFalse(r.sheet.replace!!.text.contains("MARKER-X"), "a draft is per item")
    }

    @Test
    fun `back to the proposal leaves the Replace editor, stores nothing, and keeps the draft`() = runTest {
        val r = rig(ReviewFiles.orders)
        r.open(orders); r.vm.startReplace(); r.vm.setReplaceText("[]"); r.vm.backToProposal(); r.settle()
        assertNull(r.sheet.replace)
        assertNull(r.core.decisionOf(orders))
        r.vm.startReplace(); r.settle()
        assertEquals("[]", r.sheet.replace!!.text)
    }

    // ---- skip and the Skipped group ----

    @Test
    fun `skipping an item moves it to the group Skipped by you, out of its rule group, and the count goes down`() = runTest {
        val r = rig(ReviewFiles.three)
        val ruleKey = "classic-not-replicated:warning"
        assertEquals(3, r.report.view.groups.first { it.key == ruleKey }.count)
        r.skip(orders)
        assertEquals(2, r.report.view.groups.first { it.key == ruleKey }.count)
        assertTrue(r.rowsOf(ruleKey).none { it.name == "orders" })
        val skipped = r.report.view.groups.last()
        assertEquals(SKIPPED_GROUP_KEY, skipped.key)
        assertEquals(1, skipped.count)
        assertNull(skipped.severity); assertNull(skipped.findingClass)
        assertEquals("2 items need a decision", r.review.pendingLine)
        val row = r.rowsOf(SKIPPED_GROUP_KEY).single()
        assertEquals(DecisionPill.Skipped, row.decision)
        assertEquals(orders, row.item)
        assertEquals("orders", row.name)
        assertTrue(row.message.startsWith("Left out of the migration. Was: Warning, Classic queues are no longer replicated"), row.message)
        assertEquals("", row.proposal); assertEquals("", row.why); assertNull(row.user)
    }

    @Test
    fun `un-skipping brings the item back to its rule group as undecided and removes the empty Skipped group`() = runTest {
        val r = rig(ReviewFiles.three)
        r.skip(orders)
        r.vm.unskip(orders); r.settle()
        assertEquals(3, r.review.pendingCount)
        assertTrue(SKIPPED_GROUP_KEY !in r.groupKeys(), "no skipped item: no group")
        val row = r.rowsOf("classic-not-replicated:warning").single { it.name == "orders" }
        assertEquals(DecisionPill.Undecided, row.decision)
        assertNull(r.core.decisionOf(orders))
    }

    @Test
    fun `the Skipped group is last, closed by default, absent when nothing is skipped, and not filtered by the chips`() = runTest {
        val r = rig(ReviewFiles.mixed)
        assertTrue(SKIPPED_GROUP_KEY !in r.groupKeys())
        r.skip(orders)
        val groups = r.report.view.groups
        assertEquals(SKIPPED_GROUP_KEY, groups.last().key)
        assertFalse(groups.last().open, "closed by default")
        assertEquals(1, groups.last().count)
        assertEquals(0, r.report.view.rows(SKIPPED_GROUP_KEY).size, "a closed group holds no rows")
        Severity.entries.forEach { r.vm.toggleSeverity(it); r.settle() }
        assertEquals(listOf(SKIPPED_GROUP_KEY), r.groupKeys(), "every chip is off: only the Skipped group is left")
        assertTrue(r.report.view.chips.none { it.selected })
        assertEquals(listOf(Severity.Blocker, Severity.Warning, Severity.Info), r.report.view.chips.map { it.severity })
        assertTrue(r.report.view.chips.first { it.severity == Severity.Warning }.count > 0, "chips still count the findings of the file")
    }

    @Test
    fun `the Skipped group opens and closes with toggleGroup and its key cannot collide with a rule group`() = runTest {
        val r = rig(ReviewFiles.three)
        r.skip(orders); r.skip(invoices)
        r.vm.toggleGroup(SKIPPED_GROUP_KEY); r.settle()
        val g = r.report.view.groups.last()
        assertTrue(g.open)
        assertEquals(2, r.report.view.rows(SKIPPED_GROUP_KEY).size)
        val reportOrder = r.core.items.filter { it == orders || it == invoices }.map { it.name }   // report order (ruling 14)
        assertEquals(reportOrder, (0 until 2).map { r.report.view.rows(SKIPPED_GROUP_KEY).at(it).name })
        r.vm.toggleGroup(SKIPPED_GROUP_KEY); r.settle()
        assertFalse(r.report.view.groups.last().open)
        assertTrue(r.report.view.groups.dropLast(1).all { ':' in it.key }, "rule group keys hold a colon; skipped holds none")
    }

    @Test
    fun `an item opened from the Skipped group and accepted leaves the group, and the group disappears when it was the only one`() = runTest {
        val r = rig(ReviewFiles.orders)
        r.skip(orders)
        r.vm.toggleGroup(SKIPPED_GROUP_KEY); r.settle()
        val row = r.rowsOf(SKIPPED_GROUP_KEY).single()
        r.vm.openItem(row.item!!); r.settle()
        assertEquals(DecidedAs.Skipped, r.sheet.decided)
        r.sheet.ticks.forEach { r.vm.setTick(it.acknowledgement, true) }
        r.vm.accept(); r.settle()
        assertTrue(SKIPPED_GROUP_KEY !in r.groupKeys())
        assertEquals(DecisionPill.Accepted(true), r.rowsOf("classic-not-replicated:warning").single().decision)
    }

    @Test
    fun `rows of items that need a decision carry the item and the pill, rows that need none carry neither`() = runTest {
        val r = rig(ReviewFiles.orders)
        val rows = r.report.view.groups.flatMap { g -> r.rowsOf(g.key) }
        val decidable = rows.filter { it.name == "orders" && it.message.startsWith("Classic queues are no longer replicated") }
        assertEquals(1, decidable.size)
        assertEquals(DecisionPill.Undecided, decidable.single().decision)
        assertEquals(orders, decidable.single().item)
        val info = rows.filter { it.message.startsWith("Check your clients") }
        assertTrue(info.isNotEmpty() && info.all { it.item == null && it.decision == null }, "a client check needs no decision")
    }
}
