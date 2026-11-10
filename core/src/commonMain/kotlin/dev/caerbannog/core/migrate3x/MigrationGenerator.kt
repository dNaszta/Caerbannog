package dev.caerbannog.core.migrate3x

// M8-05 STUB: the pure generator behind ConversionReview.generate. The senior developer decides its exact shape; the tests call it through the review.
internal object MigrationGenerator {
    fun generate(
        document: DefinitionsDocument,
        report: CompatibilityReport,
        decisions: Map<ReviewItem, ItemDecision>,
        pending: List<ReviewItem>,
        request: GenerationRequest,
    ): GenerationResult = GenerationResult.Incomplete(pending) // M8-05 STUB
}
