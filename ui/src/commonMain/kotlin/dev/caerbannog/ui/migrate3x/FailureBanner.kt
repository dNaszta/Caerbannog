package dev.caerbannog.ui.migrate3x

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.caerbannog.ui.connections.AppIcon

/**
 * M8-06e STUB (item 7): one banner for Rejected, CannotExport and LiveFailed, on `errorContainer`: icon, bold title, [content] below it.
 * The stub draws nothing; the developer builds it (brief M8-06e) and the three states call it.
 */
@Composable
internal fun FailureBanner(icon: AppIcon, title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit = {}) {
    // M8-06e STUB
}
