package dev.caerbannog.ui.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.planner.CheckState
import dev.caerbannog.core.planner.PlannerState
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.StepCheck
import dev.caerbannog.core.planner.StepFinding
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.migrations.Pill
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CHECK_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** The time of a check as the summary words it. `core` has no time zone, so the screen formats it. */
private fun checkTime(millis: Long): String = CHECK_TIME_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/**
 * The one banner of the check, above the list and the review card: progress while it runs, or the summary of the result and, below it,
 * the stale line when the plan changed. Nothing here sends anything but the one "check" event.
 */
@Composable
internal fun CheckBanner(state: PlannerState, events: PlannerEvents) {
    when (val check = state.check) {
        CheckState.None -> Unit
        is CheckState.Running -> BannerRow(
            icon = AppIcon.Sync,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("planner:checkProgress"),
        ) {
            Text(PlannerTexts.checkProgress(check.step, check.total, state.connectionName.orEmpty()), style = MaterialTheme.typography.bodySmall)
        }
        is CheckState.Done -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BannerRow(
                icon = AppIcon.FactCheck,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.testTag("planner:checkSummary"),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(PlannerTexts.checkSummary(check.connectionName, checkTime(check.atMillis), check.results), style = MaterialTheme.typography.bodySmall)
                    Text(PlannerTexts.CHECK_CAVEAT, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (check.stale) {
                BannerRow(
                    icon = AppIcon.Refresh,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.testTag("planner:checkStale"),
                    trailing = {
                        TextButton(onClick = events::check, enabled = state.checkEnabled, modifier = Modifier.testTag("planner:checkAgainBtn")) {
                            Text(PlannerTexts.CHECK_AGAIN, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    },
                ) {
                    Text(PlannerTexts.CHECK_STALE, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BannerRow(
    icon: AppIcon,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    text: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(container).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionIcon(icon, IconSize, tint = content)
        Row(Modifier.weight(1f)) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides content) { text() }
        }
        trailing()
    }
}

/**
 * The pills at the right of a step row: the result of the check, and "Blocked" beside it when the dry run refuses the step. A result that is
 * stale stays, dimmed, and says so to a screen reader.
 */
@Composable
internal fun StepCheckPills(index: Int, result: StepCheck, stale: Boolean) {
    val look = lookOf(result.finding)
    val dim = if (stale) Modifier.alpha(STALE_ALPHA) else Modifier
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(
            label = PlannerTexts.resultLabel(result.finding),
            background = look.background(),
            textColor = look.content(),
            icon = look.icon,
            modifier = Modifier.testTag("planStepResult:$index").then(dim).semantics { if (stale) stateDescription = "stale" },
        )
        if (result.blocked != null) {
            Pill(
                label = PlannerTexts.BLOCKED_PILL,
                background = MaterialTheme.colorScheme.errorContainer,
                textColor = MaterialTheme.colorScheme.onErrorContainer,
                icon = AppIcon.Block,
                modifier = Modifier.testTag("planStepBlocked:$index").then(dim),
            )
        }
    }
}

/** The reason line(s) of a step: the dry run's reason, the finding's sentence, the notes. Drawn only when there is one. */
@Composable
internal fun StepCheckReasons(index: Int, result: StepCheck, stale: Boolean) {
    val lines = result.reasons
    if (lines.isEmpty()) return
    val color = if (result.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.testTag("planStepReason:$index").then(if (stale) Modifier.alpha(STALE_ALPHA) else Modifier), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
    }
}

private const val STALE_ALPHA = 0.5f

private class PillLook(val icon: AppIcon?, val background: @Composable () -> Color, val content: @Composable () -> Color)

private fun lookOf(finding: StepFinding): PillLook = when (finding) {
    StepFinding.WillBeCreated -> PillLook(null, { MaterialTheme.colorScheme.secondaryContainer }, { MaterialTheme.colorScheme.onSecondaryContainer })
    StepFinding.Different -> PillLook(AppIcon.Error, { MaterialTheme.colorScheme.errorContainer }, { MaterialTheme.colorScheme.onErrorContainer })
    is StepFinding.Missing -> PillLook(AppIcon.LinkOff, { MaterialTheme.colorScheme.errorContainer }, { MaterialTheme.colorScheme.onErrorContainer })
    is StepFinding.NotChecked -> PillLook(AppIcon.Help, { MaterialTheme.colorScheme.tertiaryContainer }, { MaterialTheme.colorScheme.onTertiaryContainer })
    StepFinding.AlreadyThere, StepFinding.WillBeDeleted, StepFinding.AlreadyGone ->
        PillLook(null, { MaterialTheme.colorScheme.surfaceVariant }, { MaterialTheme.colorScheme.onSurfaceVariant })
}
