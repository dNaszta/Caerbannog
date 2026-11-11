package dev.caerbannog.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.caerbannog.core.help.HowTo

/** M8-14 STUB: draws nothing. The real one is a "How to" text button (tag `howto-<id>`) that opens [HowToDialog]. */
@Composable
fun HowToLink(howTo: HowTo, modifier: Modifier = Modifier) {
}

/** M8-14 STUB: draws nothing. Real tags: `howto-dialog-<id>`, `howto-step-<n>` (1-based), `howto-close`. */
@Composable
fun HowToDialog(howTo: HowTo, onClose: () -> Unit) {
}
