package dev.caerbannog.core.migrate3x

private val globalItem = ItemRef(ItemKind.Global, null, null)
private const val MIRRORING_FEATURE = "classic_queue_mirroring"

/** The broker reports classic queue mirroring as in use (live facts only). */
internal object MirroringInUseRule : Rule {
    override val info: RuleInfo = RuleCatalog["mirroring-in-use"]

    override fun check(context: AnalysisContext): List<Finding> =
        if (context.live?.deprecatedFeaturesInUse?.contains(MIRRORING_FEATURE) != true) {
            emptyList()
        } else {
            listOf(
                finding(
                    globalItem, Severity.Warning, FindingClass.BehaviourChange, ReportTexts.mirroringInUse, Proposal.None,
                    evidence = EvidenceSource.LiveDeprecatedFeatures,
                ),
            )
        }
}

/** Any other deprecated feature in use; verified only for the names a 4.4 broker denies. */
internal object DeprecatedFeatureInUseRule : Rule {
    override val info: RuleInfo = RuleCatalog["deprecated-feature-in-use"]

    override fun check(context: AnalysisContext): List<Finding> =
        (context.live?.deprecatedFeaturesInUse.orEmpty() - MIRRORING_FEATURE).map {
            finding(
                globalItem, Severity.Warning, FindingClass.ClientCheck, ReportTexts.deprecatedFeatureInUse(it), ReportTexts.deprecatedFeatureManual,
                about = listOf(it), evidence = EvidenceSource.LiveDeprecatedFeatures, verified = info.verified && it in DENIED_ON_4X,
            )
        }
}

/** A client check: something the app cannot see in definitions, said once per report. */
internal class ClientCheckRule(override val info: RuleInfo, private val text: RuleText) : Rule {
    override fun check(context: AnalysisContext): List<Finding> =
        listOf(
            finding(
                globalItem, Severity.Info, FindingClass.ClientCheck, text, Proposal.None,
                evidence = EvidenceSource.KnownBrokerBehaviour,
            ),
        )
}

/** Differences nobody measured yet, said once when the document has a classic queue. */
internal object ClassicToQuorumUnmeasuredRule : Rule {
    override val info: RuleInfo = RuleCatalog["classic-to-quorum-unmeasured"]

    override fun check(context: AnalysisContext): List<Finding> =
        if (!context.hasClassicQueue) {
            emptyList()
        } else {
            listOf(finding(globalItem, Severity.Info, FindingClass.BehaviourChange, ReportTexts.classicToQuorumUnmeasured, Proposal.None))
        }
}

internal object GlobalRules {
    val all: List<Rule> = listOf(
        MirroringInUseRule,
        DeprecatedFeatureInUseRule,
        ClientCheckRule(RuleCatalog["clients-global-qos"], ReportTexts.clientsGlobalQos),
        ClientCheckRule(RuleCatalog["clients-transient-queues"], ReportTexts.clientsTransientQueues),
        ClientCheckRule(RuleCatalog["clients-amqp-address-v1"], ReportTexts.clientsAmqpAddressV1),
        ClassicToQuorumUnmeasuredRule,
    )
}
