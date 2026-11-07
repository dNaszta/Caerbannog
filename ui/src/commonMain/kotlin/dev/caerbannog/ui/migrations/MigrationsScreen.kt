package dev.caerbannog.ui.migrations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

@Composable
fun MigrationsScreen(
    state: MigrationsScreenState,
    onSelectVersion: (String) -> Unit,
    onApplyPending: () -> Unit,
    onApplySingle: (String) -> Unit,
    onImportJson: (String) -> Unit,
    onExportTopology: () -> Unit,
    onUpdateFile: (String, String) -> Unit,
    onValidateAgain: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showImportDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // App Bar Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Migrations",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.testTag("migrations:title"),
            )

            Spacer(modifier = Modifier.weight(1f))

            OutlinedButton(
                onClick = { showImportDialog = true },
                modifier = Modifier.testTag("migrations:importBtn"),
            ) {
                ConnectionIcon(AppIcon.UploadFile, size = SmallIconSize)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import JSON")
            }

            OutlinedButton(
                onClick = onExportTopology,
                modifier = Modifier.testTag("migrations:exportBtn"),
            ) {
                ConnectionIcon(AppIcon.Download, size = SmallIconSize)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export current topology")
            }

            Button(
                onClick = onApplyPending,
                enabled = state.isApplyPendingEnabled && !state.isApplying,
                modifier = Modifier.testTag("migrations:applyPendingBtn"),
            ) {
                ConnectionIcon(AppIcon.PlayArrow, size = SmallIconSize)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Apply pending")
            }
        }

        // Subheader Info
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionIcon(AppIcon.Info, size = SmallIconSize, tint = Color.Gray)
            Text(
                text = "Applied versions are tracked on this machine, per connection profile. Every action must be safe to run twice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Error Banner
        val errorMessage = state.errorMessage
        if (errorMessage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFFEBEE))
                    .padding(12.dp)
                    .testTag("migrations:errorBanner"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConnectionIcon(AppIcon.Error, tint = Color(0xFFC62828), size = IconSize)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = errorMessage,
                    color = Color(0xFFC62828),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissError) {
                    Text("Dismiss", color = Color(0xFFC62828))
                }
            }
        }

        // Warning Banner (e.g. modified migration on disk)
        val warningMessage = state.warningMessage
        if (warningMessage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFFF3E0))
                    .padding(12.dp)
                    .testTag("migrations:warningBanner"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConnectionIcon(AppIcon.Warning, tint = Color(0xFFE65100), size = IconSize)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = warningMessage,
                    color = Color(0xFFE65100),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Main Content Area: Left file list + Right detail
        val selectedFile = state.selectedFile
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Left Column: Migration files list
            Column(
                modifier = Modifier
                    .width(340.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Migration files",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                if (state.entries.isEmpty()) {
                    Text(
                        text = "No migration files found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(8.dp),
                    )
                }

                for (entry in state.entries) {
                    MigrationFileCard(
                        entry = entry,
                        isSelected = entry.file.version == state.selectedVersion,
                        onClick = { onSelectVersion(entry.file.version) },
                    )
                }
            }

            // Right Column: Selected file detail & dry run
            if (selectedFile != null) {
                MigrationDetailPanel(
                    file = selectedFile,
                    state = state,
                    onEdit = { showEditDialog = true },
                    onValidateAgain = onValidateAgain,
                    onApply = { onApplySingle(selectedFile.version) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            } else {
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Select a migration file to inspect details.",
                        color = Color.Gray,
                    )
                }
            }
        }
    }

    // Import Dialog
    if (showImportDialog) {
        ImportMigrationDialog(
            onDismiss = { showImportDialog = false },
            onImport = { rawJson ->
                onImportJson(rawJson)
                showImportDialog = false
            },
        )
    }

    // Edit Dialog
    val currentSelected = state.selectedFile
    if (showEditDialog && currentSelected != null) {
        EditMigrationDialog(
            file = currentSelected,
            onDismiss = { showEditDialog = false },
            onSave = { newJson ->
                onUpdateFile(currentSelected.version, newJson)
                showEditDialog = false
            },
        )
    }
}

@Composable
private fun MigrationFileCard(
    entry: MigrationEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("migrationCard:${entry.file.version}"),
        color = bgColor,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = entry.file.version,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                StatusPill(entry.status)
            }
            Text(
                text = entry.file.description.ifEmpty { "No description" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusPill(status: MigrationStatus) {
    val (bgColor, textColor, label, icon) = when (status) {
        is MigrationStatus.Applied -> Quadruple(
            Color(0xFFE8F5E9),
            Color(0xFF2E7D32),
            "Applied",
            AppIcon.Check,
        )
        is MigrationStatus.Pending -> Quadruple(
            Color(0xFFFFF3E0),
            Color(0xFFE65100),
            "Pending",
            null,
        )
        is MigrationStatus.Modified -> Quadruple(
            Color(0xFFFFEBEE),
            Color(0xFFC62828),
            "Modified",
            AppIcon.Warning,
        )
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("migrationStatus:$label"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            ConnectionIcon(icon, size = 12.dp, tint = textColor)
        }
        Text(text = label, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun MigrationDetailPanel(
    file: MigrationFile,
    state: MigrationsScreenState,
    onEdit: () -> Unit,
    onValidateAgain: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Text(
                text = "${file.version} · ${file.description.ifEmpty { "Migration" }}",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.testTag("migrationDetail:header"),
            )

            // Raw JSON code block
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF212121))
                    .padding(12.dp),
            ) {
                Text(
                    text = file.rawJson,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFECEFF1),
                    modifier = Modifier.testTag("migrationDetail:rawJson"),
                )

                OutlinedButton(
                    onClick = { clipboardManager.setText(AnnotatedString(file.rawJson)) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .testTag("migrations:copyJsonBtn"),
                ) {
                    ConnectionIcon(AppIcon.ContentCopy, size = SmallIconSize, tint = Color.White)
                }
            }

            // Dry Run Title
            Text(
                text = "Dry run on ${state.profileName}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Dry run action items
            val dryRun = state.dryRun
            if (dryRun != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dryRun.actions.forEachIndexed { index, actionDryRun ->
                        DryRunActionRow(actionDryRun, index)
                    }
                }
            }

            // Locked banner if configure permission missing
            val bannerMessage = state.bannerMessage
            if (bannerMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFFEBEE))
                        .padding(12.dp)
                        .testTag("migrations:lockedBanner"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConnectionIcon(AppIcon.Lock, tint = Color(0xFFC62828), size = IconSize)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = bannerMessage,
                        color = Color(0xFFC62828),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("migrations:editFileBtn"),
                ) {
                    ConnectionIcon(AppIcon.Edit, size = SmallIconSize)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Edit file")
                }

                Spacer(modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = onValidateAgain,
                    modifier = Modifier.testTag("migrations:validateBtn"),
                ) {
                    ConnectionIcon(AppIcon.Rule, size = SmallIconSize)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Validate again")
                }

                Button(
                    onClick = onApply,
                    enabled = state.isApplySingleEnabled && !state.isApplying,
                    modifier = Modifier.testTag("migrations:applySingleBtn"),
                ) {
                    Text("Apply ${file.version}")
                }
            }
        }
    }
}

@Composable
private fun DryRunActionRow(
    actionDryRun: ActionDryRun,
    index: Int,
) {
    val isAllowed = actionDryRun.allowed
    val (icon, iconTint, pillText, pillBg, pillColor) = if (isAllowed) {
        Quintup(AppIcon.CheckCircle, Color(0xFF2E7D32), "Allowed", Color(0xFFE8F5E9), Color(0xFF2E7D32))
    } else {
        Quintup(AppIcon.Block, Color(0xFFC62828), "Blocked", Color(0xFFFFEBEE), Color(0xFFC62828))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("dryRunAction:$index"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionIcon(icon, size = IconSize, tint = iconTint)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = actionDryRun.action.description,
                style = MaterialTheme.typography.bodyMedium,
            )
            val reason = actionDryRun.reason
            if (reason != null) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC62828),
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(pillBg)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(text = pillText, color = pillColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private data class Quintup<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

@Composable
private fun ImportMigrationDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var rawText by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.width(520.dp).padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Import Migration JSON",
                    style = MaterialTheme.typography.titleLarge,
                )

                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    label = { Text("Paste migration JSON content") },
                    modifier = Modifier.fillMaxWidth().height(220.dp).testTag("importMigration:input"),
                    maxLines = 15,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onImport(rawText) },
                        enabled = rawText.isNotBlank(),
                        modifier = Modifier.testTag("importMigration:confirmBtn"),
                    ) {
                        Text("Import")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditMigrationDialog(
    file: MigrationFile,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var rawText by remember { mutableStateOf(file.rawJson) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.width(560.dp).padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Edit Migration ${file.version}",
                    style = MaterialTheme.typography.titleLarge,
                )

                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    label = { Text("Migration JSON") },
                    modifier = Modifier.fillMaxWidth().height(260.dp).testTag("editMigration:input"),
                    maxLines = 20,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(rawText) },
                        enabled = rawText.isNotBlank(),
                        modifier = Modifier.testTag("editMigration:saveBtn"),
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
