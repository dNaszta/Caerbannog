package dev.caerbannog.ui.publish

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.publish.PublishGate
import dev.caerbannog.core.publish.PublishMessageState
import dev.caerbannog.core.publish.PublishTargetKind
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

/**
 * Publish test message dialog per `docs/ux/Publish.dc.html`.
 */
@Composable
fun PublishTestMessageDialog(
    state: PublishMessageState,
    events: PublishEvents,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x80000000))
            .testTag("publishDialogScrim"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 680.dp)
                .fillMaxWidth(0.9f)
                .padding(vertical = 24.dp)
                .semantics {
                    paneTitle = "Publish test message"
                }
                .testTag("publishDialog"),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Header
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Publish test message",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("publishDialogTitle"),
                    )
                    Text(
                        text = "Send one message to any stream, exchange or queue on this connection.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Target kind segmented control
                TargetKindSegments(
                    selectedKind = state.targetKind,
                    onSelect = events::setTargetKind,
                )

                // Target & properties inputs
                TargetFieldsGrid(state = state, events = events)

                // Body input
                OutlinedTextField(
                    value = state.body,
                    onValueChange = events::setBody,
                    label = { Text("Body") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .testTag("bodyInput"),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )

                // Application properties section
                ApplicationPropertiesSection(
                    properties = state.applicationProperties,
                    onAdd = events::addApplicationProperty,
                    onRemove = events::removeApplicationProperty,
                )

                // Warning banner
                WarningBanner(text = state.warningBanner)

                // Status feedback banners
                val gate = state.gate
                if (gate is PublishGate.Disabled) {
                    BannerBox(
                        text = gate.reason,
                        isError = true,
                        testTag = "noWritePermissionBanner",
                    )
                }
                state.error?.let { err ->
                    BannerBox(text = err, isError = true, testTag = "publishError")
                }
                state.confirmation?.let { msg ->
                    BannerBox(text = msg, isError = false, testTag = "publishConfirmation")
                }

                // Footer actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Connection pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.testTag("connectionPill"),
                    ) {
                        Text(
                            text = "Connection: ${state.connectionName}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    TextButton(
                        onClick = events::cancel,
                        modifier = Modifier.testTag("cancelButton"),
                    ) {
                        Text("Cancel")
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = events::publish,
                        enabled = state.canPublish,
                        modifier = Modifier.testTag("publishButton"),
                    ) {
                        ConnectionIcon(AppIcon.Send, SmallIconSize)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.isPublishing) "Publishing..." else "Publish")
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetKindSegments(
    selectedKind: PublishTargetKind,
    onSelect: (PublishTargetKind) -> Unit,
) {
    Row(
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PublishTargetKind.entries.forEach { kind ->
            val isSelected = kind == selectedKind
            val tag = "targetKind:${kind.name.lowercase()}"
            val displayName = when (kind) {
                PublishTargetKind.STREAM -> "Stream"
                PublishTargetKind.EXCHANGE -> "Exchange"
                PublishTargetKind.QUEUE -> "Queue"
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                modifier = Modifier
                    .clickable { onSelect(kind) }
                    .testTag(tag),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isSelected) {
                        ConnectionIcon(AppIcon.Check, SmallIconSize)
                    }
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetFieldsGrid(
    state: PublishMessageState,
    events: PublishEvents,
) {
    val targetLabel = when (state.targetKind) {
        PublishTargetKind.STREAM -> "Stream"
        PublishTargetKind.EXCHANGE -> "Exchange"
        PublishTargetKind.QUEUE -> "Queue"
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.targetName,
                onValueChange = events::setTargetName,
                label = { Text(targetLabel) },
                modifier = Modifier.weight(1f).testTag("targetNameInput"),
                singleLine = true,
            )

            if (state.targetKind == PublishTargetKind.EXCHANGE) {
                OutlinedTextField(
                    value = state.routingKey,
                    onValueChange = events::setRoutingKey,
                    label = { Text("Routing key") },
                    modifier = Modifier.weight(1f).testTag("routingKeyInput"),
                    singleLine = true,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.messageId,
                onValueChange = events::setMessageId,
                label = { Text("message-id") },
                modifier = Modifier.weight(1f).testTag("messageIdInput"),
                singleLine = true,
            )

            OutlinedTextField(
                value = state.contentType,
                onValueChange = events::setContentType,
                label = { Text("content-type") },
                modifier = Modifier.weight(1f).testTag("contentTypeInput"),
                singleLine = true,
            )
        }
    }
}

@Composable
private fun ApplicationPropertiesSection(
    properties: List<Pair<String, String>>,
    onAdd: (String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var showAddRow by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (properties.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                properties.forEach { (k, v) ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.testTag("appProperty:$k"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "$k: $v",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            IconButton(
                                onClick = { onRemove(k) },
                                modifier = Modifier.size(16.dp).testTag("removeAppProperty:$k"),
                            ) {
                                ConnectionIcon(AppIcon.Close, 14.dp)
                            }
                        }
                    }
                }
            }
        }

        if (showAddRow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newKey,
                    onValueChange = { newKey = it },
                    label = { Text("Property name") },
                    modifier = Modifier.weight(1f).testTag("newAppPropertyKey"),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = newValue,
                    onValueChange = { newValue = it },
                    label = { Text("Value") },
                    modifier = Modifier.weight(1f).testTag("newAppPropertyValue"),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        if (newKey.isNotBlank()) {
                            onAdd(newKey.trim(), newValue.trim())
                            newKey = ""
                            newValue = ""
                            showAddRow = false
                        }
                    },
                    modifier = Modifier.testTag("confirmAddAppProperty"),
                ) {
                    Text("Add")
                }
                TextButton(onClick = { showAddRow = false }) {
                    Text("Cancel")
                }
            }
        } else {
            TextButton(
                onClick = { showAddRow = true },
                modifier = Modifier.testTag("addAppPropertyButton"),
            ) {
                ConnectionIcon(AppIcon.Add, SmallIconSize)
                Spacer(Modifier.width(6.dp))
                Text("Add application property")
            }
        }
    }
}

@Composable
private fun WarningBanner(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().testTag("realBrokerWarning"),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ConnectionIcon(AppIcon.Warning, IconSize)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun BannerBox(text: String, isError: Boolean, testTag: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ConnectionIcon(if (isError) AppIcon.Error else AppIcon.CheckCircle, IconSize)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
