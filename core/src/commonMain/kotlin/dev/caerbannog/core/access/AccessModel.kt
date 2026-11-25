package dev.caerbannog.core.access

/**
 * M10-02 contract (test developer, COMPILE-ONLY STUB: the types are final, the behaviour behind them is not built).
 * Read-only gateway of the Access view (spec "Access view (M10)"). Two calls only; later slices add their reads. Every call is a GET on the
 * management API of the held connection with the profile's own TLS setting. Data leaves the gateway already through `SecretGuard`.
 */
interface AccessGateway {
    /** `GET /api/whoami`: `{name, tags, is_internal_user}`. */
    suspend fun whoAmI(): AccessResult<WhoAmI>

    /** `GET /api/vhosts`: the virtual host names the broker lists for the connection user. Names only. */
    suspend fun virtualHosts(): AccessResult<List<String>>
}

/** The connection user as the broker reports it. [isInternalUser] false marks a token user (measured by M10-00, S-A). */
data class WhoAmI(val name: String, val tags: List<String>, val isInternalUser: Boolean)

/**
 * Typed result of one Access call. [Unexpected] carries a reason only, never a body, never a secret; the reason is mapped (`SecretGuard.brokerErrorText`)
 * or a status text such as "HTTP 500".
 */
sealed interface AccessResult<out T> {
    data class Loaded<T>(val value: T) : AccessResult<T>

    /** 401 with the broker reason `Not management user`: the user has no management tag (the locked state). */
    data object NoManagementTag : AccessResult<Nothing>

    /** Any other 401 or 403: the broker refused the sign-in or the request. */
    data object NotAuthorised : AccessResult<Nothing>

    /** Timeout, refused connection, closed holder, TLS failure: D2 turns it into a lost connection. */
    data object Unreachable : AccessResult<Nothing>

    data class Unexpected(val reason: String) : AccessResult<Nothing>
}

/** The gateway of a connection that is not a held 4.x one (and the default of a source built without one): answers [AccessResult.Unexpected], sends nothing. */
object UnavailableAccessGateway : AccessGateway {
    override suspend fun whoAmI(): AccessResult<WhoAmI> = AccessResult.Unexpected(AccessTexts.NEEDS_4X)

    override suspend fun virtualHosts(): AccessResult<List<String>> = AccessResult.Unexpected(AccessTexts.NEEDS_4X)
}
