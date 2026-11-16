package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-07 step 2: the Stream kind in the wizard of [PlannerViewModel]. API fixed by these tests: `chooseKind("Stream")` opens `StreamForm("")`;
 * `PlannerState.responseStreamLine(stepId): String?` is the second line of a step that `PlanPairs` recognises as a response stream
 * ("Response stream (DLN) of <name>"), null for every other step; the draft file keeps no marker, so a restart shows the pair again by its names.
 */
class PlannerViewModelStreamTest {
    private val rig = PlannerRig()
    private val vm get() = rig.vm

    @AfterTest
    fun close() = rig.close()

    private fun streams() = rig.state.plan.steps.map { (it.action as MigrationAction.DeclareStream).name }

    @Test
    fun `choosing Stream opens an empty stream form with the tick off`() {
        vm.openWizard()
        vm.chooseKind("Stream")
        val details = assertIs<WizardState.Details>(rig.state.wizard)
        assertEquals(StreamForm(name = ""), details.form)
        assertEquals(listOf(FieldProblem("name", "Give the stream a name")), details.problems)
        assertTrue(!details.nextEnabled)
        assertEquals(null, details.pageMessage, "only a binding has a page message")
    }

    @Test
    fun `a stream is one step and the summary lists it`() {
        vm.openWizard(); vm.chooseKind("Stream"); vm.updateForm(StreamForm("telemetry")); vm.next()
        val summary = assertIs<WizardState.Summary>(rig.state.wizard)
        assertEquals(listOf("Declare stream telemetry"), summary.actions.map { it.description })
        vm.addToPlan()
        assertEquals(listOf("Declare stream telemetry"), rig.titles())
        assertEquals(listOf(1), rig.ids())
        assertEquals(WizardState.Closed, rig.state.wizard)
    }

    @Test
    fun `with the tick the summary says two steps and the plan gets both, request first, with the next ids`() {
        rig.queue("jobs")
        vm.openWizard(); vm.chooseKind("Stream"); vm.updateForm(StreamForm("telemetry", withResponse = true)); vm.next()
        val summary = assertIs<WizardState.Summary>(rig.state.wizard)
        assertEquals(listOf("Declare stream telemetry", "Declare stream telemetry-dln"), summary.actions.map { it.description })
        assertEquals("This adds 2 steps", PlannerTexts.summaryTitle(summary.actions.size))
        vm.addToPlan()
        assertEquals(listOf("Declare quorum queue jobs", "Declare stream telemetry", "Declare stream telemetry-dln"), rig.titles())
        assertEquals(listOf(1, 2, 3), rig.ids())
    }

    @Test
    fun `the suggestion follows the naming style of the plan`() {
        vm.setNamingStyle(NamingStyle.CAMEL)
        rig.stream("orders", withResponse = true)
        assertEquals(listOf("orders", "ordersDln"), streams())
    }

    @Test
    fun `a hint shows on the response name while the form is open and the pair is still added`() {
        vm.openWizard(); vm.chooseKind("Stream")
        vm.updateForm(StreamForm("orders", withResponse = true, responseName = "Orders_Replies"))
        val details = assertIs<WizardState.Details>(rig.state.wizard)
        assertEquals(listOf(FieldHint("responseName", "This name does not follow kebab-case, for example orders-replies")), details.hints)
        assertTrue(details.nextEnabled, "a hint never blocks")
        vm.next(); vm.addToPlan()
        assertEquals(listOf("orders", "Orders_Replies"), streams())
    }

    @Test
    fun `Back from the summary returns the form as it was, with the hand-made response name`() {
        vm.openWizard(); vm.chooseKind("Stream")
        val form = StreamForm("orders", withResponse = true, responseName = "orders-replies")
        vm.updateForm(form); vm.next(); vm.back()
        assertEquals(form, assertIs<WizardState.Details>(rig.state.wizard).form)
    }

    @Test
    fun `the pair is no routing - no exchange, no binding, no policy, no link of another kind`() {
        rig.stream("telemetry", withResponse = true)
        assertTrue(rig.state.plan.steps.all { it.action is MigrationAction.DeclareStream }, rig.titles().toString())
        assertEquals(emptyList(), rig.state.graph.graph.edges)
        assertEquals(setOf(PlanLinkKind.ResponseStream), rig.state.graph.links.map { it.kind }.toSet())
        assertEquals(emptyList(), rig.state.graph.notes)
    }

    // ---- recognising the pair ----

    @Test
    fun `the second line of the response stream says whose response it is, and no other step has one`() {
        rig.queue("jobs")
        rig.stream("telemetry", withResponse = true)
        val (jobs, request, response) = rig.state.plan.steps
        assertEquals("Response stream (DLN) of telemetry", rig.state.responseStreamLine(response.id))
        assertNull(rig.state.responseStreamLine(request.id))
        assertNull(rig.state.responseStreamLine(jobs.id))
        assertNull(rig.state.responseStreamLine(999), "an unknown step has none")
    }

    @Test
    fun `two streams that do not follow the rule are two streams`() {
        rig.stream("telemetry"); rig.stream("telemetry-replies")
        assertTrue(rig.state.plan.steps.all { rig.state.responseStreamLine(it.id) == null })
        assertEquals(emptyList(), rig.state.graph.links)
    }

    @Test
    fun `a pair added in two wizard runs is a pair too - recognition is by name, not by the way it was made`() {
        rig.stream("telemetry"); rig.stream("telemetry-dln")
        assertEquals("Response stream (DLN) of telemetry", rig.state.responseStreamLine(rig.ids()[1]))
        assertEquals(1, rig.state.graph.links.size)
    }

    @Test
    fun `a saved draft shows the pair again after a restart and the file holds no marker`() {
        rig.stream("telemetry", withResponse = true)
        vm.flush()
        val text = rig.store.rawText.orEmpty()
        assertTrue("declareStream" in text && "telemetry-dln" in text, text)
        assertEquals(
            dev.caerbannog.core.migrations.MigrationParser.exportToJson(
                "draft", "", listOf(MigrationAction.DeclareStream("telemetry"), MigrationAction.DeclareStream("telemetry-dln")),
            ),
            text,
            "the draft holds the two actions and nothing else: no marker of the pair",
        )
        rig.restart()
        val response = rig.state.plan.steps[1]
        assertEquals("Response stream (DLN) of telemetry", rig.state.responseStreamLine(response.id))
        assertEquals(1, rig.state.graph.links.size)
    }

    // ---- editing and removing ----

    @Test
    fun `a response stream is edited as an ordinary stream and the tick is not part of editing`() {
        rig.stream("telemetry", withResponse = true)
        val response = rig.state.plan.steps[1]
        vm.editStep(response.id)
        val details = assertIs<WizardState.Details>(rig.state.wizard)
        assertEquals(StreamForm(name = "telemetry-dln"), details.form)
        assertEquals(response.id, details.editingStepId)
        vm.updateForm(StreamForm(name = "telemetry-replies")); vm.next(); vm.addToPlan()
        assertEquals(listOf("telemetry", "telemetry-replies"), streams())
        assertEquals(listOf(1, 2), rig.ids(), "replaced in place")
        assertNull(rig.state.responseStreamLine(response.id), "the new name does not follow the rule, so it is no longer shown as a pair")
        assertEquals(emptyList(), rig.state.graph.links)
    }

    @Test
    fun `renaming the request stream by hand breaks the pair, which is a display matter only`() {
        rig.stream("telemetry", withResponse = true)
        vm.editStep(rig.ids()[0])
        vm.updateForm(StreamForm(name = "sensors")); vm.next(); vm.addToPlan()
        assertEquals(listOf("sensors", "telemetry-dln"), streams())
        assertNull(rig.state.responseStreamLine(rig.ids()[1]))
    }

    @Test
    fun `removing the stream leaves its response stream as an ordinary stream`() {
        rig.stream("telemetry", withResponse = true)
        vm.removeStep(rig.ids()[0])
        assertEquals(listOf("Declare stream telemetry-dln"), rig.titles())
        assertNull(rig.state.responseStreamLine(rig.ids().single()))
        assertEquals(emptyList(), rig.state.graph.links)
    }

    @Test
    fun `moving the steps keeps the pair`() {
        rig.stream("telemetry", withResponse = true)
        vm.moveStepUp(rig.ids()[1])
        assertEquals(listOf("telemetry-dln", "telemetry"), streams())
        assertEquals("Response stream (DLN) of telemetry", rig.state.responseStreamLine(rig.state.plan.steps[0].id))
    }

    // ---- problems of the plan ----

    @Test
    fun `a stream named like a queue of the plan cannot be added and says so on the name`() {
        rig.queue("telemetry")
        vm.openWizard(); vm.chooseKind("Stream"); vm.updateForm(StreamForm("telemetry"))
        val details = assertIs<WizardState.Details>(rig.state.wizard)
        assertEquals(listOf(FieldProblem("name", PlannerTexts.queueAndStreamOfOneName("telemetry"))), details.problems)
        vm.next()
        assertIs<WizardState.Details>(rig.state.wizard, "Next is refused")
    }

    @Test
    fun `adding the same stream twice is the existing plan problem, not a form problem`() {
        rig.stream("telemetry")
        rig.stream("telemetry")
        assertEquals(listOf("telemetry", "telemetry"), streams())
        assertEquals(1, rig.state.problems.size)
        assertTrue(!rig.state.problems.single().blocking, "the same settings twice is a note")
    }

    @Test
    fun `nothing reaches the broker from the stream step`() {
        rig.stream("telemetry", withResponse = true)
        assertNull(rig.state.saved, "nothing was saved as a migration")
        assertEquals(listOf("0004"), kotlinx.coroutines.runBlocking { rig.folder.listFiles() }.map { it.version }, "the folder holds no new file")
    }
}
