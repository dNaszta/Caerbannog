package dev.caerbannog.core.access

/**
 * M10-03 contract (test developer, COMPILE-ONLY STUB: the types are final, the view model behind them is not built).
 * What the Permissions tab shows, all as display text (what is shown is what is copied). Mockup: `docs/ux/Access.dc.html`, frames `perm-*`.
 * The wording is approved by the owner (2026-11-25, as drawn); the strings below are the drawn ones. The state lives apart from [AccessState].
 */
object PermissionsTexts {
    const val TAB: String = "Permissions"
    const val READ_FROM_BROKER: String = "Read from the broker"
    const val NO_PERMISSION: String = "no permission"
    const val LOADING: String = "Reading permissions"
    const val LOCKED_TITLE: String = "Only an administrator can read this."
    const val LOCKED_DETAIL_NO_DECLARATION: String = "The broker gives the permission patterns only to an administrator. This connection has no declared permissions."
    const val TOKEN_TITLE: String = "No pattern table for a token user."
    const val ERROR: String = "The permissions could not be read."
    const val DECLARED_HEADING: String = "What this profile declares"
    const val DECLARED_LABEL: String = "Declared by you, not confirmed by the broker"
    const val DECLARED_YES: String = "yes"
    const val DECLARED_NO: String = "no"
    const val DECLARED_NOT_DECLARED: String = "not declared"
    const val DECLARED_NOTE: String = "You entered this in the connection settings. The broker enforces its own permissions."

    // M10-03b item 3 (test developer, COMPILE-ONLY STUB): the words of PermissionsTab.kt, final values copied from it; the developer moves the UI to these and drops its private consts.
    // M10-03b item 1: the message of the Failed frame when the offered list has no selectable name (owner 2026-11-27).
    const val NO_USER: String = "The broker lists no user to choose."
    const val USER_LABEL: String = "User"
    const val USER_FILTER_HINT: String = "Filter by user"
    const val VHOST_FILTER_HINT: String = "Filter by virtual host"
    const val VHOST_COLUMN: String = "Virtual host"
    const val CONFIGURE_COLUMN: String = "Configure"
    const val WRITE_COLUMN: String = "Write"
    const val READ_COLUMN: String = "Read"
    const val EXCHANGE_COLUMN: String = "Exchange"
    const val TOPIC_HEADING: String = "Topic permissions"
    const val PATTERN_NOTE: String =
        "A pattern is a regular expression the broker applies to the name. An empty pattern means no permission and is shown as such. Cells are copyable as displayed."

    /** "The broker gives the permission patterns only to an administrator, and svc-explorer has no administrator tag. Nothing is guessed from other calls." */
    fun lockedDetail(user: String): String =
        "The broker gives the permission patterns only to an administrator, and $user has no administrator tag. Nothing is guessed from other calls."

    /** "The permissions of jdoe come from the token and are not stored on the broker, so the broker has none to list. See the Sign-in tab." */
    fun tokenDetail(user: String): String =
        "The permissions of $user come from the token and are not stored on the broker, so the broker has none to list. See the Sign-in tab."

    /** "svc-orders has no permissions on any virtual host." */
    fun empty(user: String): String = "$user has no permissions on any virtual host."

    /** "Permissions of svc-orders" */
    fun heading(user: String): String = "Permissions of $user"

    /** "3 of 41 users" */
    fun userCount(shown: Int, total: Int): String = "$shown of $total users"

    /** What a cell shows: the hidden marker, "no permission" for the empty pattern, else the pattern text exactly as the broker returned it. */
    fun shown(cell: PatternCell): String = when {
        cell.hidden -> SecretGuard.HIDDEN
        cell.reading is PatternReading.NoPermission -> NO_PERMISSION
        else -> cell.text
    }
}

sealed interface PermissionsScreen {
    data object NotConnected : PermissionsScreen

    data object Connecting : PermissionsScreen

    /** The first read is running: loading rows, never a blank page. */
    data object Loading : PermissionsScreen

    /** At least one permission or topic permission row of the chosen user. */
    data object Loaded : PermissionsScreen

    /** The chosen user has no permission row and no topic row at all: [PermissionsTexts.empty], not an error. */
    data object Empty : PermissionsScreen

    /** Not an administrator (decided by the tags, no permission request is sent): [PermissionsTexts.LOCKED_TITLE]; the declaration of the profile may be shown. */
    data object Locked : PermissionsScreen

    /** The chosen user answered 404 (a token user): [PermissionsTexts.TOKEN_TITLE]; never an error. */
    data object TokenUser : PermissionsScreen

    /** The user list or the permissions could not be read at all (unexpected answer, refused sign-in, lost link on the first read). [reason] is a mapped reason, never a body. */
    data class Failed(val reason: String) : PermissionsScreen
}

/**
 * One entry of the user chooser. [key] is [name] for a real name and `hidden:<position>` for a name the guard hid (position = 0-based index in the list as
 * offered, same lesson as M10-02b); a hidden option is never chosen, the real name was dropped by the gateway.
 */
data class UserOption(val name: String, val key: String, val hidden: Boolean)

/** The profile's declaration, labelled as declared (spec "Declared permissions"). The model has no read flag: [read] is always [PermissionsTexts.DECLARED_NOT_DECLARED]. */
data class DeclarationView(val label: String, val configure: String, val write: String, val read: String, val note: String)

data class PermissionsState(
    val screen: PermissionsScreen = PermissionsScreen.NotConnected,
    /** The one message of the screen when it has no table: not connected, connecting, loading, lock title, token title, empty text, failure text. */
    val message: String? = AccessTexts.NOT_CONNECTED,
    /** Lock explanation, token explanation or the broker text of a failure ([AccessTexts.brokerText]); null otherwise. */
    val detail: String? = null,
    /** [PermissionsTexts.heading] of the chosen user while rows are shown; else null. */
    val heading: String? = null,
    /** [PermissionsTexts.READ_FROM_BROKER] while the table is what the broker returned (also stale); never together with [declaration]; else null. */
    val readLabel: String? = null,
    /** True for an administrator (internal or token): the chooser is drawn. False when locked or without data. */
    val canChooseUser: Boolean = false,
    /** The options that match [userFilter], in alphabetical order (case-insensitive); a token administrator's own name is among them. */
    val users: List<UserOption> = emptyList(),
    val userFilter: String = "",
    /** "N of M users" over the filtered and the whole list; null when the chooser is not drawn. */
    val userCountText: String? = null,
    /** The chosen user (a real name, never a hidden one); null when none. */
    val chosenUser: String? = null,
    val vhostFilter: String = "",
    /** One row per virtual host (the listed ones plus any row of another host), alphabetical, filtered by [vhostFilter]; a host without a broker row has "no permission" in all three cells. */
    val rows: List<PermissionRow> = emptyList(),
    /** The topic permission rows (broker order within a host), hosts alphabetical, filtered by [vhostFilter]. */
    val topicRows: List<TopicPermissionRow> = emptyList(),
    /** The profile's declaration, only in the locked frame; null when the profile declares nothing or the screen is not locked. */
    val declaration: DeclarationView? = null,
    /** [AccessTexts.RETRY] after a failure, a stale table or a lost link; else null. */
    val action: String? = null,
    val stale: Boolean = false,
    val connectionBanner: String? = null,
    val refusalBanner: String? = null,
    /** [PermissionsTexts.ERROR] and [AccessTexts.brokerText] of the reason while a table is shown and the last read answered `Unexpected`; else null. */
    val readErrorBanner: String? = null,
    val notEncrypted: String? = null,
)
