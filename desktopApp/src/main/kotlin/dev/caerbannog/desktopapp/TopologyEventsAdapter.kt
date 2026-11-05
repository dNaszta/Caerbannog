package dev.caerbannog.desktopapp

import dev.caerbannog.core.topology.QueueTypeFilter
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.core.topology.TopologyViewModel
import dev.caerbannog.ui.topology.TopologyEvents

/**
 * Maps the Topology screen's events onto the [TopologyViewModel] (the screen itself knows no view model). The view model hops onto its
 * own scope, so every call is safe from the UI thread. No event changes topology (T4).
 */
class TopologyEventsAdapter(private val viewModel: TopologyViewModel) : TopologyEvents {
    override fun setShown(shown: Boolean) = viewModel.setShown(shown)
    override fun refreshNow() = viewModel.refreshNow()
    override fun retry() = viewModel.retry()
    override fun setTab(tab: TopologyTab) = viewModel.setTab(tab)
    override fun setQueueFilter(filter: QueueTypeFilter) = viewModel.setQueueFilter(filter)
    override fun setSearch(text: String) = viewModel.setSearch(text)
    override fun selectVhost(name: String) = viewModel.selectVhost(name)
    override fun selectQueue(name: String?) = viewModel.selectQueue(name)
    override fun setShowDefaultExchange(show: Boolean) = viewModel.setShowDefaultExchange(show)
    override fun nextPage() = viewModel.nextPage()
    override fun previousPage() = viewModel.previousPage()
}
