package dev.caerbannog.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.ConnectionsState
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.TopologyControlLevel
import kotlinx.coroutines.launch

/**
 * The Connections screen (`docs/ux/Main.dc.html`): a sidebar of saved profiles plus an edit panel,
 * matching the states this task's brief calls out (empty, new-connection, editing, check-connection
 * success/error, delete confirm/cancel, topology control, an inert permission-check panel).
 *
 * Holds no broker/network code itself: [viewModel] is `core`'s [ConnectionsViewModel], which only
 * ever talks to the [dev.caerbannog.core.connections.ConnectionProfileRepository],
 * [dev.caerbannog.core.connections.SecretStore], [dev.caerbannog.core.connections.
 * BrokerReachabilityChecker] and [dev.caerbannog.core.connections.CaCertificateValidator] interfaces
 * it was built with - this screen never imports anything broker- or network-shaped.
 */
@Composable
fun ConnectionsScreen(viewModel: ConnectionsViewModel) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        SavedProfilesList(
            state = state,
            onSelect = viewModel::selectConnection,
            onNewConnection = viewModel::startNewConnection,
            modifier = Modifier.width(280.dp),
        )
        Spacer(modifier = Modifier.width(24.dp))
        EditPanel(
            state = state,
            onSave = { originalName, input ->
                if (originalName == null) viewModel.addConnection(input) else viewModel.updateConnection(originalName, input)
            },
            onDeleteRequest = viewModel::requestDelete,
            onTopologyControlChange = viewModel::setTopologyControlLevel,
            onValidateCaCertificate = { name, path ->
                if (name == null) viewModel.validateCaCertificateFile(path) else viewModel.setCaCertificate(name, path)
            },
            onCheckConnection = { name -> scope.launch { viewModel.checkConnection(name) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val pendingDeleteName = state.pendingDeleteName
    if (pendingDeleteName != null) {
        DeleteConfirmDialog(
            name = pendingDeleteName,
            onConfirm = viewModel::confirmDelete,
            onCancel = viewModel::cancelDelete,
        )
    }
}

@Composable
private fun SavedProfilesList(
    state: ConnectionsState,
    onSelect: (String) -> Unit,
    onNewConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text("Saved profiles", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onNewConnection, modifier = Modifier.fillMaxWidth()) {
            Text("New connection")
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (state.profiles.isEmpty()) {
            Text("No saved connections yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn {
                items(state.profiles, key = { it.name }) { profile ->
                    ProfileCard(
                        profile = profile,
                        selected = profile.name == state.selectedProfileName,
                        onClick = { onSelect(profile.name) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: ConnectionProfile, selected: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleSmall)
            Text(profile.host, style = MaterialTheme.typography.bodySmall)
            // No "Connected" status in this task's scope: there is no notion yet of an active broker
            // session (that is M2's stream-list territory) - only whether this profile is the one
            // currently open in the edit panel.
            Text(if (selected) "Editing" else "Not connected", style = MaterialTheme.typography.bodySmall)
        }
    }
    TextButton(onClick = onClick) { Text("Select") }
}

/** `null` for [originalName] means "New connection" - [onSave] adds rather than updates. */
@Composable
private fun EditPanel(
    state: ConnectionsState,
    onSave: (originalName: String?, input: ConnectionProfileInput) -> Unit,
    onDeleteRequest: (String) -> Unit,
    onTopologyControlChange: (name: String, level: TopologyControlLevel) -> Unit,
    onValidateCaCertificate: (name: String?, path: String) -> Unit,
    onCheckConnection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = state.selectedProfile

    var name by remember(selected?.name) { mutableStateOf(selected?.name.orEmpty()) }
    var host by remember(selected?.name) { mutableStateOf(selected?.host.orEmpty()) }
    var virtualHost by remember(selected?.name) { mutableStateOf(selected?.virtualHost ?: "/") }
    var username by remember(selected?.name) { mutableStateOf(selected?.username.orEmpty()) }
    var password by remember(selected?.name) { mutableStateOf("") }
    var caCertificatePath by remember(selected?.name) { mutableStateOf(selected?.caCertificatePath.orEmpty()) }

    // Scrollable: the panel is taller than a small window, which would push the banners and Save off-screen.
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Text(if (selected == null) "New connection" else "Edit connection", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = virtualHost, onValueChange = { virtualHost = it }, label = { Text("Virtual host") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(if (selected == null) "Password" else "Password (leave blank to keep unchanged)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("Stream port (TLS): ${selected?.streamPort ?: 5551}", style = MaterialTheme.typography.bodySmall)
        Text("Management port (HTTPS): ${selected?.managementPort ?: 15671}", style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = caCertificatePath,
            onValueChange = { path ->
                caCertificatePath = path
                if (path.isNotBlank()) onValidateCaCertificate(selected?.name, path)
            },
            label = { Text("CA certificate (optional - blank uses the system trust store)") },
            modifier = Modifier.fillMaxWidth(),
        )
        val caResult = state.lastCaCertificateValidationResult
        if (caResult is CaCertificateValidationResult.Invalid) {
            Text(caResult.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(16.dp))
        TopologyControlSelector(
            level = selected?.topologyControlLevel ?: TopologyControlLevel.READ_ONLY,
            enabled = selected != null,
            onChange = { level -> selected?.let { onTopologyControlChange(it.name, level) } },
        )

        Spacer(modifier = Modifier.height(16.dp))
        PermissionCheckPanel()

        Spacer(modifier = Modifier.height(16.dp))
        Text("TLS is always on. The app does not connect without it.", style = MaterialTheme.typography.bodySmall)

        state.lastCheckResult?.let { result ->
            Spacer(modifier = Modifier.height(8.dp))
            CheckConnectionBanner(result)
        }

        (state.lastSaveResult as? SaveConnectionResult.NameAlreadyExists)?.let { collision ->
            Spacer(modifier = Modifier.height(8.dp))
            ErrorBanner("A connection named '${collision.name}' already exists.")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected != null) {
                TextButton(onClick = { onDeleteRequest(selected.name) }) { Text("Delete profile") }
            }
            Spacer(modifier = Modifier.width(1.dp))
            if (selected != null) {
                OutlinedButton(onClick = { onCheckConnection(selected.name) }) { Text("Check connection") }
            }
            Button(onClick = {
                val input = ConnectionProfileInput(
                    name = name,
                    host = host,
                    virtualHost = virtualHost,
                    username = username,
                    password = password.ifBlank { null },
                    streamPort = selected?.streamPort ?: 5551,
                    managementPort = selected?.managementPort ?: 15671,
                )
                onSave(selected?.name, input)
            }) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun TopologyControlSelector(level: TopologyControlLevel, enabled: Boolean, onChange: (TopologyControlLevel) -> Unit) {
    Column {
        Text("Topology control", style = MaterialTheme.typography.titleSmall)
        TopologyOption("Read-only", level == TopologyControlLevel.READ_ONLY, enabled) { onChange(TopologyControlLevel.READ_ONLY) }
        TopologyOption("Declare and edit", level == TopologyControlLevel.DECLARE_AND_EDIT, enabled) { onChange(TopologyControlLevel.DECLARE_AND_EDIT) }
        TopologyOption("Full control", level == TopologyControlLevel.FULL_CONTROL, enabled) { onChange(TopologyControlLevel.FULL_CONTROL) }
    }
}

@Composable
private fun TopologyOption(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}

/**
 * The broker-read half of the self-constraint picture (`docs/ux/Main.dc.html`'s "svc-explorer can
 * change topology..." box) is M1-03's job - rendered here in a neutral, inert state per this task's
 * UX section, not a fabricated locked/unlocked verdict.
 */
@Composable
private fun PermissionCheckPanel() {
    Column {
        Text("Permission check", style = MaterialTheme.typography.titleSmall)
        Text("Permission check has not run yet.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CheckConnectionBanner(result: ConnectionCheckResult) {
    val text = when (result) {
        is ConnectionCheckResult.BothReachable ->
            "Connection test passed. The stream endpoint and the management API are both reachable."
        is ConnectionCheckResult.EndpointUnreachable ->
            "The ${result.endpoint.name.lowercase()} endpoint could not be reached: ${result.reason}"
        is ConnectionCheckResult.ManagementPluginMissing ->
            "Enable the \"${result.pluginName}\" plugin on the broker."
        is ConnectionCheckResult.TlsRefused ->
            "The connection was refused. The app does not connect without TLS."
    }
    if (result is ConnectionCheckResult.BothReachable) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer) { Text(text, modifier = Modifier.padding(12.dp)) }
    } else {
        ErrorBanner(text)
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) { Text(text, modifier = Modifier.padding(12.dp)) }
}

@Composable
private fun DeleteConfirmDialog(name: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete connection") },
        text = { Text("Delete the connection profile \"$name\"? This cannot be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Yes") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
