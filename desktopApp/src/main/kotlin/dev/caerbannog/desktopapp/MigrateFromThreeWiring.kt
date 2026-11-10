package dev.caerbannog.desktopapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.DefinitionsFileChooser
import dev.caerbannog.core.migrate3x.DefinitionsFileLoader
import dev.caerbannog.core.migrate3x.MigrateFromThreeViewModel
import dev.caerbannog.core.migrate3x.ReportFileSaver
import dev.caerbannog.datarabbit.JvmDefinitionsFileLoader
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.appframe.AppFrame
import dev.caerbannog.ui.migrate3x.MigrateFromThreeScreen
import kotlinx.coroutines.CoroutineScope

/**
 * What the Migrate from 3.x screen is built from. The [holder] lives as long as the app: the window rebuilds the view model, and the held
 * report must survive that (owner decision 4). There is no broker gateway in here on purpose: the screen cannot send a request.
 */
class MigrateFromThreeParts(
    val loader: DefinitionsFileLoader = JvmDefinitionsFileLoader(),
    val chooser: DefinitionsFileChooser = SwingDefinitionsFileChooser(),
    val saver: ReportFileSaver = SwingReportFileSaver(),
    val holder: AnalysisHolder = AnalysisHolder(),
)

/** A view model over the app's held analysis; [scope] ends with the screen. */
fun AppComposition.createMigrateFromThreeViewModel(scope: CoroutineScope): MigrateFromThreeViewModel =
    MigrateFromThreeViewModel(migrateFromThree.loader, migrateFromThree.chooser, migrateFromThree.saver, migrateFromThree.holder, migrationFolderPorts, scope)  // M8-06b: the same ports the Migrations screen uses

@Composable
internal fun MigrateFromThreeWindowContent(composition: AppComposition, control: ConnectionControlState) {
    val scope = rememberCoroutineScope()
    // No connection input: switching the connection does not rebuild this view model, and a rebuilt one reads the held report anyway.
    val vm = remember(composition) { composition.createMigrateFromThreeViewModel(scope) }
    val state by vm.state.collectAsState()
    AppFrame(
        title = AppDestination.MigrateFromThree.label,
        control = control,
        events = composition.control,
        onNavigate = composition.navigation::open,
        activeItem = AppDestination.MigrateFromThree.name,
    ) {
        MigrateFromThreeScreen(
            state = state,
            reportText = vm::reportText,
            onChooseFile = vm::chooseFile,
            onGiveVirtualHost = vm::giveVirtualHost,
            // One Cancel on the screen: it ends a running Loading or answers the virtual host prompt; each event does nothing in the other state.
            onCancelPrompt = { vm.cancelLoading(); vm.cancelPrompt() },
            onToggleSeverity = vm::toggleSeverity,
            onToggleGroup = vm::toggleGroup,
            onExport = vm::exportReportJson,
            onDismissNotice = vm::dismissNotice,
            onReportCopied = vm::reportCopied,
        )
    }
}
