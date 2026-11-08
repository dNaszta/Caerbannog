package dev.caerbannog.ui.streams

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.streams.PartitionBalanceInfo

private val PartitionSkewAmber = Color(0xFFB36B00)

/**
 * Compact Super Stream Partition Balance Bar (`[████████░░] 68%` or `[████████░░] Hot · 85%`) rendered inside
 * the `Messages` cell of an expanded partition row (M7-07).
 *
 * Always wrapped in [DisableSelection] so table cell copy-on-drag (`SelectionContainer`) only ever copies the cell's text.
 */
@Composable
internal fun PartitionBalanceBadge(
    balance: PartitionBalanceInfo,
    tag: String = "partitionBalance:${balance.partitionName}",
    modifier: Modifier = Modifier,
    barWidth: Dp = 44.dp,
    barHeight: Dp = 7.dp,
) {
    val barColor = if (balance.isSkewedHot) {
        PartitionSkewAmber
    } else {
        MaterialTheme.colorScheme.primary
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val uniformMarkerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)

    DisableSelection {
        Row(
            modifier = modifier
                .testTag(tag)
                .semantics {
                    contentDescription = "Partition share ${balance.shareLabel} of ${balance.superStreamName}"
                }
                .background(barColor.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .width(barWidth)
                    .height(barHeight)
                    .testTag("$tag:bar"),
            ) {
                val corner = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(
                    color = trackColor,
                    cornerRadius = corner,
                )
                val fillFraction = balance.shareFraction.coerceIn(0f, 1f)
                if (fillFraction > 0f) {
                    val fillW = (size.width * fillFraction).coerceAtLeast(size.height.coerceAtMost(size.width))
                    drawRoundRect(
                        color = barColor,
                        size = Size(fillW, size.height),
                        cornerRadius = corner,
                    )
                }
                // Uniform distribution tick mark (1 / partitionCount)
                if (balance.partitionCount >= 2) {
                    val uniformX = (size.width * (1f / balance.partitionCount.toFloat())).coerceIn(1f, size.width - 1f)
                    drawLine(
                        color = uniformMarkerColor,
                        start = Offset(uniformX, 0f),
                        end = Offset(uniformX, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            Text(
                text = balance.shareLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (balance.isSkewedHot) FontWeight.SemiBold else FontWeight.Medium,
                color = barColor,
                maxLines = 1,
                modifier = Modifier.testTag("$tag:label"),
            )
        }
    }
}

/**
 * Compact warning badge rendered in the `Consumers` cell of an expanded partition row when that partition has `0 consumers`
 * while sibling partitions in the same Super Stream have active consumers (`Orphaned`).
 *
 * Always wrapped in [DisableSelection] so table cell copy-on-drag (`SelectionContainer`) only ever copies the cell's text.
 */
@Composable
internal fun PartitionOrphanedWarningBadge(
    partitionName: String,
    tag: String = "partitionOrphanedWarning:$partitionName",
    modifier: Modifier = Modifier,
) {
    DisableSelection {
        Row(
            modifier = modifier
                .testTag(tag)
                .semantics { contentDescription = "Orphaned partition $partitionName has 0 consumers" }
                .background(PartitionSkewAmber.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Orphaned",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = PartitionSkewAmber,
                maxLines = 1,
                modifier = Modifier.testTag("$tag:label"),
            )
        }
    }
}
