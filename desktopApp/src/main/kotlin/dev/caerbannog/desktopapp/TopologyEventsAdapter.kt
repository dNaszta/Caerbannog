package dev.caerbannog.desktopapp

import dev.caerbannog.core.topology.QueueTypeFilter
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.core.topology.TopologyViewModel
import dev.caerbannog.ui.topology.TopologyEvents

/**
 * M2-08c COMPILE STUB by the test developer (no behaviour): maps the Topology screen's events onto the [TopologyViewModel]
 * (the screen itself knows no view model). The developer maps every method, like `StreamListEventsAdapter`.
 */
class TopologyEventsAdapter(private val viewModel: TopologyViewModel) : TopologyEvents {
    override fun setShown(shown: Boolean) = Unit
    override fun refreshNow() = Unit
    override fun retry() = Unit
    override fun setTab(tab: TopologyTab) = Unit
    override fun setQueueFilter(filter: QueueTypeFilter) = Unit
    override fun setSearch(text: String) = Unit
    override fun selectVhost(name: String) = Unit
    override fun selectQueue(name: String?) = Unit
    override fun setShowDefaultExchange(show: Boolean) = Unit
    override fun nextPage() = Unit
    override fun previousPage() = Unit
}
