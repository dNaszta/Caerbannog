package dev.caerbannog.core.connections

/** What the app knows about the connection it holds (or tries to hold). One value, name and phase together. */
sealed interface LinkState {
    /** The profile this state is about; null only before anything was connected. */
    val name: String?

    /** Nothing is held. [note] explains why, when the app closed the connection on its own. */
    data class Disconnected(override val name: String? = null, val note: String? = null) : LinkState

    /** Permission check and opening of the clients are running. */
    data class Connecting(override val name: String) : LinkState

    /** The connection is held. */
    data class Connected(override val name: String) : LinkState

    /** Connect did not succeed; nothing is held. [reason] is plain text without secrets. */
    data class Failed(override val name: String, val reason: String) : LinkState
}

/** Plain-language reasons of [LinkState.Failed]; the texts are pinned by feature 13. */
object ConnectionFailureReason {
    const val BROKER_NOT_REACHABLE: String = "The broker could not be reached"
    const val CERTIFICATE_NOT_TRUSTED: String = "The certificate was not trusted"
    const val NO_PASSWORD_STORED: String = "No password is stored"
    const val CREDENTIALS_REFUSED: String = "Credentials were refused"

    /** M2-14c: shared with the Check text (`checkResultMessage`, TlsRefused). It only tells; it never switches TLS off. */
    const val NO_TLS_ANSWER: String =
        "The broker did not answer the TLS handshake. It may not use TLS; turn off 'Use TLS' in this connection's settings if that is intended."

    /** D2 (decided 2026-11-04): a data call found the broker unreachable while connected. */
    const val CONNECTION_LOST: String = "Connection lost"

    // For an unexpected gateway failure; the exception text is never shown (it could carry a secret).
    internal const val UNEXPECTED: String = "The connection could not be opened"
}

/** Texts shown next to a connection; shared so core and screens cannot drift. */
object ConnectionNotes {
    const val SETTINGS_CHANGED: String = "Connection settings changed. Connect again."
    const val HOST_NAME_NOT_VERIFIED: String = "Host name is not verified."

    /** M2-14: said wherever a connection with TLS off is shown (edit panel, chip, popup row). Same text everywhere. */
    const val NOT_ENCRYPTED: String = "Not encrypted"

    /** M8-02: the pill of a RabbitMQ 3.x connection (chip, card, popup row). */
    const val MIGRATION_SOURCE_PILL: String = "RabbitMQ 3.x: migration source only"

    const val MIGRATION_SOURCE_DETAIL: String =
        "This broker is read only for Caerbannog. Topology changes and test publishing are off; use Migrate from 3.x."

    fun migrationSourceHeadline(user: String, version: String): String = "$user is connected to RabbitMQ $version: migration source only."
}

/**
 * M2-14 / M5-05: the notes of the edit panel for the TLS switch position [tls] and hostname verification [verifyHostName].
 * [hostNameNotVerified] is [ConnectionNotes.HOST_NAME_NOT_VERIFIED] only with TLS on AND verifyHostName off;
 * [notEncrypted] is [ConnectionNotes.NOT_ENCRYPTED] only with TLS off, otherwise null. One pure rule, so the BDD
 * steps and the screen cannot drift.
 */
data class EditPanelNotes(val hostNameNotVerified: String?, val notEncrypted: String?)

fun editPanelNotes(tls: Boolean, verifyHostName: Boolean = false): EditPanelNotes = EditPanelNotes(
    hostNameNotVerified = if (tls && !verifyHostName) ConnectionNotes.HOST_NAME_NOT_VERIFIED else null,
    notEncrypted = if (tls) null else ConnectionNotes.NOT_ENCRYPTED,
)
