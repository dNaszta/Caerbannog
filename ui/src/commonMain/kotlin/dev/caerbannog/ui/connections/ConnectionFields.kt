package dev.caerbannog.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The mockup's `auto-fit minmax(220px,1fr)` grid, gap 24 by 16: two columns when two 220dp fields fit, else one. */
@Composable
internal fun ConnectionFieldGrid(cells: List<@Composable () -> Unit>) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= 220.dp * 2 + 16.dp
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (twoColumns) {
                cells.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        pair.forEach { cell -> Box(modifier = Modifier.weight(1f)) { cell() } }
                        if (pair.size == 1) Box(modifier = Modifier.weight(1f)) {}
                    }
                }
            } else {
                cells.forEach { cell -> cell() }
            }
        }
    }
}

/** Read-only because M1 uses TLS and the standard ports; no set-text action, no onValueChange. */
@Composable
internal fun ReadOnlyPortField(label: String, port: Int) {
    OutlinedTextField(
        value = port.toString(),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
    )
}
