package dev.caerbannog.desktopapp

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.caerbannog.ui.connections.ConnectionsScreen

/**
 * M1-05: renders the real Connections screen. M2-03 part 2: all wiring lives in [createAppComposition]
 * (created once via `remember`); closing the window closes the held connection first.
 */
fun main() = application {
    val composition = remember { createAppComposition() }
    Window(onCloseRequest = { handleCloseRequest(composition, ::exitApplication) }, title = "Caerbannog") {
        ConnectionsScreen(composition.viewModel)
    }
}
