package dev.caerbannog.desktopapp

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.caerbannog.ui.connections.ConnectionsScreen

/**
 * M1-05: renders the real Connections screen. All wiring lives in [createConnectionsViewModel]; the
 * view model is created once via `remember` so recomposition does not rebuild it.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Caerbannog") {
        val viewModel = remember { createConnectionsViewModel() }
        ConnectionsScreen(viewModel)
    }
}
