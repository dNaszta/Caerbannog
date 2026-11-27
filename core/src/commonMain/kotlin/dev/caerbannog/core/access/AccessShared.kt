package dev.caerbannog.core.access

import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.ConnectionNotes
import dev.caerbannog.core.connections.StreamSource
import dev.caerbannog.core.streams.RefreshLoop

/**
 * The banner and stale rules of the Access tabs, kept once so the Who am I tab and the Permissions tab cannot drift apart (D2, M10-02, M10-02b).
 * Not thread-safe: the owning view model touches it on its scope's thread only. "Data" is whatever the tab can still draw from an earlier read.
 * Precedence of the banners: refusal, then lost connection, then read error; an answered read cannot coexist with the other two.
 */
internal class AccessBanners {
    var stale: Boolean = false
        private set
    var connection: String? = null
        private set
    var refusal: String? = null
        private set
    var readError: String? = null
        private set
    var lastSuccessMillis: Long? = null
        private set

    val any: Boolean get() = refusal != null || connection != null || readError != null

    /** A whole read worked: nothing is stale and no banner stays. */
    fun succeeded(nowMillis: Long) {
        lastSuccessMillis = nowMillis
        stale = false
        refusal = null
        connection = null
        readError = null
    }

    /** NotAuthorised. True when data stays, dimmed, with the refusal banner; false when there is nothing to show (the caller shows a failure). */
    fun refused(hasData: Boolean): Boolean {
        if (hasData) {
            stale = true
            refusal = AccessTexts.SIGN_IN_REFUSED
            readError = null
        }
        return hasData
    }

    /** An answer that was not understood or an error status: with data it stays stale with [banner]; true when data stays. */
    fun unreadable(hasData: Boolean, banner: String): Boolean {
        if (hasData) {
            stale = true
            if (refusal == null && connection == null) readError = banner
        }
        return hasData
    }

    /** D2: the link was lost while data is on screen. */
    fun connectionLost(reason: String, hasData: Boolean) {
        if (hasData) {
            stale = true
            connection = reason
            readError = null
        }
    }

    fun clear() {
        stale = false
        connection = null
        refusal = null
        readError = null
        lastSuccessMillis = null
    }
}

/**
 * One call of an Access read. Null: drop the result (superseded or closed), the connection was reported lost (D2), or the gateway answered
 * [AccessResult.NotOffered] ([onNotOffered] runs; the app leaves Access, this is not a failure).
 */
internal suspend fun <T> askAccess(
    loop: RefreshLoop,
    active: ActiveConnection,
    src: StreamSource,
    onNotOffered: () -> Unit,
    call: suspend () -> AccessResult<T>,
): AccessResult<T>? {
    val result = call()
    if (!loop.alive(src)) return null
    return when (result) {
        AccessResult.Unreachable -> {
            active.connectionLost(src)
            null
        }
        AccessResult.NotOffered -> {
            onNotOffered()
            null
        }
        else -> result
    }
}

/** The Not encrypted note of a held profile that has TLS off; null otherwise. */
internal fun notEncryptedNote(context: AccessContext?): String? = if (context?.profile?.tls == false) ConnectionNotes.NOT_ENCRYPTED else null
