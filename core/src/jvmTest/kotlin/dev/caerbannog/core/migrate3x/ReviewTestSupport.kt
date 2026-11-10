package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrate3x.fakes.ConversionReviews
import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail

/** M8-05 test helpers. Plain functions; the review logic itself is never reimplemented here. */
internal val TICK = setOf(Acknowledgement.OrderingByPriorityMayChange)

internal class Started(val review: ConversionReview, val report: CompatibilityReport, val document: DefinitionsDocument)

internal fun started(document: DefinitionsDocument, live: LiveSourceFacts? = null): Started {
    val (review, report) = ConversionReviews.start(document, live)
    return Started(review, report, document)
}

internal fun q(name: String, vhost: String = "/") = ReviewItem(ItemKind.Queue, vhost, name)
internal fun ex(name: String, vhost: String = "/") = ReviewItem(ItemKind.Exchange, vhost, name)
internal fun pol(name: String, vhost: String = "/") = ReviewItem(ItemKind.Policy, vhost, name)
internal fun opPol(name: String, vhost: String = "/") = ReviewItem(ItemKind.OperatorPolicy, vhost, name)
internal fun bind(source: String, destination: String, routingKey: String = "", vhost: String = "/") =
    ReviewItem(ItemKind.Binding, vhost, "$source->$destination:$routingKey")

internal fun Started.findingId(rule: String, name: String): String = report.only(rule, name).id

internal fun Started.decide(item: ReviewItem, decision: ItemDecision) {
    val result = review.decide(item, decision)
    assertEquals(DecisionResult.Decided, result, "deciding $item with $decision")
}

internal fun Started.acceptWithTick(item: ReviewItem, chosen: Map<String, Int> = emptyMap()) = decide(item, ItemDecision.Accept(chosen, TICK))

internal fun Started.acceptEveryDefault(): List<ReviewItem> = ConversionReviews.acceptEveryDefault(review, report)

internal val REQUEST = GenerationRequest(emptySet(), "legacy-eu")

internal fun Started.generated(request: GenerationRequest = REQUEST): GenerationResult.Generated =
    when (val result = review.generate(request)) {
        is GenerationResult.Generated -> result
        is GenerationResult.Incomplete -> fail("expected a generated migration but ${result.pending.size} items are pending: ${result.pending}")
    }

internal fun GenerationResult.Generated.actions(vhost: String = "/"): List<MigrationAction> =
    migrations.singleOrNull { it.vhost == vhost }?.file?.up ?: fail("expected one migration for \"$vhost\" but got ${migrations.map { it.vhost }}")

internal fun GenerationResult.Generated.queue(name: String, vhost: String = "/"): MigrationAction.DeclareQueue =
    actions(vhost).filterIsInstance<MigrationAction.DeclareQueue>().singleOrNull { it.name == name }
        ?: fail("no declared queue \"$name\" in ${actions(vhost).map { it.description }}")

internal fun GenerationResult.Generated.policy(name: String, vhost: String = "/"): MigrationAction.DeclarePolicy? =
    actions(vhost).filterIsInstance<MigrationAction.DeclarePolicy>().singleOrNull { it.name == name }

internal fun GenerationResult.Generated.allActions(): List<MigrationAction> = migrations.flatMap { it.file.up }

internal fun GenerationResult.Generated.noteTexts(): List<String> = notes.map { it.text }

internal fun w(n: Long) = ArgumentValue.Whole(n)
internal fun t(s: String) = ArgumentValue.Text(s)

/** A synthetic finding for the cases the analyzer does not produce (every class, severity and scope). */
internal fun synthetic(
    item: ReviewItem,
    severity: Severity = Severity.Warning,
    findingClass: FindingClass = FindingClass.BehaviourChange,
    appliesWhen: AppliesWhen = AppliesWhen.Always,
    proposal: Proposal = Proposal.None,
    alternatives: List<Proposal> = emptyList(),
    rule: String = "synthetic",
) = Finding(
    id = "$rule|${item.vhost ?: "-"}|${item.kind}|${item.name ?: "-"}|", rule = rule, severity = severity, findingClass = findingClass,
    kind = item.kind, vhost = item.vhost, name = item.name, about = emptyList(), appliesWhen = appliesWhen, message = "m", why = "w",
    proposal = proposal, alternatives = alternatives, defaultDecision = DefaultDecision.Accept, verified = false, evidence = EvidenceSource.Definitions,
)

internal fun syntheticReport(vararg findings: Finding) = CompatibilityReport(
    ReportSource("legacy-eu", "3.13.7", live = false, singleVirtualHost = null),
    ReportSummary(0, 0, 0, 0, 0, 0, 0), findings.toList(),
)
