package dev.caerbannog.ui.planner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.planner.PlanGraph
import dev.caerbannog.core.planner.PlanGraphNotice
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.ReviewView
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.SmallIconSize
import dev.caerbannog.ui.topology.BindingGraphCanvas

/**
 * The review graph: what the broker will have after the plan, read only. Every text, mark and name comes from [graph]; the canvas is the one
 * of the Bindings graph. A graph too large to draw is replaced by its notice (the step list stays the review); "only the plan" is a line above.
 */
@Composable
internal fun PlanReviewGraph(graph: PlanGraph, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        graph.notice?.let { GraphNotice(it) }
        if (graph.notice != PlanGraphNotice.TooLarge) {
            GraphLegend()
            BindingGraphCanvas(
                graph = graph.graph,
                plan = graph,
                size = Modifier.fillMaxWidth().heightIn(min = 160.dp).weight(1f, fill = false),
                containerTag = "planner:reviewGraph",
            )
        }
    }
}

@Composable
private fun GraphNotice(notice: PlanGraphNotice) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("planner:graphNotice"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ConnectionIcon(AppIcon.Info, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(notice.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(notice.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GraphLegend() {
    FlowRow(
        Modifier.fillMaxWidth().testTag("planner:graphLegend"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PlannerTexts.GRAPH_LEGEND.forEachIndexed { index, entry ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LegendSwatch(index)
                Text(entry, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A sample of the outline an entry talks about: thick, thin or dotted, in the colours the nodes use. */
@Composable
private fun LegendSwatch(index: Int) {
    val shape = RoundedCornerShape(3.dp)
    val color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val base = Modifier.size(width = 22.dp, height = 14.dp)
    when (index) {
        0 -> Box(base.border(BorderStroke(3.dp, color), shape))
        1 -> Box(base.border(BorderStroke(1.dp, color), shape))
        else -> Box(
            base.drawBehind {
                val width = 1.5.dp.toPx()
                drawRoundRect(
                    color = color,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                    style = Stroke(width = width, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, width * 2.5f))),
                )
            },
        )
    }
}

/** The Graph and JSON switch of the review card: two radios, one choice. A choice of view only; nothing in the plan changes. */
@Composable
internal fun ReviewViewSwitch(current: ReviewView, onSelect: (ReviewView) -> Unit) {
    Row(Modifier.selectableGroup().testTag("planner:reviewViewSwitch"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ReviewView.entries.forEach { view ->
            val on = view == current
            val bg = if (on) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
            val fg = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .testTag("planner:reviewViewSwitch:${view.name}")
                    .background(bg, RoundedCornerShape(6.dp))
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(view) })
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (view == ReviewView.Graph) "Graph" else "JSON", style = MaterialTheme.typography.labelMedium, color = fg)
            }
        }
    }
}
