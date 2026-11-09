package dev.caerbannog.core.migrate3x

/**
 * The plain-text rendering of a report: the summary line, then one block per finding in report order. Names come from an untrusted
 * file, so no raw control character reaches the text (see [escapeForText]): a name cannot start a fake block.
 */
object CompatibilityReportText {
    fun render(report: CompatibilityReport): String {
        val s = report.summary
        val summary = "${s.compatibleItems} of ${s.topologyItems} items are compatible as they are, ${s.needDecision} need a decision"
        return (listOf(summary) + report.findings.map { "\n${block(it)}" }).joinToString("\n")
    }

    private fun block(finding: Finding): String {
        val body = mutableListOf(finding.message, finding.why, "Proposal: ${finding.proposal.describe()}")
        finding.alternatives.forEach { body += "Alternative: ${it.describe()}" }
        (listOf(finding.proposal) + finding.alternatives).mapNotNull { it.acknowledgement }.distinct().forEach { body += "Tick needed: ${it.text}" }
        if (!finding.verified) body += "unverified"
        return (listOf(header(finding)) + body.map { "  ${escapeForText(it)}" }).joinToString("\n")
    }

    /** `[SEVERITY] rule kind "name" (vhost "v")`; a global finding without a name shows its `about` in square brackets. */
    private fun header(finding: Finding): String {
        val parts = mutableListOf("[${finding.severity.name.uppercase()}]", finding.rule, finding.kind.name.lowercase())
        finding.name?.let { parts += quoted(it) }
        finding.vhost?.let { parts += "(vhost ${quoted(it)})" }
        if (finding.name == null && finding.about.isNotEmpty()) parts += "[${escapeForText(finding.about.joinToString(","))}]"
        return parts.joinToString(" ")
    }

    private fun quoted(value: String): String = "\"${escapeForText(value, escapeQuotes = true)}\""
}

/**
 * Makes a value safe to write into a line: a backslash becomes `\\`, a double quote `\"` (when [escapeQuotes]), and every control
 * character (below 0x20, 0x7F, 0x80 to 0x9F) and the line and paragraph separators become `\uXXXX` with four uppercase hex digits.
 */
internal fun escapeForText(value: String, escapeQuotes: Boolean = false): String = buildString {
    for (c in value) {
        when {
            c == '\\' -> append("\\\\")
            c == '"' && escapeQuotes -> append("\\\"")
            c.isUnsafeInText() -> append("\\u").append(c.code.toString(16).uppercase().padStart(4, '0'))
            else -> append(c)
        }
    }
}

private fun Char.isUnsafeInText(): Boolean = code < 0x20 || code == 0x7F || code in 0x80..0x9F || this == '\u2028' || this == '\u2029'
