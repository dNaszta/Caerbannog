package dev.caerbannog.core.migrate3x

/** M8-06e STUB: the findings of one rule and severity in report order (key = `<rule>:<severity lowercase>`). The real one is built by [GroupIndex.of]. */
internal class IndexedGroup(
    val key: String,
    val rule: String,
    val severity: Severity,
    val findingClass: FindingClass,
    val findings: List<Finding>,
)

/**
 * M8-06e STUB: immutable index of a report, built in ONE pass by [of]: [groups] in display order (message loss first, then severity, then key) and
 * [lossNames] (escaped with `escapeForText`, report order, findings of class `MessageLoss`). The stub returns an empty index so that the tests of
 * the index fail by assertion; the developer replaces [of] (brief M8-06e, items 1 and 6).
 */
internal class GroupIndex private constructor(
    val groups: List<IndexedGroup>,
    val lossNames: List<String>,
) {
    companion object {
        fun of(findings: List<Finding>): GroupIndex = GroupIndex(emptyList(), emptyList()) // M8-06e STUB
    }
}
