package dev.caerbannog.ui.connections

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.ConnectionsState
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.DeclaredPermissions
import dev.caerbannog.core.connections.DeclaredPermissionsEdit
import dev.caerbannog.core.connections.PermissionBanner
import dev.caerbannog.core.connections.PermissionCheckStatus
import dev.caerbannog.core.connections.PermissionPill
import dev.caerbannog.core.connections.PillTone
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.permissionBanner
import dev.caerbannog.core.connections.permissionPill
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
                if (originalName == null) {
                    viewModel.addConnection(input)
                } else {
                    scope.launch { viewModel.saveProfileChanges(originalName, input) }
                }
            },
            onDeleteRequest = viewModel::requestDelete,
            onValidateCaCertificate = { name, path ->
                if (name == null) viewModel.validateCaCertificateFile(path) else viewModel.setCaCertificate(name, path)
            },
            onCheckConnection = { name -> scope.launch { viewModel.checkConnectionAndPermissions(name) } },
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
                        status = state.permissionChecks[profile.name],
                        onClick = { onSelect(profile.name) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: ConnectionProfile, selected: Boolean, status: PermissionCheckStatus?, onClick: () -> Unit) {
    // The whole card is the click target (as in the mockup); the pill is a child of it. Not `clickable`:
    // that merges the children into one semantics node and the pill's own tag would vanish from the tree.
    Card(
        modifier = Modifier.fillMaxWidth()
            .semantics(mergeDescendants = false) {
                role = Role.Button
                this.onClick { onClick(); true }
            }
            .pointerInput(onClick) { detectTapGestures { onClick() } },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                PermissionPillView(permissionPill(status), Modifier.testTag("permissionPill:${profile.name}"))
            }
            Text(profile.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // No "Connected" status in this task's scope: there is no notion yet of an active broker
            // session (that is M2's stream-list territory) - only whether this profile is the one
            // currently open in the edit panel.
            Text(
                if (selected) "Editing" else "Not connected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Tone to colour in one place. OK green is not in the M3 scheme, so it uses the mockup's `ok-container` values. */
private object PermissionColors {
    private val okContainer = Color(0xFFD8EBD0)
    private val onOkContainer = Color(0xFF173A0F)

    @Composable
    fun container(tone: PillTone): Color = when (tone) {
        PillTone.OK -> okContainer
        PillTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        PillTone.LOCKED -> MaterialTheme.colorScheme.errorContainer
        PillTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }

    @Composable
    fun content(tone: PillTone): Color = when (tone) {
        PillTone.OK -> onOkContainer
        PillTone.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        PillTone.LOCKED -> MaterialTheme.colorScheme.onErrorContainer
        PillTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun PermissionPillView(pill: PermissionPill, modifier: Modifier = Modifier) {
    val (text, tone) = pill
    Surface(color = PermissionColors.container(tone), contentColor = PermissionColors.content(tone), shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** `null` for [originalName] means "New connection" - [onSave] adds rather than updates. */
@Composable
private fun EditPanel(
    state: ConnectionsState,
    onSave: (originalName: String?, input: ConnectionProfileInput) -> Unit,
    onDeleteRequest: (String) -> Unit,
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
    // Form state like every other field: applied on Save only. pendingLevel non-null = confirmation open.
    var level by remember(selected?.name) { mutableStateOf(selected?.topologyControlLevel ?: TopologyControlLevel.READ_ONLY) }
    var pendingLevel by remember(selected?.name) { mutableStateOf<TopologyControlLevel?>(null) }
    // Declaration form state, applied on Save only: switch off sends Clear, on sends Set.
    var declares by remember(selected?.name) { mutableStateOf(selected?.declaredPermissions != null) }
    var declaredConfigure by remember(selected?.name) { mutableStateOf(selected?.declaredPermissions?.configure ?: false) }
    var declaredWrite by remember(selected?.name) { mutableStateOf(selected?.declaredPermissions?.write ?: false) }

    // Scrollable: the panel is taller than a small window, which would push the banners and Save off-screen.
    // The bar is always visible so the user can see that it scrolls.
    val scrollState = rememberScrollState()
    Row(modifier = modifier) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(end = 12.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(16.dp),
        ) {
            Text(if (selected == null) "New connection" else "Edit connection", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(20.dp))

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
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().semantics { password() },
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

            Spacer(modifier = Modifier.height(20.dp))
            TopologyControlSelector(
                level = level,
                onChange = { picked ->
                    if (picked == TopologyControlLevel.FULL_CONTROL && level != TopologyControlLevel.FULL_CONTROL) {
                        pendingLevel = picked
                    } else {
                        level = picked
                    }
                },
            )
            pendingLevel?.let { pending ->
                FullControlConfirmDialog(
                    onConfirm = {
                        level = pending
                        pendingLevel = null
                    },
                    onCancel = { pendingLevel = null },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            DeclaredPermissionsSection(
                declares = declares,
                configure = declaredConfigure,
                write = declaredWrite,
                onDeclaresChange = { declares = it },
                onConfigureChange = { declaredConfigure = it },
                onWriteChange = { declaredWrite = it },
            )

            Spacer(modifier = Modifier.height(20.dp))
            if (selected != null) {
                PermissionCheckSection(permissionBanner(selected, state.permissionChecks[selected.name]))
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text("TLS is always on. The app does not connect without it.", style = MaterialTheme.typography.bodySmall)

            state.lastCheckResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                CheckConnectionBanner(result)
            }

            (state.lastSaveResult as? SaveConnectionResult.NameAlreadyExists)?.let { collision ->
                Spacer(modifier = Modifier.height(8.dp))
                ErrorBanner("A connection named '${collision.name}' already exists.")
            }

            Spacer(modifier = Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selected != null) {
                    TextButton(onClick = { onDeleteRequest(selected.name) }) { Text("Delete profile") }
                }
                Spacer(modifier = Modifier.width(1.dp))
                if (selected != null) {
                    OutlinedButton(onClick = { onCheckConnection(selected.name) }) { Text("Check connection and permissions") }
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
                        topologyControlLevel = level,
                        declaredPermissions = if (declares) {
                            DeclaredPermissionsEdit.Set(DeclaredPermissions(configure = declaredConfigure, write = declaredWrite))
                        } else {
                            DeclaredPermissionsEdit.Clear
                        },
                    )
                    onSave(selected?.name, input)
                }) {
                    Text("Save")
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scrollState), modifier = Modifier.fillMaxHeight().testTag("editPanelScrollbar"))
    }
}

@Composable
private fun TopologyControlSelector(level: TopologyControlLevel, onChange: (TopologyControlLevel) -> Unit) {
    Column {
        Text("Topology control", style = MaterialTheme.typography.titleSmall)
        Text(
            "The most this app will do to topology on this connection. The broker's own permissions still apply.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TopologyOption("Read-only", level == TopologyControlLevel.READ_ONLY) { onChange(TopologyControlLevel.READ_ONLY) }
        TopologyOption("Declare and edit", level == TopologyControlLevel.DECLARE_AND_EDIT) { onChange(TopologyControlLevel.DECLARE_AND_EDIT) }
        TopologyOption("Full control", level == TopologyControlLevel.FULL_CONTROL) { onChange(TopologyControlLevel.FULL_CONTROL) }
    }
}

@Composable
private fun DeclaredPermissionsSection(
    declares: Boolean,
    configure: Boolean,
    write: Boolean,
    onDeclaresChange: (Boolean) -> Unit,
    onConfigureChange: (Boolean) -> Unit,
    onWriteChange: (Boolean) -> Unit,
) {
    Column {
        Text("Declared permissions (your own statement)", style = MaterialTheme.typography.titleSmall)
        Row {
            Switch(checked = declares, onCheckedChange = onDeclaresChange)
            Text("I know what this user may do on the broker", modifier = Modifier.padding(start = 8.dp, top = 12.dp))
        }
        Text(
            "Only used when the broker does not let this user read its own permissions. " +
                "This is your own statement; the broker still enforces its permissions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (declares) {
            YesNoChoice("Can change topology (configure)", configure, onConfigureChange)
            YesNoChoice("Can publish (write)", write, onWriteChange)
        }
    }
}

@Composable
private fun YesNoChoice(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row {
            TopologyOption("Yes", value) { onChange(true) }
            Spacer(modifier = Modifier.width(16.dp))
            TopologyOption("No", !value) { onChange(false) }
        }
    }
}

@Composable
private fun FullControlConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Allow full control?") },
        text = {
            Text(
                "Full control lets this app delete topology (queues, streams, exchanges, bindings, policies) " +
                    "on this connection and run migrations with destructive actions. This is only your own " +
                    "ceiling for this connection: the broker's permissions still apply, and the app never " +
                    "reads, gets or purges queue messages.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Use Full control") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun TopologyOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}

/** The "Permission check" group: a toned banner for what the broker (or the user's declaration) says. */
@Composable
private fun PermissionCheckSection(banner: PermissionBanner) {
    Column {
        Text("Permission check", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            color = PermissionColors.container(banner.tone),
            contentColor = PermissionColors.content(banner.tone),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().testTag("permissionBanner"),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(banner.headline, style = MaterialTheme.typography.bodyMedium)
                banner.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                banner.dimNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
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
