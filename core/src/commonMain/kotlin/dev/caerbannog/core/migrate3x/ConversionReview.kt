package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFile

// M8-05 STUB: the public API of the review, written by the test developer so the tests compile. The senior developer replaces every body.
// The contract is in docs/tasks/M8-05-conversion-proposals-and-review-core.md and in the tests under core/src/jvmTest/.../migrate3x/.

/** An item of the definitions that may need a decision. Bindings are named `source->destination:routingKey`. */
data class ReviewItem(val kind: ItemKind, val vhost: String?, val name: String?)

sealed interface ItemDecision {
    /** [chosen] maps a finding id to the index in `[proposal] + alternatives` (0 = the default). */
    data class Accept(val chosen: Map<String, Int> = emptyMap(), val acknowledged: Set<Acknowledgement> = emptySet()) : ItemDecision

    data object Skip : ItemDecision

    /** The user's own actions for the item; they replace the generated ones. */
    data class Replace(val actions: List<MigrationAction>) : ItemDecision
}

sealed interface DecisionRefusal {
    /** A blocker that always applies has a chosen proposal that is manual or none. */
    data class ManualNeeded(val findingIds: List<String>) : DecisionRefusal

    /** A chosen proposal carries an acknowledgement that is not in `acknowledged`. */
    data class TickMissing(val missing: Set<Acknowledgement>) : DecisionRefusal

    /** The item is compatible, a binding, or unknown: nothing to decide. */
    data object NotAReviewItem : DecisionRefusal
}

sealed interface DecisionResult {
    data object Decided : DecisionResult
    data class Refused(val reason: DecisionRefusal) : DecisionResult
}

/** [existingVersions] are the versions already in the migrations folder; [source] is the connection or file label for the description. */
data class GenerationRequest(val existingVersions: Set<String>, val source: String)

enum class SkipReason { SkippedByUser, NameCannotBeWritten, UnknownKind }

data class SkippedItem(val item: ReviewItem, val reason: SkipReason)

/** Fixed plain text. [item] is the item the note is about, when there is one. */
data class GenerationNote(val text: String, val item: ReviewItem? = null)

data class GeneratedMigration(val vhost: String, val file: MigrationFile)

sealed interface GenerationResult {
    /** Nothing is produced. */
    data class Incomplete(val pending: List<ReviewItem>) : GenerationResult

    data class Generated(
        val migrations: List<GeneratedMigration>,
        val skipped: List<SkippedItem>,
        val notes: List<GenerationNote>,
    ) : GenerationResult
}

/** M8-05 STUB. */
class ConversionReview private constructor(private val document: DefinitionsDocument, private val report: CompatibilityReport) {
    val items: List<ReviewItem> get() = emptyList() // M8-05 STUB
    val pending: List<ReviewItem> get() = emptyList() // M8-05 STUB

    fun decide(item: ReviewItem, decision: ItemDecision): DecisionResult = DecisionResult.Refused(DecisionRefusal.NotAReviewItem) // M8-05 STUB

    fun decisionOf(item: ReviewItem): ItemDecision? = null // M8-05 STUB

    fun generate(request: GenerationRequest): GenerationResult = GenerationResult.Incomplete(emptyList()) // M8-05 STUB

    override fun toString(): String = "ConversionReview" // M8-05 STUB

    companion object {
        fun start(document: DefinitionsDocument, report: CompatibilityReport): ConversionReview = ConversionReview(document, report)
    }
}
