package dev.caerbannog.desktopapp

import dev.caerbannog.core.streams.StreamListViewModel
import dev.caerbannog.core.streams.StreamSort
import dev.caerbannog.ui.streams.StreamListEvents

/**
 * COMPILE STUB (M2-05c step 2, test developer): every event does nothing. The rules are in `CompositionRootStreamEventsTest`:
 * the real mapping from the screen's events to the [StreamListViewModel] (the screen itself only reports; it knows no view model).
 */
class StreamListEventsAdapter(private val viewModel: StreamListViewModel) : StreamListEvents {
    override fun refreshNow() = Unit
    override fun retry() = Unit
    override fun setFilter(text: String) = Unit
    override fun setSort(sort: StreamSort) = Unit
    override fun nextPage() = Unit
    override fun previousPage() = Unit
    override fun createStream() = Unit
    override fun setShown(shown: Boolean) = Unit
}
