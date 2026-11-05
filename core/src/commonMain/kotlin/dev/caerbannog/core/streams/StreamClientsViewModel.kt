package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.ActiveConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * COMPILE STUB (M2-06a step 2, test developer): no behaviour. The contract the tests pin is in the brief and in the test files.
 * Threading rule as [StreamListViewModel]: every public method is safe from any thread and hops onto [scope].
 */
@Suppress("UnusedPrivateProperty", "UNUSED_PARAMETER")
class StreamClientsViewModel(
    private val active: ActiveConnection,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val clock: RefreshClock,
    private val reconnect: suspend (String) -> Unit,
) : AutoCloseable, RefreshTarget {
    private val _state = MutableStateFlow(StreamClientsState())
    val state: StateFlow<StreamClientsState> = _state.asStateFlow()
    private val _updatedAt = MutableStateFlow<Long?>(null)
    override val updatedAtMillis: StateFlow<Long?> = _updatedAt.asStateFlow()

    /** The tab is shown for [stream]; the data of another stream is cleared. */
    fun show(stream: String) = Unit

    /** The tab is not shown: no broker call, data cleared. */
    fun hide() = Unit

    override fun refreshNow() = Unit

    fun retry() = Unit

    override fun close() = Unit
}
