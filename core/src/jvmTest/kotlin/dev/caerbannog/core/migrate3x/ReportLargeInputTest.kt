package dev.caerbannog.core.migrate3x

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier

/** M8-03: a 60,000-queue document (the size of the M8-01 large test) is analysed in the default test heap. No time assertion. */
class ReportLargeInputTest {
    @Test
    fun `60000 queues are analysed with exact counts`() {
        val total = 60_000
        val queues = (0 until total).map { i -> if (i % 10 == 0) D.classicQueue("large-queue-$i") else D.quorumQueue("large-queue-$i", arguments = D.args("x-max-length" to 1000 + i)) }
        val policy = D.policy("p", ".*", D.args("max-length" to 1))
        // round 1: three pathological patterns that would hang a backtracking matcher; they match no queue name
        val pathological = listOf("(.*a){40}", "^(a+)+\$", "(a*)*b").mapIndexed { i, p -> D.policy("path$i", p, D.args("ha-mode" to "all"), priority = 5) }
        val r = assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier { analyze(D.document(queues = queues, policies = listOf(policy) + pathological)) })
        val classic = total / 10
        assertEquals(total + 4, r.summary.topologyItems)
        assertEquals(classic + 3, r.summary.needDecision, "the classic queues and the three ha-mode policies (blockers)")
        assertEquals(3, r.withRule("ha-policy-key").size)
        assertEquals(0, r.withRule("policy-pattern-unreadable").size, "the three patterns are supported, just expensive for a backtracking matcher")
        assertEquals(classic, r.summary.messageLoss)
        assertEquals(classic, r.withRule("classic-not-replicated").size)
        assertEquals(r.findings.size, r.findings.map { it.id }.toSet().size, "ids stay unique")
    }
}
