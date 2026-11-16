package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel

/** What the dry-run rules say about a list of actions; [MigrationRunner] adds the file's version to make a [MigrationDryRun]. */
data class DryRunVerdict(
    val actions: List<ActionDryRun>,
    val canApply: Boolean,
    val blockedReason: String? = null,
)

/**
 * The dry-run rules as pure functions, so the topology planner can ask the same question as the Migrations screen without a runner, a
 * history or an executor. This is a move of the rules that were inside [MigrationRunner], not a rewrite: the two paths keep their
 * different orders of precedence on purpose.
 *  - A topology-only list: no configure permission, then Read-only, then the virtual host, then destructive.
 *  - A list with a broker-level action: Read-only first, then administrator and Full control for the broker-level action, then
 *    configure, the virtual host, destructive.
 */
object MigrationDryRunRules {
    private const val ADMINISTRATOR = "administrator"
    private const val READ_ONLY_REASON = "Topology control level is Read-only"
    private const val NO_CONFIGURE_REASON = "User has no configure permission on this virtual host"
    private const val DESTRUCTIVE_REASON = "Destructive actions require Full control level"
    private const val NEEDS_ADMINISTRATOR = "Creating virtual hosts, users and permissions needs the administrator tag"
    private const val NEEDS_FULL_CONTROL = "Creating virtual hosts, users and permissions needs the level Full control"

    /**
     * [vhost] is the connection's virtual host: an action for any other one is blocked (spec V1), whatever the control level.
     * [userTags] (M8-12a): a broker-level action needs "administrator" (and Full control).
     */
    fun evaluate(
        actions: List<MigrationAction>,
        hasConfigurePermission: Boolean,
        controlLevel: TopologyControlLevel,
        vhost: String,
        userTags: Set<String> = emptySet(),
    ): DryRunVerdict {
        // A list with an access action is checked action by action; a topology-only list keeps the rules below untouched.
        if (actions.any { it.isBrokerLevel }) return perAction(actions, hasConfigurePermission, controlLevel, vhost, userTags)
        if (!hasConfigurePermission) {
            return DryRunVerdict(
                actions = actions.map { ActionDryRun(it, allowed = false, reason = NO_CONFIGURE_REASON) },
                canApply = false,
                blockedReason = "The user has no configure permission on this connection",
            )
        }

        if (controlLevel == TopologyControlLevel.READ_ONLY) {
            return DryRunVerdict(
                actions = actions.map { ActionDryRun(it, allowed = false, reason = READ_ONLY_REASON) },
                canApply = false,
                blockedReason = READ_ONLY_REASON,
            )
        }

        val results = actions.map { action ->
            if (action.vhost != vhost) {
                ActionDryRun(action = action, allowed = false, reason = otherVhostReason(action.vhost, vhost))
            } else if (action.isDestructive && controlLevel != TopologyControlLevel.FULL_CONTROL) {
                ActionDryRun(action = action, allowed = false, reason = DESTRUCTIVE_REASON)
            } else {
                ActionDryRun(action = action, allowed = true)
            }
        }

        val allAllowed = results.all { it.allowed }
        return DryRunVerdict(
            actions = results,
            canApply = allAllowed,
            blockedReason = when {
                allAllowed -> null
                actions.any { it.vhost != vhost } -> "Some actions belong to another virtual host than this connection's"
                else -> "Some actions are blocked by control level"
            },
        )
    }

    private fun perAction(
        actions: List<MigrationAction>,
        hasConfigurePermission: Boolean,
        controlLevel: TopologyControlLevel,
        vhost: String,
        userTags: Set<String>,
    ): DryRunVerdict {
        val readOnly = controlLevel == TopologyControlLevel.READ_ONLY
        val results = actions.map { action ->
            val reason = when {
                readOnly -> READ_ONLY_REASON
                action.isBrokerLevel -> when {
                    ADMINISTRATOR !in userTags -> NEEDS_ADMINISTRATOR
                    controlLevel != TopologyControlLevel.FULL_CONTROL -> NEEDS_FULL_CONTROL
                    else -> null
                }
                !hasConfigurePermission -> NO_CONFIGURE_REASON
                action.vhost != vhost -> otherVhostReason(action.vhost, vhost)
                action.isDestructive && controlLevel != TopologyControlLevel.FULL_CONTROL -> DESTRUCTIVE_REASON
                else -> null
            }
            ActionDryRun(action, allowed = reason == null, reason = reason)
        }
        val first = results.firstOrNull { !it.allowed }
        val blockedReason = when {
            first == null -> null
            readOnly || first.action.isBrokerLevel -> first.reason
            !hasConfigurePermission -> "The user has no configure permission on this connection"
            first.action.vhost != vhost -> "Some actions belong to another virtual host than this connection's"
            else -> "Some actions are blocked by control level"
        }
        return DryRunVerdict(results, canApply = first == null, blockedReason = blockedReason)
    }

    /** Names a virtual host only through the echo rule; the wording keeps saying "virtual host" either way. */
    private fun otherVhostReason(actionVhost: String, connectionVhost: String): String =
        if (echoableName(actionVhost) && echoableName(connectionVhost)) {
            "The action belongs to virtual host $actionVhost; this connection is for $connectionVhost"
        } else {
            "The action belongs to another virtual host than this connection's"
        }
}
