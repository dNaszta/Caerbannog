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

    /**
     * M10-03: `GET /api/users`, names only (the parser keeps `name` and drops every other field; a user whose name looks like a secret reads [SecretGuard.HIDDEN]).
     * Administrator only: the broker answers 401 "Not administrator user" otherwise (-> [AccessResult.NotAuthorised]); the view model does not ask without the tag.
     */
    suspend fun users(): AccessResult<UserNames>

    /**
     * M10-03: `GET /api/users/{user}/permissions` AND `GET /api/users/{user}/topic-permissions`, both GET, the user as an encoded path segment. [AccessResult.Loaded]
     * only when both parse (no half table); a 404 for the user (a token user, or an unknown name) is [AccessResult.NotFound]. Rows keep the broker's order.
     */
    suspend fun permissionsOf(user: String): AccessResult<UserPermissions>
}

/** M10-03: the user names of `GET /api/users` in the broker's order. A name that was hidden by the guard reads [SecretGuard.HIDDEN]; the real name is gone. Nothing else of a user is kept. */
data class UserNames(val names: List<String>)

/** M10-03: how a pattern reads. The empty string is "no permission"; every other text, `^$` included, is a pattern (spec "Broker-read permission"). */
sealed interface PatternReading {
    data object NoPermission : PatternReading

    data class HasPattern(val text: String) : PatternReading
}

/**
 * M10-03: one pattern as the broker returned it. [text] is the raw text (the empty string stays empty), [reading] its meaning, [hidden] true when the guard replaced
 * the text by [SecretGuard.HIDDEN] (M10-04 must treat it as "pattern could not be read", never as a regular expression). Pattern text is never evaluated in M10-03.
 */
data class PatternCell(val text: String, val reading: PatternReading, val hidden: Boolean = false)

/** M10-03: `GET /api/users/{u}/permissions`, one row per virtual host with a permission. */
data class PermissionRow(val vhost: String, val configure: PatternCell, val write: PatternCell, val read: PatternCell)

/** M10-03: `GET /api/users/{u}/topic-permissions`, one row per virtual host and exchange. */
data class TopicPermissionRow(val vhost: String, val exchange: String, val write: PatternCell, val read: PatternCell)

/** M10-03: what the broker returned for one user, as a plain value (M10-04 takes it as input without touching the gateway). */
data class UserPermissions(val user: String, val rows: List<PermissionRow>, val topicRows: List<TopicPermissionRow>)

/** The connection user as the broker reports it. [isInternalUser] false marks a token user (measured by M10-00, S-A). */
data class WhoAmI(val name: String, val tags: List<String>, val isInternalUser: Boolean)

/**
 * Typed result of one Access call (seven types). [Unexpected] carries a reason only, never a body, never a secret; the reason is mapped (`SecretGuard.brokerErrorText`)
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

    /** M10-03: 404 for the asked user. It is how a token user answers (M10-00, S-B); the Permissions tab shows the token text, never an error. */
    data object NotFound : AccessResult<Nothing>

    /** A known RabbitMQ 3.x broker (AQ6): nothing was sent. The view model leaves Access with the one-line notice; it never shows this as a failure. */
    data object NotOffered : AccessResult<Nothing>
}

/** The gateway of a connection that is not a held 4.x one (and the default of a source built without one): answers [AccessResult.NotOffered], sends nothing. */
object UnavailableAccessGateway : AccessGateway {
    override suspend fun whoAmI(): AccessResult<WhoAmI> = AccessResult.NotOffered

    override suspend fun virtualHosts(): AccessResult<List<String>> = AccessResult.NotOffered

    override suspend fun users(): AccessResult<UserNames> = AccessResult.NotOffered

    override suspend fun permissionsOf(user: String): AccessResult<UserPermissions> = AccessResult.NotOffered
}
