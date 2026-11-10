package dev.caerbannog.core.migrate3x

/** Groups, chips, loss block and lazy rows of a held analysis. Pure. M8-06a STUB: returns an empty view. */
object ReportViewBuilder {
    /**
     * [selected]: severities shown (default all); [openGroups]: keys of open groups; [rowFormatter] builds one row, called only by
     * [RowSource.at] of an open group (a test counts the calls).
     */
    fun build(
        held: HeldAnalysis,
        selected: Set<Severity> = Severity.entries.toSet(),
        openGroups: Set<String> = emptySet(),
        rowFormatter: (Finding) -> RowView = FindingRowFormatter::format,
    ): ReportView = ReportView( // M8-06a STUB
        held.fileLabel, null, null, held.report.summary, "", DocumentCounts(0, 0, 0, 0), null, null, emptyList(), emptyList(),
    ) { RowSource.EMPTY }
}
