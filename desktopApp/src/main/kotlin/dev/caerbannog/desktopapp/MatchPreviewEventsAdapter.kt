package dev.caerbannog.desktopapp

import dev.caerbannog.core.access.MatchAction
import dev.caerbannog.core.access.MatchPreviewViewModel
import dev.caerbannog.ui.access.MatchPreviewEvents

/**
 * Maps the match preview form's events onto the [MatchPreviewViewModel] (the screen knows no view model). The view model hops onto its own scope, so every
 * call is safe from the UI thread. Check asks the pure matcher only; no event reaches the broker.
 */
class MatchPreviewEventsAdapter(private val viewModel: MatchPreviewViewModel) : MatchPreviewEvents {
    override fun chooseAction(action: MatchAction) = viewModel.chooseAction(action)
    override fun chooseVhost(vhost: String) = viewModel.chooseVhost(vhost)
    override fun setName(text: String) = viewModel.setName(text)
    override fun setRoutingKey(text: String) = viewModel.setRoutingKey(text)
    override fun check() = viewModel.check()
}
