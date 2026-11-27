package dev.caerbannog.desktopapp

import dev.caerbannog.core.access.AccessViewModel
import dev.caerbannog.ui.access.AccessEvents

/**
 * Maps the Access screen's events onto the [AccessViewModel] (the screen itself knows no view model). The view model hops onto its own scope,
 * so every call is safe from the UI thread. No event changes anything on the broker.
 */
class AccessEventsAdapter(private val viewModel: AccessViewModel) : AccessEvents {
    override fun setShown(shown: Boolean) = viewModel.setShown(shown)
    override fun refreshNow() = viewModel.refreshNow()
    override fun retry() = viewModel.retry()
}
