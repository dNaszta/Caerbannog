package dev.caerbannog.ui.mqtt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttState
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

/**
 * Publish MQTT message dialog per milestone M6-05.
 */
@Composable
fun PublishMqttDialog(
    state: PublishMqttState,
    events: PublishMqttEvents,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x80000000))
            .testTag("publishMqttDialogScrim"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 680.dp)
                .fillMaxWidth(0.9f)
                .padding(vertical = 24.dp)
                .semantics {
                    paneTitle = "Publish MQTT message"
                }
                .testTag("publishMqttDialog"),
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
                        text = "Publish MQTT message",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("publishMqttDialogTitle"),
                    )
                    Text(
                        text = "Publish a test message to any topic on this broker.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Destination Warning Banner
                WarningBanner(state.warningBanner)

                // Topic Input
                OutlinedTextField(
                    value = state.topic,
                    onValueChange = events::setTopic,
                    label = { Text("Topic") },
                    placeholder = { Text("e.g. sensors/room-1/temperature") },
                    isError = state.topicError != null,
                    supportingText = {
                        state.topicError?.let { err ->
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("publishMqttTopicError"),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("publishMqttTopicInput"),
                    singleLine = true,
                )

                // QoS Selector
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Quality of Service (QoS)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("publishMqttQosSelector"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MqttQos.entries.forEach { qos ->
                            val isSelected = state.qos == qos
                            val label = when (qos) {
                                MqttQos.AT_MOST_ONCE -> "QoS 0 (At most once)"
                                MqttQos.AT_LEAST_ONCE -> "QoS 1 (At least once)"
                                MqttQos.EXACTLY_ONCE -> "QoS 2 (Exactly once)"
                            }
                            FilterChip(
                                selected = isSelected,
                                onClick = { events.setQos(qos) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("qosChip:${qos.value}"),
                            )
                        }
                    }
                }

                // Retain Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.retain,
                            role = Role.Switch,
                            onValueChange = events::setRetain,
                        )
                        .testTag("publishMqttRetainSwitch"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(
                            text = "Retain message",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "The broker stores this message as the latest retained value for new subscribers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.retain,
                        onCheckedChange = null,
                    )
                }

                // Payload Input
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Payload",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.payload,
                        onValueChange = events::setPayload,
                        placeholder = { Text("""{"temperature": 21.5, "unit": "C"}""") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 300.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("publishMqttPayloadInput"),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 15,
                        minLines = 5,
                    )
                }

                // Feedback Banners
                state.confirmation?.let { msg ->
                    BannerBox(text = msg, isError = false, testTag = "publishMqttConfirmation")
                }
                state.error?.let { err ->
                    BannerBox(text = err, isError = true, testTag = "publishMqttError")
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = events::cancel,
                        modifier = Modifier.testTag("publishMqttCancelButton"),
                    ) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = events::publish,
                        enabled = state.canPublish,
                        modifier = Modifier.testTag("publishMqttPublishButton"),
                    ) {
                        if (state.isPublishing) {
                            Text("Publishing...")
                        } else {
                            Text("Publish")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningBanner(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().testTag("publishMqttWarningBanner"),
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
