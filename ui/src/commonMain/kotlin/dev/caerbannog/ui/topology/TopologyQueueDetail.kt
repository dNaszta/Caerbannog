package dev.caerbannog.ui.topology

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.topology.BindingRowView
import dev.caerbannog.core.topology.QueueDetailView
import dev.caerbannog.core.topology.TopologyTexts
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.components.CopyableText

private val LabelWidth = 140.dp
private val FieldWidth = 280.dp

/**
 * The detail of the selected queue, under the table (owner decision O2, a). Everything comes from the row already loaded; nothing is asked of
 * the broker and nothing here changes it. The close button and Escape (handled by the caller's scroller) call [onClose].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QueueDetail(detail: QueueDetailView, onClose: (String?) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("queueDetail")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildAnnotatedString {
                        append(detail.name)
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("  ${detail.typeText}") }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).testTag("detailTitle"),
                )
                IconButton(
                    onClick = { onClose(null) },
                    modifier = Modifier.testTag("detailClose").semantics { contentDescription = "Close details" },
                ) {
                    ConnectionIcon(AppIcon.Close, IconSize)
                }
            }
            // Fields flow side by side so the block stays short and the table above it stays on screen.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Leader", detail.leader, "detailValue:Leader")
                Field("Members", detail.members, "detailValue:Members")
                Field("Members online", detail.membersOnline, "detailValue:MembersOnline") {
                    if (detail.memberOffline) {
                        // Icon and words together: an offline member is never shown by colour alone.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MemberOfflineMark("detailMemberOffline")
                            Text("Member offline", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Field("Ready / unacked", detail.readyUnacked, "detailValue:ReadyUnacked")
                Field("Consumers", detail.consumers, "detailValue:Consumers")
                Field("Policy", detail.policy, "detailValue:Policy")
            }
            if (detail.bindings.isEmpty()) {
                DetailRow("Binding", TopologyTexts.NOT_APPLICABLE, "detailNoBinding")
            } else {
                detail.bindings.forEachIndexed { index, binding -> DetailRow("Binding", bindingText(binding), "detailBinding:$index") }
            }
        }
    }
}

private fun bindingText(binding: BindingRowView): String =
    "${binding.source} → ${binding.destination}" + if (binding.routingKey.isEmpty()) "" else " (${binding.routingKey})"

/** A label above its value, in a column of fixed width so the fields line up. */
@Composable
private fun Field(label: String, value: String, tag: String, extra: (@Composable () -> Unit)? = null) {
    Column(Modifier.width(FieldWidth)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CopyableText(value, tag, MaterialTheme.typography.bodyMedium)
        extra?.invoke()
    }
}

@Composable
private fun DetailRow(label: String, value: String, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(LabelWidth))
        CopyableText(value, tag, MaterialTheme.typography.bodyMedium, extra = Modifier.weight(1f))
    }
}
