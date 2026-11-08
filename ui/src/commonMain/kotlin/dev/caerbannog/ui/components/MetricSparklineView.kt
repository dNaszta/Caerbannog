package dev.caerbannog.ui.components

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.streams.MetricSparkline
import dev.caerbannog.core.streams.SparklineSemantic
import dev.caerbannog.core.streams.TrendDirection

private val LagDrainingGreen = Color(0xFF386A20)
private val LagGrowingAmber = Color(0xFFB36B00)

/**
 * Compact zero-cost rolling sparkline and trend badge (`[📈 +45/s]` or `[↘ Draining (-120)]`).
 * Always wrapped in [DisableSelection] so table cell copy-on-drag (`SelectionContainer`) only ever copies the cell's text.
 */
@Composable
fun MetricSparklineBadge(
    sparkline: MetricSparkline,
    tag: String,
    modifier: Modifier = Modifier,
    canvasWidth: Dp = 40.dp,
    canvasHeight: Dp = 14.dp,
) {
    val accentColor = sparklineColor(sparkline)
    DisableSelection {
        Row(
            modifier = modifier
                .testTag(tag)
                .semantics { contentDescription = sparkline.label }
                .background(accentColor.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MetricSparklineCanvas(
                sparkline = sparkline,
                color = accentColor,
                modifier = Modifier
                    .width(canvasWidth)
                    .height(canvasHeight)
                    .testTag("$tag:canvas"),
            )
            Text(
                text = sparkline.label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = accentColor,
                maxLines = 1,
                modifier = Modifier.testTag("$tag:label"),
            )
        }
    }
}

@Composable
fun MetricSparklineCanvas(
    sparkline: MetricSparkline,
    color: Color = sparklineColor(sparkline),
    modifier: Modifier = Modifier.width(40.dp).height(14.dp),
) {
    Canvas(modifier = modifier) {
        val pts = sparkline.normalizedPoints
        if (pts.size < 2) return@Canvas

        val padX = 2.dp.toPx()
        val padY = 2.dp.toPx()
        val usableW = (size.width - padX * 2f).coerceAtLeast(1f)
        val usableH = (size.height - padY * 2f).coerceAtLeast(1f)

        fun pointAt(index: Int): Offset {
            val x = padX + (index.toFloat() / (pts.size - 1).toFloat()) * usableW
            val y = padY + (1f - pts[index].coerceIn(0f, 1f)) * usableH
            return Offset(x, y)
        }

        val linePath = Path()
        val fillPath = Path()
        val first = pointAt(0)
        linePath.moveTo(first.x, first.y)
        fillPath.moveTo(first.x, size.height - padY)
        fillPath.lineTo(first.x, first.y)

        for (i in 1 until pts.size) {
            val p = pointAt(i)
            linePath.lineTo(p.x, p.y)
            fillPath.lineTo(p.x, p.y)
        }
        val last = pointAt(pts.lastIndex)
        fillPath.lineTo(last.x, size.height - padY)
        fillPath.close()

        drawPath(
            path = fillPath,
            color = color.copy(alpha = 0.16f),
        )
        drawPath(
            path = linePath,
            color = color,
            style = Stroke(
                width = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
        drawCircle(
            color = color,
            radius = 2.dp.toPx(),
            center = last,
        )
    }
}

@Composable
private fun sparklineColor(sparkline: MetricSparkline): Color = when (sparkline.semantic) {
    SparklineSemantic.ConsumerLag -> when (sparkline.direction) {
        TrendDirection.Draining -> LagDrainingGreen
        TrendDirection.Growing -> LagGrowingAmber
        TrendDirection.Steady -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    SparklineSemantic.Velocity -> when (sparkline.direction) {
        TrendDirection.Growing -> MaterialTheme.colorScheme.primary
        TrendDirection.Draining, TrendDirection.Steady -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
