package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrate3x.fakes.ConversionReviews
import dev.caerbannog.core.migrations.ActionsParseResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M8-06b: the four additions of `ConversionReview` ("Core additions"): findingsOf, previewActions, refusalOf, undecide. Real analyzer output;
 * the sheet of the screen is built from these, so what they return is what the user sees and what `generate` writes.
 */
class ConversionReviewViewApiTest {
    private val orders = ReviewItem(ItemKind.Queue, "/", "orders")
    private val prio = ReviewItem(ItemKind.Queue, "/", "prio")
    private val temp = ReviewItem(ItemKind.Queue, "/", "temp")
    private val haAll = ReviewItem(ItemKind.Policy, "/", "ha-all")

    private val document = D.document(
        queues = listOf(D.classicQueue("orders"), D.classicQueue("prio", arguments = D.args("x-max-priority" to 5)), D.classicQueue("temp", durable = false), D.quorumQueue("modern")),
        bindings = listOf(D.binding("amq.direct", "orders", "k")),
        policies = listOf(D.policy("ha-all", "^orders", D.args("ha-mode" to "all", "max-length" to 10))),
    )
    private val started = ConversionReviews.start(document)
    private val review get() = started.first
    private val report get() = started.second

    private fun fresh() = ConversionReviews.start(document).first

    /** The id comes from the report itself, not from `findingsOf`, so a failing `findingsOf` does not hide the other tests. */
    private fun finding(rule: String, item: ReviewItem) = report.findings.first { it.rule == rule && it.name == item.name && it.kind == item.kind }
    private fun tick() = setOf(Acknowledgement.OrderingByPriorityMayChange)

    // ---- findingsOf ----

    @Test
    fun `findingsOf returns every finding that can change the declaration, any severity and any appliesWhen, in report order`() {
        val got = review.findingsOf(orders)
        assertEquals(listOf("classic-not-replicated", "classic-to-quorum-ordering"), got.map { it.rule })
        assertTrue(got.any { it.appliesWhen == AppliesWhen.ConvertedToQuorum }, "a finding that applies to one outcome only is returned too")
        val expected = report.findings.filter { it.kind == ItemKind.Queue && it.name == "orders" && it.findingClass in setOf(FindingClass.Incompatibility, FindingClass.BehaviourChange) }
        assertEquals(expected, got, "same findings, same order as the report")
    }

    @Test
    fun `findingsOf has no message-loss finding, no client check and no finding of another item`() {
        val got = review.findingsOf(orders)
        assertTrue(report.findings.any { it.findingClass == FindingClass.MessageLoss && it.name == "orders" }, "the premise: orders has a message-loss finding")
        assertTrue(got.isNotEmpty(), "findingsOf(orders) is empty")
        assertTrue(got.none { it.findingClass == FindingClass.MessageLoss || it.findingClass == FindingClass.ClientCheck }, "$got")
        assertTrue(got.all { it.name == "orders" })
    }

    @Test
    fun `findingsOf is empty for an unknown item, a compatible item, a binding and a wrong vhost`() {
        assertTrue(review.findingsOf(orders).isNotEmpty(), "premise: a real item has findings")
        assertEquals(emptyList(), review.findingsOf(ReviewItem(ItemKind.Queue, "/", "nope")))
        assertEquals(emptyList(), review.findingsOf(ReviewItem(ItemKind.Queue, "/", "modern")))
        assertEquals(emptyList(), review.findingsOf(ReviewItem(ItemKind.Binding, "/", "amq.direct->orders:k")))
        assertEquals(emptyList(), review.findingsOf(ReviewItem(ItemKind.Queue, "other", "orders")))
    }

    // ---- previewActions ----

    @Test
    fun `previewActions of the default proposal declares the queue as a quorum queue`() {
        val actions = review.previewActions(orders)
        assertEquals(listOf(MigrationAction.DeclareQueue("orders", "quorum", "/", true, emptyMap(), false)), actions)
    }

    @Test
    fun `previewActions follows the chosen alternative`() {
        val id = finding("classic-not-replicated", orders).id
        val kept = review.previewActions(orders, ItemDecision.Accept(chosen = mapOf(id to 1)))
        assertEquals(1, kept.size, "one declaration for the queue: $kept")
        assertEquals("classic", (kept.single() as MigrationAction.DeclareQueue).type)
        val prioId = finding("classic-not-replicated", prio).id
        val toQuorumActions = review.previewActions(prio, ItemDecision.Accept(chosen = mapOf(prioId to 1), acknowledged = tick()))
        assertEquals(1, toQuorumActions.size, "one declaration for the queue: $toQuorumActions")
        val toQuorum = toQuorumActions.single() as MigrationAction.DeclareQueue
        assertEquals("quorum", toQuorum.type)
        assertFalse("x-max-priority" in toQuorum.arguments, "a quorum queue rejects x-max-priority: ${toQuorum.arguments}")
        assertEquals(1, review.previewActions(prio).size)
        val keptPrio = review.previewActions(prio).single() as MigrationAction.DeclareQueue
        assertEquals(listOf("classic", ArgumentValue.Whole(5)), listOf(keptPrio.type, keptPrio.arguments["x-max-priority"]))
    }

    @Test
    fun `previewActions has no refusal check - a manual-only item returns its declaration as the file holds it`() {
        val actions = review.previewActions(temp)
        assertEquals(listOf(MigrationAction.DeclareQueue("temp", "classic", "/", durable = false, arguments = emptyMap(), autoDelete = false)), actions)
        val durableId = finding("queue-not-durable", temp).id
        val durable = review.previewActions(temp, ItemDecision.Accept(chosen = mapOf(durableId to 1))).single() as MigrationAction.DeclareQueue
        assertTrue(durable.durable)
    }

    @Test
    fun `previewActions does not need the tick and does not change any state`() {
        val before = review.toString()
        assertEquals(1, review.previewActions(orders, ItemDecision.Accept(acknowledged = emptySet())).size)
        assertEquals(before, review.toString())
        assertNull(review.decisionOf(orders))
        assertEquals(review.items, review.pending)
    }

    @Test
    fun `previewActions never contains a delete and never an action for another item`() {
        for (item in review.items) {
            val actions = review.previewActions(item)
            assertTrue(actions.isNotEmpty(), "$item: nothing to write")
            assertTrue(actions.none { it.isDestructive }, "$item: $actions")
            assertTrue(actions.all { it.vhost == item.vhost }, "$item: $actions")
        }
    }

    @Test
    fun `previewActions of a policy that holds only dropped keys is empty, and with other keys it keeps them`() {
        val dropOnly = ConversionReviews.start(D.document(policies = listOf(D.policy("mirror", "^x", D.args("ha-mode" to "all"))))).first
        assertEquals(emptyList(), dropOnly.previewActions(ReviewItem(ItemKind.Policy, "/", "mirror")))
        assertEquals(1, review.previewActions(haAll).size, "premise: the policy keeps one key")
        val kept = review.previewActions(haAll).single() as MigrationAction.DeclarePolicy
        assertEquals(setOf("max-length"), kept.definition.keys)
    }

    @Test
    fun `previewActions is empty for an unknown item and for a name that cannot be written`() {
        assertTrue(review.previewActions(orders).isNotEmpty(), "premise: a real item has actions")
        assertEquals(emptyList(), review.previewActions(ReviewItem(ItemKind.Queue, "/", "nope")))
        val dots = ConversionReviews.start(D.document(queues = listOf(D.classicQueue("..")))).first
        assertEquals(emptyList(), dots.previewActions(ReviewItem(ItemKind.Queue, "/", "..")))
    }

    @Test
    fun `previewActions throws IllegalArgumentException for a chosen index out of range`() {
        val id = finding("classic-not-replicated", orders).id
        assertFailsWith<IllegalArgumentException> { review.previewActions(orders, ItemDecision.Accept(chosen = mapOf(id to 7))) }
        assertFailsWith<IllegalArgumentException> { review.previewActions(orders, ItemDecision.Accept(chosen = mapOf(id to -1))) }
    }

    @Test
    fun `previewActions equals what generate writes for each item after decide with the same accept - default accept and chosen alternatives`() {
        val prioId = finding("classic-not-replicated", prio).id
        val tempId = finding("queue-not-durable", temp).id
        val cases = mapOf(
            orders to ItemDecision.Accept(acknowledged = tick()),                              // a default accept with its tick
            prio to ItemDecision.Accept(chosen = mapOf(prioId to 1), acknowledged = tick()),   // the quorum alternative
            temp to ItemDecision.Accept(chosen = mapOf(tempId to 1)),                          // the automatic alternative of a manual blocker
            haAll to ItemDecision.Accept(),
        )
        assertEquals(review.items.toSet(), cases.keys, "premise: the cases are every item, so generate is not Incomplete")
        val r = fresh()
        val previews = cases.mapValues { (item, accept) -> r.previewActions(item, accept) }
        for ((item, accept) in cases) assertEquals(DecisionResult.Decided, r.decide(item, accept), "premise: $item is accepted")
        val written = (r.generate(GenerationRequest(emptySet(), "legacy.json")) as GenerationResult.Generated).migrations.single().file.up
        assertEquals(previews.values.flatten().sortedBy { it.description }, written.filter { it !is MigrationAction.DeclareBinding && (it as? MigrationAction.DeclareQueue)?.name != "modern" }.sortedBy { it.description },
            "the actions of the previews are exactly the actions generate wrote for the review items (the compatible queue and the binding are written as they are)")
    }

    // ---- refusalOf ----

    @Test
    fun `refusalOf says what decide would say, for every kind of refusal and for no refusal`() {
        val prioId = finding("classic-not-replicated", prio).id
        val tempId = finding("queue-not-durable", temp).id
        val decisions: List<Pair<ReviewItem, ItemDecision>> = listOf(
            orders to ItemDecision.Accept(),
            orders to ItemDecision.Accept(acknowledged = tick()),
            prio to ItemDecision.Accept(),
            prio to ItemDecision.Accept(chosen = mapOf(prioId to 1)),
            temp to ItemDecision.Accept(),
            temp to ItemDecision.Accept(chosen = mapOf(tempId to 1)),
            orders to ItemDecision.Skip,
            orders to ItemDecision.Replace(listOf(MigrationAction.DeleteQueue("orders"))),
            orders to ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("orders", "quorum", "other"))),
            orders to ItemDecision.Replace(listOf(MigrationAction.DeclareQueue(".."))),
            orders to ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("orders"))),
            ReviewItem(ItemKind.Queue, "/", "modern") to ItemDecision.Skip,
            ReviewItem(ItemKind.Binding, "/", "amq.direct->orders:k") to ItemDecision.Skip,
        )
        for ((item, decision) in decisions) {
            val r = fresh()
            val refusal = r.refusalOf(item, decision)
            val result = r.decide(item, decision)
            when (result) {
                DecisionResult.Decided -> assertNull(refusal, "decide stored $decision for $item but refusalOf said $refusal")
                is DecisionResult.Refused -> assertEquals(result.reason, refusal, "for $item $decision")
            }
        }
        assertIs<DecisionRefusal.TickMissing>(review.refusalOf(orders, ItemDecision.Accept()))
        assertIs<DecisionRefusal.ManualNeeded>(review.refusalOf(temp, ItemDecision.Accept()))
        assertEquals(DecisionRefusal.NotAReviewItem, review.refusalOf(ReviewItem(ItemKind.Queue, "/", "modern"), ItemDecision.Skip))
    }

    @Test
    fun `refusalOf changes nothing and a refusal leaves an earlier decision as it was`() {
        val r = fresh()
        assertNull(r.refusalOf(orders, ItemDecision.Accept(acknowledged = tick())))
        assertNull(r.decisionOf(orders), "refusalOf must not store the decision it checked")
        assertEquals(r.items, r.pending)
        r.decide(orders, ItemDecision.Skip)
        assertIs<DecisionRefusal.TickMissing>(r.refusalOf(orders, ItemDecision.Accept()))
        assertEquals(ItemDecision.Skip, r.decisionOf(orders))
    }

    @Test
    fun `refusalOf with a chosen index out of range throws like decide`() {
        val id = finding("classic-not-replicated", orders).id
        val decision = ItemDecision.Accept(chosen = mapOf(id to 9))
        assertFailsWith<IllegalArgumentException> { review.refusalOf(orders, decision) }
    }

    @Test
    fun `a review item with a right-to-left override in its name has a toString without the raw character`() {
        val item = ReviewItem(ItemKind.Queue, "/", "a\u202Eb\u0007")
        val text = item.toString()
        assertFalse(text.contains('\u202E') || text.contains('\u0007'), text)
        assertTrue(text.contains("a\\u202Eb"), text)
    }

    // ---- undecide ----

    @Test
    fun `undecide removes a decision and answers true, also when there was none`() {
        val r = fresh()
        r.decide(orders, ItemDecision.Skip)
        assertTrue(r.undecide(orders))
        assertNull(r.decisionOf(orders))
        assertTrue(orders in r.pending)
        assertTrue(r.undecide(orders), "true also when the item had no decision")
        assertEquals(r.items, r.pending)
    }

    @Test
    fun `undecide answers false for anything that is not a review item and touches nothing`() {
        val r = fresh()
        r.decide(orders, ItemDecision.Skip)
        assertFalse(r.undecide(ReviewItem(ItemKind.Queue, "/", "modern")))
        assertFalse(r.undecide(ReviewItem(ItemKind.Queue, "/", "nope")))
        assertFalse(r.undecide(ReviewItem(ItemKind.Binding, "/", "amq.direct->orders:k")))
        assertEquals(ItemDecision.Skip, r.decisionOf(orders))
        assertTrue(r.undecide(orders), "premise: a real item answers true")
    }

    @Test
    fun `after undecide generate refuses again with the item pending`() {
        val r = fresh()
        ConversionReviews.generateAll(r, report)
        r.undecide(orders)
        assertEquals(GenerationResult.Incomplete(listOf(orders)), r.generate(GenerationRequest(emptySet(), "x")))
    }

    @Test
    fun `MigrationParser round trip feeds the Replace sheet - previewActions as text parses back to the same actions`() {
        val actions = review.previewActions(orders)
        assertEquals(ActionsParseResult.Parsed(actions), MigrationParser.parseActions(MigrationParser.actionsToJson(actions)))
        assertEquals(DecisionResult.Decided, review.decide(orders, ItemDecision.Replace(actions)))
    }
}
