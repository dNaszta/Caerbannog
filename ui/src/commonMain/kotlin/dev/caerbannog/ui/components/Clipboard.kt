package dev.caerbannog.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/**
 * The one place that copies text. `ui` has a single (jvm) target, so the desktop `StringSelection` lives here and nowhere
 * else; move it behind expect/actual when a second target is added.
 */
@Composable
fun rememberCopyToClipboard(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { text: String -> scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(text))) } }
    }
}
