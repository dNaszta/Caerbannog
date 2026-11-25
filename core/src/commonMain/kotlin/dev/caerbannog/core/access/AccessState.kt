package dev.caerbannog.core.access

import dev.caerbannog.core.connections.ConnectionProfile

/**
 * M10-02 contract (test developer, COMPILE-ONLY STUB: the types are final, the view model behind them is not built).
 * What the Who am I tab shows, all as display text (what is shown is what is copied). Mockup: `docs/ux/Access.dc.html`, frames "Who am I: ...".
 * The wording is approved by the owner (2026-11-25, as drawn); the strings below are the drawn ones.
 */
object AccessTexts {
    const val READ_ONLY_NOTE: String = "This screen only reads. It changes nothing on the broker."
    const val NOT_CONNECTED: String = "Not connected. Choose a connection first."
    const val CONNECTING: String = "Connecting..."
    const val LOADING: String = "Reading the user and the virtual hosts"
    const val LOCKED_TITLE: String = "This user cannot read this information."
    const val LOCKED_DETAIL: String = "The broker refuses it without a management tag. Ask for the management tag."
    const val NO_VHOSTS: String = "The broker lists no virtual host for this user."
    const val VHOSTS_ERROR: String = "The virtual hosts could not be read."
    const val CONNECTION_VHOST_NOTE: String = "This connection's virtual host"
    const val TOKEN_USER_NOTE: String = "as the broker reports it"
    const val CONNECTION_LOST_DETAIL: String = "The data below is the last that was read."
    const val NOT_ENCRYPTED_DETAIL: String = "The password and all messages travel in clear text."
    const val USER_ERROR: String = "The user could not be read."
    const val SIGN_IN_REFUSED: String = "The broker refused the sign-in."
    const val RETRY: String = "Retry"

    /** The one-line notice shown on Connections when the held connection becomes 3.x while Access is open (AQ6). Appears nowhere else. */
    const val NEEDS_4X: String = "The Access view needs a 4.x broker."
}

sealed interface AccessScreen {
    data object NotConnected : AccessScreen

    data object Connecting : AccessScreen

    /** The first read is running: frames with loading rows, never a blank page. */
    data object Loading : AccessScreen

    /** The user and tags are known. The virtual host part has its own [AccessState.vhostsStatus]. */
    data object Loaded : AccessScreen

    /** No management tag: message only, no data, no virtual host list. */
    data object Locked : AccessScreen

    /** The user could not be read at all (refused sign-in, unexpected answer, lost connection on the first read). [reason] is a mapped reason, never a body. */
    data class Failed(val reason: String) : AccessScreen
}

enum class VhostsStatus { None, Loading, Listed, Empty, Unreadable }

/** One virtual host row; [note] is [AccessTexts.CONNECTION_VHOST_NOTE] for the connection's own virtual host, otherwise null. */
data class VhostRowView(val name: String, val note: String?)

/** What the banners need besides the link: the connected profile (its TLS switch). */
data class AccessContext(val profile: ConnectionProfile)

data class AccessState(
    val screen: AccessScreen = AccessScreen.NotConnected,
    /** The one message of the screen when it has no data: not connected, connecting, loading, the lock title, the failure reason. Null when data shows. */
    val message: String? = AccessTexts.NOT_CONNECTED,
    /** The lock explanation ([AccessTexts.LOCKED_DETAIL]); null otherwise. */
    val detail: String? = null,
    /** [AccessTexts.READ_ONLY_NOTE] on every screen that shows data or loading rows; null when not connected, connecting, locked or failed. */
    val readOnlyNote: String? = null,
    val user: String? = null,
    /** The tags in the broker's order. */
    val tags: List<String> = emptyList(),
    /** True when `whoami.is_internal_user` is false: the user line says [AccessTexts.TOKEN_USER_NOTE]. */
    val tokenUser: Boolean = false,
    val vhostsStatus: VhostsStatus = VhostsStatus.None,
    /** The connection's own virtual host first, then the others alphabetical. */
    val vhosts: List<VhostRowView> = emptyList(),
    /** [AccessTexts.NO_VHOSTS] when [vhostsStatus] is Empty, [AccessTexts.VHOSTS_ERROR] when Unreadable, else null. */
    val vhostsMessage: String? = null,
    /** The mapped reason of an unreadable virtual host list ("HTTP 500"), never a broker body. */
    val vhostsReason: String? = null,
    /** [AccessTexts.RETRY] after a failure, an unreadable list or a lost link; else null. */
    val action: String? = null,
    /** D2: the data is the last read. */
    val stale: Boolean = false,
    /** D2: `ConnectionFailureReason.CONNECTION_LOST` while the link is lost with data on screen; else null. */
    val connectionBanner: String? = null,
    /** [AccessTexts.SIGN_IN_REFUSED] while the broker refused a refresh and the last data stays, dimmed and stale (mid-session NotAuthorised); else null. */
    val refusalBanner: String? = null,
    /** `ConnectionNotes.NOT_ENCRYPTED` while the held profile has TLS off; else null. */
    val notEncrypted: String? = null,
    /** False while the held connection is a RabbitMQ 3.x one (AQ6): the drawer has no Access item. True when nothing is held. */
    val offered: Boolean = true,
    /** Set once when the held connection became 3.x while Access was shown: the app returns to Connections and shows this one line. */
    val leaveNotice: String? = null,
)
