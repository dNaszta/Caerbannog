package dev.caerbannog.ui.streams

import androidx.compose.runtime.Composable
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.CreateStreamGate
import dev.caerbannog.core.streams.StreamListState
import dev.caerbannog.core.streams.StreamSort
import dev.caerbannog.ui.appframe.AppDestination

/** Events out of the stream list screen. Every one is asynchronous in the view model; read the result from the state. */
interface StreamListEvents {
    fun refreshNow()
    fun retry()
    fun setFilter(text: String)
    fun setSort(sort: StreamSort)
    fun nextPage()
    fun previousPage()

    /** The Create stream button (enabled by [CreateStreamGate]). The dialog is M3: the app wires this to nothing in M2-05. */
    fun createStream()

    /**
     * The list is on screen (true when the screen enters the composition, false when it leaves). The view model calls the broker only
     * while it is shown (D3), so a screen that forgets this polls forever or never loads.
     */
    fun setShown(shown: Boolean)
}

/**
 * COMPILE STUB (M2-05c step 2, test developer): draws nothing. The contract is in `StreamsScreenUiTest`, `StreamsCopyUiTest` and
 * `AppFrameNavigationUiTest`; the developer replaces this body (inside the [dev.caerbannog.ui.appframe.AppFrame], title "Streams").
 * [nowMillis] is the clock for "Updated N s ago" (the caller ticks it once a second).
 */
@Composable
fun StreamsScreen(
    state: StreamListState,
    nowMillis: Long,
    createStream: CreateStreamGate,
    events: StreamListEvents,
    control: ConnectionControlState,
    controlEvents: ConnectionControlEvents,
    onNavigate: (AppDestination) -> Unit = {},
) {
}
