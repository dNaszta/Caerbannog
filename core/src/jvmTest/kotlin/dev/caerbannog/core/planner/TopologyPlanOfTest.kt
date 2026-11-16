package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import kotlin.test.Test
import kotlin.test.assertEquals

/** M9-02: `TopologyPlan.of`, the plan-from-file factory (a stored draft is loaded with it; M9-12 opens a file with it). */
class TopologyPlanOfTest {
    private val queue = MigrationAction.DeclareQueue("orders", "quorum", vhost = "/orders")
    private val exchange = MigrationAction.DeclareExchange("events", "direct", vhost = "/orders")

    @Test
    fun `every action gets a step id from 1 to n in order`() {
        val plan = TopologyPlan.of("d", listOf(queue, exchange), defaultVhost = "/")
        assertEquals(listOf(1, 2), plan.steps.map { it.id })
        assertEquals(listOf(queue, exchange), plan.steps.map { it.action })
        assertEquals("d", plan.description)
    }

    @Test
    fun `the vhost is that of the first action that has one`() {
        assertEquals("/orders", TopologyPlan.of("", listOf(queue, exchange), defaultVhost = "/").vhost)
    }

    @Test
    fun `the first action with a vhost wins even when a later one differs`() {
        val other = MigrationAction.DeclareQueue("x", "quorum", vhost = "/other")
        assertEquals("/orders", TopologyPlan.of("", listOf(queue, other), defaultVhost = "/").vhost)
    }

    @Test
    fun `an access action has no vhost of its own, so the first one after it decides`() {
        val user = MigrationAction.SetPermissions(vhost = "/ignored", user = "u", configure = ".*", write = ".*", read = ".*")
        val plan = TopologyPlan.of("", listOf(user, queue), defaultVhost = "/")
        assertEquals("/orders", plan.vhost, "SetPermissions is broker-level: it does not name the plan's virtual host")
    }

    @Test
    fun `no action means the default vhost and no step`() {
        val plan = TopologyPlan.of("only a description", emptyList(), defaultVhost = "/dev")
        assertEquals("/dev", plan.vhost)
        assertEquals(emptyList(), plan.steps)
        assertEquals("only a description", plan.description)
    }

    @Test
    fun `append after of hands out the next id and never repeats a live one`() {
        val plan = TopologyPlan.of("", listOf(queue, exchange), defaultVhost = "/").append(listOf(queue.copy(name = "q2")))
        assertEquals(listOf(1, 2, 3), plan.steps.map { it.id })
    }
}
