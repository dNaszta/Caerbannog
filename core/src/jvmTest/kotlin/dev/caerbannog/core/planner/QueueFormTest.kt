package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueFormTest {
    @Test
    fun `the default type is quorum`() {
        assertEquals("quorum", QueueForm(name = "jobs").type)
        assertEquals(
            listOf(MigrationAction.DeclareQueue(name = "jobs", type = "quorum")),
            QueueForm(name = "jobs").actions(ctx()),
        )
    }

    @Test
    fun `a classic queue is declared as classic and takes the virtual host of the plan`() {
        val c = ctx(plan = TopologyPlan(vhost = "uat"))
        assertEquals(
            listOf(MigrationAction.DeclareQueue(name = "jobs", type = "classic", vhost = "uat")),
            QueueForm(name = "jobs", type = "classic").actions(c),
        )
    }

    @Test
    fun `problems as a table`() {
        val table = mapOf(
            "" to "Give the queue a name",
            "." to "a name must not be \".\" or \"..\"",
            ".." to "a name must not be \".\" or \"..\"",
        )
        for ((name, message) in table) {
            assertEquals(listOf(FieldProblem("name", message)), QueueForm(name = name).problems(ctx()), "name \"$name\"")
        }
        for (ok in listOf("orders", "Orders_EU", "...", "a.b", " x ")) {
            assertEquals(emptyList(), QueueForm(name = ok).problems(ctx()), "name \"$ok\"")
        }
    }

    @Test
    fun `a form with a problem builds no action`() {
        assertEquals(emptyList(), QueueForm(name = "").actions(ctx()))
    }

    @Test
    fun `a hint is not a problem, and the hint follows the naming style`() {
        val f = QueueForm(name = "Orders_EU")
        assertEquals(emptyList(), f.problems(ctx()))
        assertEquals(
            listOf(FieldHint("name", "This name does not follow kebab-case, for example orders-eu")),
            f.hints(ctx()),
        )
        assertEquals(emptyList(), f.hints(ctx(style = NamingStyle.NONE)))
        assertEquals(emptyList(), QueueForm(name = "").hints(ctx()), "no hint for an empty name")
    }

    @Test
    fun `no hint for a name the broker already has`() {
        val known = KnownTopology(queues = setOf("Legacy_Orders"))
        assertEquals(emptyList(), QueueForm(name = "Legacy_Orders").hints(ctx(known = known)))
    }

    @Test
    fun `formOf is the inverse for a plain queue and null for what a form cannot hold`() {
        val plain = MigrationAction.DeclareQueue(name = "orders", type = "classic")
        assertEquals(QueueForm(name = "orders", type = "classic"), StepForms.formOf(plain))
        assertNull(StepForms.formOf(MigrationAction.DeclareQueue(name = "o", durable = false)))
        assertNull(StepForms.formOf(MigrationAction.DeclareQueue(name = "o", autoDelete = true)))
        assertNull(
            StepForms.formOf(
                MigrationAction.DeclareQueue(name = "o", arguments = mapOf("x-single-active-consumer" to dev.caerbannog.core.json.ArgumentValue.Flag(true))),
            ),
        )
        assertNull(StepForms.formOf(MigrationAction.DeleteQueue(name = "o")))
    }
}
