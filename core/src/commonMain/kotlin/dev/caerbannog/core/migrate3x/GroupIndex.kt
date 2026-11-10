package dev.caerbannog.core.migrate3x

/** The findings of one rule and severity in report order (key = `<rule>:<severity lowercase>`). The class is constant per rule. */
internal class IndexedGroup(
    val key: String,
    val rule: String,
    val severity: Severity,
    val findingClass: FindingClass,
    val findings: List<Finding>,
)

/**
 * Immutable index of a report, built in ONE pass by [of]: [groups] in display order (message loss first, then severity, then key) and
 * [lossNames] (escaped with `escapeForText`, report order, findings of class `MessageLoss`). It exists so that a chip, a group toggle or a
 * decision does not group the findings again (M8-06e); decisions are applied on top of it by [ReportViewBuilder].
 */
internal class GroupIndex private constructor(
    val groups: List<IndexedGroup>,
    val lossNames: List<String>,
) {
    companion object {
        fun of(findings: List<Finding>): GroupIndex {
            val byKey = HashMap<String, MutableList<Finding>>()
            val lossNames = ArrayList<String>()
            for (finding in findings) {
                byKey.getOrPut(keyOf(finding)) { ArrayList() } += finding
                if (finding.findingClass == FindingClass.MessageLoss) lossNames += escapeForText(finding.name.orEmpty())
            }
            val groups = byKey.map { (key, list) ->
                val first = list.first()
                IndexedGroup(key, first.rule, first.severity, first.findingClass, list)
            }.sortedWith(
                compareBy<IndexedGroup>(
                    { if (it.findingClass == FindingClass.MessageLoss) 0 else 1 },
                    { it.severity.ordinal },
                    { it.key },
                ),
            )
            return GroupIndex(groups, lossNames)
        }

        fun keyOf(finding: Finding): String = "${finding.rule}:${finding.severity.name.lowercase()}"
    }
}
