package dev.caerbannog.ui.planner

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.NamingStyle
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.StreamForm
import dev.caerbannog.core.planner.WizardState
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-07 step 2: the Stream kind and form in the wizard (mockup section 4, "Stream"), 1280 x 800. RED until the stream form view exists. The tag
 * contract is in [UiRig]. Behaviour pinned where the mockup is silent:
 *  - page 1 draws Stream as a fourth kind, after Binding, with the description `kindDescription("Stream")`;
 *  - the tick `planForm:responseTick` is a toggle, off at first; the response stream's name field and the fixed note appear only while it is on;
 *  - the response name field shows the effective name (the suggestion until the user edits it, then the user's text);
 *  - the tick is for adding: editing a step offers no tick (an edit replaces one step);
 *  - the MQTT ticks and the retention block are not drawn (M9-07b, M9-08).
 */
@OptIn(ExperimentalTestApi::class)
class StreamFormUiTest {
    private fun DesktopComposeUiTest.toStreamForm(r: UiRig) {
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:Stream").performClick()
        node("planWizard:next").performClick()
    }

    private fun DesktopComposeUiTest.fieldText(tag: String) =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun UiRig.form() = assertIs<StreamForm>(assertIs<WizardState.Details>(state.wizard).form)

    // ---- page 1 ----

    @Test
    fun `page 1 draws Stream after Binding with its description and Next sends the kind`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:Stream").assertIsDisplayed()
        assertTrue(textOf("planWizard:kind:Stream").contains(PlannerTexts.kindDescription("Stream")), textOf("planWizard:kind:Stream"))
        assertTrue(textOf("planWizard:kind:Stream").contains("Optionally with its response stream (DLN)"))
        val y = { kind: String -> node("planWizard:kind:$kind").fetchSemanticsNode().positionInRoot.y }
        assertTrue(y("Binding") < y("Stream"), "Stream is drawn after Binding")
        node("planWizard:kind:Stream").performClick()
        assertEquals(true, node("planWizard:kind:Stream").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        node("planWizard:next").performClick()
        assertTrue(r.events.calls.contains("chooseKind:Stream"), r.events.calls.toString())
        assertEquals(StreamForm(name = ""), r.form())
        r.close()
    }

    @Test
    fun `Down from Binding selects Stream, and Policy is still not offered`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:Binding").performClick()
        node("planWizard:kind:Binding").requestFocus()
        node("planWizard:kind:Binding").performKeyInput { pressKey(Key.DirectionDown) }
        assertEquals(true, node("planWizard:kind:Stream").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertNotDrawn("planWizard:kind:Policy")
        r.close()
    }

    // ---- page 2 ----

    @Test
    fun `the form asks for a name and offers the tick, off, with no response name field and no MQTT tick`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        assertDrawn("planForm:name")
        node("planForm:responseTick").assertIsDisplayed().assertIsOff()
        assertTrue(textOf("planForm:responseTick").contains(PlannerTexts.RESPONSE_STREAM_TICK), textOf("planForm:responseTick"))
        assertNotDrawn("planForm:responseName")
        assertNotDrawn("planForm:responseNote")
        // nothing of M9-07b or M9-08 is drawn in this task
        assertNotDrawn("planForm:mqttAnswers")
        assertNotDrawn("planForm:retention")
        node("planWizard:next").assertIsNotEnabled()
        assertEquals(PlannerTexts.RESPONSE_STREAM_TICK, "Also create its response stream (DLN)")
        r.close()
    }

    @Test
    fun `a missing name says so under the field`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        assertEquals("Give the stream a name", textOf("planForm:error:name"))
        r.close()
    }

    @Test
    fun `ticking sends the form with the tick on, shows the name field with the suggestion and the neutral note`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:name").performTextInput("orders")
        node("planForm:responseTick").performClick()
        node("planForm:responseTick").assertIsOn()
        assertEquals(StreamForm(name = "orders", withResponse = true), r.form())
        assertEquals("orders-dln", fieldText("planForm:responseName"))
        assertEquals(PlannerTexts.RESPONSE_STREAM_NOTE, textOf("planForm:responseNote"))
        assertEquals(
            "A response stream is an ordinary stream, named after its request stream. Ticking this declares both streams and adds no routing between them. RabbitMQ does not move messages into it by itself: your applications write to it.",
            textOf("planForm:responseNote"),
        )
        node("planForm:responseNote").assertIsDisplayed()
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `unticking hides the name field and the note again, and a typed name is kept for the next tick`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:name").performTextInput("orders")
        node("planForm:responseTick").performClick()
        node("planForm:responseName").performTextReplacement("orders-replies")
        node("planForm:responseTick").performClick()
        node("planForm:responseTick").assertIsOff()
        assertNotDrawn("planForm:responseName")
        assertNotDrawn("planForm:responseNote")
        node("planForm:responseTick").performClick()
        assertEquals("orders-replies", fieldText("planForm:responseName"))
        r.close()
    }

    @Test
    fun `the suggestion follows the stream name until the response name is edited by hand`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:name").performTextInput("orders")
        node("planForm:responseTick").performClick()
        node("planForm:name").performTextReplacement("invoices")
        assertEquals("invoices-dln", fieldText("planForm:responseName"))
        assertEquals(null, r.form().responseName, "still following")
        node("planForm:responseName").performTextReplacement("invoices-replies")
        assertEquals("invoices-replies", r.form().responseName)
        node("planForm:name").performTextReplacement("bills")
        assertEquals("invoices-replies", fieldText("planForm:responseName"))
        r.close()
    }

    @Test
    fun `the suggestion uses the suffix of the naming style`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { vm.setNamingStyle(NamingStyle.CAMEL) }
        toStreamForm(r)
        node("planForm:name").performTextInput("orders")
        node("planForm:responseTick").performClick()
        assertEquals("ordersDln", fieldText("planForm:responseName"))
        r.close()
    }

    @Test
    fun `a response name of its own gets a hint under its field and Next stays enabled`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:name").performTextInput("orders")
        node("planForm:responseTick").performClick()
        node("planForm:responseName").performTextReplacement("Orders_Replies")
        assertEquals("This name does not follow kebab-case, for example orders-replies", textOf("planForm:hint:responseName"))
        assertNotDrawn("planForm:hint:name")
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `both names equal is an error on the response field and blocks Next`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:name").performTextInput("orders")
        node("planForm:responseTick").performClick()
        node("planForm:responseName").performTextReplacement("orders")
        assertEquals("The response stream needs a name of its own", textOf("planForm:error:responseName"))
        node("planWizard:next").assertIsNotEnabled()
        r.close()
    }

    @Test
    fun `an empty response name is an error on its field`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:name").performTextInput("orders")
        node("planForm:responseTick").performClick()
        node("planForm:responseName").performTextReplacement("")
        assertEquals("Give the response stream a name", textOf("planForm:error:responseName"))
        node("planWizard:next").assertIsNotEnabled()
        r.close()
    }

    @Test
    fun `a name the plan uses for a queue is an error on the stream name`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { queue("telemetry") }
        toStreamForm(r)
        node("planForm:name").performTextInput("telemetry")
        assertEquals("The plan declares telemetry as a queue and as a stream", textOf("planForm:error:name"))
        node("planWizard:next").assertIsNotEnabled()
        r.close()
    }

    @Test
    fun `a stream name the broker already has draws no naming hint`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology(streams = setOf("Legacy_Events"))))
        toStreamForm(r)
        node("planForm:name").performTextInput("Legacy_Events")
        assertNotDrawn("planForm:hint:name")
        r.close()
    }

    @Test
    fun `the tick works from the keyboard`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:name").performTextInput("orders")
        node("planForm:responseTick").requestFocus()
        node("planForm:responseTick").performKeyInput { pressKey(Key.Spacebar) }
        node("planForm:responseTick").assertIsOn()
        r.close()
    }

    // ---- page 3 and the plan ----

    @Test
    fun `the summary lists both steps and says two, and Add to plan puts both in the list`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:name").performTextInput("telemetry")
        node("planForm:responseTick").performClick()
        node("planWizard:next").performClick()
        val summary = textOf("planWizard:summary")
        assertTrue(summary.contains("This adds 2 steps"), summary)
        assertTrue(summary.contains("Declare stream telemetry") && summary.contains("Declare stream telemetry-dln"), summary)
        node("planWizard:add").performClick()
        assertEquals(listOf("Declare stream telemetry", "Declare stream telemetry-dln"), r.state.plan.steps.map { it.title })
        assertTrue(textOf("planStep:0").contains("Declare stream telemetry"))
        assertTrue(textOf("planStep:1").contains("Declare stream telemetry-dln"))
        r.close()
    }

    @Test
    fun `without the tick the summary says one step`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        node("planForm:name").performTextInput("telemetry")
        node("planWizard:next").performClick()
        assertTrue(textOf("planWizard:summary").contains("This adds 1 step"), textOf("planWizard:summary"))
        r.close()
    }

    // ---- editing ----

    @Test
    fun `editing a stream offers no tick and no response name`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { stream("telemetry") }
        showPlanner(r)
        node("planStepMenu:0").performClick()
        node("planStepMenu:0:edit").performClick()
        assertEquals(StreamForm(name = "telemetry"), r.form())
        assertDrawn("planForm:name")
        assertNotDrawn("planForm:responseTick")
        assertNotDrawn("planForm:responseName")
        r.close()
    }

    @Test
    fun `a response stream of a pair opens as an ordinary stream`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { stream("telemetry", withResponse = true) }
        showPlanner(r)
        node("planStepMenu:1").performClick()
        node("planStepMenu:1:edit").performClick()
        assertEquals(StreamForm(name = "telemetry-dln"), r.form())
        assertNotDrawn("planForm:responseTick")
        node("planForm:name").performTextReplacement("telemetry-replies")
        node("planWizard:next").performClick()
        node("planWizard:add").performClick()
        assertEquals(listOf("Declare stream telemetry", "Declare stream telemetry-replies"), r.state.plan.steps.map { it.title })
        r.close()
    }
}
