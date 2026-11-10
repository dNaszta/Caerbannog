package dev.caerbannog.core.connections

/** Colour family of a pill or banner. WARNING is reserved for declared results, which must never look verified. */
enum class PillTone { OK, WARNING, LOCKED, NEUTRAL }

data class PermissionPill(val text: String, val tone: PillTone)

data class PermissionBanner(val tone: PillTone, val headline: String, val detail: String?, val dimNote: String?)

/** What the screen knows about one profile's permission check; no entry in the map means not checked yet. */
sealed interface PermissionCheckStatus {
    data object Checking : PermissionCheckStatus
    data class Done(val result: PermissionCheckResult) : PermissionCheckStatus
}

private const val DECLARATION_NOT_USED = "Your declaration is not used, because the broker answered."
private const val DECLARED_NOT_CONFIRMED = "The broker could not confirm it and still enforces its own permissions."

/** Wording lives here (not in the UI) so Cucumber and Compose tests assert the same strings. */
fun permissionPill(status: PermissionCheckStatus?): PermissionPill = when (status) {
    null -> PermissionPill("Not checked", PillTone.NEUTRAL)
    PermissionCheckStatus.Checking -> PermissionPill("Checking...", PillTone.NEUTRAL)
    is PermissionCheckStatus.Done -> when (val result = status.result) {
        is PermissionCheckResult.Checked ->
            if (result.topologyUnlocked) PermissionPill("Can change topology", PillTone.OK)
            else PermissionPill("Topology locked", PillTone.LOCKED)
        is PermissionCheckResult.DeclaredByUser ->
            if (result.topologyUnlocked) PermissionPill("Can change topology (declared)", PillTone.WARNING)
            else PermissionPill("Topology locked (declared)", PillTone.WARNING)
        is PermissionCheckResult.Unreadable -> PermissionPill("Topology locked", PillTone.LOCKED)
        is PermissionCheckResult.MigrationSource -> PermissionPill("M8-02a STUB", PillTone.NEUTRAL) // M8-02a STUB
    }
}

fun permissionBanner(profile: ConnectionProfile, status: PermissionCheckStatus?): PermissionBanner = when (status) {
    null -> PermissionBanner(PillTone.NEUTRAL, "Permission check has not run yet.", null, null)
    PermissionCheckStatus.Checking -> PermissionBanner(PillTone.NEUTRAL, "Checking permissions...", null, null)
    is PermissionCheckStatus.Done -> doneBanner(profile, status.result)
}

private fun doneBanner(profile: ConnectionProfile, result: PermissionCheckResult): PermissionBanner = when (result) {
    is PermissionCheckResult.Checked -> {
        val dim = if (profile.declaredPermissions != null) DECLARATION_NOT_USED else null
        if (result.topologyUnlocked) {
            val publishing = if (result.publishingEnabled) "Test publishing is enabled."
            else "Test publishing is disabled: no write permission."
            PermissionBanner(
                PillTone.OK,
                "${profile.username} can change topology on virtual host ${profile.virtualHost}.",
                "The broker confirmed it, so topology screens and migrations are unlocked for this connection. $publishing",
                dim,
            )
        } else {
            PermissionBanner(
                PillTone.LOCKED,
                "${profile.username} cannot change topology on virtual host ${profile.virtualHost}.",
                "The broker reports no configure permission, so topology screens open read-only. " +
                    "Streams can still be browsed.",
                dim,
            )
        }
    }
    is PermissionCheckResult.DeclaredByUser -> PermissionBanner(
        PillTone.WARNING,
        "You declared that ${profile.username} ${claim(result)}.",
        DECLARED_NOT_CONFIRMED + "\n" + result.unreadable.reason,
        null,
    )
    is PermissionCheckResult.Unreadable -> PermissionBanner(PillTone.LOCKED, result.userMessage, null, null)
    is PermissionCheckResult.MigrationSource -> PermissionBanner(PillTone.NEUTRAL, "M8-02a STUB", null, null) // M8-02a STUB
}

private fun claim(result: PermissionCheckResult.DeclaredByUser): String = when {
    result.topologyUnlocked && result.publishingEnabled -> "can change topology and can publish test messages"
    result.topologyUnlocked -> "can change topology"
    result.publishingEnabled -> "can publish test messages"
    else -> "has no topology or publishing rights"
}

/** M8-02a STUB: what a connection offers. The real decision: a migration source offers only [ConnectionFeature.MigrateFromThreeX]. */
enum class ConnectionFeature { Streams, Topology, TopologyEdits, Publish, Search, Migrations, MigrateFromThreeX }

/** M8-02a STUB (offers everything; wrong for MigrationSource on purpose). */
fun availableFeatures(result: PermissionCheckResult?): Set<ConnectionFeature> = ConnectionFeature.entries.toSet()
