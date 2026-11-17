package dev.caerbannog.ui.planner

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.StreamForm
import dev.caerbannog.core.planner.WizardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-07b step 2: the two MQTT ticks of the stream form (mockup section 4, "Stream"), 1280 x 800. RED until the ticks exist. Tags fixed here (beyond the
 * brief's five, for the architect to confirm):
 *  - `planForm:answersOverMqtt` and `planForm:requestsOverMqtt`: toggles (role checkbox, On/Off), drawn only while the form ADDS a stream AND the pair
 *    tick is on; the text of each is its tick label "Answers arrive over MQTT" / "Requests are also recorded from an MQTT topic", and its sub-line
 *    (`planForm:answersSubline`, `planForm:requestsSubline`) says "Adds an MQTT route into the response stream." / "... into the request stream.";
 *  - `planForm:answersTopic` / `planForm:requestsTopic`: text fields, drawn only while their tick is on, label "Topic filter of the answers" / "Topic filter of
 *    the requests"; the answers field has the PLACEHOLDER `devices/+/answer` (shown while focused and empty, never the value), the requests field none;
 *  - `planForm:error:answersTopic` / `planForm:error:requestsTopic` (the form's own FormField tag rule); `planForm:answersKey` / `planForm:requestsKey`:
 *    `PlannerTexts.mqttRouteSaves(key)`, drawn only while that filter is accepted;
 *  - `planForm:mqttAssumption`: `PlannerTexts.MQTT_EXCHANGE_ASSUMPTION`, ONCE while either tick is on, none otherwise;
 *  - `planWizard:summaryNote`: `WizardState.Summary.note`, drawn on page 3 only when it is not null.
 */
@OptIn(ExperimentalTestApi::class)
class StreamFormMqttTicksUiTest {
    private fun DesktopComposeUiTest.toStreamForm(r: UiRig) {
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:Stream").performClick()
        node("planWizard:next").performClick()
    }

    private fun DesktopComposeUiTest.fieldText(tag: String) =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun UiRig.form() = assertIs<StreamForm>(assertIs<WizardState.Details>(state.wizard).form)

    private fun DesktopComposeUiTest.countTagged(tag: String) =
        onAllNodes(SemanticsMatcher("tag is $tag") { it.config.getOrNull(SemanticsProperties.TestTag) == tag }, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun DesktopComposeUiTest.pairOn(r: UiRig, name: String = "device-commands") {
        toStreamForm(r)
        node("planForm:name").performTextInput(name)
        node("planForm:responseTick").performClick()
    }

    // ---- offered only with the pair, off until ticked ----

    @Test
    fun `no MQTT tick is drawn before the pair is ticked`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toStreamForm(r)
        listOf("answersOverMqtt", "requestsOverMqtt", "answersTopic", "requestsTopic", "answersSubline", "requestsSubline", "mqttAssumption").forEach {
            assertNotDrawn("planForm:$it")
        }
        r.close()
    }

    @Test
    fun `ticking the pair offers both MQTT ticks, off, with their labels and sub-lines, and no field or assumption yet`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        pairOn(r)
        node("planForm:answersOverMqtt").assertIsDisplayed().assertIsOff()
        node("planForm:requestsOverMqtt").assertIsDisplayed().assertIsOff()
        assertTrue(textOf("planForm:answersOverMqtt").contains("Answers arrive over MQTT"), textOf("planForm:answersOverMqtt"))
        assertTrue(textOf("planForm:requestsOverMqtt").contains("Requests are also recorded from an MQTT topic"), textOf("planForm:requestsOverMqtt"))
        assertEquals("Adds an MQTT route into the response stream.", textOf("planForm:answersSubline"))
        assertEquals("Adds an MQTT route into the request stream.", textOf("planForm:requestsSubline"))
        assertNotDrawn("planForm:answersTopic")
        assertNotDrawn("planForm:requestsTopic")
        assertNotDrawn("planForm:mqttAssumption")
        assertNull(r.form().answersTopic)
        assertNull(r.form().requestsTopic)
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `the ticks are for adding, editing a stream offers none`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { stream("telemetry", withResponse = true) }
        showPlanner(r)
        node("planStepMenu:0").performClick()
        node("planStepMenu:0:edit").performClick()
        assertNotDrawn("planForm:answersOverMqtt")
        assertNotDrawn("planForm:requestsOverMqtt")
        r.close()
    }

    // ---- the answers tick ----

    @Test
    fun `ticking the answers tick starts an empty filter that says Enter a topic filter and blocks Next`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        pairOn(r)
        node("planForm:answersOverMqtt").performClick()
        node("planForm:answersOverMqtt").assertIsOn()
        assertEquals("", r.form().answersTopic, "on with an empty filter, not null and not a guess")
        assertEquals("", fieldText("planForm:answersTopic"))
        assertTrue(textOf("planForm:answersTopic").contains("Topic filter of the answers"), textOf("planForm:answersTopic"))
        assertEquals("Enter a topic filter", textOf("planForm:error:answersTopic"))
        assertNotDrawn("planForm:answersKey")
        node("planWizard:next").assertIsNotEnabled()
        assertEquals(PlannerTexts.MQTT_EXCHANGE_ASSUMPTION, textOf("planForm:mqttAssumption"))
        r.close()
    }

    @Test
    fun `the answers field shows devices slash plus slash answer as a placeholder only`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        pairOn(r)
        node("planForm:answersOverMqtt").performClick()
        node("planForm:answersTopic").requestFocus()
        waitForIdle()
        assertTrue(textOf("planForm:answersTopic").contains("devices/+/answer"), "the placeholder is not shown: ${textOf("planForm:answersTopic")}")
        assertEquals("", fieldText("planForm:answersTopic"), "the placeholder is not the value")
        assertEquals("", r.form().answersTopic)
        node("planForm:answersTopic").performTextInput("a")
        waitForIdle()
        assertEquals("a", fieldText("planForm:answersTopic"))
        r.close()
    }

    @Test
    fun `the requests field has no placeholder`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        pairOn(r)
        node("planForm:requestsOverMqtt").performClick()
        node("planForm:requestsTopic").requestFocus()
        waitForIdle()
        assertTrue(textOf("planForm:requestsTopic").contains("Topic filter of the requests"), textOf("planForm:requestsTopic"))
        assertTrue(!textOf("planForm:requestsTopic").contains("devices/+/answer"), textOf("planForm:requestsTopic"))
        assertEquals("", fieldText("planForm:requestsTopic"))
        assertEquals("", r.form().requestsTopic)
        r.close()
    }

    @Test
    fun `typing a good filter shows the key it is saved as and enables Next`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        pairOn(r)
        node("planForm:answersOverMqtt").performClick()
        node("planForm:answersTopic").performTextInput("devices/+/answer")
        assertEquals("devices/+/answer", r.form().answersTopic)
        assertEquals(PlannerTexts.mqttRouteSaves("devices.*.answer"), textOf("planForm:answersKey"))
        assertNotDrawn("planForm:error:answersTopic")
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `the three outline rows show their message under the field and disable Next`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        pairOn(r)
        node("planForm:answersOverMqtt").performClick()
        node("planForm:answersTopic").performTextInput("devices/#/answer")
        assertEquals("# must be the last level", textOf("planForm:error:answersTopic"))
        node("planWizard:next").assertIsNotEnabled()
        node("planForm:answersTopic").performTextReplacement("devices/+/answer")
        node("planForm:requestsOverMqtt").performClick()
        node("planForm:requestsTopic").performTextInput("sens+/x")
        assertEquals("+ must stand for a whole level", textOf("planForm:error:requestsTopic"))
        assertNotDrawn("planForm:error:answersTopic")
        node("planWizard:next").assertIsNotEnabled()
        r.close()
    }

    // ---- the assumption, once ----

    @Test
    fun `the assumption is drawn once for one tick, once for two, and goes when both are off`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        pairOn(r)
        node("planForm:requestsOverMqtt").performClick()
        assertEquals(1, countTagged("planForm:mqttAssumption"))
        node("planForm:answersOverMqtt").performClick()
        assertEquals(1, countTagged("planForm:mqttAssumption"), "two ticks, one sentence")
        assertEquals(PlannerTexts.MQTT_EXCHANGE_ASSUMPTION, textOf("planForm:mqttAssumption"))
        node("planForm:requestsOverMqtt").performClick()
        assertEquals(1, countTagged("planForm:mqttAssumption"), "the answers tick is still on")
        node("planForm:answersOverMqtt").performClick()
        assertEquals(0, countTagged("planForm:mqttAssumption"))
        r.close()
    }

    // ---- unticking ----

    @Test
    fun `unticking a tick removes its field and its filter from the state, the other tick stays`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        pairOn(r)
        node("planForm:answersOverMqtt").performClick()
        node("planForm:answersTopic").performTextInput("devices/+/answer")
        node("planForm:requestsOverMqtt").performClick()
        node("planForm:requestsTopic").performTextInput("commands/#")
        node("planForm:answersOverMqtt").performClick()
        node("planForm:answersOverMqtt").assertIsOff()
        assertNotDrawn("planForm:answersTopic")
        assertNull(r.form().answersTopic)
        assertEquals("commands/#", r.form().requestsTopic)
        assertEquals("commands/#", fieldText("planForm:requestsTopic"))
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `unticking the pair removes the ticks and clears both, and ticking it again starts clean`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        pairOn(r)
        node("planForm:answersOverMqtt").performClick()
        node("planForm:answersTopic").performTextInput("devices/+/answer")
        node("planForm:requestsOverMqtt").performClick()
        node("planForm:requestsTopic").performTextInput("commands/#")
        node("planForm:responseTick").performClick()
        listOf("answersOverMqtt", "requestsOverMqtt", "answersTopic", "requestsTopic", "mqttAssumption", "answersSubline").forEach { assertNotDrawn("planForm:$it") }
        assertEquals(StreamForm(name = "device-commands"), r.form())
        node("planForm:responseTick").performClick()
        node("planForm:answersOverMqtt").assertIsOff()
        node("planForm:requestsOverMqtt").assertIsOff()
        assertNotDrawn("planForm:answersTopic")
        assertNull(r.form().answersTopic)
        assertNull(r.form().requestsTopic)
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `unticking the pair with a refused filter unblocks Next`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        pairOn(r)
        node("planForm:answersOverMqtt").performClick()
        node("planWizard:next").assertIsNotEnabled()
        node("planForm:responseTick").performClick()
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    // ---- page 3 and the plan ----

    @Test
    fun `with the answers tick the summary has three steps and says what the planner does not plan`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        pairOn(r)
        node("planForm:answersOverMqtt").performClick()
        node("planForm:answersTopic").performTextInput("devices/+/answer")
        node("planWizard:next").performClick()
        val summary = textOf("planWizard:summary")
        assertTrue(summary.contains("This adds 3 steps"), summary)
        // The words of the route in the summary (the kind word "stream" is not pinned here: the list below says it, see the report)
        assertTrue(summary.contains("MQTT route devices/+/answer to") && summary.contains("device-commands-dln"), summary)
        assertEquals("Your own service turns a request into an MQTT message; the planner does not plan that.", textOf("planWizard:summaryNote"))
        node("planWizard:summaryNote").assertIsDisplayed()
        r.close()
    }

    @Test
    fun `without an answers route the summary has no note`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        pairOn(r)
        node("planWizard:next").performClick()
        assertNotDrawn("planWizard:summaryNote")
        node("planWizard:back").performClick()
        node("planForm:requestsOverMqtt").performClick()
        node("planForm:requestsTopic").performTextInput("commands/#")
        node("planWizard:next").performClick()
        assertTrue(textOf("planWizard:summary").contains("This adds 3 steps"), textOf("planWizard:summary"))
        assertNotDrawn("planWizard:summaryNote")
        r.close()
    }

    @Test
    fun `Add to plan puts the streams and both routes in the list, the answers route first, as ordinary steps`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        pairOn(r)
        node("planForm:answersOverMqtt").performClick()
        node("planForm:answersTopic").performTextInput("devices/+/answer")
        node("planForm:requestsOverMqtt").performClick()
        node("planForm:requestsTopic").performTextInput("commands/#")
        node("planWizard:next").performClick()
        assertTrue(textOf("planWizard:summary").contains("This adds 4 steps"), textOf("planWizard:summary"))
        node("planWizard:add").performClick()
        assertEquals(4, r.state.plan.steps.size)
        assertTrue(textOf("planStep:2").contains("MQTT route devices/+/answer to stream device-commands-dln"), textOf("planStep:2"))
        assertTrue(textOf("planStep:3").contains("MQTT route commands/# to stream device-commands"), textOf("planStep:3"))
        node("planStepMenu:2").performClick()
        node("planStepMenu:2:remove").performClick()
        assertEquals(3, r.state.plan.steps.size)
        r.close()
    }

    // ---- keyboard ----

    @Test
    fun `both ticks work from the keyboard and Enter in a filter goes to the summary`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        pairOn(r)
        node("planForm:answersOverMqtt").requestFocus()
        node("planForm:answersOverMqtt").performKeyInput { pressKey(Key.Spacebar) }
        node("planForm:answersOverMqtt").assertIsOn()
        node("planForm:requestsOverMqtt").requestFocus()
        node("planForm:requestsOverMqtt").performKeyInput { pressKey(Key.Spacebar) }
        node("planForm:requestsOverMqtt").assertIsOn()
        node("planForm:answersTopic").performTextInput("devices/+/answer")
        node("planForm:requestsTopic").performTextInput("commands/#")
        node("planForm:requestsTopic").requestFocus()
        node("planForm:requestsTopic").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(textOf("planWizard:summary").contains("This adds 4 steps"), "Enter did not go to page 3")
        r.close()
    }

    // ---- short and narrow windows ----

    @Test
    fun `on a short window the longest form keeps Back, Next and Cancel on screen`() = runDesktopComposeUiTest(1280, 420) {
        val r = UiRig()
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:Stream").performScrollTo().performClick()
        node("planWizard:next").performClick()
        node("planForm:name").performScrollTo().performTextInput("orders")
        node("planForm:responseTick").performScrollTo().performClick()
        node("planForm:responseName").performScrollTo().performTextReplacement("Orders_Replies") // the naming hint too
        node("planForm:answersOverMqtt").performScrollTo().performClick()
        node("planForm:requestsOverMqtt").performScrollTo().performClick()
        node("planForm:answersTopic").performScrollTo().performTextInput("devices/#/answer") // an error under it
        listOf("planWizard:cancel", "planWizard:back", "planWizard:next").forEach { node(it).assertIsDisplayed() }
        node("planForm:requestsTopic").performScrollTo().assertIsDisplayed()
        node("planForm:mqttAssumption").performScrollTo().assertIsDisplayed()
        r.close()
    }

    @Test
    fun `at 1024 the longest form is displayed and the buttons stay inside the window`() = runDesktopComposeUiTest(1024, 768) {
        val r = UiRig()
        pairOn(r)
        node("planForm:answersOverMqtt").performClick()
        node("planForm:requestsOverMqtt").performClick()
        node("planForm:answersTopic").performTextInput("devices/+/answer")
        node("planForm:requestsTopic").performTextInput("commands/#")
        listOf("planWizard:cancel", "planWizard:back", "planWizard:next").forEach { node(it).assertIsDisplayed() }
        node("planForm:mqttAssumption").performScrollTo().assertIsDisplayed() // the page scrolls; the buttons do not
        r.close()
    }
}
