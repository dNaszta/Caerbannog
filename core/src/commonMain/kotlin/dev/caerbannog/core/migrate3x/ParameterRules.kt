package dev.caerbannog.core.migrate3x

/** A parameter of a virtual host (shovel, federation upstream, any plugin): listed by component and name only, no value is read. */
internal object NotCoveredParameterRule : Rule {
    override val info: RuleInfo = RuleCatalog["not-covered-parameter"]

    override fun check(context: AnalysisContext): List<Finding> =
        context.document.otherParameters.filter { it.vhost != null }.map {
            finding(
                ItemRef(ItemKind.Parameter, it.vhost, it.name), Severity.Warning, FindingClass.NotCovered, ReportTexts.notCoveredParameter(it.component),
                ReportTexts.notCoveredParameterManual, about = listOf(it.component),
            )
        }
}

/** A global parameter (`cluster_name` and so on) belongs to the broker, not to the topology. */
internal object NotCoveredGlobalParameterRule : Rule {
    override val info: RuleInfo = RuleCatalog["not-covered-global-parameter"]

    override fun check(context: AnalysisContext): List<Finding> =
        context.document.otherParameters.filter { it.vhost == null }.map {
            finding(
                ItemRef(ItemKind.Global, null, null), Severity.Info, FindingClass.NotCovered, ReportTexts.notCoveredGlobalParameter, Proposal.None,
                about = listOf(it.name),
            )
        }
}

internal object ParameterRules {
    val all: List<Rule> = listOf(NotCoveredParameterRule, NotCoveredGlobalParameterRule)
}
