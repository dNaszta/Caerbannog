package dev.caerbannog.ui.migrate3x

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.Severity
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

/**
 * The Migrate from 3.x screen, file source (M8-06a). State in, events out; the screen sends nothing to any broker and holds no decision:
 * every string that depends on the file comes ready from `core`.
 * Test tags: `migrate3x-choose-file`, `migrate3x-state-<name>` (`choose-source`, `loading`, `rejected`, `needs-virtual-host`, `report`),
 * `migrate3x-loss-banner`, `migrate3x-loss-copy`, `migrate3x-loss-show-all`, `migrate3x-loss-name-<i>`, `migrate3x-row-copy-<group key>-<index>`, `migrate3x-chip-<severity>` (`blocker`, `warning`, `info`), `migrate3x-group-<key>`, `migrate3x-row-<group key>-<index>`,
 * `migrate3x-vhost-field`, `migrate3x-vhost-error`, `migrate3x-export`, `migrate3x-copy-text`, plus `migrate3x-cancel`, `migrate3x-vhost-analyse`,
 * `migrate3x-notice`, `migrate3x-notice-dismiss`, `migrate3x-access-note`.
 *
 * [onCancelPrompt] is the one Cancel of the screen: it cancels a running Loading and answers the virtual host prompt with "no"; the caller
 * maps it to the view model events that apply (each is a no-op in the other state).
 */
@Composable
fun MigrateFromThreeScreen(
    state: MigrateFromThreeState,
    reportText: () -> String,
    onChooseFile: () -> Unit,
    onGiveVirtualHost: (String) -> Unit,
    onCancelPrompt: () -> Unit,
    onToggleSeverity: (Severity) -> Unit,
    onToggleGroup: (String) -> Unit,
    onExport: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
    /** Called after the report text was put on the clipboard (the view model then shows "Report copied as text"). */
    onReportCopied: () -> Unit = {},
    /** M8-06b STUB: the review, generate and save events (see [ReviewEvents]). The stub screen draws none of it yet. */
    review: ReviewEvents = ReviewEvents.None,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("migrate3x-info")) {
            ConnectionIcon(AppIcon.Info, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "This screen only reads a file. It needs no connection and sends nothing to any broker.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state) {
            MigrateFromThreeState.ChooseSource -> ChooseSourceState(onChooseFile)
            is MigrateFromThreeState.Loading -> LoadingState(state, onCancelPrompt)
            is MigrateFromThreeState.Rejected -> RejectedState(state, onChooseFile)
            is MigrateFromThreeState.NeedsVirtualHost -> VirtualHostPrompt(state, onGiveVirtualHost, onChooseFile)
            is MigrateFromThreeState.Report -> ReportState(state, reportText, onChooseFile, onToggleSeverity, onToggleGroup, onExport, onDismissNotice, onReportCopied)
        }
    }
}

@Composable
private fun ChooseSourceState(onChooseFile: () -> Unit) {
    Column(Modifier.testTag("migrate3x-state-choose-source"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Where does the 3.x topology come from?", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = true,
                onClick = {},
                label = { Text("Definitions file") },
                leadingIcon = { ConnectionIcon(AppIcon.Check, SmallIconSize) },
            )
            // Drawn for context only: the live source arrives with M8-06c.
            FilterChip(
                selected = false,
                onClick = {},
                enabled = false,
                label = { Text("3.x connection (arrives with live source support)") },
                leadingIcon = { ConnectionIcon(AppIcon.Lock, SmallIconSize) },
            )
        }
        Button(onClick = onChooseFile, modifier = Modifier.testTag("migrate3x-choose-file")) {
            ConnectionIcon(AppIcon.UploadFile, SmallIconSize)
            Text("Choose definitions file", Modifier.padding(start = 8.dp))
        }
        Text(
            "In the 3.x management UI: Overview, Export definitions. Or: rabbitmqctl export_definitions file.json",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Nothing is read until you choose a file. The file is read on this machine only; it is never uploaded. Files over 64 MB are refused.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingState(state: MigrateFromThreeState.Loading, onCancel: () -> Unit) {
    Column(Modifier.testTag("migrate3x-state-loading"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(IconSize))
            Text("Reading and analysing ", style = MaterialTheme.typography.titleMedium)
            Text(state.fileLabel, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        }
        Text("Large files can take a few seconds.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("migrate3x-cancel")) { Text("Cancel") }
    }
}

@Composable
private fun RejectedState(state: MigrateFromThreeState.Rejected, onChooseFile: () -> Unit) {
    Column(Modifier.testTag("migrate3x-state-rejected"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnectionIcon(AppIcon.Error, IconSize, tint = MaterialTheme.colorScheme.error)
            Text(state.text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        }
        Text(state.fileLabel, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        Text("Nothing was analysed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onChooseFile, modifier = Modifier.testTag("migrate3x-choose-file")) {
            ConnectionIcon(AppIcon.UploadFile, SmallIconSize)
            Text("Choose another file", Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun VirtualHostPrompt(state: MigrateFromThreeState.NeedsVirtualHost, onGive: (String) -> Unit, onChooseFile: () -> Unit) {
    // Empty on purpose: there is no default and "/" is not assumed. The text goes out as typed; the view model trims it.
    var name by remember(state.fileLabel) { mutableStateOf("") }
    Column(Modifier.testTag("migrate3x-state-needs-virtual-host"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Which virtual host is this file from?", style = MaterialTheme.typography.titleMedium)
        Text(
            "The file holds one virtual host and does not say which. Its queues, exchanges and policies will be written for the name you give. " +
                "The file has no users, virtual hosts or permissions, so there will be no access migration, and the virtual host must already exist on the 4.x broker.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Virtual host name") },
            singleLine = true,
            isError = state.error != null,
            supportingText = state.error?.let { error -> { Text(error, Modifier.testTag("migrate3x-vhost-error")) } },
            modifier = Modifier.fillMaxWidth().testTag("migrate3x-vhost-field").onPreviewKeyEvent { event ->
                val enter = event.type == KeyEventType.KeyDown && event.key == Key.Enter
                if (enter) onGive(name)
                enter
            },
        )
        Text(
            "Empty on purpose: there is no default and \"/\" is not assumed. Leading and trailing spaces are removed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onGive(name) }, modifier = Modifier.testTag("migrate3x-vhost-analyse")) { Text("Analyse") }
            OutlinedButton(onClick = onChooseFile, modifier = Modifier.testTag("migrate3x-choose-file")) { Text("Choose another file") }
        }
    }
}
