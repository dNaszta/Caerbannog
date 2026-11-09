package dev.caerbannog.core.migrate3x

private const val MAX_PRIORITY = "x-max-priority"
private const val QUEUE_VERSION = "x-queue-version"
private val maxInMemoryArguments = listOf("x-max-in-memory-length", "x-max-in-memory-bytes")

private val convertToQuorum = Proposal.ConvertQueueType(QueueKind.Quorum, Acknowledgement.OrderingByPriorityMayChange)

/** A non-durable queue of any type: a 4.x import skips it without an error (S-B, S-D). */
internal object QueueNotDurableRule : QueueRule(RuleCatalog["queue-not-durable"]) {
    override fun check(context: AnalysisContext, queue: QueueDefinition): List<Finding> =
        if (queue.durable) {
            emptyList()
        } else {
            listOf(
                finding(
                    queue.ref(), Severity.Blocker, FindingClass.Incompatibility, ReportTexts.queueNotDurable, ReportTexts.queueNotDurableManual,
                    alternatives = listOf(Proposal.MakeDurable),
                ),
            )
        }
}

/** `x-queue-master-locator` on a queue of any type (S-B, S-C, S-D). */
internal object QueueMasterLocatorArgumentRule : QueueRule(RuleCatalog["queue-master-locator-argument"]) {
    private const val ARGUMENT = "x-queue-master-locator"

    override fun check(context: AnalysisContext, queue: QueueDefinition): List<Finding> =
        if (ARGUMENT !in queue.arguments) {
            emptyList()
        } else {
            listOf(
                finding(
                    queue.ref(), Severity.Blocker, FindingClass.Incompatibility, ReportTexts.queueMasterLocatorArgument, Proposal.RemoveArgument(ARGUMENT),
                    about = listOf(ARGUMENT),
                ),
            )
        }
}

/** Settings a quorum queue rejects: they block the conversion, one finding per setting (S-C). */
internal object QuorumRejectedSettingRule : QueueRule(RuleCatalog["quorum-rejected-setting"]) {
    private val rejectedArguments = listOf(MAX_PRIORITY, "x-queue-mode", QUEUE_VERSION)
    private const val AUTO_DELETE = "auto_delete"

    override fun check(context: AnalysisContext, queue: QueueDefinition): List<Finding> {
        if (!queue.isClassic) return emptyList()
        val arguments = rejectedArguments.filter { it in queue.arguments }.map { it to Proposal.RemoveArgument(it) }
        val autoDelete = if (queue.autoDelete) listOf(AUTO_DELETE to Proposal.ClearAutoDelete) else emptyList()
        return (arguments + autoDelete).map { (setting, proposal) ->
            finding(
                queue.ref(), Severity.Blocker, FindingClass.Incompatibility, ReportTexts.quorumRejectedSetting(setting), proposal,
                about = listOf(setting), appliesWhen = AppliesWhen.ConvertedToQuorum,
            )
        }
    }
}

/** Arguments a classic queue on 4.x rejects: index version 1 and the in-memory limits (S-D). */
internal object ClassicRejectedArgumentRule : QueueRule(RuleCatalog["classic-rejected-argument"]) {
    override fun check(context: AnalysisContext, queue: QueueDefinition): List<Finding> {
        if (!queue.isClassic) return emptyList()
        val oldIndex = if (queue.arguments[QUEUE_VERSION] == ArgumentValue.Whole(1)) listOf(QUEUE_VERSION to setIndexVersionTwo) else emptyList()
        val inMemory = maxInMemoryArguments.filter { it in queue.arguments }.map { it to Proposal.RemoveArgument(it) }
        return (oldIndex + inMemory).map { (argument, proposal) ->
            finding(
                queue.ref(), Severity.Blocker, FindingClass.Incompatibility, ReportTexts.classicRejectedArgument(argument), proposal,
                about = listOf(argument), appliesWhen = AppliesWhen.KeptClassic,
            )
        }
    }

    private val setIndexVersionTwo = Proposal.SetArgument(QUEUE_VERSION, ArgumentValue.Whole(2))
}

/** The in-memory limits are accepted by a quorum queue; what they do there is not measured. */
internal object QuorumMaxInMemoryRule : QueueRule(RuleCatalog["quorum-max-in-memory"]) {
    override fun check(context: AnalysisContext, queue: QueueDefinition): List<Finding> {
        if (!queue.isClassic) return emptyList()
        return maxInMemoryArguments.filter { it in queue.arguments }.map { argument ->
            finding(
                queue.ref(), Severity.Warning, FindingClass.BehaviourChange, ReportTexts.quorumMaxInMemory(argument), Proposal.None,
                about = listOf(argument), appliesWhen = AppliesWhen.ConvertedToQuorum,
            )
        }
    }
}

/**
 * A durable classic queue is no longer replicated on 4.x. Quorum is proposed, except for a queue with `x-max-priority` (P15):
 * it is proposed to stay classic and the conversion is the alternative.
 */
internal object ClassicNotReplicatedRule : QueueRule(RuleCatalog["classic-not-replicated"]) {
    override fun check(context: AnalysisContext, queue: QueueDefinition): List<Finding> {
        if (!queue.isClassic || !queue.durable) return emptyList()
        val hasPriority = MAX_PRIORITY in queue.arguments
        val text = ReportTexts.classicNotReplicated(context.mirroringPolicy(queue)?.name, hasPriority)
        val (proposal, alternative) = if (hasPriority) Proposal.KeepClassic to convertToQuorum else convertToQuorum to Proposal.KeepClassic
        return listOf(finding(queue.ref(), Severity.Warning, FindingClass.BehaviourChange, text, proposal, alternatives = listOf(alternative)))
    }
}

/** Every classic queue converted to quorum is ordered by priority (S-G). The tick sits on the conversion proposal. */
internal object ClassicToQuorumOrderingRule : QueueRule(RuleCatalog["classic-to-quorum-ordering"]) {
    override fun check(context: AnalysisContext, queue: QueueDefinition): List<Finding> =
        if (!queue.isClassic) {
            emptyList()
        } else {
            listOf(
                finding(
                    queue.ref(), Severity.Warning, FindingClass.BehaviourChange, ReportTexts.classicToQuorumOrdering(MAX_PRIORITY in queue.arguments), Proposal.None,
                    appliesWhen = AppliesWhen.ConvertedToQuorum,
                ),
            )
        }
}

/** A queue type the app does not know gets this finding and no classic rule. */
internal object QueueTypeUnknownRule : QueueRule(RuleCatalog["queue-type-unknown"]) {
    override fun check(context: AnalysisContext, queue: QueueDefinition): List<Finding> {
        val type = queue.type as? QueueKind.Other ?: return emptyList()
        return listOf(
            finding(queue.ref(), Severity.Warning, FindingClass.Incompatibility, ReportTexts.queueTypeUnknown(type.name), ReportTexts.queueTypeUnknownManual),
        )
    }
}

internal object QueueRules {
    val all: List<Rule> = listOf(
        QueueNotDurableRule, QueueMasterLocatorArgumentRule, QuorumRejectedSettingRule, ClassicRejectedArgumentRule, QuorumMaxInMemoryRule,
        ClassicNotReplicatedRule, ClassicToQuorumOrderingRule, QueueTypeUnknownRule,
    )
}
