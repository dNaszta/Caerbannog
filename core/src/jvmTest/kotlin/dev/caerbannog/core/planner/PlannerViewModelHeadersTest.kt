package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-11 step 2: what the wizard hands the screen for a binding form: `WizardState.Details.asksHeaders` (true for a known headers exchange or a form that
 * already holds headers) next to the existing `asksRoutingKey`, the page message, and the gate that adds no step while a header row is refused.
 */
class PlannerViewModelHeadersTest {
    private val rigs = mutableListOf<PlannerRig>()
    private fun rig(known: KnownTopology? = null) = PlannerRig(reader = known?.let { FakeKnownTopologyReader(it) }).also { rigs += it }

    @AfterEach
    fun close() = rigs.forEach { it.close() }

    private fun PlannerRig.open(form: StepForm): WizardState.Details {
        vm.openWizard(); vm.chooseKind("Binding"); vm.updateForm(form)
        return assertIs<WizardState.Details>(state.wizard)
    }

    private fun PlannerRig.withExchange(type: String) = apply { queue("reports"); exchange("documents", type) }

    @Test
    fun `a known headers exchange of the plan asks headers and no routing key`() {
        val r = rig().withExchange("headers")
        val d = r.open(BindingForm(exchange = "documents", destination = "reports"))
        assertTrue(d.asksHeaders)
        assertFalse(d.asksRoutingKey)
    }

    @Test
    fun `a headers exchange the broker has asks headers too`() {
        val r = rig(KnownTopology(exchanges = mapOf("documents" to "headers")))
        val d = r.open(BindingForm(exchange = "documents", destination = "reports"))
        assertTrue(d.asksHeaders)
        assertFalse(d.asksRoutingKey)
    }

    @Test
    fun `fanout asks neither, direct and topic ask the key only`() {
        val fanout = rig().withExchange("fanout").open(BindingForm(exchange = "documents", destination = "reports"))
        assertFalse(fanout.asksHeaders); assertFalse(fanout.asksRoutingKey)
        for (type in listOf("direct", "topic")) {
            val d = rig().withExchange(type).open(BindingForm(exchange = "documents", destination = "reports"))
            assertFalse(d.asksHeaders, type); assertTrue(d.asksRoutingKey, type)
        }
    }

    @Test
    fun `an exchange of unknown type shows the key and no headers section`() {
        val d = rig().open(BindingForm(exchange = "documents", destination = "reports"))
        assertFalse(d.asksHeaders)
        assertTrue(d.asksRoutingKey)
    }

    @Test
    fun `a form that holds headers shows the section even when the type is not known`() {
        val d = rig().open(BindingForm(exchange = "documents", destination = "reports", headers = listOf(HeaderMatch("format", value = "pdf"))))
        assertTrue(d.asksHeaders)
        assertTrue(d.asksRoutingKey, "the key stays asked while the type is unknown")
    }

    @Test
    fun `typing a headers exchange name switches the section on and off with the type`() {
        val r = rig().withExchange("headers")
        r.exchange("plain", "direct")
        val d = r.open(BindingForm(exchange = "plain", destination = "reports"))
        assertFalse(d.asksHeaders)
        r.vm.updateForm(BindingForm(exchange = "documents", destination = "reports"))
        assertTrue(assertIs<WizardState.Details>(r.state.wizard).asksHeaders)
    }

    @Test
    fun `the page message is the first problem and Next is off while a header row is refused`() {
        val r = rig().withExchange("headers")
        val d = r.open(
            BindingForm(
                exchange = "documents", destination = "reports",
                headers = listOf(HeaderMatch("", value = "a"), HeaderMatch("pages", HeaderValueType.WHOLE, "ten")),
            ),
        )
        assertEquals("Give the header a name", d.pageMessage)
        assertEquals(listOf("headers", "headers"), d.problems.map { it.field })
        assertFalse(d.nextEnabled)
        val stepsBefore = r.state.plan.steps.size
        r.vm.next(); r.vm.addToPlan()
        assertEquals(stepsBefore, r.state.plan.steps.size, "no step is added")
    }

    @Test
    fun `the warning is a hint, Next stays on and the step is added with x-match only`() {
        val r = rig().withExchange("headers")
        val d = r.open(BindingForm(exchange = "documents", destination = "reports", matchRule = MatchRule.ANY))
        assertEquals(listOf(FieldHint("headers", PlannerTexts.HEADERS_NONE_ANY)), d.hints)
        assertTrue(d.nextEnabled)
        r.vm.next(); r.vm.addToPlan()
        val added = r.state.plan.steps.last().action as MigrationAction.DeclareBinding
        assertEquals(mapOf("x-match" to ArgumentValue.Text("any")), added.arguments)
    }

    @Test
    fun `two header bindings of one pair are two steps and the plan has no problem`() {
        val r = rig().withExchange("headers")
        for (v in listOf("pdf", "csv")) {
            r.add(BindingForm(exchange = "documents", destination = "reports", headers = listOf(HeaderMatch("format", value = v))))
        }
        assertEquals(2, r.state.plan.steps.count { it.action is MigrationAction.DeclareBinding })
        assertEquals(emptyList(), r.state.problems)
    }

    @Test
    fun `editing an opened headers step keeps its headers and writes the change`() {
        val r = rig()
        r.restart()
        val action = MigrationAction.DeclareBinding(
            exchange = "documents", queue = "reports",
            arguments = mapOf("x-match" to ArgumentValue.Text("any"), "pages" to ArgumentValue.Whole(10)),
        )
        r.store.putDraft("", listOf(action))
        r.restart()
        val id = r.state.plan.steps.single().id
        r.vm.editStep(id)
        val details = assertIs<WizardState.Details>(r.state.wizard)
        val form = assertIs<BindingForm>(details.form)
        assertEquals(listOf(HeaderMatch("pages", HeaderValueType.WHOLE, "10")), form.headers)
        assertEquals(MatchRule.ANY, form.matchRule)
        assertTrue(details.asksHeaders)
        assertNull(form.heldRule)
        r.vm.updateForm(form.copy(headers = form.headers + HeaderMatch("format", value = "pdf")))
        r.vm.next(); r.vm.addToPlan()
        assertEquals(
            mapOf("x-match" to ArgumentValue.Text("any"), "pages" to ArgumentValue.Whole(10), "format" to ArgumentValue.Text("pdf")),
            (r.state.plan.steps.single().action as MigrationAction.DeclareBinding).arguments,
        )
    }
}
