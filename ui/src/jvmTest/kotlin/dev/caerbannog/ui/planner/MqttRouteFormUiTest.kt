package dev.caerbannog.ui.planner

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.MqttDestinationKind
import dev.caerbannog.core.planner.MqttRouteForm
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.WizardState
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-10 step 2: the MQTT route kind, form, step row and graph label on the planner screen (mockup "4. MQTT route", step 10, 1280 x 800). RED until
 * the form view exists. Tag contract in [UiRig] (M9-10 paragraph). Pinned where the mockup is silent:
 *  - the kind card is drawn after Stream with `PlannerTexts.kindDescription("MQTT route")`;
 *  - the assumption sentence is drawn on the form from the first moment, whatever the filter says;
 *  - the key line and the matching topic are drawn only while the filter is accepted, and change as the user types;
 *  - a refused filter shows its text under the filter field and disables Next; the destination is a separate field with its own problem;
 *  - editing a saved route opens the same form with the filter as the user would type it;
 *  - the step row shows the words of the route and a second line (not the binding words); the graph edge shows the filter and "MQTT route".
 */
@OptIn(ExperimentalTestApi::class)
class MqttRouteFormUiTest {
    private fun DesktopComposeUiTest.toForm(r: UiRig) {
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:MQTT route").performClick()
        node("planWizard:next").performClick()
    }

    private fun DesktopComposeUiTest.fieldText(tag: String) =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun UiRig.form() = assertIs<MqttRouteForm>(assertIs<WizardState.Details>(state.wizard).form)

    @Test
    fun `page 1 draws MQTT route after Stream with its description and Next sends the kind`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:MQTT route").assertIsDisplayed()
        assertTrue(textOf("planWizard:kind:MQTT route").contains(PlannerTexts.kindDescription("MQTT route")), textOf("planWizard:kind:MQTT route"))
        val y = { kind: String -> node("planWizard:kind:$kind").fetchSemanticsNode().positionInRoot.y }
        assertTrue(y("Stream") < y("MQTT route"), "MQTT route is drawn after Stream")
        node("planWizard:kind:MQTT route").performClick()
        node("planWizard:next").performClick()
        assertTrue(r.events.calls.contains("chooseKind:MQTT route"), r.events.calls.toString())
        r.close()
    }

    @Test
    fun `the form starts with both fields empty, the assumption on show, Next disabled and no key or topic`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toForm(r)
        assertEquals("", fieldText("planForm:filter"))
        assertEquals("", fieldText("planForm:destination"))
        assertEquals(PlannerTexts.MQTT_EXCHANGE_ASSUMPTION, textOf("planForm:mqttAssumption"))
        assertNotDrawn("planForm:mqttKey")
        assertNotDrawn("planForm:mqttTopic")
        node("planWizard:next").assertIsNotEnabled()
        node("planForm:destinationKind:queue").assertIsDisplayed()
        node("planForm:destinationKind:stream").assertIsDisplayed()
        r.close()
    }

    @Test
    fun `typing a good filter shows the key and a matching topic and enables Next with a destination`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toForm(r)
        node("planForm:filter").performTextInput("sensors/+/temp")
        node("planForm:destination").performTextInput("telemetry")
        assertEquals(PlannerTexts.mqttRouteSaves("sensors.*.temp"), textOf("planForm:mqttKey"))
        assertTrue(textOf("planForm:mqttTopic").contains("sensors/item/temp"), textOf("planForm:mqttTopic"))
        assertEquals("sensors/+/temp", r.form().filter)
        node("planWizard:next").assertIsEnabled()
        node("planForm:filter").performTextReplacement("sensors/#")
        assertEquals(PlannerTexts.mqttRouteSaves("sensors.#"), textOf("planForm:mqttKey"))
        assertTrue(textOf("planForm:mqttTopic").contains("sensors/test"), textOf("planForm:mqttTopic"))
        r.close()
    }

    @Test
    fun `a refused filter shows its reason under the field and the key and topic go away`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toForm(r)
        node("planForm:destination").performTextInput("telemetry")
        for ((filter, text) in listOf(
            "sensors/v1.2/temp" to "A level cannot contain a dot: RabbitMQ would read it as another level",
            "sensors/*/temp" to "A level cannot contain *: use + for a whole level",
            "sensors//temp" to "A level cannot be empty",
            "sensors/#/temp" to "# must be the last level",
            "sens+/temp" to "+ must stand for a whole level",
        )) {
            node("planForm:filter").performTextReplacement(filter)
            assertEquals(text, textOf("planForm:error:filter"), "filter $filter")
            assertNotDrawn("planForm:mqttKey")
            assertNotDrawn("planForm:mqttTopic")
            node("planWizard:next").assertIsNotEnabled()
            assertEquals(PlannerTexts.MQTT_EXCHANGE_ASSUMPTION, textOf("planForm:mqttAssumption"))
        }
        r.close()
    }

    @Test
    fun `a missing destination is a problem of its own and the filter keeps no error`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toForm(r)
        node("planForm:filter").performTextInput("sensors/#")
        assertEquals(PlannerTexts.BINDING_DESTINATION_MISSING, textOf("planForm:error:destination"))
        assertNotDrawn("planForm:error:filter")
        node("planWizard:next").assertIsNotEnabled()
        r.close()
    }

    @Test
    fun `the kind choice sends the kind and the form keeps it`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toForm(r)
        node("planForm:destinationKind:stream").performClick()
        assertEquals(MqttDestinationKind.Stream, r.form().destinationKind)
        assertEquals(true, node("planForm:destinationKind:stream").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        node("planForm:destinationKind:queue").performClick()
        assertEquals(MqttDestinationKind.Queue, r.form().destinationKind)
        r.close()
    }

    @Test
    fun `the form from Next to Add writes one binding on amq topic`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { stream("telemetry") }
        toForm(r)
        node("planForm:destinationKind:stream").performClick()
        node("planForm:filter").performTextInput("sensors/+/temp")
        node("planForm:destination").performTextInput("telemetry")
        node("planWizard:next").performClick()
        assertDrawn("planWizard:summary")
        node("planWizard:add").performClick()
        val last = r.state.plan.steps.last().action
        assertEquals("declareBinding", last.actionName)
        assertEquals("sensors.*.temp", (last as dev.caerbannog.core.migrations.MigrationAction.DeclareBinding).routingKey)
        assertEquals("amq.topic", last.exchange)
        r.close()
    }

    // ---- the step row ----

    @Test
    fun `the step row shows the words of a route and its second line`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { stream("telemetry"); mqttRoute("sensors/+/temp", "telemetry", MqttDestinationKind.Stream) }
        showPlanner(r)
        val row = textOf("planStep:1")
        assertTrue(row.contains("MQTT route sensors/+/temp to stream telemetry"), row)
        assertTrue(row.contains("Binding on amq.topic with the key sensors.*.temp"), row)
        assertTrue(!row.contains("Bind amq.topic"), "the binding words are gone: $row")
        r.close()
    }

    @Test
    fun `a route of a reopened draft is a route again, named from what the broker has`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology(queues = setOf("alarms")))) {
            store.putDraft("", listOf(dev.caerbannog.core.migrations.MigrationAction.DeclareBinding("amq.topic", "alarms", "alarms.#")))
        }
        showPlanner(r)
        assertTrue(textOf("planStep:0").contains("MQTT route alarms/# to queue alarms"), textOf("planStep:0"))
        r.close()
    }

    @Test
    fun `a binding on amq topic that is not a route is drawn as a binding`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig {
            store.putDraft("", listOf(dev.caerbannog.core.migrations.MigrationAction.DeclareBinding("amq.topic", "alarms", "sensors..temp")))
        }
        showPlanner(r)
        assertTrue(textOf("planStep:0").contains("Bind amq.topic to alarms with sensors..temp"), textOf("planStep:0"))
        assertTrue(!textOf("planStep:0").contains("MQTT route"), textOf("planStep:0"))
        r.close()
    }

    @Test
    fun `Edit on a route opens the route form with the filter as typed`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { stream("telemetry"); mqttRoute("sensors/+/temp", "telemetry") }
        showPlanner(r)
        node("planStepMenu:1").performClick()
        node("planStepMenu:1:edit").performClick()
        assertEquals("sensors/+/temp", fieldText("planForm:filter"))
        assertEquals("telemetry", fieldText("planForm:destination"))
        r.close()
    }

    // ---- the graph ----

    @Test
    fun `the review graph labels a route's edge with its filter and the words MQTT route`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { stream("telemetry"); mqttRoute("sensors/+/temp", "telemetry", MqttDestinationKind.Stream) }
        showPlanner(r)
        val edge = r.state.graph.graph.edges.single().edge
        assertEquals("sensors/+/temp", textOf("planGraphEdgeLabel:${edge.id}"))
        assertEquals(PlannerTexts.MQTT_ROUTE_LABEL, textOf("planGraphEdgeTag:${edge.id}"))
        r.close()
    }

    @Test
    fun `a binding that is not a route has no edge label`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { queue("orders"); exchange("events"); binding("events", "orders", "order.created") }
        showPlanner(r)
        val edge = r.state.graph.graph.edges.single().edge
        assertNotDrawn("planGraphEdgeLabel:${edge.id}")
        assertNotDrawn("planGraphEdgeTag:${edge.id}")
        r.close()
    }
}
