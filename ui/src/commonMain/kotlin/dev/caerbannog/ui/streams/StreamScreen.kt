package dev.caerbannog.ui.streams

import androidx.compose.runtime.Composable
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.streams.StreamClientsState
import dev.caerbannog.ui.appframe.AppDestination

/**
 * Events out of the stream screen (M2-06b). Every one is asynchronous in the view model; read the result from the state.
 * COMPILE STUB (test developer, step 2): the senior developer owns the real screen.
 */
interface StreamClientsEvents {
    /** The tab is on screen for [stream] (enters the composition). The view model calls the broker only while shown (D3). */
    fun show(stream: String)

    /** The tab left the composition. */
    fun hide()

    fun refreshNow()
    fun retry()

    /** The back arrow and the Back to streams button: close the stream screen, the list comes back. */
    fun back()
}

/** The stream screen with its tab list (compile stub: draws nothing). */
@Composable
fun StreamScreen(
    /** The stream that is open: the title, and the argument of [StreamClientsEvents.show] (the state has no stream before its first load). */
    stream: String,
    state: StreamClientsState,
    nowMillis: Long,
    events: StreamClientsEvents,
    control: ConnectionControlState,
    controlEvents: ConnectionControlEvents,
    onNavigate: (AppDestination) -> Unit = {},
) {
}
