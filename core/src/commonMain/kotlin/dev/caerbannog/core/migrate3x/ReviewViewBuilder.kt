package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrations.MigrationAction

/**
 * What the user has changed in the open sheet and not decided yet. Local to the sheet: closing it drops [chosen] and [ticks]; the Replace text
 * lives in the view model's drafts. [chosen] maps a finding id to a non-default option index, [ticks] holds the acknowledgements ticked.
 */
internal data class SheetEdit(
    val item: ReviewItem,
    val itemNumber: Int,
    val chosen: Map<String, Int> = emptyMap(),
    val ticks: Set<Acknowledgement> = emptySet(),
    /** Non-null while the Replace sheet is shown. */
    val replaceText: String? = null,
    val replaceError: String? = null,
)

/** Pure: builds the counts and lines of the bar and the sheet of one item. Reads the review, changes nothing. */
internal object ReviewViewBuilder {
    fun state(review: ConversionReview, edit: SheetEdit?, generation: GenerationState): ReviewState {
        var accepted = 0
        var skipped = 0
        var replaced = 0
        for (item in review.items) {
            when (review.decisionOf(item)) {
                is ItemDecision.Accept -> accepted++
                ItemDecision.Skip -> skipped++
                is ItemDecision.Replace -> replaced++
                null -> Unit
            }
        }
        val itemCount = review.items.size
        val pending = itemCount - accepted - skipped - replaced
        return ReviewState(
            itemCount = itemCount,
            pendingCount = pending,
            acceptedCount = accepted,
            skippedCount = skipped,
            replacedCount = replaced,
            pendingLine = MigrateFromThreeTexts.pendingLine(itemCount, pending),
            decidedLine = if (itemCount > 0 && pending == 0) MigrateFromThreeTexts.decidedLine(itemCount, accepted, skipped, replaced) else null,
            canGenerate = pending == 0 && generation != GenerationState.Saving,
            sheet = edit?.let { sheet(review, it, review.items.size) },
            generation = generation,
        )
    }

    /** The `Accept` the sheet would send: the non-default choices and only the ticks the chosen proposals ask for. */
    fun acceptOf(review: ConversionReview, edit: SheetEdit): ItemDecision.Accept {
        val picked = ProposalChoice.resolve(review.findingsOf(edit.item), edit.chosen)
        val required = picked.mapNotNull { it.proposal.acknowledgement }.toSet()
        return ItemDecision.Accept(chosen = edit.chosen.filterValues { it != 0 }, acknowledged = edit.ticks.filterTo(LinkedHashSet()) { it in required })
    }

    /** The sheet as it is stored: the choices and ticks of an accepted item, nothing for a skipped or replaced one. */
    fun editOf(review: ConversionReview, item: ReviewItem, itemNumber: Int): SheetEdit = when (val stored = review.decisionOf(item)) {
        is ItemDecision.Accept -> SheetEdit(item, itemNumber, stored.chosen, stored.acknowledged)
        else -> SheetEdit(item, itemNumber)
    }

    private fun sheet(review: ConversionReview, edit: SheetEdit, total: Int): ItemSheet {
        val findings = review.findingsOf(edit.item)
        val picked = ProposalChoice.resolve(findings, edit.chosen)
        val accept = acceptOf(review, edit)
        val block = review.refusalOf(edit.item, accept)
        return ItemSheet(
            item = edit.item,
            itemNumber = edit.itemNumber,
            itemTotal = total,
            findings = findings.map { findingChoice(it, edit.chosen[it.id] ?: 0) },
            ticks = picked.mapNotNull { it.proposal.acknowledgement }.distinct().map { TickView(it, it.text, it in edit.ticks) },
            manualSteps = picked.mapNotNull { (finding, proposal) ->
                (proposal as? Proposal.Manual)?.let { ManualStepView(finding.id, escapeForText(it.title), escapeForText(it.text)) }
            },
            acceptBlock = block,
            canAccept = block == null,
            replace = edit.replaceText?.let { ReplaceEditor(it, edit.replaceError, MigrateFromThreeTexts.replaceHint(edit.item.vhost.orEmpty())) },
            decided = decidedAs(review.decisionOf(edit.item)),
        )
    }

    private fun findingChoice(finding: Finding, chosen: Int): FindingChoice = FindingChoice(
        findingId = finding.id,
        severity = finding.severity,
        message = escapeForText(finding.message),
        why = escapeForText(finding.why),
        verified = finding.verified,
        options = (listOf(finding.proposal) + finding.alternatives).mapIndexed { index, proposal ->
            ProposalOption(index, escapeForText(proposal.describe()), isDefault = index == 0)
        },
        chosen = chosen,
    )

    private fun decidedAs(decision: ItemDecision?): DecidedAs? = when (decision) {
        is ItemDecision.Accept -> DecidedAs.Accepted(decision.acknowledged.isNotEmpty())
        is ItemDecision.Replace -> DecidedAs.Replaced(decision.actions.size)
        ItemDecision.Skip -> DecidedAs.Skipped
        null -> null
    }

    /** The pill of a row of an item that needs a decision. */
    fun pillOf(decision: ItemDecision?): DecisionPill = when (decision) {
        is ItemDecision.Accept -> DecisionPill.Accepted(decision.acknowledged.isNotEmpty())
        is ItemDecision.Replace -> DecisionPill.Replaced(decision.actions.size)
        ItemDecision.Skip -> DecisionPill.Skipped
        null -> DecisionPill.Undecided
    }

    /** The text of the Replace sheet when there is no draft: what the app would write for the item now. */
    fun prefill(review: ConversionReview, edit: SheetEdit): List<MigrationAction> = review.previewActions(edit.item, acceptOf(review, edit))
}
