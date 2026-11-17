package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import dev.caerbannog.core.topology.BindingDestinationType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-10 step 2: the MQTT route kind in the wizard of [PlannerViewModel]. API fixed here: `chooseKind("MQTT route")` opens `MqttRouteForm("", "")`;
 * `PlannerTexts.BUILT_KINDS` ends with "MQTT route" and `kindDescription("MQTT route")` is not empty; for this form `Details.destinationSuggestions`
 * is the plan's and the broker's queues and streams (`FormContext.destinationSuggestions(QUEUE)`), `Details.pageMessage` is null; and
 * `PlannerState.stepTitle(stepId): String` / `stepDetail(stepId): String?` give the words of a step for the list: the route words for a route,
 * else the action's own description and detail (the kind word of a route comes from the plan's steps, then from what the broker has).
 */
class PlannerViewModelMqttTest {
    private val rig = PlannerRig(reader = FakeKnownTopologyReader(KnownTopology(queues = setOf("old-q"), streams = setOf("old-s"))))
    private val vm get() = rig.vm

    @AfterTest
    fun close() = rig.close()

    private fun addRoute(filter: String, to: String, kind: MqttDestinationKind? = null) =
        rig.add(MqttRouteForm(filter = filter, destination = to, destinationKind = kind))

    @Test
    fun `MQTT route is a built kind after Stream, in the wizard order, with a description`() {
        assertEquals("MQTT route", PlannerTexts.BUILT_KINDS.last())
        assertTrue(PlannerTexts.kindDescription("MQTT route").isNotEmpty())
    }

    @Test
    fun `choosing MQTT route opens an empty route form that cannot go on`() {
        vm.openWizard()
        vm.chooseKind("MQTT route")
        val details = assertIs<WizardState.Details>(rig.state.wizard)
        assertEquals(MqttRouteForm(filter = "", destination = ""), details.form)
        assertEquals(
            listOf(FieldProblem("filter", "Enter a topic filter"), FieldProblem("destination", PlannerTexts.BINDING_DESTINATION_MISSING)),
            details.problems,
        )
        assertTrue(!details.nextEnabled)
        assertNull(details.pageMessage)
    }

    @Test
    fun `Next is refused while the filter is refused and the route is added when it is fine`() {
        rig.add(StreamForm("telemetry"))
        vm.openWizard(); vm.chooseKind("MQTT route")
        vm.updateForm(MqttRouteForm("sensors/a.b", "telemetry", MqttDestinationKind.Stream))
        vm.next()
        assertIs<WizardState.Details>(rig.state.wizard)
        vm.updateForm(MqttRouteForm("sensors/+/temp", "telemetry", MqttDestinationKind.Stream))
        vm.next()
        assertEquals(listOf(MigrationAction.DeclareBinding("amq.topic", "telemetry", "sensors.*.temp")), assertIs<WizardState.Summary>(rig.state.wizard).actions)
        vm.addToPlan()
        assertEquals(2, rig.state.plan.steps.size)
        assertEquals(WizardState.Closed, rig.state.wizard)
    }

    @Test
    fun `the destination suggestions are the plan's and the broker's queues and streams`() {
        rig.add(StreamForm("telemetry"))
        vm.openWizard(); vm.chooseKind("MQTT route")
        val details = assertIs<WizardState.Details>(rig.state.wizard)
        assertEquals(
            listOf(Suggestion("telemetry", SuggestionSource.PLAN), Suggestion("old-q", SuggestionSource.BROKER), Suggestion("old-s", SuggestionSource.BROKER)),
            details.destinationSuggestions,
        )
    }

    // ---- the words of a step ----

    @Test
    fun `the list words of a route name the kind from the plan, then from the broker, else none`() {
        rig.add(StreamForm("telemetry"))
        rig.add(QueueForm("alarms"))
        addRoute("sensors/#", "telemetry")
        addRoute("alarms/#", "alarms")
        addRoute("a/b", "old-s")
        addRoute("c/d", "old-q")
        addRoute("e/f", "ghost")
        val titles = rig.state.plan.steps.map { rig.state.stepTitle(it.id) }
        assertEquals(
            listOf(
                "Declare stream telemetry", "Declare quorum queue alarms",
                "MQTT route sensors/# to stream telemetry", "MQTT route alarms/# to queue alarms",
                "MQTT route a/b to stream old-s", "MQTT route c/d to queue old-q", "MQTT route e/f to ghost",
            ),
            titles,
        )
        assertEquals("Binding on amq.topic with the key sensors.#", rig.state.stepDetail(rig.state.plan.steps[2].id))
        assertNull(rig.state.stepDetail(rig.state.plan.steps[0].id))
    }

    @Test
    fun `a step that is not a route keeps the words of its action`() {
        rig.add(QueueForm("alarms"))
        rig.add(BindingForm("amq.topic", "alarms", BindingDestinationType.QUEUE, "sensors..temp"))
        rig.add(BindingForm("events", "alarms", BindingDestinationType.QUEUE, "sensors.#"))
        for (step in rig.state.plan.steps) {
            assertEquals(step.action.description, rig.state.stepTitle(step.id))
            assertEquals(step.action.detail, rig.state.stepDetail(step.id))
        }
    }

    @Test
    fun `a binding written in the binding form on amq topic is worded as a route`() {
        rig.add(StreamForm("telemetry"))
        rig.add(BindingForm("amq.topic", "telemetry", BindingDestinationType.QUEUE, "sensors.#"))
        assertEquals("MQTT route sensors/# to stream telemetry", rig.state.stepTitle(rig.state.plan.steps.last().id))
    }

    // ---- an opened draft ----

    @Test
    fun `a saved route is a route again after a restart and Edit opens the route form`() {
        rig.add(StreamForm("telemetry"))
        addRoute("sensors/+/temp", "telemetry", MqttDestinationKind.Stream)
        val saved = rig.state.plan.steps.map { it.action }
        rig.restart()
        assertEquals(saved, rig.state.plan.steps.map { it.action })
        assertEquals("MQTT route sensors/+/temp to stream telemetry", rig.state.stepTitle(rig.state.plan.steps[1].id))
        vm.editStep(rig.state.plan.steps[1].id)
        val form = assertIs<MqttRouteForm>(assertIs<WizardState.Details>(rig.state.wizard).form)
        assertEquals("sensors/+/temp", form.filter)
        assertEquals("telemetry", form.destination)
    }

    @Test
    fun `editing a route's filter replaces the step in place`() {
        rig.add(StreamForm("telemetry"))
        addRoute("sensors/+/temp", "telemetry")
        val id = rig.state.plan.steps[1].id
        vm.editStep(id)
        val form = assertIs<MqttRouteForm>(assertIs<WizardState.Details>(rig.state.wizard).form)
        vm.updateForm(form.copy(filter = "sensors/#"))
        vm.next(); vm.addToPlan()
        assertEquals(id, rig.state.plan.steps[1].id)
        assertEquals(2, rig.state.plan.steps.size)
        assertEquals(MigrationAction.DeclareBinding("amq.topic", "telemetry", "sensors.#"), rig.state.plan.steps[1].action)
    }
}
