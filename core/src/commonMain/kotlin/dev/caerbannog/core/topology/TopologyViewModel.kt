package dev.caerbannog.core.topology

import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.DEFAULT_PAGE_SIZE
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * M2-08b COMPILE STUB by the test developer: the real public API (pinned by the tests in `core/src/jvmTest/.../topology/`), NO behaviour.
 * The developer builds the body. Contract in short (details in the test KDoc and in the brief M2-08, 08b step 2):
 *  - one view model, ONE selected virtual host (default: the connected profile's), data loaded only while [setShown] is true;
 *  - every public method hops onto [scope] (like `StreamListViewModel`); the refresh interval is read from `settings.streamListRefresh` at every wait;
 *  - [context] gives the profile (user, vhost, topology level) and the permission result for the banner.
 */
class TopologyViewModel(
    private val active: ActiveConnection,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val clock: RefreshClock,
    private val reconnect: suspend (String) -> Unit,
    private val context: StateFlow<TopologyContext?>,
    pageSize: Int = DEFAULT_PAGE_SIZE,
) : AutoCloseable, RefreshTarget {
    private val _state = MutableStateFlow(TopologyState())
    val state: StateFlow<TopologyState> = _state.asStateFlow()
    private val _updatedAt = MutableStateFlow<Long?>(null)
    override val updatedAtMillis: StateFlow<Long?> = _updatedAt.asStateFlow()

    /** The Topology screen is on screen. Not shown means no broker call at all (D3). */
    fun setShown(shown: Boolean) = Unit

    /** Loads now unless a load runs; the timer restarts. */
    override fun refreshNow() = Unit

    /** A failed link: connect the profile again. A failed first load: load again. */
    fun retry() = Unit

    fun setTab(tab: TopologyTab) = Unit

    fun setQueueFilter(filter: QueueTypeFilter) = Unit

    /** The search text of the CURRENT tab: a substring of the name, case-insensitive like the stream list; back to page 1. */
    fun setSearch(text: String) = Unit

    /** R4: drops the rows of the old vhost, cancels the running load, loads the chosen one now. Not persisted. */
    fun selectVhost(name: String) = Unit

    /** Shows the detail of a loaded queue; null closes it. A name that is not loaded is ignored. */
    fun selectQueue(name: String?) = Unit

    fun setShowDefaultExchange(show: Boolean) = Unit

    fun setPage(page: Int) = Unit

    fun nextPage() = Unit

    fun previousPage() = Unit

    fun setPageSize(size: Int) = Unit

    override fun close() = Unit
}
