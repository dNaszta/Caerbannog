package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsDocuments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/** M8-03 test helpers. Pure functions over the report; no logic that belongs to the analyzer. */
internal const val ORDERING_TICK = "Ordering by priority may change"
internal const val KNOWN_PASSWORD_TICK = "This user has a publicly known password"

internal typealias D = DefinitionsDocuments

internal fun analyze(document: DefinitionsDocument, live: LiveSourceFacts? = null): CompatibilityReport = CompatibilityAnalyzer.analyze(document, live)

internal fun analyzeQueues(vararg queues: QueueDefinition, policies: List<PolicyDefinition> = emptyList(), live: LiveSourceFacts? = null, sourceVersion: String? = "3.13.7"): CompatibilityReport =
    analyze(D.document(queues = queues.toList(), policies = policies, sourceVersion = sourceVersion), live)

internal fun CompatibilityReport.withRule(rule: String, name: String? = null): List<Finding> = findings.filter { it.rule == rule && (name == null || it.name == name) }

private fun CompatibilityReport.listing() = findings.joinToString { "${it.rule}:${it.name}${it.about}" }

/** The one finding of [rule] (for the item [name] when given); fails with the whole list when there is none or more than one. */
internal fun CompatibilityReport.only(rule: String, name: String? = null): Finding {
    val found = withRule(rule, name)
    assertEquals(1, found.size, "expected exactly one '$rule' finding${name?.let { " for '$it'" } ?: ""} but got ${found.size}. All findings: ${listing()}")
    return found.single()
}

/** A negative test is only worth something when the analyzer really ran: every report carries the three `clients-*` checks. */
internal fun CompatibilityReport.assertAnalyzed() =
    assertTrue(findings.any { it.rule == "clients-global-qos" }, "the analyzer produced no usable report (no clients-global-qos finding), so a 'no finding' check proves nothing")

internal fun CompatibilityReport.assertNone(rule: String, name: String? = null) {
    assertAnalyzed()
    val found = withRule(rule, name)
    assertTrue(found.isEmpty(), "expected no '$rule' finding${name?.let { " for '$it'" } ?: ""} but got ${found.size}. All findings: ${listing()}")
}

/** Severity, class, scope, default decision, verified flag and evidence of one finding in one place. */
internal fun Finding.assertShape(
    severity: Severity,
    findingClass: FindingClass,
    appliesWhen: AppliesWhen = AppliesWhen.Always,
    verified: Boolean,
    evidence: EvidenceSource = EvidenceSource.Definitions,
    defaultDecision: DefaultDecision = DefaultDecision.Accept,
) {
    assertEquals(
        listOf(severity, findingClass, appliesWhen, defaultDecision, verified, evidence),
        listOf(this.severity, this.findingClass, this.appliesWhen, this.defaultDecision, this.verified, this.evidence),
        "severity, class, appliesWhen, defaultDecision, verified, evidence of '$rule' for '$name'",
    )
}

internal fun Finding.text(): String = "$message\n$why"

internal fun Finding.allProposals(): List<Proposal> = listOf(proposal) + alternatives

internal fun Finding.manualTexts(): List<String> = allProposals().filterIsInstance<Proposal.Manual>().map { "${it.title}\n${it.text}" }

internal fun assertContainsText(haystack: String, needle: String, what: String) =
    assertTrue(haystack.contains(needle, ignoreCase = true), "$what should say \"$needle\" but is: $haystack")


/** Every proposal and alternative of every finding. */
internal fun CompatibilityReport.allProposals(): List<Proposal> = findings.flatMap { it.allProposals() }

internal fun rec(v: ClassicIndexVersion, vararg queues: String, vhost: String = "/") = queues.associate { QueueRef(vhost, it) to v }
