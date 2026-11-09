package dev.caerbannog.core.migrate3x

/**
 * One compatibility rule. A rule is a small object that reads the [AnalysisContext] and returns its findings; the analyzer is a loop
 * over [DefaultRules.all], so a new rule is one new object plus one catalog entry.
 */
internal interface Rule {
    val info: RuleInfo
    fun check(context: AnalysisContext): List<Finding>
}

/** The item a finding is about. */
internal data class ItemRef(val kind: ItemKind, val vhost: String?, val name: String?)

/** A rule that looks at every queue of the document. */
internal abstract class QueueRule(final override val info: RuleInfo) : Rule {
    final override fun check(context: AnalysisContext): List<Finding> = context.document.queues.flatMap { check(context, it) }

    protected abstract fun check(context: AnalysisContext, queue: QueueDefinition): List<Finding>
}

/** A rule that looks at every user and gives at most one finding per user. */
internal abstract class UserRule(final override val info: RuleInfo) : Rule {
    final override fun check(context: AnalysisContext): List<Finding> = context.document.users.mapNotNull { check(it) }

    protected abstract fun check(user: UserDefinition): Finding?
}

/**
 * Builds a finding of this rule. The id is derived here and `about` is sorted, so every rule gets the same id format and the
 * same order. `verified` comes from the catalog entry of the rule unless the rule knows better per finding.
 */
internal fun Rule.finding(
    item: ItemRef,
    severity: Severity,
    findingClass: FindingClass,
    text: RuleText,
    proposal: Proposal,
    about: List<String> = emptyList(),
    alternatives: List<Proposal> = emptyList(),
    appliesWhen: AppliesWhen = AppliesWhen.Always,
    defaultDecision: DefaultDecision = DefaultDecision.Accept,
    evidence: EvidenceSource = EvidenceSource.Definitions,
    verified: Boolean = info.verified,
): Finding {
    val sortedAbout = about.sorted()
    return Finding(
        id = "${info.id}|${item.vhost ?: "-"}|${item.kind}|${item.name ?: "-"}|${sortedAbout.joinToString(",")}",
        rule = info.id,
        severity = severity,
        findingClass = findingClass,
        kind = item.kind,
        vhost = item.vhost,
        name = item.name,
        about = sortedAbout,
        appliesWhen = appliesWhen,
        message = text.message,
        why = text.why,
        proposal = proposal,
        alternatives = alternatives,
        defaultDecision = defaultDecision,
        verified = verified,
        evidence = evidence,
    )
}

internal fun QueueDefinition.ref(): ItemRef = ItemRef(ItemKind.Queue, vhost, name)

internal val QueueDefinition.isClassic: Boolean get() = type == QueueKind.Classic
