package dev.caerbannog.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize

/**
 * A centred message (e.g. empty, error, connecting, not-connected state), with an optional icon and action under it.
 */
@Composable
internal fun CenteredMessage(
    text: String?,
    icon: AppIcon? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
    tag: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            icon?.let { ConnectionIcon(it, IconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
                )
            }
            action?.invoke()
        }
    }
}
