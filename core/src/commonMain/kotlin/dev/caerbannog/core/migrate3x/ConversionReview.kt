package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.echoableName
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult

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

    /** A `Replace` holds a delete action (any `Delete*`). */
    data object ReplaceHasDelete : DecisionRefusal

    /** A `Replace` holds an action the migration parser refuses, or one for another vhost than the item's. [reason] is the parser's own wording or the vhost wording; a vhost is named in it only when `echoableName` allows, so a hostile name never appears. */
    data class ReplaceInvalid(val reason: String) : DecisionRefusal

    /** The item is compatible, a binding, or unknown: nothing to decide. */
    data object NotAReviewItem : DecisionRefusal
}

sealed interface DecisionResult {
    data object Decided : DecisionResult
    data class Refused(val reason: DecisionRefusal) : DecisionResult
}

/** [existingVersions] are the versions already in the migrations folder; [source] is the connection or file label for the description. */
data class GenerationRequest(val existingVersions: Set<String>, val source: String)

enum class SkipReason { SkippedByUser, NameCannotBeWritten }

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

/**
 * The review of an analysed 3.x definitions file: one decision per item that needs one, then one ordinary topology migration per
 * source virtual host. Single-threaded; decisions live in one map keyed by [ReviewItem], so a file with 60,000 queues is fine.
 * It sends nothing, writes nothing and holds no secret of its own; [toString] shows counts only.
 */
class ConversionReview private constructor(private val document: DefinitionsDocument, private val report: CompatibilityReport) {
    private val findingsByItem: Map<ReviewItem, List<Finding>> = ReviewRules.findingsByItem(report)

    /** The items that need a decision, in report order, each once. An item with a name that cannot be written is never one. */
    val items: List<ReviewItem> = ReviewRules.itemsNeedingDecision(report)

    private val itemSet: Set<ReviewItem> = items.toHashSet()
    private val decisions: MutableMap<ReviewItem, ItemDecision> = HashMap()

    /** [items] without the decided ones, same order. */
    val pending: List<ReviewItem> get() = items.filter { it !in decisions }

    /** Stores [decision] for [item] (replacing an earlier one), or refuses it and keeps the earlier one. */
    fun decide(item: ReviewItem, decision: ItemDecision): DecisionResult {
        if (item !in itemSet) return DecisionResult.Refused(DecisionRefusal.NotAReviewItem)
        val refusal = when (decision) {
            ItemDecision.Skip -> null
            is ItemDecision.Replace -> replaceRefusal(item, decision)
            is ItemDecision.Accept -> ProposalChoice.refusal(findingsByItem[item].orEmpty(), decision)
        }
        if (refusal != null) return DecisionResult.Refused(refusal)
        decisions[item] = decision
        return DecisionResult.Decided
    }

    /** Three flat steps, in this order: a destructive action, an action for another vhost, then whatever the migration parser refuses. So `generate` never fails on user input. */
    private fun replaceRefusal(item: ReviewItem, decision: ItemDecision.Replace): DecisionRefusal? {
        if (decision.actions.any { it.isDestructive }) return DecisionRefusal.ReplaceHasDelete
        val other = decision.actions.firstOrNull { it.vhost != item.vhost }
        if (other != null) return DecisionRefusal.ReplaceInvalid(otherVhostReason(item.vhost, other.vhost))
        return parserRefusal(decision)
    }

    /** Both vhosts are named only when the echo rule allows it; both come from a file, so either may be hostile. */
    private fun otherVhostReason(itemVhost: String?, actionVhost: String): String =
        if (itemVhost != null && echoableName(itemVhost) && echoableName(actionVhost)) {
            "an action is for virtual host '$actionVhost', but the item belongs to '$itemVhost'"
        } else {
            "an action is for another virtual host than the item's"
        }

    private fun parserRefusal(decision: ItemDecision.Replace): DecisionRefusal? {
        val raw = MigrationParser.exportToJson(THROWAWAY_VERSION, THROWAWAY_DESCRIPTION, decision.actions)
        return when (val result = MigrationParser.parse(raw)) {
            is MigrationValidationResult.Valid -> null
            is MigrationValidationResult.Invalid -> DecisionRefusal.ReplaceInvalid(result.reason)
        }
    }

    fun decisionOf(item: ReviewItem): ItemDecision? = decisions[item]

    // ---- M8-06b STUB: the four additions of "Core additions"; real bodies are the developer's. ----

    /** M8-06b STUB. */
    fun findingsOf(item: ReviewItem): List<Finding> = emptyList()

    /** M8-06b STUB. */
    fun previewActions(item: ReviewItem, accept: ItemDecision.Accept = ItemDecision.Accept()): List<MigrationAction> = emptyList()

    /** M8-06b STUB. */
    fun refusalOf(item: ReviewItem, decision: ItemDecision): DecisionRefusal? = null

    /** M8-06b STUB. */
    fun undecide(item: ReviewItem): Boolean = false

    /** Nothing is produced while an item is undecided. Fails loudly (IllegalStateException) when a version would pass 9999. */
    fun generate(request: GenerationRequest): GenerationResult =
        MigrationGenerator.generate(document, report, findingsByItem, decisions, pending, request)

    override fun toString(): String = "ConversionReview(items=${items.size}, decided=${decisions.size}, pending=${items.size - decisions.size})"

    companion object {
        private const val THROWAWAY_VERSION = "0001"
        private const val THROWAWAY_DESCRIPTION = "check"

        fun start(document: DefinitionsDocument, report: CompatibilityReport): ConversionReview = ConversionReview(document, report)
    }
}
