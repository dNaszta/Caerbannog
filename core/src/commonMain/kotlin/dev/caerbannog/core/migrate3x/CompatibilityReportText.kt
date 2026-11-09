package dev.caerbannog.core.migrate3x

/** The plain-text rendering of a report: the summary line, then one block per finding in report order. */
object CompatibilityReportText {
    fun render(report: CompatibilityReport): String {
        val s = report.summary
        val summary = "${s.compatibleItems} of ${s.topologyItems} items are compatible as they are, ${s.needDecision} need a decision"
        return (listOf(summary) + report.findings.map { "\n${block(it)}" }).joinToString("\n")
    }

    private fun block(finding: Finding): String {
        val lines = mutableListOf(header(finding), finding.message, finding.why)
        lines += "Proposal: ${finding.proposal.describe()}"
        finding.alternatives.forEach { lines += "Alternative: ${it.describe()}" }
        (listOf(finding.proposal) + finding.alternatives).mapNotNull { it.acknowledgement }.distinct().forEach { lines += "Tick needed: ${it.text}" }
        if (!finding.verified) lines += "unverified"
        return lines.mapIndexed { index, line -> if (index == 0) line else "  $line" }.joinToString("\n")
    }

    private fun header(finding: Finding): String {
        val item = listOfNotNull(finding.vhost, finding.name).joinToString("/")
        return listOf("[${finding.severity.name.uppercase()}]", finding.rule, finding.kind.name.lowercase(), item).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
