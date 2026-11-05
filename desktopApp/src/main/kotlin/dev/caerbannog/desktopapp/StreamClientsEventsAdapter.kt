package dev.caerbannog.desktopapp

import dev.caerbannog.core.streams.StreamClientsViewModel
import dev.caerbannog.ui.streams.StreamClientsEvents

/**
 * Maps the stream screen's events onto [StreamClientsViewModel] and [AppNavigation] (the screen itself knows neither). The view
 * model hops onto its own scope, so every call is safe from the UI thread; back closes the stream screen, so the list comes back.
 */
class StreamClientsEventsAdapter(private val viewModel: StreamClientsViewModel, private val navigation: AppNavigation) : StreamClientsEvents {
    override fun show(stream: String) = viewModel.show(stream)
    override fun hide() = viewModel.hide()
    override fun refreshNow() = viewModel.refreshNow()
    override fun retry() = viewModel.retry()
    override fun back() = navigation.closeStream()
}
