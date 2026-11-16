package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** M9-01: plan operations are pure; each returns a new plan. Ids are session keys that never repeat inside one plan. */
class TopologyPlanTest {
    private fun q(name: String, vhost: String = "/") = MigrationAction.DeclareQueue(name = name, vhost = vhost)
    private fun plan3() = TopologyPlan().append(listOf(q("a"), q("b"), q("c")))
    private fun names(p: TopologyPlan) = p.steps.map { (it.action as MigrationAction.DeclareQueue).name }

    @Test
    fun `a new plan is empty with the root virtual host and no description`() {
        val p = TopologyPlan()
        assertEquals("", p.description)
        assertEquals("/", p.vhost)
        assertEquals(emptyList(), p.steps)
    }

    @Test
    fun `append adds at the end and gives every step a distinct id`() {
        val p = plan3().append(listOf(q("d"), q("e")))
        assertEquals(listOf("a", "b", "c", "d", "e"), names(p))
        assertEquals(5, p.steps.map { it.id }.toSet().size, "ids must be distinct")
    }

    @Test
    fun `append does not change the plan it was called on`() {
        val before = plan3()
        before.append(listOf(q("z")))
        assertEquals(listOf("a", "b", "c"), names(before))
    }

    @Test
    fun `ids of other steps stay when a step is removed and a new id never repeats a live one`() {
        val p = plan3()
        val (a, b, c) = p.steps.map { it.id }
        val removed = p.remove(b)
        assertEquals(listOf(a, c), removed.steps.map { it.id })
        val grown = removed.append(listOf(q("d")))
        assertEquals(3, grown.steps.map { it.id }.toSet().size)
    }

    @Test
    fun `replace changes one action and keeps its id and place`() {
        val p = plan3()
        val id = p.steps[1].id
        val r = p.replace(id, q("B2"))
        assertEquals(listOf("a", "B2", "c"), names(r))
        assertEquals(id, r.steps[1].id)
    }

    @Test
    fun `move up and down swap neighbours`() {
        val p = plan3()
        assertEquals(listOf("b", "a", "c"), names(p.moveUp(p.steps[1].id)))
        assertEquals(listOf("a", "c", "b"), names(p.moveDown(p.steps[1].id)))
    }

    @Test
    fun `moving the first step up or the last down changes nothing, and an unknown id changes nothing`() {
        val p = plan3()
        assertEquals(listOf("a", "b", "c"), names(p.moveUp(p.steps.first().id)))
        assertEquals(listOf("a", "b", "c"), names(p.moveDown(p.steps.last().id)))
        assertEquals(listOf("a", "b", "c"), names(p.remove(9999)))
        assertEquals(listOf("a", "b", "c"), names(p.moveUp(9999)))
        assertEquals(listOf("a", "b", "c"), names(p.replace(9999, q("x"))))
    }

    @Test
    fun `remove drops exactly one step`() {
        val p = plan3()
        assertEquals(listOf("a", "c"), names(p.remove(p.steps[1].id)))
    }

    @Test
    fun `set description keeps the steps`() {
        val p = plan3().withDescription("Orders")
        assertEquals("Orders", p.description)
        assertEquals(3, p.steps.size)
    }

    @Test
    fun `set virtual host moves every step to it, whatever kind it is`() {
        val p = TopologyPlan().append(
            listOf(
                q("a"),
                MigrationAction.DeclareExchange(name = "e"),
                MigrationAction.DeclareBinding(exchange = "e", queue = "a", routingKey = "k"),
                MigrationAction.DeclareStream(name = "s"),
                MigrationAction.DeclarePolicy(name = "p", pattern = "^a$"),
            ),
        )
        val moved = p.withVhost("uat")
        assertEquals("uat", moved.vhost)
        assertEquals(listOf("uat"), moved.steps.map { it.action.vhost }.distinct())
        assertEquals(p.steps.map { it.id }, moved.steps.map { it.id }, "ids stay")
    }

    @Test
    fun `the title of every step is the description of its action`() {
        val actions = listOf(
            q("a"),
            MigrationAction.DeclareExchange(name = "e", type = "topic"),
            MigrationAction.DeclareBinding(exchange = "e", queue = "a", routingKey = "k"),
            MigrationAction.DeclareBinding(exchange = "e", queue = "a"),
        )
        val p = TopologyPlan().append(actions)
        assertEquals(actions.map { it.description }, p.steps.map { it.title })
        assertEquals(actions.map { it.detail }, p.steps.map { it.detail })
        assertTrue(p.steps.isNotEmpty())
        assertSame(actions[0], p.steps[0].action)
    }
}
