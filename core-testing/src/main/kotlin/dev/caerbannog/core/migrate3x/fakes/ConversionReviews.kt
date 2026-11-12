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
    fun defaultTicks(report: CompatibilityReport, item: ReviewItem): Set<Acknowledgement> = ticksByItem(report)[item].orEmpty()

    /**
     * Indexes the report once, so a review of N items costs O(findings) and not O(N x findings): scanning every finding per item took
     * minutes at the 60,000 queues of `ConversionReviewScaleTest` on a CI runner and looked like a hang.
     */
    private fun ticksByItem(report: CompatibilityReport): Map<ReviewItem, Set<Acknowledgement>> =
        report.findings.groupBy { ReviewItem(it.kind, it.vhost, it.name) }.mapValues { (_, findings) ->
            findings.mapNotNull { (it.proposal as? Proposal.ConvertQueueType)?.acknowledgement }.toSet()
        }

    /**
     * Accepts the default of every pending item with the ticks it needs. Returns the items the review refused (for example a non-durable
     * queue, which has no automatic fix); the caller decides what to do with them.
     */
    fun acceptEveryDefault(review: ConversionReview, report: CompatibilityReport): List<ReviewItem> {
        val ticks = ticksByItem(report)
        return review.pending.filter { item ->
            review.decide(item, ItemDecision.Accept(acknowledged = ticks[item].orEmpty())) !is DecisionResult.Decided
        }
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
