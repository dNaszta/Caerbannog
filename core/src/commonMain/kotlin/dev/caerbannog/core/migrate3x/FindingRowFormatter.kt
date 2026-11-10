package dev.caerbannog.core.migrate3x

/**
 * Turns one finding into the plain-text row of the screen. Pure. Every string that comes from the file (name, message, proposal) goes
 * through [escapeForText], the function of the text export, so a control character shows as `\u0007` and markup stays text.
 * The row has no field that could hold a password hash.
 */
object FindingRowFormatter {
    fun format(finding: Finding): RowView = RowView(
        kind = finding.kind,
        name = finding.name?.let { escapeForText(it) },
        message = escapeForText(finding.message),
        why = escapeForText(finding.why),
        proposal = escapeForText(finding.proposal.describe()),
        verified = finding.verified,
        user = (finding.proposal as? Proposal.DeclareUser)?.takeIf { finding.kind == ItemKind.User }?.let(::userPart),
    )

    private fun userPart(proposal: Proposal.DeclareUser): UserRowView = UserRowView(
        tags = proposal.tags.map { escapeForText(it) },
        algorithm = proposal.hashingAlgorithm?.let { escapeForText(it) },
        hashText = if (proposal.hasPasswordHash) MigrateFromThreeTexts.HASH_HIDDEN else MigrateFromThreeTexts.NO_PASSWORD_HASH,
    )
}
