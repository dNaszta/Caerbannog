package dev.caerbannog.core.migrate3x

/** An exchange type that is not built in may need a plugin on the 4.x broker; the app does not check (unverified). */
internal object ExchangeTypeNotBuiltinRule : Rule {
    override val info: RuleInfo = RuleCatalog["exchange-type-not-builtin"]
    private val builtIn = setOf("direct", "fanout", "topic", "headers")

    override fun check(context: AnalysisContext): List<Finding> =
        context.document.exchanges.filter { it.type !in builtIn }.map {
            finding(
                ItemRef(ItemKind.Exchange, it.vhost, it.name), Severity.Warning, FindingClass.Incompatibility,
                ReportTexts.exchangeTypeNotBuiltin(it.type), ReportTexts.exchangePluginManual,
            )
        }
}
