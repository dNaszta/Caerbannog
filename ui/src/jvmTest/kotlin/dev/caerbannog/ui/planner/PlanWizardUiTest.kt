package dev.caerbannog.ui.planner

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.planner.BindingForm
import dev.caerbannog.core.planner.ExchangeForm
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.PlannerContext
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.QueueForm
import dev.caerbannog.core.planner.WizardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-03 step 2: the three-page wizard (mockup sections 3, 4 and 5), 1280 x 800. RED until `PlanWizardDialog` exists. The kit, the tags and the
 * contract are in [UiRig]. Behaviour pinned here where the mockup is silent:
 *  - page 1: a kind is SELECTED (radio semantics, Queue first) and Next sends `chooseKind(selected)`; only the kinds that have a form are drawn
 *    (Queue, Exchange, Binding, and since M9-07 Stream, since M9-08 Policy): the others of [PlannerTexts.WIZARD_KINDS] come with the task that builds their form;
 *  - Down and Up move the selection between kinds, Enter is Next on pages 1 and 2 and Add to plan on page 3, Esc is Cancel;
 *  - a hint never disables Next; a problem does, with its reason under the field and, for a binding, also as one message of the page;
 *  - "Add to plan" closes the wizard and focuses the row of the first new step.
 */
@OptIn(ExperimentalTestApi::class)
class PlanWizardUiTest {

    private fun rig(context: PlannerContext = PlannerContext(), reader: dev.caerbannog.core.planner.KnownTopologyReader? = null, prepare: UiRig.() -> Unit = {}) = UiRig(context = context, reader = reader, prepare = prepare)

    private fun DesktopComposeUiTest.open(r: UiRig) {
        showPlanner(r)
        node("planner:addStepBtn").performClick()
    }

    private fun DesktopComposeUiTest.toForm(r: UiRig, kind: String) {
        open(r)
        node("planWizard:kind:$kind").performClick()
        node("planWizard:next").performClick()
    }

    private fun DesktopComposeUiTest.selected(kind: String): Boolean =
        node("planWizard:kind:$kind").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    private fun DesktopComposeUiTest.fieldText(tag: String) =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    // ---- page 1 ----

    @Test
    fun `page 1 offers the kinds that have a form and no other`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        open(r)
        val built = listOf("Queue", "Exchange", "Binding", "Stream", "Policy", "Dead-lettering for a queue", "MQTT route") // M9-10: the MQTT route kind has its form now; M9-08: Policy; M9-09: Dead-lettering
        built.forEach { node("planWizard:kind:$it").assertIsDisplayed() }
        PlannerTexts.WIZARD_KINDS.filterNot { it in built }.forEach {
            assertNotDrawn("planWizard:kind:$it")
        }
        node("planWizard:cancel").assertIsDisplayed().assertIsEnabled()
        node("planWizard:next").assertIsDisplayed().assertIsEnabled()
        assertNotDrawn("planWizard:back")
        r.close()
    }

    @Test
    fun `no kind deletes anything, none is for users, virtual hosts or permissions`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        open(r)
        val words = listOf("delete", "remove", "purge", "user", "virtual host", "permission", "message")
        listOf("Queue", "Exchange", "Binding", "Stream").forEach { kind ->
            val text = textOf("planWizard:kind:$kind").lowercase()
            words.forEach { assertTrue(!text.contains(it), "the kind $kind mentions \"$it\": $text") }
        }
        r.close()
    }

    @Test
    fun `Queue is selected at first`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        open(r)
        assertTrue(selected("Queue"))
        assertTrue(!selected("Exchange") && !selected("Binding"))
        r.close()
    }

    @Test
    fun `choosing Exchange and Next goes to the exchange form`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Exchange")
        assertTrue(r.events.calls.contains("chooseKind:Exchange"), r.events.calls.toString())
        assertIs<ExchangeForm>(assertIs<WizardState.Details>(r.state.wizard).form)
        r.close()
    }

    @Test
    fun `Down and Up move the selection between the kinds`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        open(r)
        node("planWizard:kind:Queue").requestFocus()
        node("planWizard:kind:Queue").performKeyInput { pressKey(Key.DirectionDown) }
        assertTrue(selected("Exchange"))
        node("planWizard:kind:Exchange").performKeyInput { pressKey(Key.DirectionDown) }
        assertTrue(selected("Binding"))
        node("planWizard:kind:Binding").performKeyInput { pressKey(Key.DirectionUp) }
        assertTrue(selected("Exchange"))
        r.close()
    }

    @Test
    fun `Enter on page 1 is Next`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        open(r)
        node("planWizard:kind:Binding").performClick()
        node("planWizard:kind:Binding").requestFocus()
        node("planWizard:kind:Binding").performKeyInput { pressKey(Key.Enter) }
        assertIs<BindingForm>(assertIs<WizardState.Details>(r.state.wizard).form)
        r.close()
    }

    @Test
    fun `Esc cancels the wizard and adds nothing`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        open(r)
        node("planWizard:kind:Queue").requestFocus()
        node("planWizard:kind:Queue").performKeyInput { pressKey(Key.Escape) }
        assertEquals(WizardState.Closed, r.state.wizard)
        assertNotDrawn("planWizard")
        assertEquals(emptyList(), r.state.plan.steps)
        r.close()
    }

    @Test
    fun `Cancel on any page closes the wizard and adds nothing`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Queue")
        node("planForm:name").performTextInput("orders")
        node("planWizard:cancel").performClick()
        assertNotDrawn("planWizard")
        assertEquals(emptyList(), r.state.plan.steps)
        assertEquals(false, r.store.hasDraft, "cancel wrote a draft")
        r.close()
    }

    // ---- page 2: queue ----

    @Test
    fun `an empty queue name disables Next and says why under the field`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Queue")
        node("planWizard:next").assertIsNotEnabled()
        assertEquals(PlannerTexts.QUEUE_NAME_MISSING, textOf("planForm:error:name"))
        assertNotDrawn("planForm:hint:name")
        r.close()
    }

    @Test
    fun `the name dot dot shows the parser's reason and keeps Next disabled`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Queue")
        node("planForm:name").performTextInput("..")
        val reason = assertIs<WizardState.Details>(r.state.wizard).problems.single().message
        assertEquals(reason, textOf("planForm:error:name"))
        node("planWizard:next").assertIsNotEnabled()
        r.close()
    }

    @Test
    fun `a good name enables Next and shows no error and no hint`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Queue")
        node("planForm:name").performTextInput("orders")
        node("planWizard:next").assertIsEnabled()
        assertNotDrawn("planForm:error:name")
        assertNotDrawn("planForm:hint:name")
        r.close()
    }

    @Test
    fun `a name against the naming style shows the hint and Next stays enabled`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Queue")
        node("planForm:name").performTextInput("Orders_EU")
        val hint = assertIs<WizardState.Details>(r.state.wizard).hints.single().message
        assertEquals(hint, textOf("planForm:hint:name"))
        node("planWizard:next").assertIsEnabled()
        assertNotDrawn("planForm:error:name")
        r.close()
    }

    @Test
    fun `the queue type is quorum until classic is chosen`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Queue")
        node("planForm:name").performTextInput("orders")
        assertEquals("quorum", (assertIs<WizardState.Details>(r.state.wizard).form as QueueForm).type)
        node("planForm:type:classic").performClick()
        assertEquals("classic", (assertIs<WizardState.Details>(r.state.wizard).form as QueueForm).type)
        assertEquals("orders", (assertIs<WizardState.Details>(r.state.wizard).form as QueueForm).name, "choosing the type lost the name")
        r.close()
    }

    @Test
    fun `Enter in the name field is Next, and does nothing while the name is empty`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Queue")
        node("planForm:name").requestFocus()
        node("planForm:name").performKeyInput { pressKey(Key.Enter) }
        assertIs<WizardState.Details>(r.state.wizard)
        node("planForm:name").performTextInput("orders")
        node("planForm:name").performKeyInput { pressKey(Key.Enter) }
        assertIs<WizardState.Summary>(r.state.wizard)
        r.close()
    }

    @Test
    fun `Back from page 2 returns to page 1`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Queue")
        node("planWizard:back").performClick()
        assertEquals(WizardState.Kind, r.state.wizard)
        assertDrawn("planWizard:kind:Queue")
        r.close()
    }

    // ---- page 2: exchange ----

    @Test
    fun `the exchange form offers the four types, direct first, and sends the chosen one`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Exchange")
        listOf("fanout", "direct", "topic", "headers").forEach { assertDrawn("planForm:type:$it") }
        node("planForm:name").performTextInput("events")
        assertEquals("direct", (assertIs<WizardState.Details>(r.state.wizard).form as ExchangeForm).type)
        node("planForm:type:topic").performClick()
        assertEquals("topic", (assertIs<WizardState.Details>(r.state.wizard).form as ExchangeForm).type)
        r.close()
    }

    @Test
    fun `an empty exchange name is refused with its own reason`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Exchange")
        assertEquals(PlannerTexts.EXCHANGE_NAME_MISSING, textOf("planForm:error:name"))
        node("planWizard:next").assertIsNotEnabled()
        r.close()
    }

    // ---- page 2: binding ----

    @Test
    fun `an empty binding form says what is missing under each field and once for the page`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Binding")
        assertEquals(PlannerTexts.BINDING_EXCHANGE_MISSING, textOf("planForm:error:exchange"))
        assertEquals(PlannerTexts.BINDING_DESTINATION_MISSING, textOf("planForm:error:destination"))
        assertEquals(PlannerTexts.BINDING_EXCHANGE_MISSING, textOf("planWizard:pageMessage"))
        node("planWizard:next").assertIsNotEnabled()
        r.close()
    }

    @Test
    fun `a binding the parser refuses shows its reason as the message of the page`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Binding")
        node("planForm:exchange").performTextInput("..")
        node("planForm:destination").performTextInput("orders")
        val details = assertIs<WizardState.Details>(r.state.wizard)
        assertEquals(details.pageMessage, textOf("planWizard:pageMessage"))
        node("planWizard:next").assertIsNotEnabled()
        r.close()
    }

    @Test
    fun `a good binding enables Next, has no page message, and keeps the routing key it was given`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Binding")
        node("planForm:exchange").performTextInput("events")
        node("planForm:destination").performTextInput("orders")
        node("planForm:routingKey").performTextInput("order.created")
        node("planWizard:next").assertIsEnabled()
        assertNotDrawn("planWizard:pageMessage")
        val form = assertIs<WizardState.Details>(r.state.wizard).form as BindingForm
        assertEquals(Triple("events", "orders", "order.created"), Triple(form.exchange, form.destination, form.routingKey))
        r.close()
    }

    @Test
    fun `the suggestions list this plan first and then the broker, and choosing one fills the field`() = runDesktopComposeUiTest(1280, 800) {
        val known = KnownTopology(exchanges = mapOf("amq.topic" to "topic"), queues = setOf("audit"))
        // M9-04: the broker's names come from the reader, no longer from the context.
        val r = rig(reader = dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader(known)) { queue("orders"); exchange("events") }
        toForm(r, "Binding")
        node("planForm:suggestionsBtn:exchange").performClick()
        assertDrawn("planForm:suggestion:exchange:events")
        assertDrawn("planForm:suggestion:exchange:amq.topic")
        val plan = node("planForm:suggestion:exchange:events").fetchSemanticsNode().boundsInRoot.top
        val broker = node("planForm:suggestion:exchange:amq.topic").fetchSemanticsNode().boundsInRoot.top
        assertTrue(plan < broker, "the exchange of this plan must come before the one of the broker")
        node("planForm:suggestion:exchange:events").performClick()
        assertEquals("events", (assertIs<WizardState.Details>(r.state.wizard).form as BindingForm).exchange)
        node("planForm:suggestionsBtn:destination").performClick()
        assertDrawn("planForm:suggestion:destination:orders")
        assertDrawn("planForm:suggestion:destination:audit")
        r.close()
    }

    @Test
    fun `a name typed that is in no list is allowed`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toForm(r, "Binding")
        node("planForm:exchange").performTextInput("not-anywhere")
        node("planForm:destination").performTextInput("also-not")
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `the routing key field is not drawn for a fanout exchange of this plan`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { exchange("events", "fanout") }
        toForm(r, "Binding")
        assertDrawn("planForm:routingKey")
        node("planForm:exchange").performTextInput("events")
        assertNotDrawn("planForm:routingKey")
        r.close()
    }

    @Test
    fun `the destination can be an exchange`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { exchange("events"); exchange("audit") }
        toForm(r, "Binding")
        node("planForm:destinationKind:exchange").performClick()
        assertEquals(
            dev.caerbannog.core.topology.BindingDestinationType.EXCHANGE,
            (assertIs<WizardState.Details>(r.state.wizard).form as BindingForm).destinationKind,
        )
        r.close()
    }

    // ---- page 3 ----

    private fun DesktopComposeUiTest.toSummary(r: UiRig, name: String = "orders") {
        toForm(r, "Queue")
        node("planForm:name").performTextInput(name)
        node("planWizard:next").performClick()
    }

    @Test
    fun `the summary says how many steps it adds and lists them in the words of the plan`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toSummary(r)
        val summary = assertIs<WizardState.Summary>(r.state.wizard)
        val text = textOf("planWizard:summary")
        assertTrue(text.contains(PlannerTexts.summaryTitle(1)), text)
        summary.actions.forEach { assertTrue(text.contains(it.description), "the summary lacks \"${it.description}\": $text") }
        assertEquals(emptyList(), r.state.plan.steps, "nothing is added before Add to plan")
        r.close()
    }

    @Test
    fun `Back from the summary returns to the form with its answers`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toSummary(r)
        node("planWizard:back").performClick()
        assertEquals("orders", fieldText("planForm:name"))
        r.close()
    }

    @Test
    fun `Add to plan appends the step, closes the wizard and puts the focus on the new step`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toSummary(r)
        node("planWizard:add").performClick()
        assertNotDrawn("planWizard")
        assertEquals(1, r.state.plan.steps.size)
        node("planStep:0").assertIsFocused()
        assertEquals(emptyList(), r.folder.written, "a file was written")
        r.close()
    }

    @Test
    fun `a second step is added at the end and gets the focus`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { queue("first") }
        toSummary(r, "second")
        node("planWizard:add").performClick()
        assertEquals(2, r.state.plan.steps.size)
        node("planStep:1").assertIsFocused()
        r.close()
    }

    @Test
    fun `Enter on the summary is Add to plan`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toSummary(r)
        node("planWizard:add").requestFocus()
        node("planWizard:add").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, r.state.plan.steps.size)
        r.close()
    }

    @Test
    fun `editing a step changes it in place`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig { queue("orders"); exchange("events") }
        showPlanner(r)
        node("planStepMenu:0").performClick()
        node("planStepMenu:0:edit").performClick()
        node("planForm:name").performTextClearance()
        node("planForm:name").performTextInput("orders-eu")
        node("planWizard:next").performClick()
        node("planWizard:add").performClick()
        assertEquals(2, r.state.plan.steps.size)
        assertTrue(r.state.plan.steps[0].title.contains("orders-eu"), r.state.plan.steps[0].title)
        assertTrue(textOf("planStep:0").contains("orders-eu"))
        r.close()
    }

    @Test
    fun `adding through the screen is nothing but a plan change, no file and no broker call`() = runDesktopComposeUiTest(1280, 800) {
        val r = rig()
        toSummary(r)
        node("planWizard:add").performClick()
        assertEquals(emptyList(), r.folder.written, "the planner wrote a migration file by adding a step")
        r.close()
    }
}
