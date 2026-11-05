package dev.caerbannog.desktopapp

import dev.caerbannog.core.streams.StreamKindFilter
import dev.caerbannog.core.streams.StreamListViewModel
import dev.caerbannog.core.streams.StreamSort
import dev.caerbannog.ui.streams.StreamListEvents

/**
 * Maps the stream list screen's events onto the [StreamListViewModel] (the screen itself knows no view model). The view model
 * hops onto its own scope, so every call is safe from the UI thread.
 */
class StreamListEventsAdapter(private val viewModel: StreamListViewModel) : StreamListEvents {
    override fun refreshNow() = viewModel.refreshNow()
    override fun retry() = viewModel.retry()
    override fun setFilter(text: String) = viewModel.setFilter(text)
    override fun setSort(sort: StreamSort) = viewModel.setSort(sort)
    override fun toggleExpanded(name: String) = viewModel.toggleExpanded(name)
    override fun setKindFilter(kind: StreamKindFilter) = viewModel.setKindFilter(kind)
    override fun nextPage() = viewModel.nextPage()
    override fun previousPage() = viewModel.previousPage()

    /** The dialog is M3: pressing Create stream does nothing in M2-05. */
    override fun createStream() = Unit
    override fun setShown(shown: Boolean) = viewModel.setShown(shown)
}
