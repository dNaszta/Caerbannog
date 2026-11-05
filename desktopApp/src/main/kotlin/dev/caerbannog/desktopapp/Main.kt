package dev.caerbannog.desktopapp

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.caerbannog.ui.connections.ConnectionsScreen
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
    Window(onCloseRequest = { handleCloseRequest(composition, ::exitApplication) }, title = "Caerbannog") {
        ConnectionsScreen(composition.viewModel, control, composition.control)
    }
}

private const val TICK_MILLIS: Long = 1000
