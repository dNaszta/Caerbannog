package dev.caerbannog.core.migrate3x

/** The rules the analyzer runs, grouped by area. Each rule id has an entry in [RuleCatalog]. */
internal object DefaultRules {
    val all: List<Rule> = PolicyRules.all + QueueRules.all + listOf(ClassicIndexRule, ExchangeTypeNotBuiltinRule) +
        ParameterRules.all + GlobalRules.all + AccessRules.all
}
