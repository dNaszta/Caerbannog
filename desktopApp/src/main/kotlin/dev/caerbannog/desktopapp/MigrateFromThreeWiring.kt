package dev.caerbannog.desktopapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.migrate3x.Acknowledgement
import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.DefinitionsFileChooser
import dev.caerbannog.core.migrate3x.DefinitionsFileLoader
import dev.caerbannog.core.migrate3x.MigrateFromThreeViewModel
import dev.caerbannog.core.migrate3x.ReportFileSaver
import dev.caerbannog.core.migrate3x.ReviewItem
import dev.caerbannog.datarabbit.JvmDefinitionsFileLoader
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.appframe.AppFrame
import dev.caerbannog.ui.migrate3x.MigrateFromThreeScreen
import dev.caerbannog.ui.migrate3x.ReviewEvents
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

/**
 * A view model over the app's held analysis; [scope] ends with the screen. [migrationFolderPorts] are the ports the Migrations screen uses, so
 * a folder chosen here is the folder shown there and the files saved here are listed there.
 */
fun AppComposition.createMigrateFromThreeViewModel(scope: CoroutineScope): MigrateFromThreeViewModel =
    MigrateFromThreeViewModel(migrateFromThree.loader, migrateFromThree.chooser, migrateFromThree.saver, migrateFromThree.holder, migrationFolderPorts, scope, liveSource = liveSource)

/** The review events of the screen, one to one onto the view model. */
internal fun MigrateFromThreeViewModel.reviewEvents(): ReviewEvents = object : ReviewEvents {
    override fun openItem(item: ReviewItem) = this@reviewEvents.openItem(item)
    override fun closeItem() = this@reviewEvents.closeItem()
    override fun chooseAlternative(findingId: String, index: Int) = this@reviewEvents.chooseAlternative(findingId, index)
    override fun setTick(ack: Acknowledgement, on: Boolean) = this@reviewEvents.setTick(ack, on)
    override fun accept() = this@reviewEvents.accept()
    override fun skip() = this@reviewEvents.skip()
    override fun unskip(item: ReviewItem) = this@reviewEvents.unskip(item)
    override fun startReplace() = this@reviewEvents.startReplace()
    override fun setReplaceText(text: String) = this@reviewEvents.setReplaceText(text)
    override fun submitReplace(text: String) = this@reviewEvents.submitReplace(text)
    override fun backToProposal() = this@reviewEvents.backToProposal()
    override fun showNextUndecided() = this@reviewEvents.showNextUndecided()
    override fun generate() = this@reviewEvents.generate()
    override fun generateAgain() = this@reviewEvents.generateAgain()
    override fun cancelGenerateAgain() = this@reviewEvents.cancelGenerateAgain()
    override fun chooseFolder() = this@reviewEvents.chooseFolder()
    override fun retrySave() = this@reviewEvents.retrySave()
    override fun exportFiles() = this@reviewEvents.exportFiles()
    override fun dismissResult() = this@reviewEvents.dismissResult()
}

@Composable
internal fun MigrateFromThreeWindowContent(composition: AppComposition, control: ConnectionControlState) {
    val scope = rememberCoroutineScope()
    // No connection input: switching the connection does not rebuild this view model, and a rebuilt one reads the held report anyway.
    val vm = remember(composition) { composition.createMigrateFromThreeViewModel(scope) }
    val state by vm.state.collectAsState()
    val picker by vm.picker.collectAsState()
    // The connection popup asks for the live source; the view model ignores the request while a report is on screen.
    val request by composition.navigation.requestedSource.collectAsState()
    LaunchedEffect(request) {
        request?.let {
            vm.chooseSource(it)
            composition.navigation.sourceRequestHandled()
        }
    }
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
            review = remember(vm) { vm.reviewEvents() },
            onOpenMigrations = { composition.navigation.open(AppDestination.Migrations) },
            picker = picker,
            onChooseSource = vm::chooseSource,
            onAnalyseLive = vm::analyseLive,
        )
    }
}
