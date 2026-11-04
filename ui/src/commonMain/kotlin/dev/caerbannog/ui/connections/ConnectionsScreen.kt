package dev.caerbannog.ui.connections

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.ConnectionsState
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.DeclaredPermissions
import dev.caerbannog.core.connections.DeclaredPermissionsEdit
import dev.caerbannog.core.connections.PermissionBanner
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.permissionBanner
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

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            ConnectionsDrawer(state = state, modifier = Modifier.width(280.dp).fillMaxHeight())
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                ConnectionsAppBar(onNewConnection = viewModel::startNewConnection)
                ConnectionsContent(
                    state = state,
                    onSelect = viewModel::selectConnection,
                    editPanel = { panelModifier ->
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
                            modifier = panelModifier,
                        )
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
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

/** `null` for [originalName] means "New connection" - [onSave] adds rather than updates. */
@OptIn(ExperimentalLayoutApi::class)
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
    // Local UI state only: never in the view model or on disk. Masked again when another profile is selected.
    var passwordVisible by remember(selected?.name) { mutableStateOf(false) }
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
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp)).padding(16.dp),
        ) {
            Text(if (selected == null) "New connection" else "Edit connection", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(20.dp))

            ConnectionFieldGrid(
                listOf(
                    {
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    },
                    {
                        OutlinedTextField(value = virtualHost, onValueChange = { virtualHost = it }, label = { Text("Virtual host") }, modifier = Modifier.fillMaxWidth())
                    },
                    {
                        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
                    },
                    {
                        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                    },
                    // Read-only in M1: TLS and the standard ports; the profile already stores them.
                    { ReadOnlyPortField("Stream port (TLS)", selected?.streamPort ?: 5551) },
                    { ReadOnlyPortField("Management port (HTTPS)", selected?.managementPort ?: 15671) },
                    {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            supportingText = if (selected == null) null else {
                                { Text("Blank keeps the saved password", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    ConnectionIcon(
                                        icon = if (passwordVisible) AppIcon.VisibilityOff else AppIcon.Visibility,
                                        size = IconSize,
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().semantics { password() },
                        )
                    },
                    {
                        OutlinedTextField(
                            value = caCertificatePath,
                            onValueChange = { path ->
                                caCertificatePath = path
                                if (path.isNotBlank()) onValidateCaCertificate(selected?.name, path)
                            },
                            label = { Text("CA certificate") },
                            supportingText = { Text("Blank uses the system trust store", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = { ConnectionIcon(AppIcon.UploadFile, IconSize) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                ),
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionIcon(AppIcon.Lock, IconSize)
                Text("TLS is always on. The app does not connect without it.", style = MaterialTheme.typography.bodySmall)
            }

            state.lastCheckResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                CheckConnectionBanner(result)
            }

            (state.lastSaveResult as? SaveConnectionResult.NameAlreadyExists)?.let { collision ->
                Spacer(modifier = Modifier.height(8.dp))
                ErrorBanner("A connection named '${collision.name}' already exists.")
            }

            Spacer(modifier = Modifier.height(20.dp))
            // FlowRow: with the button icons the three buttons no longer fit one line in a narrow panel (Save was squeezed).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selected != null) {
                    TextButton(onClick = { onDeleteRequest(selected.name) }) {
                        ConnectionIcon(AppIcon.Delete, SmallIconSize)
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Delete profile")
                    }
                }
                if (selected != null) {
                    OutlinedButton(onClick = { onCheckConnection(selected.name) }) {
                        ConnectionIcon(AppIcon.WifiTethering, SmallIconSize)
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Check connection and permissions")
                    }
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
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                banner.tone.icon()?.let { ConnectionIcon(it, IconSize) }
                Column(modifier = Modifier.weight(1f)) {
                    Text(banner.headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    banner.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        // Outside the banner Surface on purpose: it is a note about the banner, not part of the verdict.
        banner.dimNote?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("permissionDimNote"),
            )
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
        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectionIcon(AppIcon.CheckCircle, IconSize)
                Text(text, modifier = Modifier.weight(1f))
            }
        }
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
