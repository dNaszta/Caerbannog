package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * M9-07 step 2: recognising a stream pair by its name (A11), a naming rule on the plan and nothing else. API fixed by these tests:
 * `PlanPairs.responseOf(plan): Map<Int, Int>` maps the id of a response stream's step to the id of its request stream's step (read "the
 * response of ..."): a `DeclareStream` whose name gives a non-null `PlanNaming.baseOf(name, DerivedWord.DLN)` that is the name of another
 * `DeclareStream` of the same plan. Display only: no marker is written, nothing else is derived from it.
 */
class PlanPairsTest {
    private fun stream(name: String, vhost: String = "/") = MigrationAction.DeclareStream(name = name, vhost = vhost)

    @Test
    fun `each style's derived name is recognised, with the request as the base`() {
        for (style in NamingStyle.entries) {
            val plan = planOf(stream("orders"), stream(PlanNaming.derived("orders", DerivedWord.DLN, style)))
            assertEquals(mapOf(2 to 1), PlanPairs.responseOf(plan), "style $style")
        }
    }

    @Test
    fun `the pair is found whatever the order of the two steps`() {
        val plan = planOf(stream("orders-dln"), stream("orders"))
        assertEquals(mapOf(1 to 2), PlanPairs.responseOf(plan))
    }

    @Test
    fun `a free name is not a response stream`() {
        assertEquals(emptyMap(), PlanPairs.responseOf(planOf(stream("telemetry"), stream("telemetry-replies"))))
    }

    @Test
    fun `a name that looks derived but has no base in the plan is not a response stream`() {
        assertEquals(emptyMap(), PlanPairs.responseOf(planOf(stream("orders-dln"))))
        assertEquals(emptyMap(), PlanPairs.responseOf(planOf(stream("orders-dln"), stream("invoices"))))
        assertEquals(emptyMap(), PlanPairs.responseOf(planOf(stream("-dln"), stream(""))), "no base, nothing to pair with")
    }

    @Test
    fun `the base must be a stream of the plan, not a queue or an exchange`() {
        val plan = planOf(MigrationAction.DeclareQueue(name = "orders"), stream("orders-dln"), MigrationAction.DeclareExchange(name = "orders"))
        assertEquals(emptyMap(), PlanPairs.responseOf(plan))
    }

    @Test
    fun `a delete step of the base is not a base`() {
        assertEquals(emptyMap(), PlanPairs.responseOf(planOf(MigrationAction.DeleteStream(name = "orders"), stream("orders-dln"))))
    }

    @Test
    fun `two candidates for one base are both response streams of it`() {
        val plan = planOf(stream("orders"), stream("orders-dln"), stream("orders_dln"))
        assertEquals(mapOf(2 to 1, 3 to 1), PlanPairs.responseOf(plan))
    }

    @Test
    fun `a chain pairs each stream with its own base`() {
        val plan = planOf(stream("a"), stream("a-dln"), stream("a-dln-dln"))
        assertEquals(mapOf(2 to 1, 3 to 2), PlanPairs.responseOf(plan))
    }

    @Test
    fun `the same stream declared twice is one base and both copies of the response are listed`() {
        val plan = planOf(stream("orders"), stream("orders"), stream("orders-dln"))
        assertEquals(1, PlanPairs.responseOf(plan).size)
        assertEquals(setOf(3), PlanPairs.responseOf(plan).keys)
    }

    @Test
    fun `a stream is never its own response and an empty plan has no pair`() {
        assertEquals(emptyMap(), PlanPairs.responseOf(TopologyPlan()))
        assertEquals(emptyMap(), PlanPairs.responseOf(planOf(stream("dln"))))
    }

    @Test
    fun `the answer follows the steps - removing the request leaves the response an ordinary stream`() {
        val plan = planOf(stream("telemetry"), stream("telemetry-dln"))
        assertEquals(mapOf(2 to 1), PlanPairs.responseOf(plan))
        assertEquals(emptyMap(), PlanPairs.responseOf(plan.remove(1)))
        assertEquals(mapOf(2 to 1), PlanPairs.responseOf(plan.moveUp(2)), "the order of steps does not matter")
    }

    @Test
    fun `a hostile name is only compared, never interpreted`() {
        val nasty = "a\nb`<x>\${y}"
        val plan = planOf(stream(nasty), stream("$nasty-dln"))
        assertEquals(mapOf(2 to 1), PlanPairs.responseOf(plan))
    }
}
