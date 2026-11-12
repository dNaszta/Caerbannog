package dev.caerbannog.ui.migrations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.caerbannog.core.migrations.ApplyOutcome
import dev.caerbannog.core.migrations.ApplyProgress
import dev.caerbannog.core.migrations.HistoryUnreadable
import dev.caerbannog.core.migrations.InvalidMigrationFile
import dev.caerbannog.core.migrations.MigrationFolderState
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.core.migrations.MigrationsWording
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

// The state banners of docs/ux/Migrations.dc.html (states 1 to 11). Everything here is drawn from structured state; names, paths and reasons
// are passed to Text, which never reads markup.

private enum class BannerTone { Error, Warning, Info }

@Composable
private fun StateBanner(
    tag: String,
    icon: AppIcon,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    body: @Composable ColumnScope.(Color) -> Unit,
) {
    val container = when (tone) {
        BannerTone.Error -> MaterialTheme.colorScheme.errorContainer
        BannerTone.Warning -> MaterialTheme.colorScheme.secondaryContainer
        BannerTone.Info -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (tone) {
        BannerTone.Error -> MaterialTheme.colorScheme.onErrorContainer
        BannerTone.Warning -> MaterialTheme.colorScheme.onSecondaryContainer
        BannerTone.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionIcon(icon, size = IconSize, tint = content)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { body(content) }
        trailing?.invoke()
    }
}

@Composable
private fun PathText(path: String, color: Color, modifier: Modifier = Modifier) {
    Text(text = path, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color, modifier = modifier)
}

@Composable
private fun ActionButtons(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** The path the folder row shows, or null when no folder is chosen or this is the old in-memory mode. */
private fun MigrationFolderState.shownPath(): String? = when (this) {
    MigrationFolderState.ChooseFolder -> null
    is MigrationFolderState.FolderUnavailable -> path
    is MigrationFolderState.FolderReadOnly -> path
    is MigrationFolderState.Ready -> path
}

/** State 1: "Migration files in <path>" with Show folder (only for a folder that exists) and Change folder. */
@Composable
internal fun FolderRow(state: MigrationsScreenState, onShowFolder: (String) -> Unit, onChangeFolder: () -> Unit) {
    val path = state.folder.shownPath() ?: return
    Row(
        modifier = Modifier.fillMaxWidth().testTag("migrations:folderRow"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConnectionIcon(AppIcon.Folder, size = SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Migration files in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PathText(path, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f, fill = false).testTag("migrations:folderPath"))
        MigTip(23)
        Spacer(modifier = Modifier.weight(1f))
        if (state.canShowFolder) {
            TextButton(onClick = { onShowFolder(path) }, modifier = Modifier.testTag("migrations:showFolderBtn")) {
                ConnectionIcon(AppIcon.FolderOpen, size = SmallIconSize)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Show folder")
            }
        }
        TextButton(onClick = onChangeFolder, modifier = Modifier.testTag("migrations:changeFolderBtn")) {
            ConnectionIcon(AppIcon.DriveFileMove, size = SmallIconSize)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Change folder")
        }
    }
}

/** State 2: nothing is read or written until the user clicks; the click is the only way the system dialog opens. */
@Composable
internal fun ChooseFolderPanel(onChooseFolder: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.widthIn(max = 640.dp).testTag("migrations:chooseFolderPanel"),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionIcon(AppIcon.FolderOpen, size = 40.dp, tint = MaterialTheme.colorScheme.primary)
            Text(MigrationsWording.CHOOSE_FOLDER_TITLE, style = MaterialTheme.typography.titleMedium)
            Text(MigrationsWording.CHOOSE_FOLDER_TEXT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onChooseFolder, modifier = Modifier.testTag("migrations:chooseFolderBtn")) {
                ConnectionIcon(AppIcon.FolderOpen, size = SmallIconSize)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Choose folder")
            }
        }
    }
}

/** State 3, 4 and 10 and the other banners that describe where the files and the history are. */
@Composable
internal fun FolderAndHistoryBanners(
    state: MigrationsScreenState,
    onRetry: () -> Unit,
    onChangeFolder: () -> Unit,
    onShowFolder: (String) -> Unit,
    onStartWithEmptyHistory: () -> Unit,
) {
    when (val folder = state.folder) {
        is MigrationFolderState.FolderUnavailable -> {
            StateBanner("migrations:folderUnavailableBanner", AppIcon.Error, BannerTone.Error) { color ->
                Text(folder.reason, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = color)
                PathText(folder.path, color)
                Text("Nothing was created or changed.", style = MaterialTheme.typography.bodySmall, color = color)
            }
            ActionButtons {
                FilledTonalButton(onClick = onRetry, modifier = Modifier.testTag("migrations:retryBtn")) {
                    ConnectionIcon(AppIcon.Refresh, size = SmallIconSize)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Try again")
                }
                OutlinedButton(onClick = onChangeFolder, modifier = Modifier.testTag("migrations:chooseAnotherFolderBtn")) {
                    ConnectionIcon(AppIcon.DriveFileMove, size = SmallIconSize)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Choose another folder")
                }
            }
        }
        is MigrationFolderState.FolderReadOnly ->
            StateBanner("migrations:readOnlyBanner", AppIcon.Warning, BannerTone.Warning) { color ->
                Text(folder.reason, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = color)
                PathText(folder.path, color)
                Text(MigrationsWording.FOLDER_READ_ONLY_HINT, style = MaterialTheme.typography.bodySmall, color = color)
            }
        else -> Unit
    }
    state.historyProblem?.let { problem ->
        HistoryBanner(problem, onRetry, onShowFolder, onStartWithEmptyHistory)
    }
}

@Composable
private fun HistoryBanner(
    problem: HistoryUnreadable,
    onRetry: () -> Unit,
    onShowFolder: (String) -> Unit,
    onStartWithEmptyHistory: () -> Unit,
) {
    StateBanner("migrations:historyBanner", AppIcon.History, BannerTone.Error) { color ->
        Text(MigrationsWording.HISTORY_TITLE, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = color)
        PathText(problem.path, color)
        Text("${problem.reason}. It was not changed.", style = MaterialTheme.typography.bodySmall, color = color)
    }
    ActionButtons {
        FilledTonalButton(onClick = onRetry, modifier = Modifier.testTag("migrations:historyRetryBtn")) {
            ConnectionIcon(AppIcon.Refresh, size = SmallIconSize)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Try again")
        }
        // The adapter opens the parent folder of the history file.
        OutlinedButton(onClick = { onShowFolder(problem.path) }, modifier = Modifier.testTag("migrations:historyShowFolderBtn")) {
            ConnectionIcon(AppIcon.FolderOpen, size = SmallIconSize)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Show folder")
        }
        OutlinedButton(onClick = onStartWithEmptyHistory, modifier = Modifier.testTag("migrations:emptyHistoryBtn")) {
            ConnectionIcon(AppIcon.RestartAlt, size = SmallIconSize)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start with an empty history")
        }
    }
}

/** State 11. */
@Composable
internal fun UnavailableBanner(reason: String) {
    StateBanner("migrations:unavailableBanner", AppIcon.Lock, BannerTone.Error) { color ->
        Text(MigrationsWording.UNAVAILABLE_TITLE, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = color)
        Text(reason, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** State 6: the action being sent, with a progress bar that exposes its range to screen readers. */
@Composable
internal fun ApplyingBanner(progress: ApplyProgress) {
    StateBanner("migrations:applyingBanner", AppIcon.Sync, BannerTone.Info) { color ->
        Text(MigrationsWording.applying(progress), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = color)
        val fraction = if (progress.total > 0) progress.index.toFloat() / progress.total else 0f
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().testTag("migrations:applyProgress"))
    }
}

/** States 7, 8 and 9: how the last apply ended, each in its own banner, all with Dismiss. */
@Composable
internal fun OutcomeBanner(outcome: ApplyOutcome, onDismiss: () -> Unit) {
    val (tag, icon, tone) = when (outcome) {
        is ApplyOutcome.Failed -> Triple("migrations:failedBanner", AppIcon.Error, BannerTone.Error)
        is ApplyOutcome.Conflict -> Triple("migrations:conflictBanner", AppIcon.Warning, BannerTone.Warning)
        is ApplyOutcome.NotRecorded -> Triple("migrations:notRecordedBanner", AppIcon.Warning, BannerTone.Warning)
    }
    StateBanner(
        tag, icon, tone,
        trailing = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("migrations:dismissOutcomeBtn")) { Text("Dismiss") }
        },
    ) { color ->
        Text(MigrationsWording.headline(outcome), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = color)
        Text(MigrationsWording.explanation(outcome), style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** State 5: a file in the folder that is not a valid migration; listed after the valid ones, never selectable. */
@Composable
internal fun InvalidFileRow(file: InvalidMigrationFile) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().testTag("migrationInvalid:${file.fileName}"),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(file.fileName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Pill("Invalid", Color(0xFFFFEBEE), Color(0xFFC62828), AppIcon.Error)
            }
            Text(file.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun Pill(label: String, background: Color, textColor: Color, icon: AppIcon? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(background).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) ConnectionIcon(icon, size = 12.dp, tint = textColor)
        Text(text = label, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** The dialog of "Start with an empty history": Cancel, Esc and a click outside all call [onCancel]; nothing moves until [onConfirm]. */
@Composable
internal fun EmptyHistoryDialog(question: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.width(520.dp).padding(16.dp).testTag("migrations:emptyHistoryDialog"),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Start with an empty history?", style = MaterialTheme.typography.titleLarge)
                Text(question, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Every migration then shows as pending for every connection. Running one again is safe because each action checks what is already there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel, modifier = Modifier.testTag("migrations:emptyHistoryCancelBtn")) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onConfirm, modifier = Modifier.testTag("migrations:emptyHistoryConfirmBtn")) { Text("Move file and start") }
                }
            }
        }
    }
}
