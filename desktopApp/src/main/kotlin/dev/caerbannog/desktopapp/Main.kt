package dev.caerbannog.desktopapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.MessageBrowserState
import dev.caerbannog.core.browser.MessageBrowserViewModel
import dev.caerbannog.core.browser.PayloadViewKind
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.PermissionCheckStatus
import dev.caerbannog.core.connections.createStreamGate
import dev.caerbannog.core.connections.deleteStreamGate
import dev.caerbannog.core.mqtt.PublishMqttViewModel
import dev.caerbannog.core.planner.PlannerEntry
import dev.caerbannog.core.planner.PlannerViewModel
import dev.caerbannog.core.mqtt.StreamInboundRoute
import dev.caerbannog.core.mqtt.UnavailableMqttMessagePublisher
import dev.caerbannog.core.publish.PublishMessageViewModel
import dev.caerbannog.core.publish.PublishTargetKind
import dev.caerbannog.core.publish.UnavailableMessagePublisher
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.StreamManagementViewModel
import dev.caerbannog.core.streams.UnavailableStreamManager
import dev.caerbannog.core.topology.QueueMessageInspectionViewModel
import dev.caerbannog.core.topology.UnavailableQueueMessageInspector
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.appframe.LocalNavigationFeatures
import dev.caerbannog.ui.appframe.NavigationFeaturesInfo
import dev.caerbannog.ui.connections.ConnectionsScreen
import dev.caerbannog.ui.migrations.MigrationsScreen
import dev.caerbannog.ui.planner.PlannerScreen
import dev.caerbannog.ui.publish.PublishEvents
import dev.caerbannog.ui.streams.EmptyMessageBrowserEvents
import dev.caerbannog.ui.streams.StreamListEvents
import dev.caerbannog.ui.streams.StreamScreen
import dev.caerbannog.ui.streams.StreamsScreen
import dev.caerbannog.ui.topology.EmptyQueueInspectionEvents
import dev.caerbannog.ui.topology.QueueInspectionEvents
import dev.caerbannog.ui.topology.TopologyScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M1-05: renders the real Connections screen. M2-03 part 2: all wiring lives in [createAppComposition]
 * (created once via `remember`); closing the window closes the held connection first.
 * M2-11: a JVM shutdown hook closes the held connection on Cmd+Q, app menu, or SIGTERM.
 * M2-05b: the screen sits in the app frame, whose app bar carries the connection chip; the ticker below ages "Updated N s ago".
 * The other screens come with M2-05c and later, each in the same frame.
 */
fun main() = application {
    val composition = remember { createAppComposition().also(::registerShutdownHook) }
    val control by composition.control.state.collectAsState()
    // One tick per second while the window is shown; it recomputes the chip state from the clock and never calls the broker.
    LaunchedEffect(composition) {
        while (true) {
            delay(TICK_MILLIS)
            composition.control.tick()
        }
    }
    // 1280 x 800 dp is the size the mockups are drawn for; a smaller default cuts the app bar title next to the drawer.
    val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))
    Window(onCloseRequest = { handleCloseRequest(composition, ::exitApplication) }, title = "Caerbannog", state = windowState) {
        val screen by composition.navigation.screen.collectAsState()
        val openStream by composition.navigation.selectedStream.collectAsState()
        val plannerOpen by composition.navigation.plannerOpen.collectAsState()
        val navigationFeatures by composition.navigationFeatures.collectAsState()
        CompositionLocalProvider(LocalNavigationFeatures provides NavigationFeaturesInfo(navigationFeatures.features, navigationFeatures.reason)) {
            when (screen) {
                AppDestination.Streams -> openStream?.let { StreamWindowContent(composition, control, it) } ?: StreamsWindowContent(composition, control)
                AppDestination.Topology -> TopologyWindowContent(composition, control)
                AppDestination.Migrations ->
                    if (plannerOpen) PlannerWindowContent(composition, control) else MigrationsWindowContent(composition, control)
                AppDestination.MigrateFromThree -> MigrateFromThreeWindowContent(composition, control)
                else -> ConnectionsScreen(composition.viewModel, control, composition.control, onNavigate = composition.navigation::open)
            }
        }
    }
}

/** The stream screen (one stream open from the list) with its own one-second clock, which runs only while it is in the composition. */
@Composable
internal fun StreamWindowContent(composition: AppComposition, control: ConnectionControlState, stream: String) {
    val clients by composition.streamClients.state.collectAsState()
    val initialOffset by composition.navigation.initialOffset.collectAsState()
    val scope = rememberCoroutineScope()
    val source = composition.active.source
    val browserVm = remember(stream, source) {
        source?.let { src ->
            MessageBrowserViewModel(
                reader = src.messages,
                vhost = src.vhost,
                stream = stream,
                scope = scope,
            ).also { vm ->
                val jump = initialOffset
                if (jump != null) {
                    vm.browseOffset(jump)
                } else {
                    vm.browseFirst()
                }
            }
        }
    }
    val fallbackState = remember(stream) {
        MessageBrowserState.Loading(source?.vhost.orEmpty(), stream, BrowseStart.First, 100)
    }
    val browserState = browserVm?.state?.collectAsState()?.value ?: fallbackState
    val browserEvents = remember(browserVm) { browserVm?.let(::MessageBrowserEventsAdapter) ?: EmptyMessageBrowserEvents }
    val connections by composition.viewModel.state.collectAsState()
    val currentConn = connections.currentConnection
    val done = currentConn?.status as? PermissionCheckStatus.Done
    val canWrite = done?.result?.publishingEnabled ?: false
    val connName = currentConn?.name.orEmpty()
    val vhost = source?.vhost ?: "/"
    val publisher = source?.publisher ?: UnavailableMessagePublisher
    val publishVm = remember(connName, vhost, canWrite, publisher, stream) {
        PublishMessageViewModel(
            publisher = publisher,
            connectionName = connName,
            vhost = vhost,
            writePermission = canWrite,
            scope = scope,
            initialTargetKind = PublishTargetKind.STREAM,
            initialTargetName = stream,
        )
    }
    var showPublishDialog by remember(stream) { mutableStateOf(false) }
    val publishState = publishVm.state.collectAsState().value
    val publishEvents = remember(publishVm, browserVm) {
        PublishEventsAdapter(
            vm = publishVm,
            onClose = { showPublishDialog = false },
            onPublished = afterStreamPublish(
                browseLastPage = browserVm?.let { vm -> { vm.browseLastPage() } },
                refreshClients = { composition.streamClients.refreshNow() },
                refreshStreamList = { composition.streamList.refreshNow() },
            ),
        )
    }
    val manager = source?.manager ?: UnavailableStreamManager
    val canConfigure = done?.result?.topologyUnlocked ?: false
    val managementVm = remember(connName, vhost, canConfigure, manager, stream) {
        StreamManagementViewModel(
            manager = manager,
            connectionName = connName,
            vhost = vhost,
            configurePermission = canConfigure,
            existingStreams = { emptySet() },
            consumerCounts = { clients.consumers.size },
            onStreamDeleted = { composition.streamList.refreshNow() },
            scope = scope,
        )
    }
    val deleteState by managementVm.deleteState.collectAsState()
    val deleteGate = deleteStreamGate(currentConn, composition.active.state.collectAsState().value)
    var inboundRoutes by remember(stream, connName, vhost) { mutableStateOf<List<StreamInboundRoute>>(emptyList()) }
    LaunchedEffect(stream, source, vhost) {
        val topo = source?.topology ?: return@LaunchedEffect
        when (val res = topo.bindings(vhost)) {
            is CatalogResult.Success -> {
                inboundRoutes = inboundRoutesFor(res.value, stream)
            }
            else -> Unit
        }
    }
    val mqttPublisher = source?.mqttPublisher ?: UnavailableMqttMessagePublisher
    val publishMqttVm = remember(connName, mqttPublisher, stream) {
        PublishMqttViewModel(
            publisher = mqttPublisher,
            connectionName = connName,
            scope = scope,
        )
    }
    var showPublishMqttDialog by remember(stream, connName) { mutableStateOf(false) }
    val publishMqttState by publishMqttVm.state.collectAsState()
    val publishMqttEvents = remember(publishMqttVm, browserVm) {
        PublishMqttEventsAdapter(
            vm = publishMqttVm,
            onClose = { showPublishMqttDialog = false },
            onPublished = afterStreamPublish(
                browseLastPage = browserVm?.let { vm -> { vm.browseLastPage() } },
                refreshClients = { composition.streamClients.refreshNow() },
                refreshStreamList = { composition.streamList.refreshNow() },
            ),
        )
    }
    val now = rememberNowMillis()
    StreamScreen(
        stream = stream,
        state = clients,
        nowMillis = now,
        events = composition.streamClientsEvents,
        control = control,
        controlEvents = composition.control,
        onNavigate = composition.navigation::open,
        browserState = browserState,
        browserEvents = browserEvents,
        publishState = publishState,
        publishEvents = publishEvents,
        showPublishDialog = showPublishDialog,
        onOpenPublishDialog = { showPublishDialog = true },
        deleteStreamState = deleteState,
        deleteStreamGate = deleteGate,
        onDeleteConfirmationChange = managementVm::setDeleteConfirmation,
        onConfirmDelete = {
            scope.launch {
                if (managementVm.confirmDelete()) {
                    composition.navigation.closeStream()
                }
            }
        },
        onCancelDelete = managementVm::closeDelete,
        onOpenDeleteDialog = { managementVm.openDelete(stream, clients.consumers.size) },
        inboundRoutes = inboundRoutes,
        showPublishMqttDialog = showPublishMqttDialog,
        publishMqttState = publishMqttState,
        publishMqttEvents = publishMqttEvents,
        onOpenPublishMqttDialog = streamMqttOpener(source, setTopic = publishMqttVm::setTopic, openDialog = { showPublishMqttDialog = true }),
    )
}

/** One-second clock for "Updated N s ago"; it ticks only while the caller is in the composition and never calls the broker. */
@Composable
private fun rememberNowMillis(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(TICK_MILLIS)
        }
    }
    return now
}

/** The Streams screen with its own one-second clock; the clock runs only while this is in the composition (screen shown). */
@Composable
private fun StreamsWindowContent(composition: AppComposition, control: ConnectionControlState) {
    val list by composition.streamList.state.collectAsState()
    val link by composition.active.state.collectAsState()
    val connections by composition.viewModel.state.collectAsState()
    val source = composition.active.source
    val scope = rememberCoroutineScope()
    val currentConn = connections.currentConnection
    val done = currentConn?.status as? PermissionCheckStatus.Done
    val canConfigure = done?.result?.topologyUnlocked ?: false
    val connName = currentConn?.name.orEmpty()
    val vhost = source?.vhost ?: "/"
    val manager = source?.manager ?: UnavailableStreamManager

    val managementVm = remember(connName, vhost, canConfigure, manager) {
        StreamManagementViewModel(
            manager = manager,
            connectionName = connName,
            vhost = vhost,
            configurePermission = canConfigure,
            existingStreams = { list.rows.map { it.name }.toSet() },
            onStreamCreated = { composition.streamList.refreshNow() },
            onStreamDeleted = { composition.streamList.refreshNow() },
            scope = scope,
        )
    }
    val createState by managementVm.createState.collectAsState()
    val deleteState by managementVm.deleteState.collectAsState()
    val streamEvents = remember(composition.streamEvents, managementVm) {
        object : StreamListEvents by composition.streamEvents {
            override fun createStream() {
                managementVm.openCreate()
            }
        }
    }

    val now = rememberNowMillis()
    StreamsScreen(
        state = list,
        nowMillis = now,
        createStream = createStreamGate(connections.currentConnection, link),
        events = streamEvents,
        control = control,
        controlEvents = composition.control,
        onNavigate = composition.navigation::open,
        onOpenStream = composition.navigation::openStream,
        createStreamState = createState,
        onCreateNameChange = managementVm::setCreateName,
        onConfirmCreate = { scope.launch { managementVm.confirmCreate() } },
        onCancelCreate = managementVm::closeCreate,
        deleteStreamState = deleteState,
        onDeleteConfirmationChange = managementVm::setDeleteConfirmation,
        onConfirmDelete = { scope.launch { managementVm.confirmDelete() } },
        onCancelDelete = managementVm::closeDelete,
    )
}

/** The Topology screen with its own one-second clock; the clock runs only while this is in the composition (screen shown). */
@Composable
internal fun TopologyWindowContent(composition: AppComposition, control: ConnectionControlState) {
    val topology by composition.topology.state.collectAsState()
    val connections by composition.viewModel.state.collectAsState()
    val currentConn = connections.currentConnection
    val connName = currentConn?.name.orEmpty()
    val source = composition.active.source
    val scope = rememberCoroutineScope()
    val mqttPublisher = source?.mqttPublisher ?: UnavailableMqttMessagePublisher

    val publishMqttVm = remember(connName, mqttPublisher) {
        PublishMqttViewModel(
            publisher = mqttPublisher,
            connectionName = connName,
            scope = scope,
        )
    }
    var showPublishMqttDialog by remember(connName) { mutableStateOf(false) }
    val publishMqttState by publishMqttVm.state.collectAsState()
    val publishMqttEvents = remember(publishMqttVm) {
        PublishMqttEventsAdapter(
            vm = publishMqttVm,
            onClose = { showPublishMqttDialog = false },
            onPublished = afterTopologyPublish(
                refreshTopology = { composition.topology.refreshNow() },
                refreshStreamList = { composition.streamList.refreshNow() },
            ),
        )
    }

    val now = rememberNowMillis()
    val selectedQueueName = topology.selectedQueue?.name
    val selectedVhost = topology.vhost.ifEmpty { "/" }
    val queueInspector = source?.queueInspector ?: UnavailableQueueMessageInspector
    val queueInspectionVm = remember(connName, selectedVhost, selectedQueueName, queueInspector) {
        selectedQueueName?.let { queueName ->
            QueueMessageInspectionViewModel(
                inspector = queueInspector,
                vhost = selectedVhost,
                queue = queueName,
                scope = scope,
            )
        }
    }
    val queueInspectionState = queueInspectionVm?.state?.collectAsState()?.value
    val queueInspectionEvents = remember(queueInspectionVm) {
        if (queueInspectionVm != null) {
            object : QueueInspectionEvents {
                override fun inspectMessages() = queueInspectionVm.inspectMessages()
                override fun setCount(count: Int) = queueInspectionVm.setCount(count)
                override fun selectMessage(index: Int) = queueInspectionVm.selectMessage(index)
                override fun setPayloadViewKind(kind: PayloadViewKind?) = queueInspectionVm.setPayloadViewKind(kind)
                override fun clear() = queueInspectionVm.clear()
            }
        } else {
            EmptyQueueInspectionEvents
        }
    }
    TopologyScreen(
        state = topology,
        nowMillis = now,
        events = composition.topologyEvents,
        control = control,
        controlEvents = composition.control,
        onNavigate = composition.navigation::open,
        showPublishMqttDialog = showPublishMqttDialog,
        publishMqttState = publishMqttState,
        publishMqttEvents = publishMqttEvents,
        onOpenPublishMqttDialog = topologyMqttOpener(source, openDialog = { showPublishMqttDialog = true }),
        onOpenStream = { streamName ->
            composition.navigation.open(AppDestination.Streams)
            composition.navigation.openStream(streamName)
        },
        queueInspectionState = queueInspectionState,
        queueInspectionEvents = queueInspectionEvents,
    )
}

/** The Migrations screen: the connection's real executor, the profile's own level, the folder repository and the history on disk. */
@Composable
private fun MigrationsWindowContent(composition: AppComposition, control: ConnectionControlState) {
    val connections by composition.viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val inputs = composition.migrationsInputs(connections)
    val migrationsVm = remember(inputs) { composition.createMigrationsViewModel(inputs, scope) }
    val state by migrationsVm.state.collectAsState()

    // Callbacks held by a dialog outlive one composition; they must reach the view model built for the latest inputs, not the one they started with.
    val current by rememberUpdatedState(migrationsVm)
    val folderOpener = remember { DesktopFolderOpener() }

    // The way into the planner: read from the draft file each time this screen is shown (the planner may just have saved or dismissed it).
    var plannerEntry by remember { mutableStateOf<PlannerEntry>(PlannerEntry.NewPlan) }
    LaunchedEffect(composition) { plannerEntry = withContext(Dispatchers.IO) { composition.plannerEntry() } }

    // After a save the planner has closed: show the notice and select the new version once the listing has it.
    val savedPlan by composition.navigation.savedPlan.collectAsState()
    var plannerNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savedPlan, state.entries) {
        val saved = savedPlan ?: return@LaunchedEffect
        plannerNotice = saved.message
        if (state.entries.any { it.file.version == saved.version }) {
            current.selectVersion(saved.version)
            composition.navigation.savedPlanHandled()
        }
    }

    MigrationsScreen(
        state = state,
        onSelectVersion = { current.selectVersion(it) },
        onApplyPending = { scope.launch { current.applyPending() } },
        onApplySingle = { scope.launch { current.applySingle(it) } },
        onImportJson = { scope.launch { current.importFile(it) } },
        onExportTopology = { scope.launch { current.exportCurrentTopology() } },
        onUpdateFile = { ver, json -> scope.launch { current.updateFile(ver, json) } },
        onValidateAgain = { current.loadMigrations() },
        onDismissError = { current.dismissError() },
        onChooseFolder = { current.chooseFolder() },
        onChangeFolder = { current.changeFolder() },
        onRetry = { current.retry() },
        onShowFolder = folderOpener::show,
        onStartWithEmptyHistory = { current.startWithEmptyHistory() },
        onConfirmEmptyHistory = { current.confirmEmptyHistory() },
        onCancelEmptyHistory = { current.cancelEmptyHistory() },
        onDismissOutcome = { current.dismissOutcome() },
        control = control,
        controlEvents = composition.control,
        onNavigate = composition.navigation::open,
        plannerEntry = plannerEntry,
        onOpenPlanner = composition.navigation::openPlanner,
        onDismissDraft = {
            scope.launch {
                withContext(Dispatchers.IO) { composition.planDraftStore.clear() }
                plannerEntry = PlannerEntry.NewPlan
            }
        },
        plannerNotice = plannerNotice,
        onDismissPlannerNotice = { plannerNotice = null },
    )
}

/**
 * The planner, a sub-screen of Migrations. The view model is rebuilt when what it is built from changes (the connection, its level or
 * permission). The window keeps ONE planner scope (one IO thread: the draft store does blocking file I/O) and each view model runs in a child
 * job of it. On a rebuild the old view model is flushed BEFORE the new one is created, so the flush is queued first on the same thread and
 * the new view model reads a description the old one still held back (M9-03 review, R1 and R2). The old one's job is cancelled behind its
 * flush, so it stops collecting. Leaving the screen flushes and ends the scope the same way.
 */
@Composable
private fun PlannerWindowContent(composition: AppComposition, control: ConnectionControlState) {
    val connections by composition.viewModel.state.collectAsState()
    val inputs = composition.migrationsInputs(connections)
    val context = plannerContextOf(inputs)
    val windowScope = remember { newPlannerScope() }
    val live = remember { LivePlanner() }
    val plannerVm = remember(context) {
        live.retire(windowScope)
        val job = SupervisorJob(windowScope.coroutineContext[Job])
        composition.createPlannerViewModel(inputs, CoroutineScope(windowScope.coroutineContext + job)).also { live.vm = it; live.job = job }
    }
    DisposableEffect(windowScope) {
        onDispose {
            live.retire(windowScope)
            // Queued behind the flush on the planner's one thread, so the draft is written before the scope ends.
            windowScope.launch { windowScope.cancel() }
        }
    }
    LaunchedEffect(plannerVm) { composition.followPlanner(plannerVm, this) }
    val state by plannerVm.state.collectAsState()
    val events = remember(plannerVm) { PlannerEventsAdapter(plannerVm) }
    PlannerScreen(
        state = state,
        events = events,
        control = control,
        controlEvents = composition.control,
        onNavigate = composition.navigation::open,
    )
}

/** The view model of the planner window that is running now, so the next one can flush it first. Not state: nothing draws from it. */
private class LivePlanner {
    var vm: PlannerViewModel? = null
    var job: Job? = null

    fun retire(windowScope: CoroutineScope) {
        val old = vm ?: return
        val oldJob = job
        old.flush()
        if (oldJob != null) windowScope.launch { oldJob.cancel() }
        vm = null
        job = null
    }
}

private const val TICK_MILLIS: Long = 1000
