package dev.caerbannog.core.planner

import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-04 round 2 (F2), written first (red). API fixed here: `PlannerState.knownPending: Boolean`, true from the moment a read of the broker's
 * names is asked for until its answer (or failure) for the plan's current virtual host arrives. While pending the graph is the plan alone,
 * with NO "could not be read" notice and NO "not in this plan" mark (the broker's answer decides both); after a failed read both appear;
 * with no reader (not connected) it is never pending and the notice stays "not connected".
 */
class PlannerViewModelKnownPendingTest {
    private val rigs = mutableListOf<PlannerRig>()
    private fun rig(reader: KnownTopologyReader?) = PlannerRig(reader = reader).also { rigs += it }

    @AfterEach
    fun close() = rigs.forEach { it.close() }

    private val broker = KnownTopology(exchanges = mapOf("events" to "topic"))

    @Test
    fun `while the first read is pending the plan is drawn with no notice and no not in this plan mark`() {
        val reader = FakeKnownTopologyReader(broker)
        val gate = reader.holdNext()
        val r = rig(reader)
        r.queue("orders"); r.binding("events", "orders", "k")
        assertTrue(r.state.knownPending)
        assertNull(r.state.known)
        assertNull(r.state.graph.notice, "nothing is known yet, and nothing has failed")
        assertEquals(emptySet(), r.state.graph.notInPlanNodeIds, "events may exist: the broker has not answered")
        assertEquals(setOf("q:orders", "e:events"), r.state.graph.graph.nodes.map { it.node.id }.toSet(), "the plan is still drawn")
        gate.release()
        assertFalse(r.state.knownPending)
        assertEquals(broker, r.state.known)
        assertNull(r.state.graph.notice)
        assertEquals(emptySet(), r.state.graph.notInPlanNodeIds, "events is on the broker")
    }

    @Test
    fun `the re-read after a virtual host change is pending too`() {
        val reader = FakeKnownTopologyReader(broker)
        val r = rig(reader)
        assertFalse(r.state.knownPending)
        val gate = reader.holdNext()
        r.vm.setVirtualHost("billing")
        r.queue("orders"); r.binding("events", "orders", "k")
        assertTrue(r.state.knownPending)
        assertNull(r.state.graph.notice)
        assertEquals(emptySet(), r.state.graph.notInPlanNodeIds)
        gate.release()
        assertFalse(r.state.knownPending)
    }

    @Test
    fun `a late answer for an old virtual host does not end the pending of the current one`() {
        val reader = FakeKnownTopologyReader(broker)
        val first = reader.holdNext()
        val r = rig(reader) // "/" is pending
        val second = reader.holdNext()
        r.vm.setVirtualHost("billing") // billing is pending
        first.release()
        assertTrue(r.state.knownPending, "the answer for / is late; billing has not answered")
        second.release()
        assertFalse(r.state.knownPending)
    }

    @Test
    fun `after a failed read the notice and the marks appear and it is not pending`() {
        val r = rig(FakeKnownTopologyReader(null))
        r.queue("orders"); r.binding("events", "orders", "k")
        assertFalse(r.state.knownPending)
        assertEquals(PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.BrokerNotRead), r.state.graph.notice)
        assertEquals(setOf("e:events"), r.state.graph.notInPlanNodeIds)
    }

    @Test
    fun `not connected is never pending and keeps its notice`() {
        val r = rig(null)
        r.queue("orders"); r.binding("events", "orders", "k")
        assertFalse(r.state.knownPending)
        assertEquals(PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.NotConnected), r.state.graph.notice)
    }

    @Test
    fun `a locked planner reads nothing and is not pending`() {
        val reader = FakeKnownTopologyReader(broker)
        val r = rig(reader).also { it.restart(PlannerContext(unavailableReason = "RabbitMQ 3.x")) }
        assertFalse(r.state.knownPending)
    }
}
