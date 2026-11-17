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

    // ---- M9-01b: the broker's name rules (S-H of docs/tasks/M9-00c-spike-results.md) ----

    private val reserved = "Names that start with \"amq.\" are reserved"
    private val tooLong = "A queue name can be at most 255 bytes long"

    private fun queueProblems(name: String) = QueueForm(name = name).problems(ctx())

    @Test
    fun `an amq prefix is refused with the fixed message, whatever follows`() {
        for (name in listOf("amq.orders", "amq.", "amq.gen-x", "amq.a.b")) {
            assertEquals(listOf(FieldProblem("name", reserved)), queueProblems(name), "name \"$name\"")
        }
    }

    @Test
    fun `only the lower-case prefix with its dot is reserved`() {
        for (ok in listOf("amq", "AMQ.orders", "Amq.orders", "amqx", "amq-orders", "amq_orders", "xamq.orders", " amq.orders", "a.amq.orders")) {
            assertEquals(emptyList(), queueProblems(ok), "name \"$ok\"")
        }
    }

    @Test
    fun `the length rule counts UTF-8 bytes, 255 pass and 256 do not`() {
        val table = mapOf(
            "a".repeat(254) to true,
            "a".repeat(255) to true,
            "a".repeat(256) to false,
            "é".repeat(127) + "a" to true, // 255 bytes in 128 characters
            "é".repeat(127) to true, // 254 bytes
            "é".repeat(128) to false, // 256 bytes in 128 characters
            "€".repeat(85) to true, // 255 bytes
            "€".repeat(85) + "a" to false, // 256 bytes
            "a".repeat(251) + "\uD83D\uDE00" to true, // 251 + one four-byte character = 255 bytes
            "a".repeat(252) + "\uD83D\uDE00" to false, // 256 bytes
            "a".repeat(253) + "\uD83D\uDE00" to false, // 257 bytes
        )
        for ((name, accepted) in table) {
            val expected = if (accepted) emptyList() else listOf(FieldProblem("name", tooLong))
            assertEquals(expected, queueProblems(name), "a name of ${name.length} characters")
        }
    }

    @Test
    fun `a long name of 256 letters is refused with the new text, not with the parser's reason`() {
        // M9-01b step 2 check: the migration parser's round trip has no length rule, so the order "byte length, then the parser" shows only one message.
        assertEquals(listOf(FieldProblem("name", tooLong)), queueProblems("a".repeat(256)))
        assertEquals(listOf(FieldProblem("name", tooLong)), queueProblems("a".repeat(5000)))
    }

    @Test
    fun `one message per field, in the order empty, dot, amq prefix, byte length`() {
        assertEquals(listOf(FieldProblem("name", "Give the queue a name")), queueProblems(""))
        assertEquals(listOf(FieldProblem("name", "a name must not be \".\" or \"..\"")), queueProblems(".."))
        // a name that breaks the prefix rule and the length rule says the prefix only
        assertEquals(listOf(FieldProblem("name", reserved)), queueProblems("amq." + "a".repeat(300)))
    }

    @Test
    fun `a space and a slash are not refused, and a refused name builds no action`() {
        for (ok in listOf("orders eu", "orders/eu", "a b/c")) assertEquals(emptyList(), queueProblems(ok), "name \"$ok\"")
        assertEquals(emptyList(), QueueForm(name = "amq.orders").actions(ctx()))
        assertEquals(emptyList(), QueueForm(name = "a".repeat(256)).actions(ctx()))
        assertEquals(1, QueueForm(name = "a".repeat(255)).actions(ctx()).size)
    }

    @Test
    fun `the rule does not depend on the naming style or on what the broker has`() {
        for (style in NamingStyle.entries) assertEquals(listOf(FieldProblem("name", reserved)), QueueForm(name = "amq.orders").problems(ctx(style = style)), style.label)
        val known = KnownTopology(queues = setOf("amq.orders"))
        assertEquals(listOf(FieldProblem("name", reserved)), QueueForm(name = "amq.orders").problems(ctx(known = known)))
    }
}
