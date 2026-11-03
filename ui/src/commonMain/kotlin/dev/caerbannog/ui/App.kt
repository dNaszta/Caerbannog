package dev.caerbannog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * M1-01 skeleton spike placeholder screen.
 *
 * Per the task brief's Design notes, this is intentionally the only production code this spike
 * needs: a single composable with no view model and no behaviour to hold state for. It exists only
 * to prove the desktop window and the Compose UI test API both work end to end; the real target
 * screen is `docs/ux/Main.dc.html`, built in the follow-up task `M1-02`.
 */
@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Caerbannog")
                Text("v0.1.0 (M1 skeleton)")
            }
        }
    }
}
