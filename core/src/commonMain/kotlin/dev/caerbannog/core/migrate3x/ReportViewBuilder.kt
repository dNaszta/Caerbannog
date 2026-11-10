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
    ): ReportView {
        val report = held.report
        val review = held.review
        val skipped = skippedItems(review)
        val byKey = groupFindings(report.findings, skipped)
        val visible = byKey.filterValues { it.first().severity in selected }
        val groups = visible.map { (key, findings) -> groupView(key, findings, key in openGroups) } +
            listOfNotNull(skippedGroup(skipped, SKIPPED_GROUP_KEY in openGroups))
        return ReportView(
            fileLabel = held.fileLabel,
            sourceVersion = report.source.sourceVersion,
            singleVirtualHost = report.source.singleVirtualHost,
            summary = report.summary,
            summaryLine = summaryLine(report.summary),
            counts = documentCounts(held.document),
            countsLine = countsLine(held.document, report.source.singleVirtualHost),
            lossBlock = lossBlock(report.findings),
            accessNote = MigrateFromThreeTexts.ACCESS_EMPTY.takeIf { report.source.singleVirtualHost != null },
            chips = chipOrder.map { severity ->
                ChipView(severity, count(report.summary, severity), selected = severity in selected)
            },
            groups = groups,
        ) { key ->
            if (key == SKIPPED_GROUP_KEY) skippedRows(review, skipped, SKIPPED_GROUP_KEY in openGroups)
            else rowsOf(visible[key], key in openGroups) { decorate(rowFormatter(it), it, review) }
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

    /** One pass; the map keeps the groups in their display order, and every list keeps the report order of its findings. */
    private fun groupFindings(findings: List<Finding>, skipped: List<ReviewItem>): Map<String, List<Finding>> {
        val groups = HashMap<String, MutableList<Finding>>()
        val skippedSet = skipped.toHashSet()
        for (finding in findings) {
            if (skippedSet.isNotEmpty() && ReviewRules.isReviewFinding(finding) && ReviewItem(finding.kind, finding.vhost, finding.name) in skippedSet) continue
            groups.getOrPut(keyOf(finding)) { mutableListOf() } += finding
        }
        val order = compareBy<Map.Entry<String, List<Finding>>>(
            { if (it.value.first().findingClass == FindingClass.MessageLoss) 0 else 1 },
            { it.value.first().severity.ordinal },
            { it.key },
        )
        return groups.entries.sortedWith(order).associate { it.key to it.value }
    }

    private fun keyOf(finding: Finding): String = "${finding.rule}:${finding.severity.name.lowercase()}"

    private fun groupView(key: String, findings: List<Finding>, open: Boolean): GroupView {
        val first = findings.first()
        return GroupView(key, MigrateFromThreeTexts.ruleTitle(first.rule), first.severity, first.findingClass, findings.size, open)
    }

    private fun rowsOf(findings: List<Finding>?, open: Boolean, rowFormatter: (Finding) -> RowView): RowSource {
        if (findings == null || !open) return RowSource.EMPTY
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
    private fun lossBlock(findings: List<Finding>): LossBlockView? {
        val names = findings.filter { it.findingClass == FindingClass.MessageLoss }.map { escapeForText(it.name.orEmpty()) }
        if (names.isEmpty()) return null
        return LossBlockView(
            title = MigrateFromThreeTexts.lossTitle(names.size),
            queueCount = names.size,
            preview = names.take(LOSS_PREVIEW_COUNT),
            detection = MigrateFromThreeTexts.LOSS_DETECTION,
            fixes = listOf(
                FixView(MigrateFromThreeTexts.LOSS_FIX_NEW_BROKER_TITLE, MigrateFromThreeTexts.LOSS_FIX_NEW_BROKER_TEXT, recommended = true),
                FixView(MigrateFromThreeTexts.LOSS_FIX_POLICY_TITLE, MigrateFromThreeTexts.LOSS_FIX_POLICY_TEXT, recommended = false),
                FixView(MigrateFromThreeTexts.LOSS_FIX_UPGRADE_THROUGH_TITLE, MigrateFromThreeTexts.LOSS_FIX_UPGRADE_THROUGH_TEXT, recommended = false),
            ),
            copyText = names.joinToString("\n"),
        ) { names[it] }
    }
}
