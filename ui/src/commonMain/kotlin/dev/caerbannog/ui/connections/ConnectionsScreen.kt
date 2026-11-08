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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.connections.BrokerEndpoints
import dev.caerbannog.core.connections.BrokerPorts
import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.checkResultMessage
import dev.caerbannog.core.connections.editPanelNotes
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.ui.appframe.AppFrameLayout
import dev.caerbannog.ui.appframe.ConnectionControl
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
import kotlinx.coroutines.Dispatchers
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
fun ConnectionsScreen(
    viewModel: ConnectionsViewModel,
    control: ConnectionControlState? = null,
    controlEvents: ConnectionControlEvents? = null,
    /** The drawer reports the destination the user chose. */
    onNavigate: (dev.caerbannog.ui.appframe.AppDestination) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // The chip is the one place that connects (M2-05b); without a control (null) the frame has no chip.
    AppFrameLayout(
        title = "Connections",
        onNavigate = onNavigate,
        actions = {
            Button(onClick = viewModel::startNewConnection) {
                ConnectionIcon(AppIcon.Add, SmallIconSize)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("New connection")
            }
        },
        chip = if (control != null && controlEvents != null) ({ ConnectionControl(control, controlEvents) }) else null,
    ) {
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
                    // The file is read off the UI thread; the view model drops a result the user has moved away from.
                    onValidateCaCertificate = { name, path -> viewModel.validateCaCertificateOffThread(name, path, Dispatchers.IO) },
                    onCheckConnection = { name -> scope.launch { viewModel.checkConnectionAndPermissions(name) } },
                    modifier = panelModifier,
                )
            },
            modifier = Modifier.fillMaxSize(),
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

/** `null` for [originalName] means "New connection" - [onSave] adds rather than updates. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditPanel(
    state: ConnectionsState,
    onSave: (originalName: String?, input: ConnectionProfileInput) -> Unit,
    onDeleteRequest: (String) -> Unit,
    onValidateCaCertificate: suspend (name: String?, path: String) -> Unit,
    onCheckConnection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = state.selectedProfile

    val form = remember(selected?.name) { ConnectionFormState(selected) }
    // Local UI state only: never in the view model or on disk. Masked again when another profile is selected.
    var passwordVisible by remember(selected?.name) { mutableStateOf(false) }
    var pendingLevel by remember(selected?.name) { mutableStateOf<TopologyControlLevel?>(null) }
    val panelScope = rememberCoroutineScope()
    val validateCa: () -> Unit = {
        val path = form.caCertificatePath
        if (form.caEdited && form.tls && path.isNotBlank()) {
            form.caEdited = false
            form.caValidatedPath = path
            panelScope.launch { onValidateCaCertificate(selected?.name, path) }
        }
    }
    // Scrollable: the panel is taller than a small window, which would push the banners and Save off-screen.
    // The bar is always visible so the user can see that it scrolls.
    val scrollState = rememberScrollState()
    Row(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(end = 12.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (selected == null) "New connection" else "Edit connection", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(modifier = Modifier.height(16.dp))

            ConnectionFieldGrid(
                listOf(
                    {
                        OutlinedTextField(value = form.name, onValueChange = { form.name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    },
                    {
                        OutlinedTextField(value = form.virtualHost, onValueChange = { form.virtualHost = it }, label = { Text("Virtual host") }, modifier = Modifier.fillMaxWidth())
                    },
                    {
                        OutlinedTextField(value = form.host, onValueChange = { form.host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
                    },
                    {
                        OutlinedTextField(value = form.username, onValueChange = { form.username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                    },
                    // Read-only: the ports follow the TLS switch (P1 of M2-14).
                    { ReadOnlyPortField(if (form.tls) "Stream port (TLS)" else "Stream port", form.ports.stream) },
                    { ReadOnlyPortField(if (form.tls) "Management port (HTTPS)" else "Management port", form.ports.management) },
                    {
                        OutlinedTextField(
                            value = form.password,
                            onValueChange = { form.password = it },
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
                            value = form.caCertificatePath,
                            onValueChange = { path ->
                                form.caCertificatePath = path
                                form.caEdited = true
                            },
                            enabled = form.tls,
                            label = { Text("CA certificate") },
                            supportingText = {
                                Text(
                                    if (form.tls) "Blank uses the system trust store" else "Not used without TLS",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = { ConnectionIcon(AppIcon.UploadFile, IconSize) },
                            modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                                if (form.caHadFocus && !focus.isFocused) validateCa()
                                form.caHadFocus = focus.isFocused
                            },
                        )
                    },
                ),
            )
            // Product decision D7: TLS is checked against the CA, the host name is not. Said where the user sets the CA.
            editPanelNotes(form.tls, form.verifyHostName).hostNameNotVerified?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            val caResult = state.lastCaCertificateValidationResult
            // The CA is ignored with TLS off, so is a message about it.
            if (form.tls && caResult is CaCertificateValidationResult.Invalid) {
                Text(caResult.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(14.dp))
            TopologyControlSelector(
                level = form.level,
                onChange = { picked ->
                    if (picked == TopologyControlLevel.FULL_CONTROL && form.level != TopologyControlLevel.FULL_CONTROL) {
                        pendingLevel = picked
                    } else {
                        form.level = picked
                    }
                },
            )
            pendingLevel?.let { pending ->
                FullControlConfirmDialog(
                    onConfirm = {
                        form.level = pending
                        pendingLevel = null
                    },
                    onCancel = { pendingLevel = null },
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            DeclaredPermissionsSection(
                declares = form.declares,
                configure = form.declaredConfigure,
                write = form.declaredWrite,
                onDeclaresChange = { form.declares = it },
                onConfigureChange = { form.declaredConfigure = it },
                onWriteChange = { form.declaredWrite = it },
            )

            Spacer(modifier = Modifier.height(14.dp))
            if (selected != null) {
                PermissionCheckSection(permissionBanner(selected, state.permissionChecks[selected.name]))
            }

            Spacer(modifier = Modifier.height(14.dp))
            TlsSwitchRow(
                tls = form.tls,
                onChange = { now ->
                    form.tls = now
                    form.ports = BrokerEndpoints.afterTlsSwitch(form.ports, now)
                },
            )

            if (form.tls) {
                Spacer(modifier = Modifier.height(10.dp))
                VerifyHostNameRow(
                    verifyHostName = form.verifyHostName,
                    onChange = { form.verifyHostName = it },
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            MqttPluginSection(
                enabled = form.enableMqtt,
                tls = form.tls,
                port = form.ports.mqtt,
                onToggle = { form.toggleMqtt(it) },
            )

            state.lastCheckResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                CheckConnectionBanner(result, tls = selected?.tls ?: form.tls)
            }

            (state.lastSaveResult as? SaveConnectionResult.NameAlreadyExists)?.let { collision ->
                Spacer(modifier = Modifier.height(8.dp))
                ErrorBanner("A connection named '${collision.name}' already exists.")
            }

            Spacer(modifier = Modifier.height(14.dp))
            // FlowRow: with the button icons the three buttons no longer fit one line in a narrow panel (Save was squeezed).
            FlowRow(
                modifier = Modifier.fillMaxWidth().testTag("editPanelActionBar"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                    // The typed CA path travels in the input: the view model validates it once and stores it only when Valid (R2),
                    // so there is no validate-then-save detour any more and no stale panel scope can save under an old name (R5).
                    form.caEdited = false
                    val input = form.toInput(state.selectedProfile)
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
        Row(
            modifier = Modifier.toggleable(value = declares, role = Role.Switch, onValueChange = onDeclaresChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = declares, onCheckedChange = null)
            Text("I know what this user may do on the broker", modifier = Modifier.padding(start = 8.dp))
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
private fun CheckConnectionBanner(result: ConnectionCheckResult, tls: Boolean) {
    val text = checkResultMessage(result, tls)
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

/** The "Use TLS" row; with TLS off the warning sits directly under the switch (colour is never the only signal: icon and text). */
@Composable
private fun TlsSwitchRow(tls: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        // The row is the toggle so the Switch node carries the name "Use TLS" (merged from the Text) for a screen reader.
        Row(
            modifier = Modifier.testTag("tlsSwitch").toggleable(value = tls, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionIcon(if (tls) AppIcon.Lock else AppIcon.LockOpen, IconSize)
            Text("Use TLS", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = tls, onCheckedChange = null)
        }
        editPanelNotes(tls).notEncrypted?.let { note ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
                // The text is also a property of the tagged node, so a screen reader and a test find it on the warning itself.
                modifier = Modifier.fillMaxWidth().testTag("notEncryptedWarning").clearAndSetSemantics { text = AnnotatedString(note) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ConnectionIcon(AppIcon.LockOpen, IconSize)
                    Text(note, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun VerifyHostNameRow(verifyHostName: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("verifyHostNameSwitch")
            .toggleable(value = verifyHostName, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionIcon(AppIcon.VerifiedUser, IconSize)
        Column(modifier = Modifier.weight(1f)) {
            Text("Verify host name", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Enforce strict TLS host name verification against the server certificate",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = verifyHostName, onCheckedChange = null)
    }
}

@Composable
private fun MqttPluginSection(
    enabled: Boolean,
    tls: Boolean,
    port: Int?,
    onToggle: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("mqttPluginSwitch")
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionIcon(AppIcon.Hub, IconSize)
            Column(modifier = Modifier.weight(1f)) {
                Text("Enable MQTT plugin support", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Enable if the broker has the rabbitmq_mqtt plugin installed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
        if (enabled) {
            ReadOnlyPortField(
                label = if (tls) "MQTT port (TLS)" else "MQTT port",
                port = port ?: BrokerEndpoints.defaultMqttPort(tls),
            )
        }
    }
}
