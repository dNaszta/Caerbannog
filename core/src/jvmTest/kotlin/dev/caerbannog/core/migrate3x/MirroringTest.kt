package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M8-03 rule 4 (which policy mirrors a queue), tested through the analyzer: the finding `classic-not-replicated` of a mirrored queue
 * names the mirroring policy, an unmirrored queue's finding names none. The brief fixes no helper signature, so nothing here calls one.
 */
class MirroringTest {
    private fun ha(name: String, pattern: String, priority: Int = 0, applyTo: String = "all", vhost: String = "/") =
        D.policy(name, pattern, D.args("ha-mode" to "all"), applyTo, priority, vhost)

    private fun plain(name: String, pattern: String, priority: Int = 0, applyTo: String = "all", vhost: String = "/") =
        D.policy(name, pattern, D.args("max-length" to 10), applyTo, priority, vhost)

    private fun report(queue: QueueDefinition, vararg policies: PolicyDefinition, operatorPolicies: List<OperatorPolicyDefinition> = emptyList()) =
        analyze(D.document(queues = listOf(queue), policies = policies.toList(), operatorPolicies = operatorPolicies))

    private fun mirroredBy(r: CompatibilityReport, queue: String, policy: String) =
        assertContainsText(r.only("classic-not-replicated", queue).text(), policy, "classic-not-replicated of $queue")

    private fun notMirrored(r: CompatibilityReport, queue: String, vararg policyNames: String) {
        val text = r.only("classic-not-replicated", queue).text()
        policyNames.forEach { assertTrue(!text.contains(it), "queue $queue must not be named as mirrored by $it but: $text") }
    }

    @Test
    fun `the pattern is unanchored`() {
        mirroredBy(report(D.classicQueue("my-orders-queue"), ha("pol-unanch", "orders")), "my-orders-queue", "pol-unanch")
    }

    @Test
    fun `an anchored pattern that does not match does not mirror`() {
        notMirrored(report(D.classicQueue("my-orders-queue"), ha("pol-anch", "^orders$")), "my-orders-queue", "pol-anch")
    }

    @Test
    fun `the highest priority wins even when it does not mirror`() {
        val r = report(D.classicQueue("q"), ha("pol-low-ha", ".*", priority = 1), plain("pol-high-plain", ".*", priority = 5))
        notMirrored(r, "q", "pol-low-ha", "pol-high-plain")
    }

    @Test
    fun `the highest priority wins when it mirrors`() {
        val r = report(D.classicQueue("q"), ha("pol-high-ha", ".*", priority = 5), plain("pol-low-plain", ".*", priority = 1))
        mirroredBy(r, "q", "pol-high-ha")
    }

    @Test
    fun `a tie is broken by the policy name ascending`() {
        val first = report(D.classicQueue("q"), ha("b-ha", ".*", 1), ha("a-ha", ".*", 1))
        mirroredBy(first, "q", "a-ha")
        notMirrored(first, "q", "b-ha")
        val second = report(D.classicQueue("q"), ha("b-ha", ".*", 1), plain("a-plain", ".*", 1))
        notMirrored(second, "q", "b-ha", "a-plain")
    }

    @Test
    fun `apply-to exchanges does not mirror, queues and all do`() {
        notMirrored(report(D.classicQueue("q"), ha("pol-ex", ".*", applyTo = "exchanges")), "q", "pol-ex")
        mirroredBy(report(D.classicQueue("q"), ha("pol-qs", ".*", applyTo = "queues")), "q", "pol-qs")
        mirroredBy(report(D.classicQueue("q"), ha("pol-all", ".*", applyTo = "all")), "q", "pol-all")
    }

    @Test
    fun `a policy of another virtual host does not mirror`() {
        notMirrored(report(D.classicQueue("q", vhost = "/"), ha("pol-other-vhost", ".*", vhost = "v2")), "q", "pol-other-vhost")
    }

    @Test
    fun `a pattern that does not compile is never a match, is reported, and does not stop a lower policy from winning`() {
        val r = report(D.classicQueue("q"), ha("pol-bad", "(", priority = 9), ha("pol-good", ".*", priority = 1))
        mirroredBy(r, "q", "pol-good")
        notMirrored(r, "q", "pol-bad")
        assertEquals("pol-bad", r.only("policy-pattern-unreadable").name)
    }

    @Test
    fun `an operator policy never mirrors`() {
        val op = D.operatorPolicy("op-ha-xyz", ".*", D.args("ha-mode" to "all"), priority = 99)
        val r = report(D.classicQueue("q"), operatorPolicies = listOf(op))
        notMirrored(r, "q", "op-ha-xyz")
    }

    @Test
    fun `a policy without ha-mode but with ha-params does not mirror`() {
        val p = D.policy("pol-params-only", ".*", D.args("ha-params" to 2))
        notMirrored(report(D.classicQueue("q"), p), "q", "pol-params-only")
    }

    @Test
    fun `full fixture - classic-mirrored is mirrored by ha-all, classic-plain by ha-exactly, no other queue is mirrored`() {
        val r = analyze(fullFixture())
        mirroredBy(r, "classic-mirrored", "ha-all")
        mirroredBy(r, "classic-plain", "ha-exactly")
        r.withRule("classic-not-replicated").filter { it.name != "classic-mirrored" && it.name != "classic-plain" }.forEach {
            assertTrue(!it.text().contains("ha-all") && !it.text().contains("ha-exactly"), "${it.name} must not be named as mirrored but: ${it.text()}")
        }
    }
}
