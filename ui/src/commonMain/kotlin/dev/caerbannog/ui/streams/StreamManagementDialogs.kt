package dev.caerbannog.ui.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.streams.CreateStreamState
import dev.caerbannog.core.streams.DeleteStreamState
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

/**
 * Dialog to create a new stream.
 */
@Composable
fun CreateStreamDialog(
    state: CreateStreamState,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x80000000))
            .testTag("createStreamDialogScrim"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 520.dp)
                .fillMaxWidth(0.9f)
                .padding(vertical = 24.dp)
                .semantics { paneTitle = "Create stream" }
                .testTag("createStreamDialog"),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Header
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Create stream",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("createStreamDialogTitle"),
                    )
                    Text(
                        text = "Create a new stream on this virtual host.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Name input
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text("Stream name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("streamNameInput"),
                    isError = state.error != null,
                )

                // Error feedback
                state.error?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().testTag("createStreamError"),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ConnectionIcon(AppIcon.Error, IconSize)
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("cancelCreateStreamButton"),
                    ) {
                        Text("Cancel")
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = onConfirm,
                        enabled = state.name.isNotBlank() && !state.isCreating,
                        modifier = Modifier.testTag("confirmCreateStreamButton"),
                    ) {
                        ConnectionIcon(AppIcon.Add, SmallIconSize)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.isCreating) "Creating..." else "Create stream")
                    }
                }
            }
        }
    }
}

/**
 * Destructive confirmation dialog to delete a stream.
 * Requires the stream name to be typed in full before confirming.
 */
@Composable
fun DeleteStreamDialog(
    state: DeleteStreamState,
    onConfirmationChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x80000000))
            .testTag("deleteStreamDialogScrim"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 540.dp)
                .fillMaxWidth(0.9f)
                .padding(vertical = 24.dp)
                .semantics { paneTitle = "Delete stream" }
                .testTag("deleteStreamDialog"),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Header
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Delete stream",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("deleteStreamDialogTitle"),
                    )
                    Text(
                        text = "Deleting a stream is destructive and permanently deletes all stored messages.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Active consumers warning
                state.consumersWarning?.let { warning ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth().testTag("deleteStreamConsumersWarning"),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ConnectionIcon(AppIcon.Warning, IconSize)
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                // Instruction & confirmation input
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Type \"${state.streamName}\" to confirm deletion:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = state.typedConfirmation,
                        onValueChange = onConfirmationChange,
                        label = { Text("Stream name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("confirmStreamNameInput"),
                    )
                }

                // Error feedback
                state.error?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().testTag("deleteStreamError"),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ConnectionIcon(AppIcon.Error, IconSize)
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("cancelDeleteStreamButton"),
                    ) {
                        Text("Cancel")
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = onConfirm,
                        enabled = state.canConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.testTag("confirmDeleteStreamButton"),
                    ) {
                        ConnectionIcon(AppIcon.Delete, SmallIconSize)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.isDeleting) "Deleting..." else "Delete stream")
                    }
                }
            }
        }
    }
}
