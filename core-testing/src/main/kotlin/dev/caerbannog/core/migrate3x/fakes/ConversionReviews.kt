package dev.caerbannog.core.migrate3x.fakes

import dev.caerbannog.core.migrate3x.CompatibilityAnalyzer
import dev.caerbannog.core.migrate3x.CompatibilityReport
import dev.caerbannog.core.migrate3x.ConversionReview
import dev.caerbannog.core.migrate3x.DecisionResult
import dev.caerbannog.core.migrate3x.DefinitionsDocument
import dev.caerbannog.core.migrate3x.GenerationRequest
import dev.caerbannog.core.migrate3x.GenerationResult
import dev.caerbannog.core.migrate3x.ItemDecision
import dev.caerbannog.core.migrate3x.LiveSourceFacts
import dev.caerbannog.core.migrate3x.Proposal
import dev.caerbannog.core.migrate3x.ReviewItem
import dev.caerbannog.core.migrate3x.Acknowledgement

/**
 * M8-05: helpers for review tests and scenarios. Builds the report with the real analyzer and accepts defaults. No logic of the review itself
 * lives here: the only decision it makes is "which ticks does the default proposal of this item carry", read from the report data.
 * Lives in core-testing; never used by production code.
 */
object ConversionReviews {
    fun report(document: DefinitionsDocument, live: LiveSourceFacts? = null): CompatibilityReport = CompatibilityAnalyzer.analyze(document, live)

    fun start(document: DefinitionsDocument, live: LiveSourceFacts? = null): Pair<ConversionReview, CompatibilityReport> {
        val report = report(document, live)
        return ConversionReview.start(document, report) to report
    }

    /** The ticks the DEFAULT proposals of [item]'s findings carry. */
    fun defaultTicks(report: CompatibilityReport, item: ReviewItem): Set<Acknowledgement> =
        report.findings.filter { it.kind == item.kind && it.vhost == item.vhost && it.name == item.name }
            .mapNotNull { (it.proposal as? Proposal.ConvertQueueType)?.acknowledgement }.toSet()

    /**
     * Accepts the default of every pending item with the ticks it needs. Returns the items the review refused (for example a non-durable
     * queue, which has no automatic fix); the caller decides what to do with them.
     */
    fun acceptEveryDefault(review: ConversionReview, report: CompatibilityReport): List<ReviewItem> =
        review.pending.filter { item ->
            review.decide(item, ItemDecision.Accept(acknowledged = defaultTicks(report, item))) !is DecisionResult.Decided
        }

    /** Accepts every default and skips what cannot be accepted, then generates. Fails loudly when the result is not generated. */
    fun generateAll(
        review: ConversionReview,
        report: CompatibilityReport,
        request: GenerationRequest = GenerationRequest(emptySet(), "legacy-eu"),
    ): GenerationResult.Generated {
        acceptEveryDefault(review, report).forEach { review.decide(it, ItemDecision.Skip) }
        return review.generate(request) as? GenerationResult.Generated
            ?: error("generation did not produce a result: ${review.generate(request)}")
    }
}
