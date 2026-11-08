package dev.caerbannog.ui.streams

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.browser.ConsumerPin
import dev.caerbannog.core.browser.ConsumerPinHealth
import dev.caerbannog.core.browser.StreamLogTape
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.SmallIconSize

private val CaughtUpGreen = Color(0xFF386A20)
private val LaggingAmber = Color(0xFFE65100)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamLogTapeCard(
    tape: StreamLogTape,
    onSeekOffset: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth().testTag("streamLogTapeCard"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row: title, active range, truncated badge, retention cliff badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ConnectionIcon(AppIcon.Stream, SmallIconSize, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = "Stream Log Tape",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = tape.rangeSummaryText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("logTapeRangeText"),
                )

                Spacer(Modifier.weight(1f))

                tape.truncatedBadgeText?.let { truncText ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.testTag("logTapeTruncatedBadge"),
                    ) {
                        Text(
                            text = truncText,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }

                tape.cliffBadgeText?.let { cliffText ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.testTag("logTapeRetentionCliffBadge"),
                    ) {
                        Text(
                            text = cliffText,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            // Interactive Canvas Bar
            StreamLogTapeCanvas(
                tape = tape,
                onSeekOffset = onSeekOffset,
            )

            // Scale axis + Consumer Pins strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = tape.leftAxisText,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                tape.viewingSliceText?.let { sliceText ->
                    Text(
                        text = sliceText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("logTapeViewingSlice"),
                    )
                }

                Text(
                    text = tape.rightAxisText,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (tape.consumerPins.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().testTag("logTapeConsumersRow"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tape.consumerPins.forEach { pin ->
                        ConsumerPinChip(
                            pin = pin,
                            onClick = { onSeekOffset(pin.offset) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamLogTapeCanvas(
    tape: StreamLogTape,
    onSeekOffset: (Long) -> Unit,
) {
    val trackBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val truncatedBg = MaterialTheme.colorScheme.surfaceContainerLow
    val hatchColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val dangerZoneColor = MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
    val dangerBorderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
    val sliceFillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val sliceBorderColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error
    val inactiveColor = MaterialTheme.colorScheme.outline
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, outlineColor, RoundedCornerShape(6.dp))
            .pointerInput(tape) {
                detectTapGestures { pos ->
                    if (size.width > 0) {
                        val totalFrac = (pos.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeekOffset(tape.offsetAtTotalFraction(totalFrac))
                    }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = "Stream log tape from offset ${tape.firstOffset} to ${tape.lastOffset}"
            }
            .testTag("streamLogTapeCanvas"),
    ) {
        val w = size.width
        val h = size.height
        val truncW = w * tape.truncatedFraction
        val activeStartX = truncW
        val activeW = (w - truncW).coerceAtLeast(1f)

        // 1. Background for active window
        drawRect(
            color = trackBg,
            topLeft = Offset(activeStartX, 0f),
            size = Size(activeW, h),
        )

        // 2. Truncated prefix region (if firstOffset > 0)
        if (truncW > 0f) {
            drawRect(
                color = truncatedBg,
                topLeft = Offset(0f, 0f),
                size = Size(truncW, h),
            )
            clipRect(left = 0f, top = 0f, right = truncW, bottom = h) {
                val step = 8.dp.toPx()
                var x = -h
                while (x < truncW + h) {
                    drawLine(
                        color = hatchColor,
                        start = Offset(x, h),
                        end = Offset(x + h, 0f),
                        strokeWidth = 1.dp.toPx(),
                    )
                    x += step
                }
            }
            drawLine(
                color = outlineColor,
                start = Offset(truncW, 0f),
                end = Offset(truncW, h),
                strokeWidth = 1.5.dp.toPx(),
            )
        }

        // 3. Retention danger cliff zone (first 10% of active window when truncated)
        if (tape.dangerZoneActiveFraction > 0f) {
            val dangerW = activeW * tape.dangerZoneActiveFraction
            drawRect(
                color = dangerZoneColor,
                topLeft = Offset(activeStartX, 0f),
                size = Size(dangerW, h),
            )
            drawLine(
                color = dangerBorderColor,
                start = Offset(activeStartX + dangerW, 0f),
                end = Offset(activeStartX + dangerW, h),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
        }

        // 4. Current page viewport slice
        tape.pageSlice?.let { slice ->
            val sliceStartX = activeStartX + activeW * slice.startFraction
            val rawEndX = activeStartX + activeW * slice.endFraction
            val sliceW = (rawEndX - sliceStartX).coerceAtLeast(6.dp.toPx())
            val clampedStartX = sliceStartX.coerceAtMost(w - sliceW)

            drawRoundRect(
                color = sliceFillColor,
                topLeft = Offset(clampedStartX, 2.dp.toPx()),
                size = Size(sliceW, h - 4.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
            drawRoundRect(
                color = sliceBorderColor,
                topLeft = Offset(clampedStartX, 2.dp.toPx()),
                size = Size(sliceW, h - 4.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        // 5. Selected message cursor
        tape.selectedPin?.let { sel ->
            val selX = (activeStartX + activeW * sel.fraction).coerceIn(activeStartX + 2.dp.toPx(), w - 2.dp.toPx())
            drawLine(
                color = selectedColor,
                start = Offset(selX, 0f),
                end = Offset(selX, h),
                strokeWidth = 2.dp.toPx(),
            )
            val diamond = Path().apply {
                val r = 4.dp.toPx()
                moveTo(selX, 0f)
                lineTo(selX + r, r)
                lineTo(selX, r * 2)
                lineTo(selX - r, r)
                close()
            }
            drawPath(diamond, color = selectedColor)
        }

        // 6. Consumer pins
        tape.consumerPins.forEach { pin ->
            val pinColor = when (pin.health) {
                ConsumerPinHealth.CaughtUp -> CaughtUpGreen
                ConsumerPinHealth.Lagging -> LaggingAmber
                ConsumerPinHealth.AtRisk -> errorColor
                ConsumerPinHealth.Inactive -> inactiveColor
            }
            val pinX = (activeStartX + activeW * pin.fraction).coerceIn(activeStartX + 4.dp.toPx(), w - 4.dp.toPx())
            drawLine(
                color = pinColor,
                start = Offset(pinX, 4.dp.toPx()),
                end = Offset(pinX, h - 4.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
            )
            drawCircle(
                color = pinColor,
                radius = 4.5.dp.toPx(),
                center = Offset(pinX, h / 2f),
            )
        }
    }
}

@Composable
private fun ConsumerPinChip(
    pin: ConsumerPin,
    onClick: () -> Unit,
) {
    val dotColor = when (pin.health) {
        ConsumerPinHealth.CaughtUp -> CaughtUpGreen
        ConsumerPinHealth.Lagging -> LaggingAmber
        ConsumerPinHealth.AtRisk -> MaterialTheme.colorScheme.error
        ConsumerPinHealth.Inactive -> MaterialTheme.colorScheme.outline
    }
    val healthBadge = when (pin.health) {
        ConsumerPinHealth.CaughtUp -> "Caught up"
        ConsumerPinHealth.AtRisk -> "At risk (near cliff)"
        ConsumerPinHealth.Lagging -> pin.statusText
        ConsumerPinHealth.Inactive -> "Standby"
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.5f)),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("logTapeConsumerChip:${pin.key}"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape),
            )
            Text(
                text = pin.label,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "($healthBadge)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
