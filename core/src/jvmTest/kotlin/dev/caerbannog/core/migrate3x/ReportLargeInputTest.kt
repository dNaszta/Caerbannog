package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** M8-03: a 60,000-queue document (the size of the M8-01 large test) is analysed in the default test heap. No time assertion. */
class ReportLargeInputTest {
    @Test
    fun `60000 queues are analysed with exact counts`() {
        val total = 60_000
        val queues = (0 until total).map { i -> if (i % 10 == 0) D.classicQueue("large-queue-$i") else D.quorumQueue("large-queue-$i", arguments = D.args("x-max-length" to 1000 + i)) }
        val policy = D.policy("p", ".*", D.args("max-length" to 1))
        val r = analyze(D.document(queues = queues, policies = listOf(policy)))
        val classic = total / 10
        assertEquals(total + 1, r.summary.topologyItems)
        assertEquals(classic, r.summary.needDecision)
        assertEquals(classic, r.summary.messageLoss)
        assertEquals(classic, r.withRule("classic-not-replicated").size)
        assertEquals(r.findings.size, r.findings.map { it.id }.toSet().size, "ids stay unique")
    }
}
