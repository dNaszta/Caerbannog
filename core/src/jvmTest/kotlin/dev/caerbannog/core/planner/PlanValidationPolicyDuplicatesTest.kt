package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M9-12 step 2, written first (gap 10 of M9-08, decided by the owner on 2026-11-18, spec O20): `PlanValidation.problems` tracks a policy step and an operator
 * policy step by (vhost, kind, name), the kinds being their own: a policy and an operator policy of one name do not clash, and neither clashes with a queue,
 * an exchange or a stream of that name. The LATER step carries the problem, like the existing duplicates. Texts are the decided sentences, word for word:
 *   blocking (the two actions differ):  "The plan already declares the policy <name> with other settings"
 *   note (the two actions are equal):   "The plan declares the policy <name> twice with the same settings"
 *   and the same with "the operator policy <name>".
 * The name goes through the echo rule. Where it may not be echoed the blocking sentence reads "The plan already declares a policy of that name with other settings"
 * (decided). The three other fallbacks (operator blocking, both notes) are NOT in the owner's text: they follow the same pattern and are pinned here as the
 * architect's call to confirm ("an operator policy of that name"; "a policy of that name twice with the same settings").
 */
class PlanValidationPolicyDuplicatesTest {
    private fun policy(name: String, limit: Long = 1000, vhost: String = "/", pattern: String = "^orders$") =
        MigrationAction.DeclarePolicy(name, pattern, mapOf("max-length" to ArgumentValue.Whole(limit)), vhost, "queues")

    private fun operator(name: String, limit: Long = 1000, vhost: String = "/") =
        MigrationAction.DeclareOperatorPolicy(name, "^orders$", mapOf("max-length" to ArgumentValue.Whole(limit)), vhost, "queues")

    private fun problems(vararg actions: MigrationAction) = PlanValidation.problems(TopologyPlan.of("", actions.toList(), "/"))
        .filter { it.message.contains("policy") && it.message.contains("declares") }

    @Test
    fun `two policies of one name with other settings block, on the later step, with the decided sentence`() {
        val found = problems(policy("orders-dlx", 1000), policy("orders-dlx", 2000))
        assertEquals(listOf(PlanProblem(2, "The plan already declares the policy orders-dlx with other settings", blocking = true)), found)
    }

    @Test
    fun `two identical policy steps are a note that does not block, on the later step`() {
        val found = problems(policy("orders-dlx"), policy("orders-dlx"))
        assertEquals(listOf(PlanProblem(2, "The plan declares the policy orders-dlx twice with the same settings", blocking = false)), found)
    }

    @Test
    fun `a difference in the pattern alone is other settings`() {
        val found = problems(policy("p", pattern = "^a$"), policy("p", pattern = "^b$"))
        assertEquals(listOf(true), found.map { it.blocking })
    }

    @Test
    fun `two operator policies of one name say the operator policy`() {
        assertEquals(
            listOf(PlanProblem(2, "The plan already declares the operator policy op-limit with other settings", blocking = true)),
            problems(operator("op-limit", 1), operator("op-limit", 2)),
        )
        assertEquals(
            listOf(PlanProblem(2, "The plan declares the operator policy op-limit twice with the same settings", blocking = false)),
            problems(operator("op-limit"), operator("op-limit")),
        )
    }

    @Test
    fun `a policy and an operator policy of one name do not clash`() {
        assertEquals(emptyList(), problems(policy("same"), operator("same", 5)))
        assertEquals(emptyList(), problems(operator("same", 5), policy("same")))
    }

    @Test
    fun `a policy does not clash with a queue, an exchange or a stream of its name`() {
        val all = PlanValidation.problems(
            TopologyPlan.of(
                "",
                listOf(MigrationAction.DeclareQueue("same"), MigrationAction.DeclareExchange("same"), MigrationAction.DeclareStream("same"), policy("same"), operator("same")),
                "/",
            ),
        ).filter { it.stepId in 4..5 }
        assertEquals(emptyList(), all)
    }

    @Test
    fun `the same policy name in another virtual host is another policy`() {
        assertEquals(emptyList(), problems(policy("p", vhost = "/"), policy("p", vhost = "orders")))
        assertEquals(emptyList(), problems(operator("p", vhost = "/"), operator("p", vhost = "orders")))
    }

    @Test
    fun `three steps of one name each later one carries its own problem`() {
        val found = problems(policy("p", 1), policy("p", 2), policy("p", 3))
        assertEquals(listOf(2 to true, 3 to true), found.map { it.stepId to it.blocking })
    }

    @Test
    fun `different names are no duplicate and a policy alone has none`() {
        assertEquals(emptyList(), problems(policy("a"), policy("b")))
        assertEquals(emptyList(), problems(policy("a")))
    }

    @Test
    fun `a name that may not be echoed is left out of the blocking sentence`() {
        val odd = "p \"x\"\nline"
        assertEquals(
            listOf("The plan already declares a policy of that name with other settings"),
            problems(policy(odd, 1), policy(odd, 2)).map { it.message },
        )
        val long = "p".repeat(65)
        assertEquals(
            listOf("The plan already declares a policy of that name with other settings"),
            problems(policy(long, 1), policy(long, 2)).map { it.message },
        )
    }

    @Test
    fun `the other fallbacks follow the same pattern (architect to confirm)`() {
        val odd = "p \"x\""
        assertEquals(listOf("The plan already declares an operator policy of that name with other settings"), problems(operator(odd, 1), operator(odd, 2)).map { it.message })
        assertEquals(listOf("The plan declares a policy of that name twice with the same settings"), problems(policy(odd), policy(odd)).map { it.message })
        assertEquals(listOf("The plan declares an operator policy of that name twice with the same settings"), problems(operator(odd), operator(odd)).map { it.message })
    }

    @Test
    fun `the blocking one stops the save and the note does not`() {
        val blocking = PlanValidation.problems(TopologyPlan.of("", listOf(policy("p", 1), policy("p", 2)), "/"))
        val note = PlanValidation.problems(TopologyPlan.of("", listOf(policy("p"), policy("p")), "/"))
        assertEquals(1, blocking.count { it.blocking })
        assertEquals(0, note.count { it.blocking })
    }
}
