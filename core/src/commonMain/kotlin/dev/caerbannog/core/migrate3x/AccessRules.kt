package dev.caerbannog.core.migrate3x

private const val GUEST = "guest"
private const val ADMINISTRATOR = "administrator"
private val allowedHashingAlgorithms = setOf("rabbit_password_hashing_sha256", "rabbit_password_hashing_sha512", "rabbit_password_hashing_md5")

private fun userItem(user: UserDefinition) = ItemRef(ItemKind.User, null, user.name)

private val UserDefinition.hasPasswordHash: Boolean get() = passwordHash != null

/** The hash exists but 4.x cannot take it as it is: the user is not declared (P12). */
private val UserDefinition.isBlocked: Boolean
    get() = hasPasswordHash && (hashingAlgorithm == null || hashingAlgorithm !in allowedHashingAlgorithms)

private fun UserDefinition.declareProposal(acknowledgement: Acknowledgement? = null) =
    Proposal.DeclareUser(name, tags, hashingAlgorithm, hasPasswordHash, acknowledgement)

internal object AccessDeclareVhostRule : Rule {
    override val info: RuleInfo = RuleCatalog["access-declare-vhost"]

    override fun check(context: AnalysisContext): List<Finding> =
        context.document.virtualHosts.map {
            finding(ItemRef(ItemKind.VirtualHost, null, it.name), Severity.Info, FindingClass.Access, ReportTexts.accessDeclareVhost, Proposal.DeclareVirtualHost(it.name))
        }
}

/** A user that is not guest and has no blocker is declared; the hash is carried over and never shown. */
internal object AccessDeclareUserRule : UserRule(RuleCatalog["access-declare-user"]) {
    override fun check(user: UserDefinition): Finding? =
        if (user.name == GUEST || user.isBlocked) {
            null
        } else {
            finding(userItem(user), Severity.Info, FindingClass.Access, ReportTexts.accessDeclareUser(user.hasPasswordHash), user.declareProposal())
        }
}

internal object AccessSetPermissionsRule : Rule {
    override val info: RuleInfo = RuleCatalog["access-set-permissions"]

    override fun check(context: AnalysisContext): List<Finding> =
        context.document.permissions.map {
            finding(ItemRef(ItemKind.Permission, it.vhost, it.user), Severity.Info, FindingClass.Access, ReportTexts.accessSetPermissions, Proposal.SetPermissions(it.vhost, it.user))
        }
}

internal object AccessSetTopicPermissionsRule : Rule {
    override val info: RuleInfo = RuleCatalog["access-set-topic-permissions"]

    override fun check(context: AnalysisContext): List<Finding> =
        context.document.topicPermissions.map {
            finding(
                ItemRef(ItemKind.TopicPermission, it.vhost, it.user), Severity.Info, FindingClass.Access, ReportTexts.accessSetTopicPermissions,
                Proposal.SetTopicPermissions(it.vhost, it.user, it.exchange), about = listOf(it.exchange),
            )
        }
}

/** `guest` has a publicly known password: skipped by default, accepting it needs the tick (P11). */
internal object UserGuestRule : UserRule(RuleCatalog["user-guest"]) {
    override fun check(user: UserDefinition): Finding? =
        if (user.name != GUEST) {
            null
        } else {
            finding(
                userItem(user), Severity.Warning, FindingClass.Access, ReportTexts.userGuest, user.declareProposal(Acknowledgement.PubliclyKnownPassword),
                defaultDecision = DefaultDecision.Skip,
            )
        }
}

internal object UserAdministratorTagRule : UserRule(RuleCatalog["user-administrator-tag"]) {
    override fun check(user: UserDefinition): Finding? =
        if (ADMINISTRATOR !in user.tags) null else finding(userItem(user), Severity.Warning, FindingClass.Access, ReportTexts.userAdministratorTag, Proposal.None)
}

internal object UserHashWithoutAlgorithmRule : UserRule(RuleCatalog["user-hash-without-algorithm"]) {
    override fun check(user: UserDefinition): Finding? =
        if (!user.hasPasswordHash || user.hashingAlgorithm != null) {
            null
        } else {
            finding(userItem(user), Severity.Blocker, FindingClass.Access, ReportTexts.userHashWithoutAlgorithm, ReportTexts.newPasswordManual)
        }
}

internal object UserHashAlgorithmUnsupportedRule : UserRule(RuleCatalog["user-hash-algorithm-unsupported"]) {
    override fun check(user: UserDefinition): Finding? {
        val algorithm = user.hashingAlgorithm
        if (!user.hasPasswordHash || algorithm == null || algorithm in allowedHashingAlgorithms) return null
        return finding(
            userItem(user), Severity.Blocker, FindingClass.Access, ReportTexts.userHashAlgorithmUnsupported(algorithm), ReportTexts.newPasswordManual,
            about = listOf(algorithm),
        )
    }
}

internal object UserNoPasswordHashRule : UserRule(RuleCatalog["user-no-password-hash"]) {
    override fun check(user: UserDefinition): Finding? =
        if (user.hasPasswordHash) null else finding(userItem(user), Severity.Info, FindingClass.Access, ReportTexts.userNoPasswordHash, Proposal.None)
}

/** A per-virtual-host export holds no access data; the virtual host must already exist on the 4.x broker (P17). */
internal object AccessNotInFileRule : Rule {
    override val info: RuleInfo = RuleCatalog["access-not-in-file"]

    override fun check(context: AnalysisContext): List<Finding> {
        val origin = context.document.origin as? DefinitionsOrigin.SingleVirtualHost ?: return emptyList()
        val vhost = origin.assignedName
        return listOf(
            finding(
                ItemRef(ItemKind.Global, null, null), Severity.Info, FindingClass.Access, ReportTexts.accessNotInFile(vhost), ReportTexts.accessNotInFileManual(vhost),
                about = listOf(vhost),
            ),
        )
    }
}

internal object AccessRules {
    val all: List<Rule> = listOf(
        AccessDeclareVhostRule, AccessDeclareUserRule, AccessSetPermissionsRule, AccessSetTopicPermissionsRule, UserGuestRule, UserAdministratorTagRule,
        UserHashWithoutAlgorithmRule, UserHashAlgorithmUnsupportedRule, UserNoPasswordHashRule, AccessNotInFileRule,
    )
}
