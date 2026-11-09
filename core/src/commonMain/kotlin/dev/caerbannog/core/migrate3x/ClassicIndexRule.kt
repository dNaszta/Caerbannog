package dev.caerbannog.core.migrate3x

/**
 * The message-loss rule (S-N, P16): a durable classic queue on an index version 1 is silently emptied by an in-place upgrade to
 * RabbitMQ 4.3 or later. Live facts decide when present; a file decides only when it states `x-queue-version`. A source that is
 * already 4.x is skipped. The proposals never offer an in-place upgrade as a safe step.
 */
internal object ClassicIndexRule : QueueRule(RuleCatalog["classic-index-v1"]) {
    private const val QUEUE_VERSION = "x-queue-version"
    private val proposals = listOf(ReportTexts.classicIndexPolicyFix, ReportTexts.classicIndexUpgradeThrough)

    override fun check(context: AnalysisContext, queue: QueueDefinition): List<Finding> {
        if (!queue.isClassic || !queue.durable || context.isSourceFourOrLater()) return emptyList()
        val live = context.live
        return listOfNotNull(if (live != null) fromLiveFacts(live, queue) else fromFile(queue))
    }

    private fun AnalysisContext.isSourceFourOrLater(): Boolean = (sourceMajorVersion ?: 0) >= 4

    private fun fromLiveFacts(live: LiveSourceFacts, queue: QueueDefinition): Finding? =
        when (live.classicIndexVersions[QueueRef(queue.vhost, queue.name)] ?: ClassicIndexVersion.Unknown) {
            ClassicIndexVersion.V2 -> null
            ClassicIndexVersion.V1 -> messageLoss(queue, Severity.Blocker, ReportTexts.classicIndexLive, EvidenceSource.LiveQueueStats)
            ClassicIndexVersion.Unknown -> messageLoss(queue, Severity.Blocker, ReportTexts.classicIndexUnknown, EvidenceSource.LiveQueueStats)
        }

    private fun fromFile(queue: QueueDefinition): Finding? =
        when (queue.arguments[QUEUE_VERSION]) {
            ArgumentValue.Whole(2) -> null
            ArgumentValue.Whole(1) -> messageLoss(queue, Severity.Blocker, ReportTexts.classicIndexFileStates, EvidenceSource.Definitions, listOf(QUEUE_VERSION))
            else -> messageLoss(queue, Severity.Warning, ReportTexts.classicIndexFileSilent, EvidenceSource.Definitions)
        }

    private fun messageLoss(queue: QueueDefinition, severity: Severity, text: RuleText, evidence: EvidenceSource, about: List<String> = emptyList()): Finding =
        finding(
            queue.ref(), severity, FindingClass.MessageLoss, text, ReportTexts.classicIndexRecommended,
            about = about, alternatives = proposals, evidence = evidence,
        )
}
