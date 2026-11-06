package dev.caerbannog.ui.topology

import dev.caerbannog.core.topology.BindingsViewMode
import dev.caerbannog.core.topology.QueueTypeFilter
import dev.caerbannog.core.topology.TopologyTab

/**
 * M2-08c: events out of the Topology screen. Every one is asynchronous in the view model; read the result
 * from the state. There is no method that changes topology or touches a queue's messages, on purpose (T4): a new one needs the architect.
 */
interface TopologyEvents {
    /** The screen is on show: true when it enters the composition, false when it leaves (D3: the view model polls only while shown). */
    fun setShown(shown: Boolean)

    fun refreshNow()
    fun retry()
    fun setTab(tab: TopologyTab)
    fun setQueueFilter(filter: QueueTypeFilter)

    /** The search text of the CURRENT tab. */
    fun setSearch(text: String)
    fun selectVhost(name: String)

    /** A queue name opens its detail, null closes it. */
    fun selectQueue(name: String?)
    fun setShowDefaultExchange(show: Boolean)
    fun setBindingsView(mode: BindingsViewMode)
    fun nextPage()
    fun previousPage()
}
