package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** M8-05: what `decide` accepts and refuses. The ordering tick is data enforced here (P15). */
class ItemDecisionTest {
    private val ordering = Acknowledgement.OrderingByPriorityMayChange

    private fun classic(vararg args: Pair<String, Any>) = started(D.document(queues = listOf(D.classicQueue("orders", arguments = D.args(*args)))))

    private fun refused(reason: DecisionRefusal) = DecisionResult.Refused(reason)

    @Test
    fun `the default of a classic queue converts to quorum and is refused without the tick`() {
        val s = classic()
        assertEquals(refused(DecisionRefusal.TickMissing(setOf(ordering))), s.review.decide(q("orders"), ItemDecision.Accept()))
        assertEquals(listOf(q("orders")), s.review.pending, "a refused decision decides nothing")
        assertEquals(null, s.review.decisionOf(q("orders")))
    }

    @Test
    fun `the same default is accepted with the tick`() {
        val s = classic()
        val decision = ItemDecision.Accept(acknowledged = TICK)
        assertEquals(DecisionResult.Decided, s.review.decide(q("orders"), decision))
        assertEquals(decision, s.review.decisionOf(q("orders")))
        assertEquals(emptyList<ReviewItem>(), s.review.pending)
    }

    @Test
    fun `a tick that nothing asks for is harmless`() {
        val s = classic()
        val id = s.findingId("classic-not-replicated", "orders")
        assertEquals(DecisionResult.Decided, s.review.decide(q("orders"), ItemDecision.Accept(mapOf(id to 1), TICK)))
    }

    @Test
    fun `keeping the classic queue needs no tick`() {
        val s = classic()
        val id = s.findingId("classic-not-replicated", "orders")
        assertEquals(DecisionResult.Decided, s.review.decide(q("orders"), ItemDecision.Accept(chosen = mapOf(id to 1))))
    }

    @Test
    fun `a queue with x-max-priority keeps classic by default and needs no tick, the quorum alternative needs it`() {
        val s = classic("x-max-priority" to 5)
        val id = s.findingId("classic-not-replicated", "orders")
        assertEquals(refused(DecisionRefusal.TickMissing(setOf(ordering))), s.review.decide(q("orders"), ItemDecision.Accept(chosen = mapOf(id to 1))))
        assertEquals(DecisionResult.Decided, s.review.decide(q("orders"), ItemDecision.Accept(chosen = mapOf(id to 1), acknowledged = TICK)))
        assertEquals(DecisionResult.Decided, s.review.decide(q("orders"), ItemDecision.Accept()), "the default (keep classic) needs no tick")
    }

    @Test
    fun `a non-durable queue cannot be accepted as it is because its blocker has no automatic fix`() {
        val s = started(D.document(queues = listOf(D.classicQueue("temp", durable = false))))
        val id = s.findingId("queue-not-durable", "temp")
        assertEquals(refused(DecisionRefusal.ManualNeeded(listOf(id))), s.review.decide(q("temp"), ItemDecision.Accept(acknowledged = TICK)))
        assertEquals(listOf(q("temp")), s.review.pending)
    }

    @Test
    fun `a non-durable queue can be decided by declaring it durable, skipping it or replacing it`() {
        val s = started(D.document(queues = listOf(D.classicQueue("temp", durable = false), D.classicQueue("tmp2", durable = false), D.classicQueue("tmp3", durable = false))))
        val makeDurable = s.findingId("queue-not-durable", "temp")
        assertEquals(DecisionResult.Decided, s.review.decide(q("temp"), ItemDecision.Accept(mapOf(makeDurable to 1))))
        assertEquals(DecisionResult.Decided, s.review.decide(q("tmp2"), ItemDecision.Skip))
        assertEquals(DecisionResult.Decided, s.review.decide(q("tmp3"), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("tmp3", "quorum")))))
        assertEquals(emptyList<ReviewItem>(), s.review.pending)
    }

    @Test
    fun `a blocker with an automatic fix does not need a manual decision`() {
        val s = classic("x-queue-master-locator" to "min-masters")
        assertEquals(DecisionResult.Decided, s.review.decide(q("orders"), ItemDecision.Accept(acknowledged = TICK)))
    }

    @Test
    fun `a message-loss finding does not refuse an accept (S-N)`() {
        val s = started(D.document(queues = listOf(D.classicQueue("orders"))), D.liveFacts(indexVersions = mapOf(QueueRef("/", "orders") to ClassicIndexVersion.V1)))
        assertEquals(DecisionResult.Decided, s.review.decide(q("orders"), ItemDecision.Accept(acknowledged = TICK)))
    }

    @Test
    fun `a warning with a manual default does not refuse an accept, the item is declared as in the source`() {
        val s = started(D.document(exchanges = listOf(D.exchange("delayed", "x-delayed-message"))))
        assertEquals(listOf(ex("delayed")), s.review.items)
        assertEquals(DecisionResult.Decided, s.review.decide(ex("delayed"), ItemDecision.Accept()))
    }

    @Test
    fun `skip is always possible and so is replace`() {
        val s = started(D.document(queues = listOf(D.classicQueue("a", durable = false), D.classicQueue("b"))))
        assertEquals(DecisionResult.Decided, s.review.decide(q("a"), ItemDecision.Skip))
        assertEquals(ItemDecision.Skip, s.review.decisionOf(q("a")))
        val replace = ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("b", "quorum")))
        assertEquals(DecisionResult.Decided, s.review.decide(q("b"), replace))
        assertEquals(replace, s.review.decisionOf(q("b")))
    }

    @Test
    fun `deciding a compatible item, a binding or an unknown item is refused as not a review item`() {
        val s = started(D.document(queues = listOf(D.quorumQueue("orders")), exchanges = listOf(D.exchange("events")), bindings = listOf(D.binding("events", "orders", "k"))))
        s.report.assertAnalyzed()
        val refusal = refused(DecisionRefusal.NotAReviewItem)
        assertEquals(refusal, s.review.decide(q("orders"), ItemDecision.Skip), "compatible queue")
        assertEquals(refusal, s.review.decide(bind("events", "orders", "k"), ItemDecision.Skip), "binding")
        assertEquals(refusal, s.review.decide(q("nope"), ItemDecision.Skip), "unknown item")
        assertEquals(null, s.review.decisionOf(q("orders")))
    }

    @Test
    fun `deciding twice replaces the first decision`() {
        val s = classic()
        s.decide(q("orders"), ItemDecision.Skip)
        s.decide(q("orders"), ItemDecision.Accept(acknowledged = TICK))
        assertEquals(ItemDecision.Accept(acknowledged = TICK), s.review.decisionOf(q("orders")))
        assertEquals(emptyList<ReviewItem>(), s.review.pending)
    }

    @Test
    fun `a refused decision keeps the earlier one`() {
        val s = classic()
        s.decide(q("orders"), ItemDecision.Skip)
        assertEquals(refused(DecisionRefusal.TickMissing(setOf(ordering))), s.review.decide(q("orders"), ItemDecision.Accept()))
        assertEquals(ItemDecision.Skip, s.review.decisionOf(q("orders")))
    }

    @Test
    fun `pending shrinks one by one in report order`() {
        val s = started(D.document(queues = listOf(D.classicQueue("a"), D.classicQueue("b"), D.classicQueue("c"))))
        s.decide(q("b"), ItemDecision.Skip)
        assertEquals(listOf(q("a"), q("c")), s.review.pending)
        assertEquals(listOf(q("a"), q("b"), q("c")), s.review.items, "items stay whatever is decided")
    }

    @Test
    fun `an item named dot or dot dot is not a review item, even when it is a non-durable classic queue`() {
        val s = started(D.document(queues = listOf(D.classicQueue("..", durable = false), D.classicQueue(".")), exchanges = listOf(D.exchange(".."))))
        assertEquals(emptyList<ReviewItem>(), s.review.items)
        assertEquals(emptyList<ReviewItem>(), s.review.pending)
        assertEquals(refused(DecisionRefusal.NotAReviewItem), s.review.decide(q(".."), ItemDecision.Skip))
        assertEquals(refused(DecisionRefusal.NotAReviewItem), s.review.decide(ex(".."), ItemDecision.Skip))
    }

    @Test
    fun `an unknown queue type can be accepted and skipped`() {
        val s = started(D.document(queues = listOf(D.queue("x", QueueKind.Other("x-foo")), D.queue("y", QueueKind.Other("x-foo")))))
        s.report.only("queue-type-unknown", "x")
        assertEquals(DecisionResult.Decided, s.review.decide(q("x"), ItemDecision.Accept()))
        assertEquals(DecisionResult.Decided, s.review.decide(q("y"), ItemDecision.Skip))
    }

    @Test
    fun `a Replace with a delete action is refused and decides nothing`() {
        val s = classic()
        for (delete in listOf(
            MigrationAction.DeleteQueue("orders"), MigrationAction.DeleteExchange("e"), MigrationAction.DeleteBinding("e", "orders"),
            MigrationAction.DeleteStream("s"), MigrationAction.DeletePolicy("p"),
        )) {
            assertEquals(refused(DecisionRefusal.ReplaceHasDelete), s.review.decide(q("orders"), ItemDecision.Replace(listOf(delete))), delete.actionName)
            assertEquals(refused(DecisionRefusal.ReplaceHasDelete), s.review.decide(q("orders"), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("orders", "quorum"), delete))), "mixed ${delete.actionName}")
        }
        assertEquals(null, s.review.decisionOf(q("orders")))
        assertEquals(listOf(q("orders")), s.review.pending)
        val declares = ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("orders", "quorum"), MigrationAction.DeclareExchange("e")))
        assertEquals(DecisionResult.Decided, s.review.decide(q("orders"), declares))
    }

    private fun assertReplaceInvalid(result: DecisionResult) {
        val r = result as? DecisionResult.Refused ?: fail("expected a refusal but got $result")
        val reason = r.reason as? DecisionRefusal.ReplaceInvalid ?: fail("expected ReplaceInvalid but got ${r.reason}")
        assertTrue(reason.reason.isNotBlank(), "the refusal carries the parser's reason")
    }

    @Test
    fun `a Replace with a queue named dot dot is refused as ReplaceInvalid and decides nothing`() {
        val s = classic()
        assertReplaceInvalid(s.review.decide(q("orders"), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("..", "quorum")))))
        assertEquals(null, s.review.decisionOf(q("orders")), "nothing stored")
        assertEquals(listOf(q("orders")), s.review.pending)
    }

    @Test
    fun `a refused Replace keeps an earlier decision`() {
        val s = classic()
        val earlier = ItemDecision.Accept(acknowledged = TICK)
        assertEquals(DecisionResult.Decided, s.review.decide(q("orders"), earlier))
        assertReplaceInvalid(s.review.decide(q("orders"), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("..", "quorum")))))
        assertEquals(earlier, s.review.decisionOf(q("orders")))
    }

    @Test
    fun `a Replace whose action is for another vhost is refused as ReplaceInvalid`() {
        val s = classic()
        assertReplaceInvalid(s.review.decide(q("orders"), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("orders", "quorum", vhost = "other")))))
        assertReplaceInvalid(s.review.decide(q("orders"), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("orders", "quorum"), MigrationAction.DeclareExchange("e", vhost = "other")))))
        assertEquals(null, s.review.decisionOf(q("orders")))
        assertEquals(listOf(q("orders")), s.review.pending)
    }

    @Test
    fun `a valid Replace in the item's vhost is still decided and generates`() {
        val s = classic()
        val replace = ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("orders", "quorum")))
        assertEquals(DecisionResult.Decided, s.review.decide(q("orders"), replace))
        assertEquals(replace, s.review.decisionOf(q("orders")))
        s.generated()
    }

    private val hostileVhosts = listOf("amqp://u:S3CR3T@h", "line1\nline2", "v".repeat(65))

    private fun reasonOf(result: DecisionResult): String {
        val r = result as? DecisionResult.Refused ?: fail("expected a refusal but got $result")
        return (r.reason as? DecisionRefusal.ReplaceInvalid ?: fail("expected ReplaceInvalid but got ${r.reason}")).reason
    }

    @Test
    fun `a hostile action vhost is refused and never appears in the reason`() {
        for (hostile in hostileVhosts) {
            val s = classic()
            val reason = reasonOf(s.review.decide(q("orders"), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("orders", "quorum", vhost = hostile)))))
            assertTrue(reason.isNotBlank(), "reason is blank for '$hostile'")
            assertTrue(!reason.contains(hostile) && !reason.contains("S3CR3T"), "reason echoes the hostile vhost: $reason")
            assertEquals(null, s.review.decisionOf(q("orders")))
        }
    }

    @Test
    fun `a hostile item vhost is refused and never appears in the reason`() {
        for (hostile in hostileVhosts) {
            val s = started(D.document(queues = listOf(D.classicQueue("orders", vhost = hostile))))
            val reason = reasonOf(s.review.decide(q("orders", hostile), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("orders", "quorum", vhost = "other")))))
            assertTrue(reason.isNotBlank(), "reason is blank for '$hostile'")
            assertTrue(!reason.contains(hostile) && !reason.contains("S3CR3T"), "reason echoes the hostile vhost: $reason")
        }
    }

    @Test
    fun `a plain other vhost is still named in the reason`() {
        val s = classic()
        val reason = reasonOf(s.review.decide(q("orders"), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("orders", "quorum", vhost = "other")))))
        assertTrue(reason.contains("'other'"), "the plain vhost is named: $reason")
    }
}
