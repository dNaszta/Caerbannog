package dev.caerbannog.core.migrate3x

/** Turns a parsed 3.x definitions export, plus optional live facts, into a [CompatibilityReport]. Pure: no I/O, no clock, no logging. */
object CompatibilityAnalyzer {
    /**
     * Runs every rule of [DefaultRules] over [document]. [live] holds facts only a live 3.x connection can give; pass null for a
     * file. The report is sorted: message-loss findings first (P16), then virtual host, kind, name, rule and `about`, so the same
     * input in any list order gives an equal report.
     */
    fun analyze(document: DefinitionsDocument, live: LiveSourceFacts? = null): CompatibilityReport {
        val context = AnalysisContext(document, live)
        val findings = DefaultRules.all.flatMap { it.check(context) }.sortedWith(reportOrder)
        return CompatibilityReport(sourceOf(document, live), summarize(document, findings), findings)
    }

    private val reportOrder: Comparator<Finding> = compareBy<Finding>(
        { it.findingClass != FindingClass.MessageLoss },
        { it.vhost != null },
        { it.vhost },
        { it.kind },
        { it.name != null },
        { it.name },
        { it.rule },
        { it.about.joinToString(",") },
    )

    private fun sourceOf(document: DefinitionsDocument, live: LiveSourceFacts?) = ReportSource(
        name = live?.connectionName,
        sourceVersion = document.sourceVersion,
        live = live != null,
        singleVirtualHost = (document.origin as? DefinitionsOrigin.SingleVirtualHost)?.assignedName,
    )

    private val topologyKinds = setOf(ItemKind.Queue, ItemKind.Exchange, ItemKind.Binding, ItemKind.Policy, ItemKind.OperatorPolicy)

    /**
     * An item needs a decision when a Blocker or Warning that always applies names it. Info, conditional, access, parameter and
     * global findings do not change the counts.
     */
    private fun summarize(document: DefinitionsDocument, findings: List<Finding>): ReportSummary {
        val topologyItems = document.queues.size + document.exchanges.size + document.bindings.size + document.policies.size + document.operatorPolicies.size
        val needDecision = findings
            .filter { it.kind in topologyKinds && it.severity != Severity.Info && it.appliesWhen == AppliesWhen.Always }
            .mapTo(mutableSetOf()) { ItemRef(it.kind, it.vhost, it.name) }
            .size
        return ReportSummary(
            topologyItems = topologyItems,
            compatibleItems = topologyItems - needDecision,
            needDecision = needDecision,
            blockers = findings.count { it.severity == Severity.Blocker },
            warnings = findings.count { it.severity == Severity.Warning },
            infos = findings.count { it.severity == Severity.Info },
            messageLoss = findings.count { it.findingClass == FindingClass.MessageLoss },
        )
    }
}
