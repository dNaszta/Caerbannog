package dev.caerbannog.core.connections

/** The connection user's permission patterns on the profile's virtual host. An empty string means none. */
data class BrokerPermissions(val configure: String, val write: String, val read: String)

/** The user's own claim about their rights, used only when the broker's permissions cannot be read. */
data class DeclaredPermissions(val configure: Boolean, val write: Boolean)

/** Why the permission check could not produce a [PermissionCheckResult.Checked]. */
enum class UnreadableCause { NOT_AUTHORIZED, NOT_FOUND, TIMEOUT, UNREACHABLE, BAD_RESPONSE, NO_PASSWORD, UNEXPECTED }

/**
 * Outcome of the broker permission check. Reading streams never depends on it, so [canBrowseStreams]
 * is true in every case; topology and publishing default to off whenever the broker did not confirm them.
 */
sealed interface PermissionCheckResult {
    val topologyUnlocked: Boolean
    val publishingEnabled: Boolean
    val canBrowseStreams: Boolean get() = true

    /** Read from the broker. */
    data class Checked(
        override val topologyUnlocked: Boolean,
        override val publishingEnabled: Boolean,
    ) : PermissionCheckResult

    /** The broker could not be asked; these values are the user's declaration, kept with the reason. */
    data class DeclaredByUser(
        override val topologyUnlocked: Boolean,
        override val publishingEnabled: Boolean,
        val unreadable: Unreadable,
    ) : PermissionCheckResult

    /** M8-02a STUB: a RabbitMQ 3.x connection, a migration source only; must be locked (the stub is wrong on purpose). */
    data class MigrationSource(val brokerVersion: String) : PermissionCheckResult {
        override val topologyUnlocked: Boolean get() = true
        override val publishingEnabled: Boolean get() = true
    }

    /** Safest mode: topology locked, publishing disabled. [detail] holds only safe text, never secrets. */
    data class Unreadable(val cause: UnreadableCause, val detail: String? = null) : PermissionCheckResult {
        override val topologyUnlocked: Boolean get() = false
        override val publishingEnabled: Boolean get() = false

        /** Why the check failed, in plain language; shown on its own next to a declaration. */
        val reason: String
            get() = when (cause) {
                UnreadableCause.NOT_AUTHORIZED ->
                    "Permissions could not be read: the broker refused the request. Either this user is not an " +
                        "administrator (only administrators can read permissions) or the password is wrong."
                UnreadableCause.NOT_FOUND ->
                    "Permissions could not be read: the user has no permissions on this virtual host."
                UnreadableCause.TIMEOUT ->
                    "Permissions could not be read: the management API did not answer in time."
                UnreadableCause.UNREACHABLE ->
                    "Permissions could not be read: the management API could not be reached over TLS."
                UnreadableCause.BAD_RESPONSE ->
                    "Permissions could not be read: the management API answered in a form the app does not understand."
                UnreadableCause.NO_PASSWORD ->
                    "Permissions could not be read: no password is stored for this connection."
                UnreadableCause.UNEXPECTED ->
                    "Permissions could not be read: an unexpected error occurred while checking."
            }

        val userMessage: String
            get() = reason + " Topology stays locked and test publishing disabled; streams can still be browsed."
    }
}

/**
 * Pure decision: a non-empty configure pattern unlocks topology, a non-empty write pattern enables
 * publishing; read is ignored. Any non-empty pattern counts (even "^$"): the broker has the final say.
 */
fun decidePermissions(permissions: BrokerPermissions): PermissionCheckResult.Checked =
    PermissionCheckResult.Checked(
        topologyUnlocked = permissions.configure.isNotEmpty(),
        publishingEnabled = permissions.write.isNotEmpty(),
    )

/** Applies the user's declaration in place of an unreadable check, keeping the reason. */
fun decideDeclared(
    declared: DeclaredPermissions,
    unreadable: PermissionCheckResult.Unreadable,
): PermissionCheckResult.DeclaredByUser =
    PermissionCheckResult.DeclaredByUser(declared.configure, declared.write, unreadable)

/**
 * Reads the connection user's permissions from the broker. Returns [PermissionCheckResult.Checked] or
 * [PermissionCheckResult.Unreadable] only, never DeclaredByUser: that override belongs to [ConnectionSession].
 */
interface PermissionChecker {
    suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult
}
