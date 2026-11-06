package dev.caerbannog.ui.streams

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.MessageBrowserState
import dev.caerbannog.core.browser.PayloadFormatter
import dev.caerbannog.core.browser.PayloadViewKind
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.IconSize
import dev.caerbannog.ui.connections.SmallIconSize
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PAGE_SIZES = listOf(25, 50, 100, 250, 500)
private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

@Composable
fun MessageBrowserView(
    state: MessageBrowserState,
    events: MessageBrowserEvents,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        when (state) {
            is MessageBrowserState.Loading -> {
                Box(Modifier.fillMaxSize().testTag("browserLoading"), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Loading messages...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            is MessageBrowserState.Empty -> {
                Box(Modifier.fillMaxSize().testTag("browserEmpty"), contentAlignment = Alignment.Center) {
                    Text("This stream has no messages yet", style = MaterialTheme.typography.bodyLarge)
                }
            }
            is MessageBrowserState.NoMessagesAtOrAfterTime -> {
                Box(Modifier.fillMaxSize().testTag("browserNoMessagesAtTime"), contentAlignment = Alignment.Center) {
                    Text("No messages found at or after timestamp ${state.epochMs}", style = MaterialTheme.typography.bodyLarge)
                }
            }
            is MessageBrowserState.OutsideRange -> {
                Box(Modifier.fillMaxSize().testTag("browserOutsideRange"), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "Offset ${state.requestedOffset} is outside valid range (${state.validRange.first}..${state.validRange.last})",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(
                            onClick = events::jumpToNearestValidOffset,
                            modifier = Modifier.testTag("jumpToNearestButton"),
                        ) {
                            Text("Jump to nearest valid offset (${state.nearestOffset})")
                        }
                    }
                }
            }
            is MessageBrowserState.Error -> {
                Box(Modifier.fillMaxSize().testTag("browserError"), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(state.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                        Button(
                            onClick = events::retry,
                            modifier = Modifier.testTag("retryButton"),
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
            is MessageBrowserState.Loaded -> {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    state.retentionShiftNotice?.let { notice ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().testTag("retentionNotice"),
                        ) {
                            Text(
                                notice,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

                    ControlCard(state, events)
                    FilterBar(state, events)

                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        MessagesTable(
                            state = state,
                            events = events,
                            modifier = Modifier.weight(1f),
                        )

                        state.selectedMessage?.let { selected ->
                            MessageDetailsSheet(
                                message = selected,
                                events = events,
                                modifier = Modifier.width(420.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterBar(state: MessageBrowserState.Loaded, events: MessageBrowserEvents) {
    var query by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("browserFilterBar"),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Filter by text, property or application property") },
                    singleLine = true,
                    leadingIcon = {
                        ConnectionIcon(AppIcon.FilterList, SmallIconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    query = ""
                                    events.clearFilter()
                                },
                                modifier = Modifier.testTag("clearFilterButton"),
                            ) {
                                ConnectionIcon(AppIcon.Clear, SmallIconSize)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("browserFilterInput"),
                )

                Button(
                    onClick = { events.filter(query) },
                    modifier = Modifier.testTag("applyFilterButton"),
                ) {
                    Text("Filter")
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("scanLimitChip"),
                ) {
                    Text(
                        "Scan limit: 10,000",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }

                OutlinedButton(
                    onClick = events::cancelScan,
                    modifier = Modifier.testTag("cancelScanButton"),
                ) {
                    Text("Cancel scan")
                }
            }

            state.filterNotice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("filterStatusNotice"),
                )
            }
        }
    }
}

@Composable
private fun ControlCard(state: MessageBrowserState.Loaded, events: MessageBrowserEvents) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("browserControls"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StartModeSegmentedGroup(state.startMode, events)

                val mode = state.startMode
                var offsetText by remember(state.startMode) {
                    mutableStateOf(
                        if (mode is BrowseStart.Offset) mode.offset.toString()
                        else state.messages.firstOrNull()?.offset?.toString() ?: ""
                    )
                }

                if (state.startMode is BrowseStart.Offset) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = offsetText,
                            onValueChange = { offsetText = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Offset") },
                            modifier = Modifier.width(140.dp).testTag("offsetInput"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        Button(
                            onClick = { offsetText.toLongOrNull()?.let(events::browseOffset) },
                            modifier = Modifier.testTag("applyOffsetButton"),
                        ) {
                            Text("Jump")
                        }
                    }
                }

                PageSizeSelector(state.pageSize, events::setPageSize)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = events::previousPage,
                    enabled = state.canGoPrevious,
                    modifier = Modifier.testTag("previousPageButton"),
                ) {
                    Text("Previous")
                }

                Button(
                    onClick = events::nextPage,
                    enabled = state.canGoNext,
                    modifier = Modifier.testTag("nextPageButton"),
                ) {
                    Text("Next")
                }

                val summaryText = if (state.messages.isNotEmpty()) {
                    val first = state.messages.first().offset
                    val last = state.messages.last().offset
                    val totalStr = state.approximateTotal?.let { "about $it" } ?: "unknown"
                    "Offsets $first to $last of $totalStr"
                } else {
                    "0 messages"
                }

                Text(
                    summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("browserSummary"),
                )
            }
        }
    }
}

@Composable
private fun StartModeSegmentedGroup(startMode: BrowseStart, events: MessageBrowserEvents) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    ) {
        SegmentButton("First", "startMode:First", startMode is BrowseStart.First) { events.browseFirst() }
        SegmentButton("Last page", "startMode:Last", startMode is BrowseStart.LastPage) { events.browseLastPage() }
        SegmentButton("Offset", "startMode:Offset", startMode is BrowseStart.Offset) {
            events.browseOffset(0L)
        }
        SegmentButton("Time", "startMode:Time", startMode is BrowseStart.Timestamp) {
            events.browseTimestamp(System.currentTimeMillis())
        }
    }
}

@Composable
private fun SegmentButton(
    label: String,
    tag: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .background(bg)
            .clickable(onClick = onClick)
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
private fun PageSizeSelector(currentSize: Int, onSelectSize: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("pageSizeDropdown"),
        ) {
            Text("Page size: $currentSize")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            PAGE_SIZES.forEach { size ->
                DropdownMenuItem(
                    text = { Text("$size") },
                    onClick = {
                        expanded = false
                        onSelectSize(size)
                    },
                    modifier = Modifier.testTag("pageSizeOption:$size"),
                )
            }
        }
    }
}

@Composable
private fun MessagesTable(
    state: MessageBrowserState.Loaded,
    events: MessageBrowserEvents,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth().testTag("messagesTable"),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Table Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Offset",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(90.dp).testTag("header:Offset"),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    "Timestamp",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.width(170.dp).testTag("header:Timestamp"),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    "Properties",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.width(160.dp).testTag("header:Properties"),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    "Body",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).testTag("header:Body"),
                )
            }

            HorizontalDivider()

            // Table Rows
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.messages, key = { it.offset }) { msg ->
                    val isSelected = msg.offset == state.selectedMessage?.offset
                    val rowBg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBg)
                            .selectable(
                                selected = isSelected,
                                role = Role.Button,
                                onClick = { events.selectMessage(msg.offset) },
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("messageRow:${msg.offset}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${msg.offset}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(90.dp).testTag("messageCell:${msg.offset}:offset"),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = formatTimestamp(msg.timestamp),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(170.dp).testTag("messageCell:${msg.offset}:timestamp"),
                        )
                        Spacer(Modifier.width(16.dp))
                        Box(Modifier.width(160.dp).testTag("messageCell:${msg.offset}:properties")) {
                            val contentType = msg.properties["content-type"]?.toString().orEmpty()
                            if (contentType.isNotEmpty()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(
                                        contentType,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = previewBody(msg.payload),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 1,
                            modifier = Modifier.weight(1f).testTag("messageCell:${msg.offset}:body"),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun MessageDetailsSheet(
    message: StreamMessage,
    events: MessageBrowserEvents,
    modifier: Modifier = Modifier,
) {
    val formatter = remember { PayloadFormatter() }
    var requestedKind by remember(message.offset) { mutableStateOf<PayloadViewKind?>(null) }
    var showAll by remember(message.offset) { mutableStateOf(false) }

    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    val inspection = remember(message, requestedKind, showAll) {
        formatter.inspect(message, requestedKind = requestedKind, showAll = showAll)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxHeight().testTag("messageDetailsSheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header: title and close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Message at offset ${message.offset}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("detailsTitle"),
                )
                IconButton(
                    onClick = { events.selectMessage(null) },
                    modifier = Modifier.testTag("closeDetailsButton"),
                ) {
                    ConnectionIcon(AppIcon.Close, IconSize)
                }
            }

            // Tabs: Text, JSON, Hex
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
            ) {
                val currentKind = inspection.currentKind
                listOf(PayloadViewKind.TEXT, PayloadViewKind.JSON, PayloadViewKind.HEX).forEach { kind ->
                    val isSelected = currentKind == kind
                    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    val fg = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    val label = when (kind) {
                        PayloadViewKind.TEXT -> "Text"
                        PayloadViewKind.JSON -> "JSON"
                        PayloadViewKind.HEX -> "Hex"
                    }
                    Box(
                        modifier = Modifier
                            .background(bg)
                            .clickable { requestedKind = kind }
                            .selectable(selected = isSelected, role = Role.Tab, onClick = { requestedKind = kind })
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("viewerTab:$label"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
                    }
                }
            }

            // Validation warning if any (e.g. malformed JSON)
            inspection.validationWarning?.let { warning ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("validationWarningBanner"),
                ) {
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            // Body preview box
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    val scrollState = rememberScrollState()
                    Box(Modifier.weight(1f).verticalScroll(scrollState)) {
                        Text(
                            text = inspection.displayText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.testTag("detailsBodyText"),
                        )
                    }

                    if (inspection.canShowAll && inspection.isCapped) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showAll = true },
                            modifier = Modifier.fillMaxWidth().testTag("showAllButton"),
                        ) {
                            Text("Show all")
                        }
                    }
                }
            }

            // Key-values list
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KeyValueRow("Timestamp", formatTimestamp(message.timestamp))
                for ((k, v) in inspection.properties) {
                    KeyValueRow(k, v)
                }
                for ((k, v) in inspection.applicationProperties) {
                    KeyValueRow("$k (app property)", v)
                }
            }

            // Copy buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        val bodyText = formatter.copyBody(message)
                        coroutineScope.launch {
                            try {
                                clipboard.setClipEntry(ClipEntry(StringSelection(bodyText)))
                            } catch (_: Exception) {
                                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(bodyText), null)
                            }
                        }
                    },
                    modifier = Modifier.testTag("copyBodyButton"),
                ) {
                    ConnectionIcon(AppIcon.ContentCopy, SmallIconSize)
                    Spacer(Modifier.width(6.dp))
                    Text("Copy body")
                }

                OutlinedButton(
                    onClick = {
                        val offsetText = formatter.copyOffset(message)
                        coroutineScope.launch {
                            try {
                                clipboard.setClipEntry(ClipEntry(StringSelection(offsetText)))
                            } catch (_: Exception) {
                                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(offsetText), null)
                            }
                        }
                    },
                    modifier = Modifier.testTag("copyOffsetButton"),
                ) {
                    ConnectionIcon(AppIcon.Tag, SmallIconSize)
                    Spacer(Modifier.width(6.dp))
                    Text("Copy offset")
                }
            }
        }
    }
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            textAlign = TextAlign.End,
        )
    }
}

private fun formatTimestamp(epochMs: Long): String = try {
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(TIMESTAMP_FORMATTER)
} catch (_: Exception) {
    "$epochMs"
}

private fun previewBody(payload: ByteArray): String = try {
    val text = payload.decodeToString()
    if (text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
        payload.take(32).joinToString(" ") { "%02x".format(it) } + if (payload.size > 32) " …" else ""
    } else {
        text.replace("\n", " ").take(120)
    }
} catch (_: Exception) {
    payload.take(32).joinToString(" ") { "%02x".format(it) }
}
