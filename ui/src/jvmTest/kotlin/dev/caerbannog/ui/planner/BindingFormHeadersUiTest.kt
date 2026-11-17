package dev.caerbannog.ui.planner

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.BindingForm
import dev.caerbannog.core.planner.HeaderMatch
import dev.caerbannog.core.planner.HeaderValueType
import dev.caerbannog.core.planner.MatchRule
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.WizardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-11 step 2: the headers rows of `BindingFormView` (mockup "form-binding-headers", 1280 x 800). RED until the rows exist.
 *
 * Tag contract (the brief pins the first group; the second is pinned here for the architect to confirm):
 *  - brief: `planForm:matchRule:all`, `planForm:matchRule:any` (radio, `selected` semantics, labels `all headers match` / `any header matches`),
 *    `planForm:header:<n>:name` and `:value` (text fields), `planForm:header:<n>:type`, `planForm:header:<n>:remove`, `planForm:addHeader`;
 *  - pinned here: the type chips are radios `planForm:header:<n>:type:text`, `:type:number`, `:type:flag` (labels `text`, `number`, `true or false`); a
 *    boolean value is a two-way choice, radios `planForm:header:<n>:value:true` and `:value:false`, and NO `:value` text field is drawn for that row;
 *    `planForm:header:<n>:type` itself is not asserted (the chips are what a user can press); the headers problem (first one) is drawn once, under the
 *    rows, as `planForm:error:headers`, the warning as `planForm:hint:headers` (the existing `planForm:error|hint:<field>` convention, field `headers`).
 *  - the section (match rule, rows, Add header) is drawn only when `Details.asksHeaders`; the routing key field only when `Details.asksRoutingKey`.
 */
@OptIn(ExperimentalTestApi::class)
class BindingFormHeadersUiTest {
    private fun UiRig.headersPlan(type: String = "headers") { queue("reports"); exchange("documents", type) }

    private fun DesktopComposeUiTest.toForm(r: UiRig, exchange: String = "documents") {
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:Binding").performClick()
        node("planWizard:next").performClick()
        if (exchange.isNotEmpty()) at("planForm:exchange").performTextInput(exchange)
        at("planForm:destination").performTextInput("reports")
    }

    /** The pages of the wizard scroll inside a fixed footer: a row far down is scrolled into view before a user can press it. */
    private fun DesktopComposeUiTest.at(tag: String) = node(tag).also { it.performScrollTo() }

    private fun UiRig.form() = assertIs<BindingForm>(assertIs<WizardState.Details>(state.wizard).form)
    private fun UiRig.details() = assertIs<WizardState.Details>(state.wizard)

    private fun DesktopComposeUiTest.selected(tag: String) = node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true
    private fun DesktopComposeUiTest.fieldText(tag: String) =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun `a known headers exchange shows the rule choice, no rows, Add header and no routing key`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { headersPlan() }
        toForm(r)
        node("planForm:matchRule:all").assertIsDisplayed()
        node("planForm:matchRule:any").assertIsDisplayed()
        assertTrue(textOf("planForm:matchRule:all").contains("all headers match"), textOf("planForm:matchRule:all"))
        assertTrue(textOf("planForm:matchRule:any").contains("any header matches"), textOf("planForm:matchRule:any"))
        assertTrue(selected("planForm:matchRule:all"), "all is chosen at the start")
        assertTrue(!selected("planForm:matchRule:any"))
        node("planForm:addHeader").assertIsDisplayed()
        assertNotDrawn("planForm:header:0:name")
        assertNotDrawn("planForm:routingKey")
        assertTrue(r.details().asksHeaders)
        r.close()
    }

    @Test
    fun `with no row the form says what the rule would match, per rule, and Next stays on`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { headersPlan() }
        toForm(r)
        assertEquals(PlannerTexts.HEADERS_NONE_ALL, textOf("planForm:hint:headers"))
        node("planWizard:next").assertIsEnabled()
        at("planForm:matchRule:any").performClick()
        assertEquals(MatchRule.ANY, r.form().matchRule)
        assertTrue(selected("planForm:matchRule:any") && !selected("planForm:matchRule:all"))
        assertEquals(PlannerTexts.HEADERS_NONE_ANY, textOf("planForm:hint:headers"))
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `Add header draws a row with name, value, the three type chips and a remove button`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { headersPlan() }
        toForm(r)
        at("planForm:addHeader").performClick()
        for (tag in listOf("planForm:header:0:name", "planForm:header:0:value", "planForm:header:0:remove", "planForm:header:0:type:text", "planForm:header:0:type:number", "planForm:header:0:type:flag")) {
            node(tag).assertIsDisplayed()
        }
        assertEquals("text", textOf("planForm:header:0:type:text").trim())
        assertEquals("number", textOf("planForm:header:0:type:number").trim())
        assertEquals("true or false", textOf("planForm:header:0:type:flag").trim())
        assertTrue(selected("planForm:header:0:type:text"), "text is the start type")
        assertEquals(listOf(HeaderMatch("")), r.form().headers)
        // the empty name is refused at once, and the warning is gone (there is a row now)
        assertEquals(PlannerTexts.HEADER_NAME_MISSING, textOf("planForm:error:headers"))
        assertNotDrawn("planForm:hint:headers")
        node("planWizard:next").assertIsNotEnabled()
        r.close()
    }

    @Test
    fun `typing a name and a value fills the form and enables Next`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { headersPlan() }
        toForm(r)
        at("planForm:addHeader").performClick()
        at("planForm:header:0:name").performTextInput("format")
        at("planForm:header:0:value").performTextInput("pdf")
        assertEquals(listOf(HeaderMatch("format", HeaderValueType.TEXT, "pdf")), r.form().headers)
        assertNotDrawn("planForm:error:headers")
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `the number chip asks digits only and clears when the value is a whole number`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { headersPlan() }
        toForm(r)
        at("planForm:addHeader").performClick()
        at("planForm:header:0:name").performTextInput("pages")
        at("planForm:header:0:type:number").performClick()
        assertEquals(HeaderValueType.WHOLE, r.form().headers.single().type)
        assertTrue(selected("planForm:header:0:type:number") && !selected("planForm:header:0:type:text"))
        at("planForm:header:0:value").performTextInput("ten")
        assertEquals(PlannerTexts.HEADER_WHOLE_DIGITS_ONLY, textOf("planForm:error:headers"))
        node("planWizard:next").assertIsNotEnabled()
        at("planForm:header:0:value").performTextReplacement("10")
        assertNotDrawn("planForm:error:headers")
        assertEquals(HeaderMatch("pages", HeaderValueType.WHOLE, "10"), r.form().headers.single())
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `the boolean chip replaces the value field with a two way choice`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { headersPlan() }
        toForm(r)
        at("planForm:addHeader").performClick()
        at("planForm:header:0:name").performTextInput("signed")
        at("planForm:header:0:type:flag").performClick()
        assertNotDrawn("planForm:header:0:value")
        node("planForm:header:0:value:true").assertIsDisplayed()
        node("planForm:header:0:value:false").assertIsDisplayed()
        at("planForm:header:0:value:false").performClick()
        assertEquals(HeaderMatch("signed", HeaderValueType.FLAG, "false"), r.form().headers.single())
        assertTrue(selected("planForm:header:0:value:false") && !selected("planForm:header:0:value:true"))
        at("planForm:header:0:value:true").performClick()
        assertEquals("true", r.form().headers.single().value)
        assertEquals(ArgumentValue.Flag(true), r.form().headers.single().argumentValue())
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `x-match as a name and a name used twice are refused under the rows`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { headersPlan() }
        toForm(r)
        at("planForm:addHeader").performClick()
        at("planForm:header:0:name").performTextInput("x-match")
        assertEquals(PlannerTexts.HEADER_NAME_IS_MATCH_RULE, textOf("planForm:error:headers"))
        node("planWizard:next").assertIsNotEnabled()
        at("planForm:header:0:name").performTextReplacement("format")
        at("planForm:addHeader").performClick()
        at("planForm:header:1:name").performTextInput("format")
        assertEquals(PlannerTexts.HEADER_LISTED_TWICE, textOf("planForm:error:headers"))
        node("planWizard:next").assertIsNotEnabled()
        assertEquals(PlannerTexts.HEADER_LISTED_TWICE, textOf("planWizard:pageMessage"), "the page message is the first problem")
        r.close()
    }

    @Test
    fun `Remove takes the row away, the others keep their values`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { headersPlan() }
        toForm(r)
        at("planForm:addHeader").performClick()
        at("planForm:header:0:name").performTextInput("a")
        at("planForm:addHeader").performClick()
        at("planForm:header:1:name").performTextInput("b")
        at("planForm:header:1:value").performTextInput("2")
        at("planForm:header:0:remove").performClick()
        assertEquals(listOf(HeaderMatch("b", HeaderValueType.TEXT, "2")), r.form().headers)
        assertEquals("b", fieldText("planForm:header:0:name"), "the remaining row moved up")
        assertEquals("2", fieldText("planForm:header:0:value"))
        assertNotDrawn("planForm:header:1:name")
        at("planForm:header:0:remove").performClick()
        assertEquals(emptyList(), r.form().headers)
        assertEquals(PlannerTexts.HEADERS_NONE_ALL, textOf("planForm:hint:headers"), "no row left: the warning is back")
        r.close()
    }

    @Test
    fun `an exchange of unknown type shows the routing key and no headers section`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { queue("reports") }
        toForm(r, exchange = "somewhere")
        node("planForm:routingKey").assertIsDisplayed()
        for (tag in listOf("planForm:matchRule:all", "planForm:matchRule:any", "planForm:addHeader", "planForm:hint:headers")) assertNotDrawn(tag)
        assertTrue(!r.details().asksHeaders)
        r.close()
    }

    @Test
    fun `fanout and direct exchanges draw no headers section, and only direct asks the key`() = runDesktopComposeUiTest(1280, 800) {
        val fanout = UiRig { headersPlan("fanout") }
        toForm(fanout)
        assertNotDrawn("planForm:routingKey"); assertNotDrawn("planForm:matchRule:all"); assertNotDrawn("planForm:addHeader")
        fanout.close()
    }

    @Test
    fun `a direct exchange keeps the routing key and has no headers section`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { headersPlan("direct") }
        toForm(r)
        node("planForm:routingKey").assertIsDisplayed()
        assertNotDrawn("planForm:matchRule:all"); assertNotDrawn("planForm:addHeader")
        r.close()
    }

    @Test
    fun `typing another exchange name switches the section with the exchange type`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { headersPlan(); exchange("plain", "direct") }
        toForm(r, exchange = "documents")
        node("planForm:addHeader").assertIsDisplayed()
        at("planForm:exchange").performTextReplacement("plain")
        assertNotDrawn("planForm:addHeader")
        node("planForm:routingKey").assertIsDisplayed()
        at("planForm:exchange").performTextReplacement("documents")
        node("planForm:addHeader").assertIsDisplayed()
        r.close()
    }

    @Test
    fun `Add to plan writes the step and the list shows its headers`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig { headersPlan() }
        toForm(r)
        at("planForm:addHeader").performClick()
        at("planForm:header:0:name").performTextInput("format")
        at("planForm:header:0:value").performTextInput("pdf")
        node("planWizard:next").performClick()
        node("planWizard:add").performClick()
        val binding = r.state.plan.steps.last().action as MigrationAction.DeclareBinding
        assertEquals(mapOf("x-match" to ArgumentValue.Text("all"), "format" to ArgumentValue.Text("pdf")), binding.arguments)
        assertEquals("", binding.routingKey)
        assertTrue(textOf("planStep:2").contains("format=pdf, x-match=all"), textOf("planStep:2"))
        r.close()
    }

    @Test
    fun `a step opened from a file holds its headers in the rows even when the exchange type is not known`() = runDesktopComposeUiTest(1280, 800) {
        val action = MigrationAction.DeclareBinding(
            exchange = "documents", queue = "reports", routingKey = "legacy",
            arguments = linkedMapOf("x-match" to ArgumentValue.Text("any"), "pages" to ArgumentValue.Whole(10), "signed" to ArgumentValue.Flag(true)),
        )
        val r = UiRig(prepare = { store.putDraft("", listOf(action)) })
        showPlanner(r)
        r.vm.editStep(r.state.plan.steps.single().id)
        waitForIdle()
        node("planForm:matchRule:any").assertIsDisplayed()
        assertTrue(selected("planForm:matchRule:any"))
        assertEquals("pages", fieldText("planForm:header:0:name"))
        assertEquals("10", fieldText("planForm:header:0:value"))
        assertTrue(selected("planForm:header:0:type:number"))
        assertEquals("signed", fieldText("planForm:header:1:name"))
        assertTrue(selected("planForm:header:1:type:flag") && selected("planForm:header:1:value:true"))
        node("planForm:routingKey").assertIsDisplayed()
        assertEquals("legacy", fieldText("planForm:routingKey"))
        at("planForm:header:1:remove").performClick()
        assertEquals(listOf(HeaderMatch("pages", HeaderValueType.WHOLE, "10")), r.form().headers)
        r.close()
    }

    @Test
    fun `a kept rule has no choice selected until the user chooses one`() = runDesktopComposeUiTest(1280, 800) {
        val action = MigrationAction.DeclareBinding(
            exchange = "documents", queue = "reports",
            arguments = mapOf("x-match" to ArgumentValue.Text("all-with-x"), "format" to ArgumentValue.Text("pdf")),
        )
        val r = UiRig(prepare = { store.putDraft("", listOf(action)) })
        showPlanner(r)
        r.vm.editStep(r.state.plan.steps.single().id)
        waitForIdle()
        assertTrue(!selected("planForm:matchRule:all") && !selected("planForm:matchRule:any"), "the rule of the file is not one of the two offered")
        assertEquals("all-with-x", r.form().heldRule)
        at("planForm:matchRule:any").performClick()
        assertNull(r.form().heldRule)
        assertEquals(MatchRule.ANY, r.form().matchRule)
        assertTrue(selected("planForm:matchRule:any"))
        r.close()
    }

    @Test
    fun `a long header name and value stay inside the dialog at the narrow width`() = runDesktopComposeUiTest(700, 700) {
        val r = UiRig { headersPlan() }
        toForm(r)
        at("planForm:addHeader").performClick()
        at("planForm:header:0:name").performTextInput("a-very-long-header-name-".repeat(6))
        at("planForm:header:0:value").performTextInput("a-very-long-header-value-".repeat(6))
        for (tag in listOf("planForm:header:0:name", "planForm:header:0:value", "planForm:header:0:remove", "planWizard:next")) {
            val box = node(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(box.left >= 0f && box.right <= 700f, "$tag is cut off: $box")
        }
        r.close()
    }
}
