package dev.caerbannog.desktopapp

import dev.caerbannog.core.streams.StreamClientsViewModel
import dev.caerbannog.ui.streams.StreamClientsEvents

/**
 * Maps the stream screen's events onto [StreamClientsViewModel] and [AppNavigation] (M2-06b).
 * COMPILE STUB (test developer, step 2): every method does nothing until the senior developer builds it.
 */
class StreamClientsEventsAdapter(private val viewModel: StreamClientsViewModel, private val navigation: AppNavigation) : StreamClientsEvents {
    override fun show(stream: String) = Unit
    override fun hide() = Unit
    override fun refreshNow() = Unit
    override fun retry() = Unit
    override fun back() = Unit
}
