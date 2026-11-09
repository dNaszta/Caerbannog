package dev.caerbannog.core.migrate3x

/** A rule over the definition keys of policies: it fires when a policy of one of [kinds] has a key that satisfies [isKey]. */
internal class PolicyKeyRule(
    override val info: RuleInfo,
    private val kinds: Set<ItemKind>,
    private val severity: Severity,
    private val findingClass: FindingClass,
    private val isKey: (String) -> Boolean,
    private val text: (List<String>) -> RuleText,
    private val proposal: (List<String>) -> Proposal,
    private val alternatives: (List<String>) -> List<Proposal> = { emptyList() },
) : Rule {
    override fun check(context: AnalysisContext): List<Finding> =
        context.policies.filter { it.kind in kinds }.mapNotNull { policy ->
            val keys = policy.keys(isKey)
            if (keys.isEmpty()) null else finding(policy.ref, severity, findingClass, text(keys), proposal(keys), about = keys, alternatives = alternatives(keys))
        }
}

/** Policy rules that are about the keys of the definition (policies and operator policies). */
internal object PolicyRules {
    private val mirroringKeys = setOf("ha-mode", "ha-params", "ha-sync-mode", "ha-sync-batch-size", "ha-promote-on-shutdown", "ha-promote-on-failure")
    private val rejectedInOperatorPolicy = setOf("queue-mode", "dead-letter-exchange", "dead-letter-routing-key", "alternate-exchange", "queue-leader-locator")
    private val maxInMemoryKeys = setOf("max-in-memory-length", "max-in-memory-bytes")
    private val bothKinds = setOf(ItemKind.Policy, ItemKind.OperatorPolicy)

    val haPolicyKey = PolicyKeyRule(
        RuleCatalog["ha-policy-key"], bothKinds, Severity.Blocker, FindingClass.Incompatibility,
        isKey = { it in mirroringKeys }, text = ReportTexts::haPolicyKey, proposal = Proposal::DropPolicyKeys,
    )

    val queueMasterLocatorPolicyKey = PolicyKeyRule(
        RuleCatalog["queue-master-locator-policy-key"], bothKinds, Severity.Blocker, FindingClass.Incompatibility,
        isKey = { it == "queue-master-locator" }, text = { ReportTexts.queueMasterLocatorPolicyKey }, proposal = Proposal::DropPolicyKeys,
    )

    val operatorPolicyKeyRejected = PolicyKeyRule(
        RuleCatalog["operator-policy-key-rejected"], setOf(ItemKind.OperatorPolicy), Severity.Blocker, FindingClass.Incompatibility,
        isKey = { it in rejectedInOperatorPolicy }, text = ReportTexts::operatorPolicyKeyRejected, proposal = Proposal::DropPolicyKeys,
        alternatives = { listOf(ReportTexts.operatorPolicyKeyManual(it)) },
    )

    val policyQueueMode = PolicyKeyRule(
        RuleCatalog["policy-queue-mode"], setOf(ItemKind.Policy), Severity.Info, FindingClass.BehaviourChange,
        isKey = { it == "queue-mode" }, text = { ReportTexts.policyQueueMode }, proposal = { Proposal.None },
    )

    val policyMaxInMemory = PolicyKeyRule(
        RuleCatalog["policy-max-in-memory"], setOf(ItemKind.Policy), Severity.Warning, FindingClass.BehaviourChange,
        isKey = { it in maxInMemoryKeys }, text = ReportTexts::policyMaxInMemory, proposal = { Proposal.None },
    )

    val notCoveredPolicyKey = PolicyKeyRule(
        RuleCatalog["not-covered-policy-key"], setOf(ItemKind.Policy), Severity.Warning, FindingClass.NotCovered,
        isKey = { it.startsWith("federation-") }, text = ReportTexts::notCoveredPolicyKey, proposal = { ReportTexts.federationManual },
    )

    /** A pattern the app cannot evaluate never matches; the finding says why (the reason code is in `about`). */
    val policyPatternUnreadable: Rule = object : Rule {
        override val info: RuleInfo = RuleCatalog["policy-pattern-unreadable"]

        override fun check(context: AnalysisContext): List<Finding> =
            context.policies.mapNotNull { policy ->
                policy.unevaluableReason?.let {
                    finding(policy.ref, Severity.Info, FindingClass.BehaviourChange, ReportTexts.policyPatternUnreadable(it), Proposal.None, about = listOf(it.code))
                }
            }
    }

    val all: List<Rule> = listOf(
        haPolicyKey, queueMasterLocatorPolicyKey, operatorPolicyKeyRejected, policyQueueMode, policyMaxInMemory, notCoveredPolicyKey, policyPatternUnreadable,
    )
}
