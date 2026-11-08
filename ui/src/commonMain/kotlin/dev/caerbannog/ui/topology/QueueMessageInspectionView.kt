package dev.caerbannog.ui.topology

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.browser.PayloadFormatter
import dev.caerbannog.core.browser.PayloadViewKind
import dev.caerbannog.core.topology.InspectedQueueMessage
import dev.caerbannog.core.topology.QueueInspectionConstants
import dev.caerbannog.core.topology.QueueInspectionState
import dev.caerbannog.core.topology.TopologyTexts
import dev.caerbannog.ui.components.CopyableText

private val NotAcknowledgedAmber = Color(0xFFB36B00)

/**
 * Events for non-destructive queue message inspection in requeue mode.
 */
interface QueueInspectionEvents {
    fun inspectMessages()
    fun setCount(count: Int)
    fun selectMessage(index: Int)
    fun setPayloadViewKind(kind: PayloadViewKind?)
    fun clear()
}

object EmptyQueueInspectionEvents : QueueInspectionEvents {
    override fun inspectMessages() = Unit
    override fun setCount(count: Int) = Unit
    override fun selectMessage(index: Int) = Unit
    override fun setPayloadViewKind(kind: PayloadViewKind?) = Unit
    override fun clear() = Unit
}

/**
 * Non-destructive queue message inspection section embedded inside [QueueDetail] when [state] is provided.
 *
 * Messages are fetched through the RabbitMQ Management API using requeue mode (`ack_requeue_true`) and are clearly
 * marked as `Not acknowledged (requeued)` in both the section header and on every inspected message row and detail card.
 */
@Composable
internal fun QueueMessageInspectionSection(
    state: QueueInspectionState,
    events: QueueInspectionEvents,
    modifier: Modifier = Modifier,
) {
    val currentCount = when (state) {
        is QueueInspectionState.Idle -> state.count
        is QueueInspectionState.Loading -> state.count
        is QueueInspectionState.Empty -> state.count
        is QueueInspectionState.Loaded -> state.count
        is QueueInspectionState.Error -> state.count
    }

    Column(
        modifier = modifier.fillMaxWidth().testTag("queueMessageInspector"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Header toolbar: title, non-destructive requeue mode badge, count selector, and Inspect button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Non-destructive message inspection",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("queueInspectorTitle"),
            )

            NotAcknowledgedModeBadge(tag = "queueInspectorAckModeBadge")

            Spacer(Modifier.weight(1f))

            // Count selector pills (1, 5, 10, 25)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
            ) {
                listOf(1, 5, 10, 25).forEach { countOption ->
                    val selected = currentCount == countOption
                    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    Box(
                        modifier = Modifier
                            .background(bg)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { events.setCount(countOption) },
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("inspectCountOption:$countOption"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$countOption", style = MaterialTheme.typography.labelSmall, color = fg)
                    }
                }
            }

            Button(
                onClick = events::inspectMessages,
                enabled = state !is QueueInspectionState.Loading,
                modifier = Modifier.testTag("inspectQueueMessagesButton"),
            ) {
                Text("Inspect messages (requeue)")
            }

            if (state is QueueInspectionState.Loaded || state is QueueInspectionState.Empty || state is QueueInspectionState.Error) {
                OutlinedButton(
                    onClick = events::clear,
                    modifier = Modifier.testTag("clearInspectedMessagesButton"),
                ) {
                    Text("Clear")
                }
            }
        }

        // Requeue notice explaining non-destructive behavior
        Text(
            text = QueueInspectionConstants.REQUEUE_MODE_NOTICE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("queueInspectorRequeueNotice"),
        )

        when (state) {
            is QueueInspectionState.Idle -> Unit
            is QueueInspectionState.Loading -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("queueInspectorLoading"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(16.dp))
                    Text("Inspecting messages in requeue mode...", style = MaterialTheme.typography.bodySmall)
                }
            }
            is QueueInspectionState.Empty -> {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("queueInspectorEmpty"),
                ) {
                    Text(
                        text = "No ready messages in this queue to inspect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            is QueueInspectionState.Error -> {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("queueInspectorError"),
                ) {
                    Text(
                        text = state.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            is QueueInspectionState.Loaded -> {
                InspectedMessagesContent(state, events)
            }
        }
    }
}

@Composable
private fun NotAcknowledgedModeBadge(
    text: String = QueueInspectionConstants.NOT_ACKNOWLEDGED_BADGE,
    tag: String,
    modifier: Modifier = Modifier,
) {
    DisableSelection {
        Row(
            modifier = modifier
                .testTag(tag)
                .semantics { contentDescription = text }
                .background(NotAcknowledgedAmber.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                .border(1.dp, NotAcknowledgedAmber.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = NotAcknowledgedAmber,
                maxLines = 1,
                modifier = Modifier.testTag("$tag:label"),
            )
        }
    }
}

@Composable
private fun InspectedMessagesContent(
    state: QueueInspectionState.Loaded,
    events: QueueInspectionEvents,
) {
    val formatter = remember { PayloadFormatter() }
    val selectedMsg = state.selectedMessage
    val inspection = remember(selectedMsg, state.payloadViewKind) {
        selectedMsg?.let { formatter.inspect(it.toStreamMessage(), requestedKind = state.payloadViewKind) }
    }

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Left: list of inspected messages, each clearly marked as Not acknowledged (requeued)
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.weight(0.48f).testTag("inspectedMessagesList"),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.messages.forEach { msg ->
                    val isSelected = msg.index == selectedMsg?.index
                    val rowBg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBg)
                            .selectable(
                                selected = isSelected,
                                role = Role.Button,
                                onClick = { events.selectMessage(msg.index) },
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("inspectedMessageRow:${msg.index}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "#${msg.index}",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.testTag("inspectedMessageIndex:${msg.index}"),
                        )
                        val exText = if (msg.exchange.isEmpty()) TopologyTexts.DEFAULT_EXCHANGE else msg.exchange
                        val rkText = if (msg.routingKey.isEmpty()) "" else " \u00b7 ${msg.routingKey}"
                        Text(
                            text = "$exText$rkText",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).testTag("inspectedMessageRoute:${msg.index}"),
                        )
                        NotAcknowledgedModeBadge(
                            text = msg.acknowledgementStatusText,
                            tag = "inspectedMessageAckBadge:${msg.index}",
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }

        // Right: selected message payload & properties viewer with prominent Not acknowledged badge
        if (selectedMsg != null && inspection != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.weight(0.52f).testTag("inspectedMessageDetail"),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Message #${selectedMsg.index} (${selectedMsg.payloadBytes} B)",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.testTag("inspectedDetailTitle"),
                            )
                            NotAcknowledgedModeBadge(
                                text = selectedMsg.acknowledgementStatusText,
                                tag = "inspectedDetailAckBadge",
                            )
                        }

                        // Text / JSON / Hex format switcher
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
                        ) {
                            listOf(PayloadViewKind.TEXT, PayloadViewKind.JSON, PayloadViewKind.HEX).forEach { kind ->
                                val selected = inspection.currentKind == kind
                                val label = when (kind) {
                                    PayloadViewKind.TEXT -> "Text"
                                    PayloadViewKind.JSON -> "JSON"
                                    PayloadViewKind.HEX -> "Hex"
                                }
                                Box(
                                    modifier = Modifier
                                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                        .clickable { events.setPayloadViewKind(kind) }
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                        .testTag("inspectedViewerTab:$label"),
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // Payload body
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(Modifier.padding(8.dp)) {
                            CopyableText(
                                text = inspection.displayText,
                                tag = "inspectedDetailPayload",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }

                    // Metadata & headers summary
                    val metaParts = buildList {
                        add("Redelivered: ${if (selectedMsg.redelivered) "yes" else "no"}")
                        selectedMsg.properties["content_type"]?.let { add("Content-Type: $it") }
                        selectedMsg.properties["message_id"]?.let { add("ID: $it") }
                        if (selectedMsg.headers.isNotEmpty()) {
                            add("Headers: " + selectedMsg.headers.entries.joinToString { "${it.key}=${it.value}" })
                        }
                    }
                    Text(
                        text = metaParts.joinToString(" \u00b7 "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("inspectedDetailMeta"),
                    )
                }
            }
        }
    }
}
