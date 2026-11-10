package dev.caerbannog.core.migrate3x

/** Turns one finding into the plain-text row of the screen. Pure. M8-06a STUB: returns an empty row. */
object FindingRowFormatter {
    fun format(finding: Finding): RowView = RowView(finding.kind, null, "", "", "", false, null) // M8-06a STUB
}
