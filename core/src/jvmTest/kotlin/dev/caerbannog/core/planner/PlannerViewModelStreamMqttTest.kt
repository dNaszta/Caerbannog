package dev.caerbannog.core.planner

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-07b step 2: the MQTT ticks in the wizard of [PlannerViewModel]. API fixed here (beyond the brief): `WizardState.Summary.note: String?` (new field,
 * default null) is `PlannerTexts.SERVICE_NOT_PLANNED` when an answers route is among the summary's own actions (the shared rule of `PlanPairs`),
 * otherwise null; the neutral name is on purpose (M9-09 reuses it).
 */
class PlannerViewModelStreamMqttTest {
    private val rig = PlannerRig()
    private val vm get() = rig.vm

    @AfterEach
    fun close() = rig.close()

    private fun open(form: StreamForm) {
        vm.openWizard()
        vm.chooseKind("Stream")
        vm.updateForm(form)
    }

    private fun details() = assertIs<WizardState.Details>(rig.state.wizard)
    private fun summary() = assertIs<WizardState.Summary>(rig.state.wizard)

    @Test
    fun `an empty tick is a problem under its field and Next stays disabled`() {
        open(StreamForm("device-commands", withResponse = true, answersTopic = ""))
        assertEquals(listOf(FieldProblem("answersTopic", "Enter a topic filter")), details().problems)
        assertTrue(!details().nextEnabled)
        vm.next()
        assertIs<WizardState.Details>(rig.state.wizard)
    }

    @Test
    fun `the three outline rows block Next with the texts of the route form`() {
        val rows = listOf(
            StreamForm("s", true, null, answersTopic = "") to FieldProblem("answersTopic", "Enter a topic filter"),
            StreamForm("s", true, null, answersTopic = "devices/#/answer") to FieldProblem("answersTopic", "# must be the last level"),
            StreamForm("s", true, null, requestsTopic = "sens+/x") to FieldProblem("requestsTopic", "+ must stand for a whole level"),
        )
        for ((form, problem) in rows) {
            open(form)
            assertEquals(listOf(problem), details().problems)
            assertTrue(!details().nextEnabled, "Next for $form")
            vm.cancelWizard()
        }
    }

    @Test
    fun `the summary of the answers tick has three steps and the sentence`() {
        open(StreamForm("device-commands", withResponse = true, answersTopic = "devices/+/answer"))
        vm.next()
        assertEquals(3, summary().actions.size)
        assertEquals("This adds 3 steps", PlannerTexts.summaryTitle(summary().actions.size))
        assertEquals("Your own service turns a request into an MQTT message; the planner does not plan that.", summary().note)
        assertEquals(PlannerTexts.SERVICE_NOT_PLANNED, summary().note)
    }

    @Test
    fun `no sentence without an answers route`() {
        open(StreamForm("device-commands"))
        vm.next()
        assertNull(summary().note)
        vm.cancelWizard()
        open(StreamForm("device-commands", withResponse = true))
        vm.next()
        assertNull(summary().note, "the plain pair")
        vm.cancelWizard()
        open(StreamForm("device-commands", withResponse = true, requestsTopic = "commands/#"))
        vm.next()
        assertEquals(3, summary().actions.size)
        assertNull(summary().note, "a route into the request stream says nothing")
    }

    @Test
    fun `both ticks give four steps and the sentence once`() {
        open(StreamForm("device-commands", withResponse = true, answersTopic = "devices/+/answer", requestsTopic = "commands/#"))
        vm.next()
        assertEquals(4, summary().actions.size)
        assertEquals(PlannerTexts.SERVICE_NOT_PLANNED, summary().note)
    }

    @Test
    fun `unticking the pair gives the stream only and no sentence`() {
        val form = StreamForm("device-commands", withResponse = true, answersTopic = "devices/+/answer")
        open(form)
        vm.updateForm(form.withPair(false))
        assertEquals(emptyList(), details().problems)
        vm.next()
        assertEquals(1, summary().actions.size)
        assertNull(summary().note)
    }

    @Test
    fun `going back and changing the tick changes the sentence`() {
        open(StreamForm("device-commands", withResponse = true, answersTopic = "devices/+/answer"))
        vm.next()
        assertEquals(PlannerTexts.SERVICE_NOT_PLANNED, summary().note)
        vm.back()
        vm.updateForm(StreamForm("device-commands", withResponse = true))
        vm.next()
        assertNull(summary().note)
    }

    @Test
    fun `Add to plan puts the steps in the plan in order`() {
        open(StreamForm("device-commands", withResponse = true, answersTopic = "devices/+/answer", requestsTopic = "commands/#"))
        vm.next()
        vm.addToPlan()
        assertEquals(
            listOf(
                "Declare stream device-commands",
                "Declare stream device-commands-dln",
                "MQTT route devices/+/answer to stream device-commands-dln",
                "MQTT route commands/# to stream device-commands",
            ),
            rig.state.plan.steps.map { rig.state.stepTitle(it.id) },
        )
        assertEquals(PlanGraphNote("q:device-commands", "e:amq.topic", PlannerTexts.SERVICE_NOT_PLANNED), rig.state.graph.notes.single())
    }

    @Test
    fun `the routes of a pair are ordinary steps that can be removed one by one`() {
        rig.add(StreamForm("device-commands", withResponse = true, answersTopic = "devices/+/answer"))
        val route = rig.state.plan.steps.last()
        vm.removeStep(route.id)
        assertEquals(listOf("Declare stream device-commands", "Declare stream device-commands-dln"), rig.titles())
        assertEquals(emptyList(), rig.state.problems)
        assertEquals(emptyList(), rig.state.graph.notes)
    }

    @Test
    fun `a reopened pair's route opens as an MQTT route form`() {
        rig.add(StreamForm("device-commands", withResponse = true, answersTopic = "devices/+/answer"))
        vm.editStep(rig.state.plan.steps.last().id)
        assertEquals(MqttRouteForm(filter = "devices/+/answer", destination = "device-commands-dln"), details().form)
    }

    @Test
    fun `editing a stream of the plan carries no tick`() {
        rig.add(StreamForm("telemetry"))
        vm.editStep(rig.state.plan.steps.single().id)
        assertEquals(StreamForm(name = "telemetry"), details().form)
    }

    // ---- step 2 addendum (architect's gate) ----

    @Test
    fun `the route line of the summary reads to stream and the sentence is the note`() {
        open(StreamForm("device-commands", withResponse = true, answersTopic = "devices/+/answer"))
        vm.next()
        val s = summary()
        val titles = s.actions.map { rig.state.wordsOf(it, s.actions).title }
        assertEquals(
            listOf("Declare stream device-commands", "Declare stream device-commands-dln", "MQTT route devices/+/answer to stream device-commands-dln"),
            titles,
        )
    }

    @Test
    fun `a single route form into a planned response stream has no summary note, and the graph has the note after Add`() {
        rig.add(StreamForm("device-commands", withResponse = true))
        assertEquals(emptyList(), rig.state.graph.notes)
        vm.openWizard()
        vm.chooseKind("MQTT route")
        vm.updateForm(MqttRouteForm("devices/+/answer", "device-commands-dln", MqttDestinationKind.Stream))
        vm.next()
        assertNull(summary().note, "the sentence belongs to the pair's tick, not to a route form")
        vm.addToPlan()
        assertEquals(listOf(PlanGraphNote("q:device-commands", "e:amq.topic", PlannerTexts.SERVICE_NOT_PLANNED)), rig.state.graph.notes)
    }
}
