package dev.caerbannog.core.planner

import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-04 step 2: the planner's one read of the broker's names through [KnownTopologyReader] (spec "Which existing items are drawn").
 * API fixed here: `PlannerViewModel(..., reader: KnownTopologyReader? = null)` (named, default null); `PlannerState.known: KnownTopology?`
 * (null: no answer, or the broker could not be read), `PlannerState.graph: PlanGraph`, `PlannerState.graphView: ReviewView` (default Graph) and
 * `PlannerViewModel.selectReviewView(view)`. A read must not hold the view model's lock while it waits: a plan change made during a slow read
 * has to take effect at once (these tests run on `Dispatchers.Unconfined`, where a held lock shows as a state that never changes).
 */
class PlannerViewModelKnownTest {
    private val rigs = mutableListOf<PlannerRig>()
    private fun rig(reader: KnownTopologyReader?, context: PlannerContext = PlannerContext()) = PlannerRig(context = context, reader = reader).also { rigs += it }

    @AfterEach
    fun close() = rigs.forEach { it.close() }

    private val broker = KnownTopology(exchanges = mapOf("events" to "topic"), queues = setOf("audit"), streams = setOf("log"))

    @Test
    fun `it reads the broker once when it starts, for the plan's virtual host`() {
        val reader = FakeKnownTopologyReader(broker)
        val r = rig(reader, PlannerContext(vhost = "/"))
        assertEquals(listOf("/"), reader.calls)
        assertEquals(broker, r.state.known)
    }

    @Test
    fun `it never polls, whatever the user does`() {
        val reader = FakeKnownTopologyReader(broker)
        val r = rig(reader)
        r.threeSteps()
        r.vm.selectStep(r.ids().first()); r.vm.moveStepUp(r.ids().last()); r.vm.removeStep(r.ids().last())
        r.vm.setDescription("text"); r.vm.selectReviewView(ReviewView.Json); r.vm.openWizard(); r.vm.cancelWizard()
        r.vm.setNamingStyle(NamingStyle.SNAKE)
        assertEquals(listOf("/"), reader.calls, "one read at the start and nothing after")
    }

    @Test
    fun `it reads again when the plan's virtual host changes, and the new answer replaces the old`() {
        val reader = FakeKnownTopologyReader(broker)
        val billing = KnownTopology(queues = setOf("invoices"))
        reader.answerFor("billing", billing)
        val r = rig(reader)
        r.vm.setVirtualHost("billing")
        assertEquals(listOf("/", "billing"), reader.calls)
        assertEquals(billing, r.state.known)
        r.vm.setVirtualHost("billing")
        assertEquals(listOf("/", "billing"), reader.calls, "the same virtual host is no change")
        r.vm.setVirtualHost("/")
        assertEquals(listOf("/", "billing", "/"), reader.calls)
        assertEquals(broker, r.state.known)
    }

    @Test
    fun `a late answer for an old virtual host is dropped`() {
        val reader = FakeKnownTopologyReader(broker)
        val billing = KnownTopology(queues = setOf("invoices"))
        reader.answerFor("billing", billing)
        val gate = reader.holdNext()
        val r = rig(reader) // the read of "/" is waiting
        assertNull(r.state.known)
        r.vm.setVirtualHost("billing")
        assertEquals(billing, r.state.known, "the read for billing must not wait behind the slow one")
        gate.release()
        assertEquals(billing, r.state.known, "the answer for \"/\" came late and must not replace the one for billing")
        assertEquals(planPreview(r.state.plan, billing), r.state.graph)
    }

    @Test
    fun `a broker that cannot be read gives no known names and the broker not read notice`() {
        val reader = FakeKnownTopologyReader(null)
        val r = rig(reader)
        r.queue("orders"); r.binding("events", "orders", "k")
        assertNull(r.state.known)
        assertEquals(PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.BrokerNotRead), r.state.graph.notice)
        assertEquals(setOf("e:events"), r.state.graph.notInPlanNodeIds)
    }

    @Test
    fun `an unreadable virtual host after a readable one falls back to only the plan`() {
        val reader = FakeKnownTopologyReader(broker)
        reader.answerFor("hidden", null)
        val r = rig(reader)
        r.vm.setVirtualHost("hidden")
        assertNull(r.state.known)
        assertEquals(PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.BrokerNotRead), r.state.graph.notice)
    }

    @Test
    fun `no reader reads nothing and the graph says not connected`() {
        val r = rig(reader = null, context = PlannerContext(connectionName = null))
        r.queue("orders"); r.binding("events", "orders", "k")
        assertNull(r.state.known)
        assertEquals(PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.NotConnected), r.state.graph.notice)
        assertEquals(setOf("e:events"), r.state.graph.notInPlanNodeIds)
    }

    @Test
    fun `a connection that cannot run migrations reads nothing even when a reader is given`() {
        val reader = FakeKnownTopologyReader(broker)
        val r = rig(reader, PlannerContext(unavailableReason = "This broker runs RabbitMQ 3.x: it is a migration source only"))
        r.vm.setVirtualHost("billing")
        r.vm.selectReviewView(ReviewView.Json)
        assertEquals(emptyList(), reader.calls, "a locked planner sends nothing, not even a list call")
        assertNull(r.state.known)
    }

    @Test
    fun `the graph is the preview of the plan and the known names, and follows every change`() {
        val reader = FakeKnownTopologyReader(broker)
        val r = rig(reader)
        assertEquals(planPreview(TopologyPlan(), broker), r.state.graph)
        r.threeSteps()
        assertEquals(planPreview(r.state.plan, broker), r.state.graph)
        assertEquals(1, r.state.graph.graph.edges.size)
        r.vm.removeStep(r.ids().last())
        assertEquals(planPreview(r.state.plan, broker), r.state.graph)
        assertEquals(emptyList(), r.state.graph.graph.edges, "removing the binding step removes its edge")
        assertEquals(2, r.state.graph.graph.nodes.size)
    }

    @Test
    fun `the broker's names reach the destination lists of the wizard`() {
        val r = rig(FakeKnownTopologyReader(broker))
        r.queue("orders")
        r.vm.openWizard(); r.vm.chooseKind("Binding")
        val d = r.state.wizard as WizardState.Details
        assertEquals(Suggestion("orders", SuggestionSource.PLAN), d.destinationSuggestions.first())
        assertTrue(Suggestion("audit", SuggestionSource.BROKER) in d.destinationSuggestions)
        assertTrue(Suggestion("log", SuggestionSource.BROKER) in d.destinationSuggestions, "streams are destinations too")
        assertTrue(Suggestion("events", SuggestionSource.BROKER) in d.exchangeSuggestions)
    }

    @Test
    fun `without an answer the lists hold the plan's names only`() {
        val r = rig(FakeKnownTopologyReader(null))
        r.queue("orders")
        r.vm.openWizard(); r.vm.chooseKind("Binding")
        val d = r.state.wizard as WizardState.Details
        assertEquals(listOf(Suggestion("orders", SuggestionSource.PLAN)), d.destinationSuggestions)
    }

    @Test
    fun `the graph is shown first and the switch changes nothing in the plan, the draft or the saved text`() {
        val r = rig(FakeKnownTopologyReader(broker))
        r.threeSteps()
        assertEquals(ReviewView.Graph, r.state.graphView)
        val plan = r.state.plan
        val drafts = r.store.savedTexts.toList()
        val text = r.state.migrationText
        r.vm.selectReviewView(ReviewView.Json)
        assertEquals(ReviewView.Json, r.state.graphView)
        assertEquals(plan, r.state.plan)
        assertEquals(drafts, r.store.savedTexts, "choosing a view writes nothing")
        assertEquals(text, r.state.migrationText)
        r.vm.selectReviewView(ReviewView.Graph)
        assertEquals(ReviewView.Graph, r.state.graphView)
    }

    @Test
    fun `the view survives plan changes and a read, it is not reset by them`() {
        val reader = FakeKnownTopologyReader(broker)
        val r = rig(reader)
        r.vm.selectReviewView(ReviewView.Json)
        r.queue("orders")
        r.vm.setVirtualHost("other")
        assertEquals(ReviewView.Json, r.state.graphView)
    }
}
