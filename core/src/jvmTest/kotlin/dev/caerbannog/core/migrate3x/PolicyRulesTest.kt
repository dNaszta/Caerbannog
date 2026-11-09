package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** M8-03: the policy and operator policy rules. Each rule: fires on its condition with the exact shape, and not on the neighbour. */
class PolicyRulesTest {
    private val haKeys = listOf("ha-mode", "ha-params", "ha-sync-mode", "ha-sync-batch-size", "ha-promote-on-shutdown", "ha-promote-on-failure")

    private fun withPolicy(vararg entries: Pair<String, Any>, pattern: String = "^none$", name: String = "p1") =
        analyze(D.document(policies = listOf(D.policy(name, pattern, D.args(*entries)))))

    private fun withOperatorPolicy(vararg entries: Pair<String, Any>, pattern: String = "^none$", name: String = "op1") =
        analyze(D.document(operatorPolicies = listOf(D.operatorPolicy(name, pattern, D.args(*entries)))))

    // ---- ha-policy-key ----

    @ParameterizedTest
    @ValueSource(strings = ["ha-mode", "ha-params", "ha-sync-mode", "ha-sync-batch-size", "ha-promote-on-shutdown", "ha-promote-on-failure"])
    fun `ha-policy-key fires for each mirroring key on a policy`(key: String) {
        val f = withPolicy(key to "x", "max-length" to 10).only("ha-policy-key", "p1")
        f.assertShape(Severity.Blocker, FindingClass.Incompatibility, verified = true)
        assertEquals(listOf(ItemKind.Policy, listOf(key)), listOf(f.kind, f.about))
        assertEquals(Proposal.DropPolicyKeys(listOf(key)), f.proposal)
        assertEquals(emptyList<Proposal>(), f.alternatives)
        assertContainsText(f.text(), "RabbitMQ 4 rejects these keys when the policy is created or imported", "ha-policy-key")
        assertContainsText(f.text(), "quorum", "ha-policy-key")
    }

    @Test
    fun `ha-policy-key lists every mirroring key present, sorted, and drops exactly those`() {
        val f = withPolicy("ha-sync-mode" to "automatic", "ha-mode" to "all", "max-length" to 1).only("ha-policy-key")
        assertEquals(listOf("ha-mode", "ha-sync-mode"), f.about)
        assertEquals(Proposal.DropPolicyKeys(listOf("ha-mode", "ha-sync-mode")), f.proposal)
    }

    @Test
    fun `ha-policy-key fires for an operator policy and is of kind OperatorPolicy`() {
        val f = withOperatorPolicy("ha-mode" to "all").only("ha-policy-key", "op1")
        f.assertShape(Severity.Blocker, FindingClass.Incompatibility, verified = true)
        assertEquals(ItemKind.OperatorPolicy, f.kind)
    }

    @Test
    fun `ha-policy-key does not fire for a policy without a mirroring key`() {
        val r = withPolicy("max-length" to 10, "message-ttl" to 1000)
        r.assertNone("ha-policy-key")
        r.assertAnalyzed()
        assertTrue(r.findings.none { it.name == "p1" }, "a plain limits policy must give no finding but got ${r.findings.filter { it.name == "p1" }.map { it.rule }}")
    }

    // ---- queue-master-locator-policy-key ----

    @Test
    fun `queue-master-locator-policy-key fires for a policy and for an operator policy`() {
        for (r in listOf(withPolicy("queue-master-locator" to "min-masters"), withOperatorPolicy("queue-master-locator" to "min-masters"))) {
            val f = r.only("queue-master-locator-policy-key")
            f.assertShape(Severity.Blocker, FindingClass.Incompatibility, verified = true)
            assertEquals(Proposal.DropPolicyKeys(listOf("queue-master-locator")), f.proposal)
            assertEquals(emptyList<Proposal>(), f.alternatives)
            assertContainsText(f.text(), "deprecated", "queue-master-locator-policy-key")
        }
    }

    @Test
    fun `queue-master-locator-policy-key does not fire for queue-leader-locator or a mirroring key`() {
        withPolicy("queue-leader-locator" to "balanced", "ha-mode" to "all").assertNone("queue-master-locator-policy-key")
    }

    // ---- operator-policy-key-rejected ----

    @ParameterizedTest
    @ValueSource(strings = ["queue-mode", "dead-letter-exchange", "dead-letter-routing-key", "alternate-exchange", "queue-leader-locator"])
    fun `operator-policy-key-rejected fires for each key an operator policy does not accept`(key: String) {
        val f = withOperatorPolicy(key to "x", "max-length" to 5).only("operator-policy-key-rejected", "op1")
        f.assertShape(Severity.Blocker, FindingClass.Incompatibility, verified = true)
        assertEquals(ItemKind.OperatorPolicy, f.kind)
        assertEquals(listOf(key), f.about)
        assertEquals(Proposal.DropPolicyKeys(listOf(key)), f.proposal)
        assertEquals(1, f.alternatives.size, "one alternative: set it as a normal policy")
        assertTrue(f.alternatives.single() is Proposal.Manual, "the alternative is a Manual step but is ${f.alternatives.single()}")
        assertContainsText(f.text(), "accepts only the limits", "operator-policy-key-rejected")
    }

    @Test
    fun `operator-policy-key-rejected does not fire for limits, for a normal policy, or for mirroring keys`() {
        withOperatorPolicy("max-length" to 100, "message-ttl" to 5, "expires" to 9).assertNone("operator-policy-key-rejected")
        withPolicy("dead-letter-exchange" to "dlx", "alternate-exchange" to "ae", "queue-mode" to "lazy").assertNone("operator-policy-key-rejected")
        withOperatorPolicy("ha-mode" to "all").assertNone("operator-policy-key-rejected")
    }

    // ---- policy-queue-mode ----

    @Test
    fun `policy-queue-mode is an info for a policy`() {
        val f = withPolicy("queue-mode" to "lazy").only("policy-queue-mode", "p1")
        f.assertShape(Severity.Info, FindingClass.BehaviourChange, verified = true)
        assertEquals(Proposal.None, f.proposal)
        assertEquals(listOf("queue-mode"), f.about)
        assertContainsText(f.text(), "lazy mode has no effect on a quorum queue", "policy-queue-mode")
    }

    @Test
    fun `policy-queue-mode does not fire for an operator policy or a policy without the key`() {
        withOperatorPolicy("queue-mode" to "lazy").assertNone("policy-queue-mode")
        withPolicy("max-length" to 1).assertNone("policy-queue-mode")
    }

    // ---- policy-max-in-memory ----

    @ParameterizedTest
    @ValueSource(strings = ["max-in-memory-length", "max-in-memory-bytes"])
    fun `policy-max-in-memory is an unverified warning`(key: String) {
        val f = withPolicy(key to 1000).only("policy-max-in-memory", "p1")
        f.assertShape(Severity.Warning, FindingClass.BehaviourChange, verified = false)
        assertEquals(Proposal.None, f.proposal)
        assertEquals(listOf(key), f.about)
        assertContainsText(f.text(), "not verified", "policy-max-in-memory")
    }

    @Test
    fun `policy-max-in-memory does not fire for max-length`() {
        withPolicy("max-length" to 1000, "max-length-bytes" to 5).assertNone("policy-max-in-memory")
    }

    // ---- not-covered-policy-key ----

    @Test
    fun `not-covered-policy-key fires for any federation- key and proposes a manual step`() {
        val f = withPolicy("federation-upstream-set" to "all", "max-length" to 1).only("not-covered-policy-key", "p1")
        f.assertShape(Severity.Warning, FindingClass.NotCovered, verified = true)
        assertEquals(listOf("federation-upstream-set"), f.about)
        assertTrue(f.proposal is Proposal.Manual, "proposal is ${f.proposal}")
        assertContainsText(f.text(), "federation is not migrated by this app", "not-covered-policy-key")
    }

    @Test
    fun `not-covered-policy-key does not fire for a key that only contains federation or for an operator policy`() {
        withPolicy("my-federation-thing" to 1, "max-length" to 2).assertNone("not-covered-policy-key")
        withOperatorPolicy("federation-upstream" to "x").assertNone("not-covered-policy-key")
    }

    // ---- policy-pattern-unreadable ----

    @Test
    fun `policy-pattern-unreadable is an unverified info for a policy and an operator policy with a pattern that does not compile`() {
        for (r in listOf(withPolicy("max-length" to 1, pattern = "(unclosed"), withOperatorPolicy("max-length" to 1, pattern = "[a-"))) {
            val f = r.only("policy-pattern-unreadable")
            f.assertShape(Severity.Info, FindingClass.BehaviourChange, verified = false)
            assertEquals(Proposal.None, f.proposal)
            assertEquals(listOf("invalid"), f.about, "round 1: about carries the reason code")
            assertContainsText(f.text(), "The app could not evaluate this pattern (", "policy-pattern-unreadable")
            assertContainsText(f.text(), "), so it did not use it to decide which queues are mirrored. If it matches queues, check them by hand.", "policy-pattern-unreadable")
        }
        assertEquals(ItemKind.OperatorPolicy, withOperatorPolicy("max-length" to 1, pattern = "[a-").only("policy-pattern-unreadable").kind)
    }

    @Test
    fun `policy-pattern-unreadable carries the reason code invalid, unsupported or too-large in about`() {
        assertEquals(listOf("invalid"), withPolicy("max-length" to 1, pattern = "(").only("policy-pattern-unreadable").about)
        assertEquals(listOf("unsupported"), withPolicy("max-length" to 1, pattern = "(?=a)").only("policy-pattern-unreadable").about)
        assertEquals(listOf("unsupported"), withOperatorPolicy("max-length" to 1, pattern = "\\1").only("policy-pattern-unreadable").about)
        assertEquals(listOf("too-large"), withPolicy("max-length" to 1, pattern = "a{1000}").only("policy-pattern-unreadable").about)
        assertEquals(listOf("too-large"), withPolicy("max-length" to 1, pattern = "a".repeat(1025)).only("policy-pattern-unreadable").about)
    }

    @Test
    fun `policy-pattern-unreadable does not fire for a readable pattern`() {
        withPolicy("max-length" to 1, pattern = "^orders\\..*$").assertNone("policy-pattern-unreadable")
        withPolicy("max-length" to 1, pattern = "").assertNone("policy-pattern-unreadable")
    }
}
