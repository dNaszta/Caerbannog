package dev.caerbannog.core.planner

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M9-02 step 4: exploratory checks turned into tests (realistic sequences the scenarios do not walk). */
class PlannerExploratoryTest {
    private val rig = PlannerRig()
    private val vm get() = rig.vm

    @AfterTest
    fun close() = rig.close()

    @Test
    fun `a plan with a blocking problem is kept as a draft and comes back as a plan, not as an unreadable file`() {
        rig.queue("orders")
        rig.queue("orders", type = "classic") // the same name with other settings: Save is blocked, the plan is still a draft
        assertTrue(rig.state.problems.any { it.blocking })
        rig.restart()
        assertNull(rig.state.draftUnreadable, "a plan the planner itself wrote must be read back: ${rig.state.draftUnreadable}")
        assertEquals(2, rig.state.plan.steps.size)
    }

    @Test
    fun `a binding placed before its queue is kept and read back`() {
        rig.exchange("events"); rig.binding("events", "orders", "k"); rig.queue("orders")
        rig.restart()
        assertNull(rig.state.draftUnreadable)
        assertEquals(3, rig.state.plan.steps.size)
    }

    @Test
    fun `double Save writes one file`() {
        rig.queue("orders")
        vm.saveAsMigration(); vm.saveAsMigration()
        assertEquals(listOf("0005"), rig.folder.written)
    }

    @Test
    fun `double confirm of Dismiss clears once and nothing else`() {
        rig.queue("orders")
        vm.dismiss(); vm.confirm(); vm.confirm()
        assertEquals(1, rig.store.clears)
        assertEquals(setOf("0004"), rig.folder.files.keys)
    }

    @Test
    fun `hostile descriptions are kept, saved and read back as typed`() {
        val nasty = listOf("tab\there", "line\nbreak", "quote \" and \\ slash", "‮right-to-left", "emoji 🐇", "x".repeat(200_000), "</script><b>")
        nasty.forEach { text ->
            rig.restart()
            vm.setDescription(text)
            rig.queue("orders-${text.length}")
            rig.restart()
            assertEquals(text, rig.state.description, "the description changed on the way")
            assertEquals(text.length, rig.state.description.length, "a description of ${text.length} characters changed length")
            vm.dismiss(); vm.confirm()
        }
    }

    @Test
    fun `a huge plan is saved whole and the gate stays correct`() {
        repeat(400) { rig.queue("q-$it") }
        assertEquals(SaveGate.Enabled, rig.state.saveGate)
        vm.saveAsMigration()
        assertEquals(400, rig.folder.files.getValue("0005").up.size)
        assertFalse(rig.store.hasDraft)
    }

    @Test
    fun `removing every step one by one ends with no draft and no selection`() {
        rig.queue("a"); rig.queue("b"); rig.queue("c")
        rig.ids().forEach { vm.selectStep(it); vm.removeStep(it) }
        assertEquals(emptyList(), rig.state.plan.steps)
        assertNull(rig.state.selectedStepId)
        assertFalse(rig.store.hasDraft)
    }

    @Test
    fun `a failed draft write on Save does not matter, the migration is written and the plan reset`() {
        rig.queue("orders")
        rig.store.refuseWrites = true
        vm.saveAsMigration()
        assertEquals("0005", rig.state.saved?.version)
    }

    @Test
    fun `an unreadable draft stays locked after Save, Dismiss and a wizard attempt, with the file intact`() {
        rig.store.putUnreadable("garbage")
        rig.restart()
        vm.openWizard(); vm.saveAsMigration(); vm.dismiss(); vm.confirm()
        assertEquals("garbage", rig.store.rawText)
        assertEquals(0, rig.store.clears)
        assertEquals(emptyList(), rig.folder.attempts)
        assertIs<PlannerBanner.DraftUnreadable>(rig.state.banner)
    }
}
