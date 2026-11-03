package dev.caerbannog.desktopapp

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.caerbannog.ui.App

/**
 * M1-01 skeleton spike, Acceptance item 2: "desktopApp launches an empty window via the chosen
 * tool's run command."
 *
 * Renders the placeholder `App()` composable from `ui`'s commonMain. No view model, no broker code
 * here - `desktopApp` only holds `main()`, the window and (later) menus and packaging, per
 * docs/spec.md's Module layout table.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Caerbannog") {
        App()
    }
}
