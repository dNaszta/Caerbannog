package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction

internal fun ctx(
    plan: TopologyPlan = TopologyPlan(),
    style: NamingStyle = NamingStyle.KEBAB,
    known: KnownTopology = KnownTopology.None,
) = FormContext(plan = plan, namingStyle = style, known = known)

internal fun planOf(vararg actions: MigrationAction) = TopologyPlan().append(actions.toList())
