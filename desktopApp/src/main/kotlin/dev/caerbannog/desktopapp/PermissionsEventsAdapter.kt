package dev.caerbannog.desktopapp

import dev.caerbannog.core.access.PermissionsViewModel
import dev.caerbannog.core.access.AccessViewModel
import dev.caerbannog.core.streams.RefreshTarget
import dev.caerbannog.ui.access.PermissionsEvents
import kotlinx.coroutines.flow.StateFlow

/**
 * Maps the Permissions tab's events onto the [PermissionsViewModel] (the screen itself knows no view model). The view model hops onto its own scope,
 * so every call is safe from the UI thread. No event changes anything on the broker.
 */
class PermissionsEventsAdapter(private val viewModel: PermissionsViewModel) : PermissionsEvents {
    override fun setShown(shown: Boolean) = viewModel.setShown(shown)
    override fun refreshNow() = viewModel.refreshNow()
    override fun retry() = viewModel.retry()
    override fun chooseUser(key: String) = viewModel.chooseUser(key)
    override fun setUserFilter(text: String) = viewModel.setUserFilter(text)
    override fun setVhostFilter(text: String) = viewModel.setVhostFilter(text)
}

/**
 * What the connection control refreshes and ages while the Access screen is shown: both tabs. Each view model reads only while its own tab is shown, so a
 * refresh reaches the tab on screen and the other one ignores it. The age is the newest successful read of either.
 */
internal class AccessRefreshTarget(
    private val who: AccessViewModel,
    private val permissions: PermissionsViewModel,
    override val updatedAtMillis: StateFlow<Long?>,
) : RefreshTarget {
    override fun refreshNow() {
        who.refreshNow()
        permissions.refreshNow()
    }
}
