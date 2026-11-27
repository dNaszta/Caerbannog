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
 * M10-03 contract (test developer, COMPILE-ONLY STUB: the signature is final, the behaviour is the senior developer's). State holder of the Permissions tab.
 * Same threading and refresh rules as [AccessViewModel] (every public method hops onto [scope]; the refresh is the shared `RefreshLoop`, the banners are the
 * same code extracted, not copied). Rules (brief docs/tasks/M10-03-permissions-tab.md, "Rulings"): the lock is decided by `whoami.tags` and sends no
 * permission request; an administrator reads `users()` and `permissionsOf(chosen)` and `virtualHosts()`; the choice is per tab visit; leaving the tab cancels.
 */
class PermissionsViewModel(
    private val active: ActiveConnection,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val clock: RefreshClock,
    private val reconnect: suspend (String) -> Unit,
    private val context: StateFlow<AccessContext?>,
) : AutoCloseable, RefreshTarget {
    private val _state = MutableStateFlow(PermissionsState())
    val state: StateFlow<PermissionsState> = _state.asStateFlow()
    private val _updatedAt = MutableStateFlow<Long?>(null)
    override val updatedAtMillis: StateFlow<Long?> = _updatedAt.asStateFlow()

    /** The Permissions tab is shown or not; nothing is called while it is not. */
    fun setShown(shown: Boolean) = Unit

    override fun refreshNow() = Unit

    /** Failed link: connect again. Failed read: read again, the user list included. */
    fun retry() = Unit

    /** Chooses the user of [UserOption.key]; a hidden option or an unknown key changes nothing. */
    fun chooseUser(key: String) = Unit

    fun setUserFilter(text: String) = Unit

    fun setVhostFilter(text: String) = Unit

    override fun close() = Unit
}
