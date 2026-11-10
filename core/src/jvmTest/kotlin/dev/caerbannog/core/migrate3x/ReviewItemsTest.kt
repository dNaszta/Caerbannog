package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** M8-05: which items need a decision. Blocker or Warning, scope Always, class Incompatibility or BehaviourChange, kind queue, exchange, policy, operator policy. */
class ReviewItemsTest {
    private fun itemsOf(vararg findings: Finding, document: DefinitionsDocument = D.document(queues = listOf(D.quorumQueue("x")))) =
        ConversionReview.start(document, syntheticReport(*findings)).items

    private val target = q("x")

    @Test
    fun `a Warning of class BehaviourChange that always applies needs a decision (the control case of the table below)`() {
        assertEquals(listOf(target), itemsOf(synthetic(target, Severity.Warning, FindingClass.BehaviourChange)))
    }

    @Test
    fun `a Blocker of class Incompatibility that always applies needs a decision`() {
        assertEquals(listOf(target), itemsOf(synthetic(target, Severity.Blocker, FindingClass.Incompatibility)))
    }

    @ParameterizedTest
    @EnumSource(value = FindingClass::class, names = ["MessageLoss", "NotCovered", "ClientCheck", "Access"])
    fun `message loss, not covered, client check and access findings never need a decision, even as blockers`(findingClass: FindingClass) {
        assertEquals(emptyList<ReviewItem>(), itemsOf(synthetic(target, Severity.Blocker, findingClass)), "Blocker of class $findingClass")
        assertEquals(emptyList<ReviewItem>(), itemsOf(synthetic(target, Severity.Warning, findingClass)), "Warning of class $findingClass")
    }

    @ParameterizedTest
    @EnumSource(value = FindingClass::class, names = ["Incompatibility", "BehaviourChange"])
    fun `an Info finding never needs a decision`(findingClass: FindingClass) {
        assertEquals(emptyList<ReviewItem>(), itemsOf(synthetic(target, Severity.Info, findingClass)))
    }

    @ParameterizedTest
    @EnumSource(value = AppliesWhen::class, names = ["ConvertedToQuorum", "KeptClassic"])
    fun `a finding that applies to one outcome only does not make the item need a decision`(scope: AppliesWhen) {
        assertEquals(emptyList<ReviewItem>(), itemsOf(synthetic(target, Severity.Blocker, FindingClass.Incompatibility, appliesWhen = scope)))
    }

    @ParameterizedTest
    @EnumSource(value = ItemKind::class, names = ["Queue", "Exchange", "Policy", "OperatorPolicy"])
    fun `queues, exchanges, policies and operator policies can need a decision`(kind: ItemKind) {
        val item = ReviewItem(kind, "/", "x")
        assertEquals(listOf(item), itemsOf(synthetic(item, Severity.Blocker, FindingClass.Incompatibility)))
    }

    @ParameterizedTest
    @EnumSource(value = ItemKind::class, names = ["Binding", "VirtualHost", "User", "Permission", "TopicPermission", "Parameter", "Global"])
    fun `bindings and every other kind never need a decision`(kind: ItemKind) {
        val item = ReviewItem(kind, "/", "x")
        assertEquals(emptyList<ReviewItem>(), itemsOf(synthetic(item, Severity.Blocker, FindingClass.Incompatibility)), "kind $kind")
    }

    @Test
    fun `an item with several findings appears once`() {
        val items = itemsOf(
            synthetic(target, Severity.Warning, FindingClass.BehaviourChange, rule = "a"),
            synthetic(target, Severity.Blocker, FindingClass.Incompatibility, rule = "b"),
        )
        assertEquals(listOf(target), items)
    }

    @Test
    fun `items come in report order and pending starts as all of them`() {
        val s = started(D.document(queues = listOf(D.classicQueue("a"), D.classicQueue("b"), D.quorumQueue("c"))))
        assertEquals(listOf(q("a"), q("b")), s.review.items)
        assertEquals(s.review.items, s.review.pending)
    }

    // ---- real analyzer output ----

    @Test
    fun `compatible items are not review items and the analyzer really ran`() {
        val s = started(
            D.document(
                queues = listOf(D.quorumQueue("orders"), D.streamQueue("events")),
                exchanges = listOf(D.exchange("direct-ex"), D.exchange("topic-ex", "topic")),
                bindings = listOf(D.binding("direct-ex", "orders")),
                policies = listOf(D.policy("limits", "^orders$", D.args("max-length" to 1000))),
                operatorPolicies = listOf(D.operatorPolicy("op-limits", "^orders$", D.args("max-length" to 1000))),
            ),
        )
        s.report.assertAnalyzed()
        assertEquals(emptyList<ReviewItem>(), s.review.items)
    }

    @Test
    fun `a message-loss finding next to a conversion finding does not add anything of its own`() {
        val s = started(D.document(queues = listOf(D.classicQueue("orders"))), D.liveFacts(indexVersions = mapOf(QueueRef("/", "orders") to ClassicIndexVersion.V1)))
        assertTrue(s.report.findings.any { it.findingClass == FindingClass.MessageLoss && it.name == "orders" }, "the report has no message-loss finding for orders")
        assertEquals(listOf(q("orders")), s.review.items)
    }

    @Test
    fun `a plugin exchange type, an unknown-type policy key and a non-durable classic queue need a decision`() {
        val s = started(
            D.document(
                queues = listOf(D.classicQueue("temp", durable = false)),
                exchanges = listOf(D.exchange("delayed", "x-delayed-message")),
                policies = listOf(D.policy("ha", "^a$", D.args("ha-mode" to "all"))),
                operatorPolicies = listOf(D.operatorPolicy("op", "^a$", D.args("queue-mode" to "lazy"))),
            ),
        )
        assertEquals(setOf(q("temp"), ex("delayed"), pol("ha"), opPol("op")), s.review.items.toSet())
    }

    @Test
    fun `the full 3_13 fixture needs exactly these 14 decisions`() {
        val s = started(fullFixture())
        val expected = setOf("classic-autodel", "classic-dlx", "classic-lazy", "classic-locator", "classic-maxlen-bytes", "classic-mirrored", "classic-plain", "classic-prio", "classic-sac", "classic-transient", "classic-ttl")
            .map { q(it) }.toSet() + q("v1-q", "v1") + pol("ha-all") + pol("ha-exactly")
        assertEquals(14, s.report.summary.needDecision, "the golden summary says 14 items need a decision")
        assertEquals(expected, s.review.items.toSet())
        assertEquals(14, s.review.items.size, "an item must not appear twice")
    }

    @Test
    fun `toString shows counts only`() {
        val s = started(fullFixture())
        val text = s.review.toString()
        assertTrue(text.contains("14"), "toString should show the number of items: $text")
        assertTrue(!text.contains("classic-plain") && !text.contains("SENTINEL"), "toString must not list names or secrets: $text")
    }

    @Test
    fun `items are ordered by their first qualifying finding`() {
        val a = q("a"); val b = q("b")
        val doc = D.document(queues = listOf(D.quorumQueue("a"), D.quorumQueue("b")))
        val report = syntheticReport(
            synthetic(b, Severity.Info, rule = "r1"), synthetic(a, Severity.Warning, rule = "r2"), synthetic(b, Severity.Warning, rule = "r3"),
        )
        assertEquals(listOf(a, b), ConversionReview.start(doc, report).items)
    }
}
