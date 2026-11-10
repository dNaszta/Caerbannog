package dev.caerbannog.core.migrate3x

/** Which findings drive a decision and which items need one. One place, used by the review and the generator. */
internal object ReviewRules {
    private val decidableKinds = setOf(ItemKind.Queue, ItemKind.Exchange, ItemKind.Policy, ItemKind.OperatorPolicy)
    private val conversionClasses = setOf(FindingClass.Incompatibility, FindingClass.BehaviourChange)

    /** The broker's own folder names; the migration parser refuses them (M8-04), so such an item cannot be written. */
    fun isWritableName(name: String): Boolean = name != "." && name != ".."

    /** Findings that can change a declaration: kind, class and a name. Message loss, not covered, client check and access never do. */
    fun findingsByItem(report: CompatibilityReport): Map<ReviewItem, List<Finding>> {
        val result = LinkedHashMap<ReviewItem, MutableList<Finding>>()
        for (finding in report.findings) {
            val name = finding.name ?: continue
            if (finding.kind !in decidableKinds || finding.findingClass !in conversionClasses) continue
            result.getOrPut(ReviewItem(finding.kind, finding.vhost, name)) { ArrayList(2) }.add(finding)
        }
        return result
    }

    /** A finding of an item the review can decide on (a name that cannot be written still counts: it is only never a review item). */
    fun isReviewFinding(finding: Finding): Boolean =
        finding.name != null && finding.kind in decidableKinds && finding.findingClass in conversionClasses

    fun needsDecision(finding: Finding): Boolean =
        finding.severity != Severity.Info && finding.appliesWhen == AppliesWhen.Always

    /** Report order, each item at its first qualifying finding. */
    fun itemsNeedingDecision(report: CompatibilityReport): List<ReviewItem> {
        val seen = LinkedHashSet<ReviewItem>()
        for (finding in report.findings) {
            val name = finding.name ?: continue
            if (finding.kind !in decidableKinds || finding.findingClass !in conversionClasses) continue
            if (!needsDecision(finding) || !isWritableName(name)) continue
            seen.add(ReviewItem(finding.kind, finding.vhost, name))
        }
        return seen.toList()
    }
}

/** A proposal picked for a finding. */
internal data class ChosenProposal(val finding: Finding, val proposal: Proposal)

/** Turns the user's choice (an index in `[proposal] + alternatives`, 0 = default) into proposals and checks the rules of Accept. */
internal object ProposalChoice {
    fun resolve(findings: List<Finding>, chosen: Map<String, Int>): List<ChosenProposal> = findings.map { finding ->
        val index = chosen[finding.id] ?: 0
        require(index in 0..finding.alternatives.size) { "finding ${finding.id} has no proposal number $index" }
        ChosenProposal(finding, if (index == 0) finding.proposal else finding.alternatives[index - 1])
    }

    fun refusal(findings: List<Finding>, accept: ItemDecision.Accept): DecisionRefusal? {
        val picked = resolve(findings, accept.chosen)
        val manual = picked.filter {
            it.finding.severity == Severity.Blocker && it.finding.appliesWhen == AppliesWhen.Always &&
                (it.proposal is Proposal.Manual || it.proposal == Proposal.None)
        }
        if (manual.isNotEmpty()) return DecisionRefusal.ManualNeeded(manual.map { it.finding.id })
        val needed = picked.mapNotNull { (it.proposal as? Proposal.ConvertQueueType)?.acknowledgement }.toSet()
        val missing = needed - accept.acknowledged
        return if (missing.isEmpty()) null else DecisionRefusal.TickMissing(missing)
    }
}
