package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** M8-05 step 4: edge cases found while verifying (boundaries of `chosen`, duplicates, empty policies, replacement oddities). */
class ConversionExploratoryTest {
    @Test
    fun `a chosen index outside the proposals throws IllegalArgumentException and decides nothing`() {
        val s = started(D.document(queues = listOf(D.classicQueue("orders"))))
        val id = s.findingId("classic-not-replicated", "orders")
        for (bad in listOf(-1, 2, 99)) {
            assertThrows(IllegalArgumentException::class.java) { s.review.decide(q("orders"), ItemDecision.Accept(mapOf(id to bad), TICK)) }
        }
        assertEquals(null, s.review.decisionOf(q("orders")))
    }

    @Test
    fun `a chosen entry for a finding the item does not have changes nothing`() {
        val s = started(D.document(queues = listOf(D.classicQueue("orders"))))
        s.decide(q("orders"), ItemDecision.Accept(mapOf("no-such-finding" to 7), TICK))
        assertEquals("quorum", s.generated().queue("orders").type)
    }

    @Test
    fun `a policy with an empty definition in the source is written, not treated as emptied`() {
        val s = started(D.document(policies = listOf(D.policy("empty", "^a$", emptyMap()))))
        val result = s.generated()
        assertEquals(emptyMap<String, Any>(), result.policy("empty")?.definition)
        assertTrue(result.notes.none { it.text.contains("held only keys") })
    }

    @Test
    fun `an operator policy that only holds rejected keys is not written and the note says operator policy`() {
        val s = started(D.document(operatorPolicies = listOf(D.operatorPolicy("op", "^a$", D.args("queue-mode" to "lazy")))))
        s.decide(opPol("op"), ItemDecision.Accept())
        val result = s.generated()
        assertEquals(emptyList<GeneratedMigration>(), result.migrations)
        assertTrue(result.notes.any { it.text.startsWith("The operator policy \"op\"") && it.text.contains("held only keys that 4.x rejects") }, "notes: ${result.noteTexts()}")
    }

    @Test
    fun `notes come in the order single virtual host, report notes, then generation notes`() {
        val s = started(
            D.document(
                queues = listOf(D.classicQueue("orders", vhost = "v")), policies = listOf(D.policy("ha", "^a$", D.args("ha-mode" to "all"), vhost = "v")),
                virtualHosts = listOf(D.virtualHost("v")), origin = DefinitionsOrigin.SingleVirtualHost("v"),
            ),
        )
        s.acceptWithTick(q("orders", "v")); s.decide(pol("ha", "v"), ItemDecision.Accept())
        val texts = s.generated().noteTexts()
        val single = texts.indexOfFirst { it.contains("export of one virtual host") }
        val loss = texts.indexOfFirst { it.contains("old classic queue index") }
        val emptied = texts.indexOfFirst { it.contains("held only keys") }
        assertTrue(single == 0 && loss in 1 until emptied, "order: $texts")
    }

    @Test
    fun `a queue named twice in a hand-edited file does not crash generation`() {
        val s = started(D.document(queues = listOf(D.quorumQueue("dup"), D.quorumQueue("dup"))))
        s.generated()
    }

    @Test
    fun `a user replacement with an unwritable name is refused where it is entered`() {
        val s = started(D.document(queues = listOf(D.classicQueue("orders"))))
        val result = s.review.decide(q("orders"), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("..", "quorum"))))
        assertTrue(result is DecisionResult.Refused && result.reason is DecisionRefusal.ReplaceInvalid, "expected ReplaceInvalid but got $result")
        assertEquals(null, s.review.decisionOf(q("orders")))
    }

    @Test
    fun `M8-05b a Replace for a global item with no vhost is not a review item`() {
        val s = started(D.document(queues = listOf(D.classicQueue("orders"))))
        val result = s.review.decide(ReviewItem(ItemKind.Global, null, null), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("x", "quorum"))))
        assertEquals(DecisionResult.Refused(DecisionRefusal.NotAReviewItem), result)
    }

    @Test
    fun `M8-05b an empty Replace is decided or refused, and generation never throws after it`() {
        val s = started(D.document(queues = listOf(D.classicQueue("orders"))))
        val result = s.review.decide(q("orders"), ItemDecision.Replace(emptyList()))
        if (result == DecisionResult.Decided) s.generated() else assertTrue(result is DecisionResult.Refused && result.reason is DecisionRefusal.ReplaceInvalid, "got $result")
    }

    @Test
    fun `M8-05b hostile names never make generate throw and a refusal reason holds no secret`() {
        val names = listOf("..", ".", "", " ", "a/b", "a\\b", "a\u0000b", "\u202e", "x".repeat(300), "con:", "%2e%2e", "\n", "\"quote\"", "{\"a\":1}")
        val sentinel = "S3CR3T-SENTINEL"
        // the sentinel alone passes echoableName (and is then rightly named), so only a form the rule rejects must never be echoed
        for (n in names + "amqp://u:$sentinel@h") {
            val s = started(D.document(queues = listOf(D.classicQueue("orders"))))
            for (a in listOf<MigrationAction>(MigrationAction.DeclareQueue(n, "quorum"), MigrationAction.DeclareExchange(n), MigrationAction.DeclareQueue("orders", n), MigrationAction.DeclareQueue("orders", "quorum", vhost = n))) {
                val result = s.review.decide(q("orders"), ItemDecision.Replace(listOf(a)))
                if (result == DecisionResult.Decided) {
                    val out = runCatching { s.review.generate(REQUEST) }
                    assertTrue(out.isSuccess, "generate threw after a Decided Replace of $a: ${out.exceptionOrNull()}")
                } else {
                    val reason = ((result as DecisionResult.Refused).reason as? DecisionRefusal.ReplaceInvalid)?.reason ?: fail("unexpected refusal ${result.reason}")
                    assertTrue(reason.isNotBlank())
                    assertTrue(!reason.contains(sentinel), "reason echoes input: $reason")
                }
            }
        }
    }
}
