package dev.caerbannog.core.migrate3x

/**
 * Groups, chips, loss block and lazy rows of a held analysis. Pure. A group holds the findings of one rule and one severity; the groups of
 * message loss come first, then blocker, warning, info, each by rule name. A closed group builds no rows: [RowSource.at] formats one finding
 * when the list draws it, so a report of 240,000 findings costs one pass over the findings and nothing per row until it is shown.
 */
object ReportViewBuilder {
    private val chipOrder = listOf(Severity.Blocker, Severity.Warning, Severity.Info)

    /**
     * [selected]: severities shown (default all); [openGroups]: keys of open groups; [rowFormatter] builds one row, called only by
     * [RowSource.at] of an open group (a test counts the calls).
     */
    fun build(
        held: HeldAnalysis,
        selected: Set<Severity> = Severity.entries.toSet(),
        openGroups: Set<String> = emptySet(),
        rowFormatter: (Finding) -> RowView = FindingRowFormatter::format,
        /** M8-06c: the 3.x connection that is Connected and readable now, or null. A live analysis of another name shows "(disconnected)". */
        liveConnectionName: String? = null,
    ): ReportView {
        val report = held.report
        val review = held.review
        val skipped = skippedItems(review)
        val removals = Removals.of(review, skipped)
        val visible = held.groupIndex.groups
            .filter { it.severity in selected }
            .map { it to it.findings.size - removals.countOf(it.key) }
            .filter { (_, count) -> count > 0 }
        val visibleByKey = visible.associate { (group, _) -> group.key to group }
        val groups = visible.map { (group, count) -> groupView(group, count, group.key in openGroups) } +
            listOfNotNull(skippedGroup(skipped, SKIPPED_GROUP_KEY in openGroups))
        return ReportView(
            fileLabel = held.fileLabel,
            source = sourceCard(held, liveConnectionName),
            sourceVersion = report.source.sourceVersion,
            singleVirtualHost = report.source.singleVirtualHost,
            summary = report.summary,
            summaryLine = summaryLine(report.summary),
            counts = documentCounts(held.document),
            countsLine = countsLine(held.document, report.source.singleVirtualHost),
            lossBlock = lossBlock(held.groupIndex.lossNames, held.kind),
            accessNote = MigrateFromThreeTexts.ACCESS_EMPTY.takeIf { report.source.singleVirtualHost != null },
            chips = chipOrder.map { severity ->
                ChipView(severity, count(report.summary, severity), selected = severity in selected)
            },
            groups = groups,
        ) { key ->
            if (key == SKIPPED_GROUP_KEY) skippedRows(review, skipped, SKIPPED_GROUP_KEY in openGroups)
            else rowsOf(visibleByKey[key], removals, key in openGroups) { decorate(rowFormatter(it), it, review) }
        }
    }

    /** A live analysis is locked ("read only"); its label is the connection while that connection is readable now, else "from <name> (disconnected)". */
    private fun sourceCard(held: HeldAnalysis, liveConnectionName: String?): SourceCardView = when (held.kind) {
        SourceKind.File -> SourceCardView(SourceKind.File, held.fileLabel, locked = false, lockText = null, canAnalyseAgain = false)
        SourceKind.Live -> {
            val same = liveConnectionName == held.fileLabel
            SourceCardView(
                SourceKind.Live,
                if (same) held.fileLabel else MigrateFromThreeTexts.liveDisconnected(held.fileLabel),
                locked = true,
                lockText = MigrateFromThreeTexts.READ_ONLY,
                canAnalyseAgain = same,
            )
        }
    }

    /** The items the user skipped, in report order. One pass over the items; empty when nothing is skipped. */
    private fun skippedItems(review: ConversionReview): List<ReviewItem> = review.items.filter { review.decisionOf(it) == ItemDecision.Skip }

    /** The Skipped group is last, ignores the chips, and exists only while an item is skipped. Closed unless [open]. */
    private fun skippedGroup(skipped: List<ReviewItem>, open: Boolean): GroupView? =
        if (skipped.isEmpty()) null else GroupView(SKIPPED_GROUP_KEY, MigrateFromThreeTexts.SKIPPED_GROUP_TITLE, null, null, skipped.size, open)

    private fun skippedRows(review: ConversionReview, skipped: List<ReviewItem>, open: Boolean): RowSource {
        if (!open || skipped.isEmpty()) return RowSource.EMPTY
        return object : RowSource {
            override val size: Int = skipped.size
            override fun at(index: Int): RowView = skippedRow(review, skipped[index])
        }
    }

    private fun skippedRow(review: ConversionReview, item: ReviewItem): RowView {
        val finding = review.findingsOf(item).firstOrNull(ReviewRules::needsDecision)
        val was = finding?.let { "${it.severity.name}, ${escapeForText(it.message)}" }
        return RowView(
            kind = item.kind,
            name = item.name?.let { escapeForText(it) },
            message = "Left out of the migration." + (was?.let { " Was: $it" } ?: ""),
            why = "",
            proposal = "",
            verified = finding?.verified ?: true,
            user = null,
            item = item,
            decision = DecisionPill.Skipped,
        )
    }

    /** A row of a finding that needs a decision offers its item and shows the pill; any other row stays as the formatter made it. */
    private fun decorate(row: RowView, finding: Finding, review: ConversionReview): RowView {
        if (!ReviewRules.isReviewFinding(finding) || !ReviewRules.needsDecision(finding)) return row
        val item = ReviewItem(finding.kind, finding.vhost, finding.name)
        // Skip is never refused for a review item, so a refusal here means "not an item that needs a decision" (for example a name that cannot be written).
        if (review.refusalOf(item, ItemDecision.Skip) != null) return row
        return row.copy(item = item, decision = ReviewViewBuilder.pillOf(review.decisionOf(item)))
    }

    /** What the skipped items take out of the index: the removed findings per group key (counts) and their ids, from [ReviewRules] through `findingsOf`. */
    private class Removals(private val counts: Map<String, Int>, private val ids: Set<String>) {
        fun countOf(key: String): Int = counts[key] ?: 0
        fun hasAny(key: String): Boolean = key in counts
        fun removes(finding: Finding): Boolean = finding.id in ids

        companion object {
            fun of(review: ConversionReview, skipped: List<ReviewItem>): Removals {
                if (skipped.isEmpty()) return Removals(emptyMap(), emptySet())
                val counts = HashMap<String, Int>()
                val ids = HashSet<String>()
                for (item in skipped) for (finding in review.findingsOf(item)) {
                    counts.merge(GroupIndex.keyOf(finding), 1, Int::plus)
                    ids += finding.id
                }
                return Removals(counts, ids)
            }
        }
    }

    private fun groupView(group: IndexedGroup, count: Int, open: Boolean): GroupView =
        GroupView(group.key, MigrateFromThreeTexts.ruleTitle(group.rule), group.severity, group.findingClass, count, open)

    /** A group without removals reads the index list itself; one with removals filters its list once, only when it is open and drawn. */
    private fun rowsOf(group: IndexedGroup?, removals: Removals, open: Boolean, rowFormatter: (Finding) -> RowView): RowSource {
        if (group == null || !open) return RowSource.EMPTY
        val findings = if (removals.hasAny(group.key)) group.findings.filterNot(removals::removes) else group.findings
        return object : RowSource {
            override val size: Int = findings.size
            override fun at(index: Int): RowView = rowFormatter(findings[index])
        }
    }

    private fun count(summary: ReportSummary, severity: Severity): Int = when (severity) {
        Severity.Blocker -> summary.blockers
        Severity.Warning -> summary.warnings
        Severity.Info -> summary.infos
    }

    private fun summaryLine(s: ReportSummary): String =
        "${MigrateFromThreeTexts.plural(s.topologyItems, "item")}, ${MigrateFromThreeTexts.count(s.compatibleItems)} compatible as they are " +
            "(not listed below), ${MigrateFromThreeTexts.count(s.needDecision)} need a decision"

    private fun documentCounts(document: DefinitionsDocument) =
        DocumentCounts(document.virtualHosts.size, document.queues.size, document.exchanges.size, document.bindings.size)

    private fun countsLine(document: DefinitionsDocument, singleVirtualHost: String?): String {
        val counts = documentCounts(document)
        val vhosts = if (singleVirtualHost != null) "virtual host ${escapeForText(singleVirtualHost)}" else MigrateFromThreeTexts.plural(counts.virtualHosts, "virtual host")
        return listOf(
            vhosts,
            MigrateFromThreeTexts.plural(counts.queues, "queue"),
            MigrateFromThreeTexts.plural(counts.exchanges, "exchange"),
            MigrateFromThreeTexts.plural(counts.bindings, "binding"),
        ).joinToString(", ")
    }

    /** The queues at risk, names escaped like the text export. Null when no queue is at risk. Never filtered by the chips. */
    private fun lossBlock(names: List<String>, kind: SourceKind): LossBlockView? {
        if (names.isEmpty()) return null
        return LossBlockView(
            title = MigrateFromThreeTexts.lossTitle(names.size),
            queueCount = names.size,
            preview = names.take(LOSS_PREVIEW_COUNT),
            detection = if (kind == SourceKind.Live) MigrateFromThreeTexts.LOSS_DETECTION_LIVE else MigrateFromThreeTexts.LOSS_DETECTION,
            fixes = listOf(
                FixView(MigrateFromThreeTexts.LOSS_FIX_NEW_BROKER_TITLE, MigrateFromThreeTexts.LOSS_FIX_NEW_BROKER_TEXT, recommended = true),
                FixView(MigrateFromThreeTexts.LOSS_FIX_POLICY_TITLE, MigrateFromThreeTexts.LOSS_FIX_POLICY_TEXT, recommended = false),
                FixView(MigrateFromThreeTexts.LOSS_FIX_UPGRADE_THROUGH_TITLE, MigrateFromThreeTexts.LOSS_FIX_UPGRADE_THROUGH_TEXT, recommended = false),
            ),
            copyTextSupplier = { names.joinToString("\n") },
        ) { names[it] }
    }
}
