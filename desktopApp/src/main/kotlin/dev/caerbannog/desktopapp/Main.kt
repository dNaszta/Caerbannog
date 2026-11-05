package dev.caerbannog.desktopapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.createStreamGate
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.connections.ConnectionsScreen
import dev.caerbannog.ui.streams.StreamsScreen
import kotlinx.coroutines.delay

/**
 * M1-05: renders the real Connections screen. M2-03 part 2: all wiring lives in [createAppComposition]
 * (created once via `remember`); closing the window closes the held connection first.
 * M2-05b: the screen sits in the app frame, whose app bar carries the connection chip; the ticker below ages "Updated N s ago".
 * The other screens come with M2-05c and later, each in the same frame.
 */
fun main() = application {
    val composition = remember { createAppComposition() }
    val control by composition.control.state.collectAsState()
    // One tick per second while the window is shown; it recomputes the chip state from the clock and never calls the broker.
    LaunchedEffect(composition) {
        while (true) {
            delay(TICK_MILLIS)
            composition.control.tick()
        }
    }
    // 1280 x 800 dp is the size the mockups are drawn for; a smaller default cuts the app bar title next to the drawer.
    val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))
    Window(onCloseRequest = { handleCloseRequest(composition, ::exitApplication) }, title = "Caerbannog", state = windowState) {
        val screen by composition.navigation.screen.collectAsState()
        when (screen) {
            AppDestination.Streams -> StreamsWindowContent(composition, control)
            // Topology and Migrations have no screen yet: AppNavigation never selects them.
            else -> ConnectionsScreen(composition.viewModel, control, composition.control, onNavigate = composition.navigation::open)
        }
    }
}

/** The Streams screen with its own one-second clock; the clock runs only while this is in the composition (screen shown). */
@Composable
private fun StreamsWindowContent(composition: AppComposition, control: ConnectionControlState) {
    val list by composition.streamList.state.collectAsState()
    val link by composition.active.state.collectAsState()
    val connections by composition.viewModel.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(TICK_MILLIS)
        }
    }
    StreamsScreen(
        state = list,
        nowMillis = now,
        createStream = createStreamGate(connections.currentConnection, link),
        events = composition.streamEvents,
        control = control,
        controlEvents = composition.control,
        onNavigate = composition.navigation::open,
    )
}

private const val TICK_MILLIS: Long = 1000
