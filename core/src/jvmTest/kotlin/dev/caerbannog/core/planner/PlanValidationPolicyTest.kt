package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M9-08 slice 2, written first: the two notes of `PlanValidation.problems(plan, known: KnownTopology = KnownTopology.None)` (the default keeps every caller).
 * Notes are `PlanProblem(stepId, message, blocking = false)`, shown on EACH policy step involved; derived on every change, never stored.
 *  - OVERLAP: two policies of the plan that WOULD BIND to one queue or stream the plan declares: the pattern matches the name, `applyTo` suits the type (a declared
 *    stream is `streams`, a queue its type; `queues` and `all` suit all) and EVERY key of the definition is honoured by that type (`PolicyKeyMatrix.effect`, through
 *    the catalog). Different and equal priorities get the same note. A plan queue whose type string is neither `quorum` nor `classic` is unknown: no note for it.
 *  - NO EFFECT: a policy that matches a queue of the plan and suits its type but holds a key the type ignores; the first ignored key in catalog order is named by
 *    its label, or by the broker key when the catalog has no label. The type words are `quorum queue`, `classic queue`, `stream`.
 * Texts are the decided sentences of the scenarios, verbatim.
 */
class PlanValidationPolicyTest {
    private fun queue(name: String, type: String = "quorum") = MigrationAction.DeclareQueue(name, type)
    private fun stream(name: String) = MigrationAction.DeclareStream(name)
    private fun policy(name: String, pattern: String, vararg keys: String, applyTo: String = "all", priority: Int = 0) =
        MigrationAction.DeclarePolicy(name, pattern, keys.associateWith { sample(it) }, "/", applyTo, priority)

    private fun sample(key: String): ArgumentValue = when (key) {
        "max-age" -> ArgumentValue.Text("7D")
        "overflow" -> ArgumentValue.Text("reject-publish")
        "dead-letter-exchange", "dead-letter-routing-key", "alternate-exchange" -> ArgumentValue.Text("x")
        "queue-leader-locator" -> ArgumentValue.Text("balanced")
        "dead-letter-strategy" -> ArgumentValue.Text("at-least-once")
        else -> ArgumentValue.Whole(10)
    }

    private fun plan(vararg actions: MigrationAction) = TopologyPlan.of("", actions.toList(), "/")

    /**
     * The overlap and no-effect notes only (a plan can carry other problems), as (step position 1-based, message). The missing dead-letter exchange note
     * (the sample value "x" of a dead-letter key names an exchange that is nowhere) is a note of its own, pinned in the `21` scenarios.
     */
    private fun notes(plan: TopologyPlan, known: KnownTopology = KnownTopology.None): List<Pair<Int, String>> =
        PlanValidation.problems(plan, known).filter { !it.blocking && it.stepId != null && "olic" in it.message && "does not exist yet" !in it.message }
            .map { p -> plan.steps.indexOfFirst { it.id == p.stepId } + 1 to p.message }

    private fun overlap(a: String, b: String, on: String) =
        "The policies $a and $b both match $on. RabbitMQ applies only one policy to a queue: put the settings into one policy."

    private fun noEffect(on: String, type: String, ignored: String) =
        "This policy has no effect on $on: a $type ignores $ignored, and RabbitMQ then applies none of the policy's settings."

    // ---- overlap ----

    @Test
    fun `two policies that bind to one queue of the plan both get the note`() {
        val p = plan(queue("orders"), policy("orders-dlx", "^orders$", "max-length"), policy("orders-limits", "^orders", "message-ttl"))
        val message = overlap("orders-dlx", "orders-limits", "orders")
        assertEquals(listOf(2 to message, 3 to message), notes(p))
    }

    @Test
    fun `the note is not blocking and does not stop saving`() {
        val p = plan(queue("orders"), policy("a", "^orders$", "max-length"), policy("b", "^orders$", "max-length"))
        val problems = PlanValidation.problems(p).filter { it.stepId != null }
        assertTrue(problems.isNotEmpty() && problems.none { it.blocking }, "problems: $problems")
    }

    @Test
    fun `equal and different priorities give the same note`() {
        listOf(0 to 0, 1 to 5, 9 to 2).forEach { (x, y) ->
            val p = plan(queue("orders"), policy("a", "^orders$", "max-length", priority = x), policy("b", "^orders$", "max-length", priority = y))
            assertEquals(listOf(2 to overlap("a", "b", "orders"), 3 to overlap("a", "b", "orders")), notes(p), "priorities $x and $y")
        }
    }

    @Test
    fun `the order in the sentence is the order of the plan`() {
        val p = plan(queue("orders"), policy("zzz", "^orders$", "max-length"), policy("aaa", "^orders$", "max-length"))
        assertEquals(overlap("zzz", "aaa", "orders"), notes(p).first().second)
    }

    @Test
    fun `a retention policy that meets another policy of the plan on a stream is pointed out`() {
        val p = plan(policy("all-streams", "^telemetry", "max-length-bytes", applyTo = "streams"), stream("telemetry"), policy("telemetry-retention", "^telemetry$", "max-age", applyTo = "streams"))
        val message = overlap("all-streams", "telemetry-retention", "telemetry")
        assertEquals(listOf(1 to message, 3 to message), notes(p))
    }

    @Test
    fun `queues and all suit every kind, a queue type decides for the others`() {
        listOf("all", "queues").forEach { applyTo ->
            val p = plan(queue("orders"), policy("a", "^orders$", "max-length-bytes", applyTo = applyTo), policy("b", "^orders$", "max-length-bytes", applyTo = "quorum_queues"))
            assertEquals(2, notes(p).size, "apply-to $applyTo with quorum_queues")
        }
        val classic = plan(queue("orders", "classic"), policy("a", "^orders$", "max-length", applyTo = "classic_queues"), policy("b", "^orders$", "max-length", applyTo = "classic_queues"))
        assertEquals(2, notes(classic).size)
    }

    @Test
    fun `policies that cannot bind to one queue get no note`() {
        listOf("quorum_queues" to "streams", "streams" to "quorum_queues", "classic_queues" to "quorum_queues", "exchanges" to "all").forEach { (first, second) ->
            val p = plan(queue("orders"), policy("a", "^orders$", "alternate-exchange", applyTo = first), policy("b", "^orders$", "alternate-exchange", applyTo = second))
            assertEquals(emptyList(), notes(p).filter { "both match" in it.second }, "$first and $second")
        }
        val stream = plan(stream("orders"), policy("a", "^orders$", "max-length-bytes", applyTo = "quorum_queues"), policy("b", "^orders$", "max-length-bytes", applyTo = "streams"))
        assertEquals(emptyList(), notes(stream).filter { "both match" in it.second }, "a stream is not a quorum queue")
    }

    @Test
    fun `patterns that do not both match the same name get no note`() {
        val p = plan(queue("orders"), queue("invoices"), policy("a", "^orders$", "max-length"), policy("b", "^invoices$", "max-length"))
        assertEquals(emptyList(), notes(p))
    }

    @Test
    fun `a policy with a key the type ignores does not bind, so it does not overlap with a valid one`() {
        // S-F1b: one ignored key and the policy binds to nothing. b holds max-age, which a quorum queue ignores.
        val p = plan(queue("orders"), policy("a", "^orders$", "max-length"), policy("b", "^orders$", "max-length", "max-age"))
        assertEquals(emptyList(), notes(p).filter { "both match" in it.second })
        assertEquals(listOf(3), notes(p).filter { "no effect" in it.second }.map { it.first })
    }

    @Test
    fun `a pattern the app cannot evaluate gives no note and no crash`() {
        val p = plan(queue("orders"), policy("a", "(?=orders)", "max-length"), policy("b", "^orders$", "max-length"))
        assertEquals(emptyList(), notes(p))
    }

    @Test
    fun `a queue whose type is not quorum or classic is unknown, no note for it`() {
        val p = plan(queue("orders", "lazy"), policy("a", "^orders$", "max-length"), policy("b", "^orders$", "max-length"))
        assertEquals(emptyList(), notes(p))
    }

    @Test
    fun `no policy step means no note, and a plan with one policy has none either`() {
        assertEquals(emptyList(), notes(plan(queue("orders"))))
        assertEquals(emptyList(), notes(plan(queue("orders"), policy("a", "^orders$", "max-length"))))
    }

    @Test
    fun `the note reads the plan only, a queue that exists on the broker is not part of it`() {
        val p = plan(policy("a", "^orders$", "max-length"), policy("b", "^orders$", "max-length"))
        val known = KnownTopology(queues = setOf("orders"), queueDetails = mapOf("orders" to KnownQueue("quorum", null)))
        assertEquals(emptyList(), notes(p, known))
    }

    @Test
    fun `a policy that comes before the queue it matches still binds in the plan`() {
        val p = plan(policy("a", "^orders$", "max-length"), policy("b", "^orders$", "max-length"), queue("orders"))
        assertEquals(2, notes(p).size)
    }

    @Test
    fun `the default for known keeps every caller`() {
        val p = plan(queue("orders"), policy("a", "^orders$", "max-length"), policy("b", "^orders$", "max-length"))
        assertEquals(PlanValidation.problems(p, KnownTopology.None), PlanValidation.problems(p))
    }

    // ---- no effect ----

    @Test
    fun `a quorum queue that ignores a key of the policy is said to have no effect, with the label of the first ignored key`() {
        val p = plan(queue("orders"), policy("orders-old", "^orders$", "max-length", "max-age"))
        assertEquals(listOf(2 to noEffect("orders", "quorum queue", "Keep messages for")), notes(p))
    }

    @Test
    fun `classic queue and stream words`() {
        assertEquals(
            listOf(2 to noEffect("orders", "classic queue", "Delivery limit")),
            notes(plan(queue("orders", "classic"), policy("p", "^orders$", "max-length", "delivery-limit"))),
        )
        assertEquals(
            listOf(2 to noEffect("telemetry", "stream", "Dead-letter exchange")),
            notes(plan(stream("telemetry"), policy("p", "^telemetry$", "max-age", "dead-letter-exchange", applyTo = "streams"))),
        )
    }

    @Test
    fun `the first ignored key in catalog order is named, the others are not`() {
        // catalog order: max-length, ..., delivery-limit, ..., max-age: for a quorum queue the policy holds max-age and message-ttl; max-age is the only ignored one
        val p = plan(queue("orders"), policy("p", "^orders$", "max-age", "stream-max-segment-size-bytes", "message-ttl"))
        val message = notes(p).single().second
        assertTrue("Keep messages for" in message, message)
        assertFalse("Segment size" in message, "only the first ignored key is named: $message")
    }

    @Test
    fun `a key the catalog has no label for is named by its broker key`() {
        val p = plan(queue("orders"), policy("p", "^orders$", "max-length", "x-made-up"))
        assertEquals(listOf(2 to noEffect("orders", "quorum queue", "x-made-up")), notes(p))
    }

    @Test
    fun `no effect note only when the policy could bind by pattern and apply-to`() {
        // apply-to streams never matches a quorum queue: no note, whatever it holds
        assertEquals(emptyList(), notes(plan(queue("orders"), policy("p", "^orders$", "max-age", applyTo = "streams"))))
        // pattern does not match
        assertEquals(emptyList(), notes(plan(queue("orders"), policy("p", "^invoices$", "max-age"))))
    }

    @Test
    fun `a policy that is fine for the queue it matches has no note`() {
        assertEquals(emptyList(), notes(plan(queue("orders"), policy("p", "^orders$", "max-length", "delivery-limit"))))
        assertEquals(emptyList(), notes(plan(stream("telemetry"), policy("p", "^telemetry$", "max-age", "max-length-bytes", applyTo = "streams"))))
    }

    @Test
    fun `a policy that suits the stream but not the quorum queue it also matches is pointed out for the queue only`() {
        // apply-to all, pattern matches a quorum queue and a stream; max-age suits the stream only
        val p = plan(queue("orders"), stream("orders-log"), policy("p", "^orders", "max-age"))
        val messages = notes(p).map { it.second }
        assertEquals(listOf(noEffect("orders", "quorum queue", "Keep messages for")), messages)
    }
}
