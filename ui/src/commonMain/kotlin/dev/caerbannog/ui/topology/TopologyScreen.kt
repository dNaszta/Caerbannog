package dev.caerbannog.ui.topology

import androidx.compose.runtime.Composable
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.topology.QueueTypeFilter
import dev.caerbannog.core.topology.TopologyState
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.ui.appframe.AppDestination

/**
 * M2-08c step 2 CONTRACT (test developer): events out of the Topology screen. Every one is asynchronous in the view model; read the result
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
    fun nextPage()
    fun previousPage()
}

/**
 * M2-08c COMPILE STUB by the test developer: the real signature, NO behaviour (draws nothing, reports nothing). The developer builds it per
 * `docs/ux/Topology.dc.html`; the tag contract is in the KDoc of `TopologyScreenUiTest` (and its siblings in `ui/src/jvmTest/.../topology/`).
 * It draws the strings of [state] and decides none, like `StreamsScreen`. [nowMillis] is the clock for "Updated N s ago".
 */
@Composable
fun TopologyScreen(
    state: TopologyState,
    nowMillis: Long,
    events: TopologyEvents,
    control: ConnectionControlState,
    controlEvents: ConnectionControlEvents,
    onNavigate: (AppDestination) -> Unit = {},
) = Unit
