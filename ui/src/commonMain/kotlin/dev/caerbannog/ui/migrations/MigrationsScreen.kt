package dev.caerbannog.ui.migrations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.caerbannog.core.help.HowToTexts
import dev.caerbannog.ui.components.HelpTip
import dev.caerbannog.ui.components.HowToLink
import dev.caerbannog.ui.components.TopLineWithHowTo
import dev.caerbannog.ui.components.rememberCopyToClipboard
import dev.caerbannog.ui.migrate3x.Advice
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationFolderState
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationsWording
import dev.caerbannog.core.migrations.redactedRaw
import dev.caerbannog.core.mqtt.CorrelationIdSource
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.MqttRoute
import dev.caerbannog.core.planner.MqttRoutes
import dev.caerbannog.core.planner.MqttTestMessage
import dev.caerbannog.core.planner.MqttTestMessageTexts
import dev.caerbannog.core.planner.MqttTopicSample
import dev.caerbannog.core.planner.TestMessageOffer
import dev.caerbannog.core.planner.TestMessageOpening
import dev.caerbannog.core.planner.PlanOpeningDecision
import dev.caerbannog.core.planner.PlannerEntry
import dev.caerbannog.core.planner.PlannerQuestion
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.appframe.AppFrameLayout
import dev.caerbannog.ui.appframe.ConnectionControl
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize

/** Compact buttons: five of them and the connection chip share one line at 1280 px. */
private val BAR_BUTTON_PADDING = PaddingValues(horizontal = 6.dp, vertical = 8.dp)

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
    onChooseFolder: () -> Unit = {},
    onChangeFolder: () -> Unit = {},
    onRetry: () -> Unit = {},
    onShowFolder: (String) -> Unit = {},
    onStartWithEmptyHistory: () -> Unit = {},
    onConfirmEmptyHistory: () -> Unit = {},
    onCancelEmptyHistory: () -> Unit = {},
    onDismissOutcome: () -> Unit = {},
    control: ConnectionControlState? = null,
    controlEvents: ConnectionControlEvents? = null,
    onNavigate: (AppDestination) -> Unit = {},
    /** What the draft of the planner offers: "New plan" or "Continue plan" with a card, or "Open planner" for a draft that cannot be read (M9-03). */
    plannerEntry: PlannerEntry = PlannerEntry.NewPlan,
    onOpenPlanner: () -> Unit = {},
    /** Called after the user confirmed the question "Dismiss this plan?" on the draft card. */
    onDismissDraft: () -> Unit = {},
    /** "Plan saved as ...", set by the window after a save; transient, not part of the migrations state. */
    plannerNotice: String? = null,
    onDismissPlannerNotice: () -> Unit = {},
    /** M9-10b: called with what the Publish MQTT dialog is to open with, after "Send a test message" (and its sample values) of an MQTT route. */
    onSendTestMessage: (TestMessageOpening) -> Unit = {},
    /** Where the generated `correlation-id` of a test message comes from; the window passes a UUID source. */
    correlationIds: CorrelationIdSource = CorrelationIdSource { "" },
    /** M9-12: "Edit steps" of the selected file; called with its version. */
    onEditSteps: (String) -> Unit = {},
    /** M9-12: "Copy into a new plan" of a file that is applied elsewhere; called with its version, after the question when a draft with steps exists. */
    onCopyIntoNewPlan: (String) -> Unit = {},
) {
    var copyAsk by remember { mutableStateOf<String?>(null) }
    var sampleFor by remember { mutableStateOf<MqttRoute?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var askDismissDraft by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    AppFrameLayout(
        title = AppDestination.Migrations.name,
        modifier = modifier,
        onNavigate = onNavigate,
        actions = {
            // M9-03: the way into the planner is the first button.
            Button(
                onClick = onOpenPlanner,
                modifier = Modifier.testTag("migrations:newPlanBtn"),
                contentPadding = BAR_BUTTON_PADDING,
            ) {
                Text(plannerEntry.label)
            }

            // M8-06a, owner decision 1: the second entry to the migration from 3.x (the first is the drawer item).
            OutlinedButton(
                onClick = { onNavigate(AppDestination.MigrateFromThree) },
                modifier = Modifier.testTag("migrations:migrate3xBtn"),
                contentPadding = BAR_BUTTON_PADDING,
            ) {
                Text(AppDestination.MigrateFromThree.label)
            }

            OutlinedButton(
                onClick = { showImportDialog = true },
                enabled = state.canImport,
                modifier = Modifier.testTag("migrations:importBtn"),
                contentPadding = BAR_BUTTON_PADDING,
            ) {
                Text("Import JSON")
            }

            OutlinedButton(
                onClick = onExportTopology,
                enabled = state.canExport,
                modifier = Modifier.testTag("migrations:exportBtn"),
                contentPadding = BAR_BUTTON_PADDING,
            ) {
                Text("Export current topology")
            }

            Button(
                onClick = onApplyPending,
                enabled = state.canApplyPending,
                modifier = Modifier.testTag("migrations:applyPendingBtn"),
                contentPadding = BAR_BUTTON_PADDING,
            ) {
                Text("Apply pending")
            }
        },
        chip = if (control != null && controlEvents != null) ({ ConnectionControl(control, controlEvents) }) else null,
    ) {
        // The page measures the width its content is given (window minus the drawer of the frame) and stacks under 672 dp (M2-21b).
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val stacked = migrationsLayoutFor(maxWidth) == MigrationsLayout.Stacked
            val pageScroll = rememberScrollState()
            val listMaxHeight = stackedListMaxHeight(maxHeight)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Only a stacked page scrolls as a whole (CLAUDE.md pitfall: no weight or fillMaxHeight below a scroll); side by side the list and the detail scroll on their own.
                    .then(if (stacked) Modifier.verticalScroll(pageScroll) else Modifier)
                    .padding(horizontal = 24.dp, vertical = 8.dp), // 8 dp: the guidance line needs the height at 1024x768
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Subheader Info
                // The info line takes the full width (one line); the advice and the "How to" link share the next line, so the added guidance costs little height.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.testTag("migrations:title"),
                    ) {
                        ConnectionIcon(AppIcon.Info, size = SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "Applied versions are tracked on this machine, per connection profile. Every action must be safe to run twice.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TopLineWithHowTo(link = { HowToLink(HowToTexts.APPLY_MIGRATIONS) }) {
                        Advice(MigrationsWording.ADVICE_TOP, "migrations-advice-top", Modifier.weight(1f))
                    }
                }

                plannerNotice?.let { PlannerNoticeBanner(it, onDismissPlannerNotice) }

                FolderRow(state, onShowFolder, onChangeFolder)

                // State banners: where the files and the history are, what applying is doing and how it ended.
                state.unavailableReason?.let { UnavailableBanner(it) }
                FolderAndHistoryBanners(state, onRetry, onChangeFolder, onShowFolder, onStartWithEmptyHistory)
                state.applyProgress?.let { ApplyingBanner(it) }
                state.applyOutcome?.let {
                    OutcomeBanner(it, onDismissOutcome)
                    MigrationsWording.applyAdvice(it)?.let { advice -> Advice(advice, "migrations-advice-outcome") }
                }

                // Error Banner
                val errorMessage = state.errorMessage
                if (errorMessage != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(12.dp)
                            .semantics(mergeDescendants = true) {}
                            .testTag("migrations:errorBanner"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ConnectionIcon(AppIcon.Error, tint = MaterialTheme.colorScheme.onErrorContainer, size = IconSize)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissError) {
                            Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer)
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
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(12.dp)
                            .testTag("migrations:warningBanner"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ConnectionIcon(AppIcon.Warning, tint = MaterialTheme.colorScheme.onSecondaryContainer, size = IconSize)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = warningMessage,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // M9-14: what the last export could not carry (null when it lost nothing).
                state.exportNote?.let { Advice(it, "migrations-export-note") }

                // Main Content Area: Left file list + Right detail
                val selectedFile = state.selectedFile
                val draftCard: @Composable () -> Unit = {
                    DraftCard(
                        entry = plannerEntry,
                        onContinue = onOpenPlanner,
                        onDismiss = { askDismissDraft = true },
                    )
                }
                // The same list and detail in both layouts; only the modifiers differ (height rules below a scroll, weights in a Row).
                val fileList: @Composable (Modifier) -> Unit = { listModifier ->
                    Column(
                        modifier = listModifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        draftCard()
                        Text(
                            text = "Migration files",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )

                        if (state.entries.isEmpty() && state.invalidFiles.isEmpty()) {
                            Text(
                                text = "No migration files found.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

                        for (invalid in state.invalidFiles) InvalidFileRow(invalid)
                    }
                }
                val detail: @Composable (Modifier, Boolean) -> Unit = { detailModifier, stackedPanel ->
                    if (selectedFile != null) {
                        MigrationDetailPanel(
                            file = selectedFile,
                            state = state,
                            onEdit = { showEditDialog = true },
                            plannerEntry = plannerEntry,
                            onEditSteps = { onEditSteps(selectedFile.version) },
                            onCopyIntoNewPlan = {
                                // A draft with steps is never replaced without the question; one with only a description is not worth asking about.
                                if ((plannerEntry as? PlannerEntry.ContinuePlan)?.stepCount?.let { it > 0 } == true) copyAsk = selectedFile.version
                                else onCopyIntoNewPlan(selectedFile.version)
                            },
                            onValidateAgain = onValidateAgain,
                            onApply = { onApplySingle(selectedFile.version) },
                            onTestMessage = { route ->
                                // A route without wildcards needs no sample value: the Publish MQTT dialog opens at once.
                                if (MqttTopicSample.slots(route.filter).isEmpty()) {
                                    onSendTestMessage(testMessageOpening(route, selectedFile, emptyList(), correlationIds))
                                } else {
                                    sampleFor = route
                                }
                            },
                            modifier = detailModifier,
                            stacked = stackedPanel,
                        )
                    } else {
                        Box(
                            modifier = detailModifier,
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Select a migration file to inspect details.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (state.folder is MigrationFolderState.ChooseFolder) {
                    draftCard()
                    ChooseFolderPanel(onChooseFolder)
                } else if (stacked) {
                    fileList(Modifier.fillMaxWidth().heightIn(max = listMaxHeight).testTag("migrations:fileList"))
                    detail(Modifier.fillMaxWidth().heightIn(min = STACKED_DETAIL_MIN_HEIGHT), true)
                } else Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(MIGRATION_PAGE_GAP),
                ) {
                    fileList(Modifier.width(MIGRATION_LIST_WIDTH).fillMaxHeight())
                    detail(Modifier.weight(1f).fillMaxHeight(), false)
                }
            }
            if (stacked) VerticalScrollbar(rememberScrollbarAdapter(pageScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("migrations:pageScrollbar"))
        }
    }

    if (askDismissDraft && plannerEntry is PlannerEntry.ContinuePlan) {
        DismissDraftDialog(
            question = PlannerQuestion.Dismiss(plannerEntry.stepCount),
            onCancel = { askDismissDraft = false },
            onConfirm = {
                askDismissDraft = false
                onDismissDraft()
            },
        )
    }

    copyAsk?.let { version ->
        DismissDraftDialog(
            question = PlannerQuestion.CopyOverDraft(version),
            onCancel = { copyAsk = null },
            onConfirm = {
                copyAsk = null
                onCopyIntoNewPlan(version)
            },
        )
    }

    val sampleRoute = sampleFor
    val sampleFile = state.selectedFile
    if (sampleRoute != null && sampleFile != null) {
        Dialog(onDismissRequest = { sampleFor = null }) {
            MqttTestMessageDialog(
                filter = sampleRoute.filter,
                onOpen = { values ->
                    sampleFor = null
                    onSendTestMessage(testMessageOpening(sampleRoute, sampleFile, values, correlationIds))
                },
                onCancel = { sampleFor = null },
            )
        }
    }

    state.confirmation?.let { EmptyHistoryDialog(it, onCancelEmptyHistory, onConfirmEmptyHistory) }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(entry.status)
                    if (entry.status is MigrationStatus.Modified) MigTip(26)
                }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MigrationDetailPanel(
    file: MigrationFile,
    state: MigrationsScreenState,
    onEdit: () -> Unit,
    plannerEntry: PlannerEntry,
    onEditSteps: () -> Unit,
    onCopyIntoNewPlan: () -> Unit,
    onValidateAgain: () -> Unit,
    onApply: () -> Unit,
    onTestMessage: (MqttRoute) -> Unit,
    modifier: Modifier = Modifier,
    /** Stacked page (M2-21b): the page scrolls, so the panel takes its natural height and neither scrolls itself nor fills or weights its height. */
    stacked: Boolean = false,
) {
    val copyToClipboard = rememberCopyToClipboard()

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(16.dp)
            .testTag("migrationDetail:panel"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = if (stacked) Modifier.fillMaxWidth() else Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
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
                    text = redactedRaw(file.rawJson), // D4: a password hash is never shown; the file keeps it
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFECEFF1),
                    modifier = Modifier.testTag("migrationDetail:rawJson"),
                )

                OutlinedButton(
                    onClick = { copyToClipboard(redactedRaw(file.rawJson)) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .testTag("migrations:copyJsonBtn"),
                ) {
                    ConnectionIcon(AppIcon.ContentCopy, size = SmallIconSize, tint = Color.White)
                }
            }

            // Dry Run Title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Dry run on ${state.profileName}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MigTip(24)
            }

            // Dry run action items
            val dryRun = state.dryRun
            if (dryRun != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val failedIndex = MigrationsWording.failedActionIndex(state.applyOutcome, file.version)
                    // Applied means applied on THIS connection profile: the entry's status is read from this profile's history.
                    val status = state.entries.firstOrNull { it.file.version == file.version }?.status
                    dryRun.actions.forEachIndexed { index, actionDryRun ->
                        val route = MqttRoutes.routeOf(actionDryRun.action)
                        val offer = if (route == null || status == null) {
                            TestMessageOffer.NotOffered
                        } else {
                            MqttTestMessage.offer(status, route, state.hasMqtt, state.publishingEnabled, state.vhost)
                        }
                        DryRunActionRow(
                            actionDryRun, index, failed = index == failedIndex, siblings = dryRun.actions.map { it.action },
                            testMessage = offer,
                            onTestMessage = { route?.let(onTestMessage) },
                        )
                    }
                }
            }

            // Locked banner if configure permission missing
            // The unavailable banner (drawn above) wins over this one.
            val bannerMessage = state.bannerMessage?.takeIf { state.unavailableReason == null }
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

            // The raw editor would draw the real hash (decision B of M8-12a): no Edit button, a fixed text instead.
            val holdsHash = state.selectedFile?.up?.any { it is MigrationAction.DeclareUser } == true
            if (holdsHash) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = MigrationsWording.HASH_FILE_NO_EDIT,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("migrations:hashFileNoEdit"),
                    )
                    MigTip(27)
                }
            }

            // Why the file is not opened as steps, in place of "Edit steps" (the hash text above is the same case for a file with a password hash).
            val refusal = state.planOpening as? PlanOpeningDecision.NotOffered
            if (refusal != null && !holdsHash) PlanNotOffered(refusal, plannerEntry, onCopyIntoNewPlan)

            // A weight needs a bounded height; the stacked panel has its natural height.
            if (!stacked) Spacer(modifier = Modifier.weight(1f))

            // Action row. A FlowRow, not a Row: at font scale 1.5 the four buttons need more than the detail panel is wide, and a Row squeezed the last ones to
            // zero width (M2-21 D2). Every button keeps its natural width; the weighted spacer pushes Validate and Apply to the right, or starts their line when it wraps.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                itemVerticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!holdsHash && refusal == null) {
                    FilledTonalButton(
                        onClick = onEditSteps,
                        enabled = state.canEditFile,
                        modifier = Modifier.testTag("migrations:editStepsBtn"),
                    ) {
                        ConnectionIcon(AppIcon.Edit, size = SmallIconSize)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Edit steps")
                    }
                }
                if (!holdsHash) {
                    TextButton(
                        onClick = onEdit,
                        enabled = state.canEditFile,
                        modifier = Modifier.testTag("migrations:editFileBtn"),
                    ) {
                        ConnectionIcon(AppIcon.Edit, size = SmallIconSize)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Edit file")
                    }
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
                    enabled = state.canApplySingle,
                    modifier = Modifier.testTag("migrations:applySingleBtn"),
                ) {
                    Text("Apply ${file.version}")
                }
            }
        }
    }
}

/**
 * The fixed text that stands in place of "Edit steps" (M9-12), with its second line and, for a file applied elsewhere, "Copy into a new plan". The
 * button stays visible but disabled while the draft file cannot be read (a copy would write over it), with the reason under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanNotOffered(refusal: PlanOpeningDecision.NotOffered, plannerEntry: PlannerEntry, onCopy: () -> Unit) {
    // A FlowRow: at font scale 1.5 the copy button is about 300 px wide and a Row left the reason 140 px; the button now drops under the text instead
    // (M2-21b), and the text keeps at least 200 dp to read.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f).widthIn(min = 200.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(refusal.reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("migrations:planNotOffered"))
            refusal.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("migrations:planNotOfferedDetail"),
                )
            }
            if (refusal.canCopy && plannerEntry is PlannerEntry.DraftUnreadable) {
                Text(
                    PlannerTexts.COPY_BLOCKED_BY_DRAFT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("migrations:copyIntoPlanBlocked"),
                )
            }
        }
        if (refusal.canCopy) {
            FilledTonalButton(
                onClick = onCopy,
                enabled = plannerEntry !is PlannerEntry.DraftUnreadable,
                modifier = Modifier.testTag("migrations:copyIntoPlanBtn"),
            ) {
                ConnectionIcon(AppIcon.ContentCopy, size = SmallIconSize)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copy into a new plan")
            }
        }
    }
}

@Composable
private fun DryRunActionRow(
    actionDryRun: ActionDryRun,
    index: Int,
    failed: Boolean = false,
    siblings: List<MigrationAction> = emptyList(),
    testMessage: TestMessageOffer = TestMessageOffer.NotOffered,
    onTestMessage: () -> Unit = {},
) {
    // The kind of a route's destination comes from the migration's own actions only: the file may be applied to another broker later.
    val words = MqttRoutes.words(actionDryRun.action, siblings)
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
                text = words?.title ?: actionDryRun.action.description,
                style = MaterialTheme.typography.bodyMedium,
            )
            (words?.detail ?: actionDryRun.action.detail)?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("actionDetail:$index"),
                )
            }
            val reason = actionDryRun.reason
            if (reason != null) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC62828),
                )
            }
            TestMessageControl(testMessage, index, onTestMessage)
        }
        if (failed) {
            Pill("Failed", Color(0xFFFFEBEE), Color(0xFFC62828), AppIcon.Error, Modifier.testTag("dryRunFailed:$index"))
            Spacer(modifier = Modifier.width(6.dp))
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

/** The button of an MQTT route row and, when it is disabled, the reason under it; nothing for a row that is not offered one. */
@Composable
private fun TestMessageControl(offer: TestMessageOffer, index: Int, onClick: () -> Unit) {
    if (offer is TestMessageOffer.NotOffered) return
    TextButton(
        onClick = onClick,
        enabled = offer is TestMessageOffer.Available,
        modifier = Modifier.testTag("migrations:testMessageBtn:$index"),
    ) {
        Text(MqttTestMessageTexts.BUTTON)
    }
    if (offer is TestMessageOffer.Disabled) {
        Text(
            text = offer.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp).testTag("migrations:testMessageReason:$index"),
        )
    }
}

/** The kind of the destination comes from the migration's own declare steps, as for the row's words: unknown gives no notice and no guess. */
private fun testMessageOpening(route: MqttRoute, file: MigrationFile, values: List<String>, ids: CorrelationIdSource): TestMessageOpening =
    MqttTestMessage.opening(route, MqttRoutes.kindOf(route.destination, file.up, KnownTopology.None), values, ids)

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

/** The tip of row [n] of the brief for the Migrations screen; the text comes from core. */
@Composable
internal fun MigTip(n: Int) = HelpTip("mig-$n", MigrationsWording.tip("mig-$n"))
