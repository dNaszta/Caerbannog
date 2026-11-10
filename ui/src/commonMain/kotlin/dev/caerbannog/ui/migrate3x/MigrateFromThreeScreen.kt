package dev.caerbannog.ui.migrate3x

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.Severity

/**
 * The Migrate from 3.x screen, file source (M8-06a). State in, events out; the screen sends nothing to any broker.
 * Test tags: `migrate3x-choose-file`, `migrate3x-state-<name>` (`choose-source`, `loading`, `rejected`, `needs-virtual-host`, `report`),
 * `migrate3x-loss-banner`, `migrate3x-loss-copy`, `migrate3x-loss-show-all`, `migrate3x-loss-name-<i>`, `migrate3x-row-copy-<group key>-<index>`, `migrate3x-chip-<severity>` (`blocker`, `warning`, `info`), `migrate3x-group-<key>`, `migrate3x-row-<group key>-<index>`,
 * `migrate3x-vhost-field`, `migrate3x-vhost-error`, `migrate3x-export`, `migrate3x-copy-text`, plus `migrate3x-cancel`, `migrate3x-vhost-analyse`,
 * `migrate3x-notice`, `migrate3x-notice-dismiss`.
 * M8-06a STUB: draws nothing.
 */
@Composable
fun MigrateFromThreeScreen(
    state: MigrateFromThreeState,
    reportText: () -> String,
    onChooseFile: () -> Unit,
    onGiveVirtualHost: (String) -> Unit,
    onCancelPrompt: () -> Unit,
    onToggleSeverity: (Severity) -> Unit,
    onToggleGroup: (String) -> Unit,
    onExport: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {} // M8-06a STUB
}
