package dev.caerbannog.core.access

import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * M10-02 contract (test developer, COMPILE-ONLY STUB: it does nothing). One long-lived instance per app, like `TopologyViewModel`, whose threading and
 * refresh rules it mirrors (every public method hops onto [scope]; the timer, the load token, the alive checks and the D2 mapping are the shared
 * `RefreshLoop`). Reads `StreamSource.access` of the held connection only while shown (D3); a 3.x connection is never asked ([AccessState.offered]).
 *
 * @param reconnect Retry on a lost link: connect the profile of that name again.
 * @param context the held profile, for the Not encrypted banner only (never for what loads).
 */
@Suppress("UnusedPrivateProperty")
class AccessViewModel(
    private val active: ActiveConnection,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val clock: RefreshClock,
    private val reconnect: suspend (String) -> Unit,
    private val context: StateFlow<AccessContext?>,
) : AutoCloseable, RefreshTarget {
    private val _state = MutableStateFlow(AccessState())
    val state: StateFlow<AccessState> = _state.asStateFlow()
    private val _updatedAt = MutableStateFlow<Long?>(null)
    override val updatedAtMillis: StateFlow<Long?> = _updatedAt.asStateFlow()

    /** The Access screen is shown or not; nothing is called while it is not. */
    fun setShown(shown: Boolean) = Unit

    override fun refreshNow() = Unit

    /** Failed link: connect again. Failed read: read again. */
    fun retry() = Unit

    /** The one-shot [AccessState.leaveNotice] was shown on Connections. */
    fun noticeShown() = Unit

    override fun close() = Unit
}
