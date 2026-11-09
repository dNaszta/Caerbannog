package dev.caerbannog.desktopapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.rememberCoroutineScope
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.MessageBrowserState
import dev.caerbannog.core.browser.MessageBrowserViewModel
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.PermissionCheckStatus
import dev.caerbannog.core.mqtt.PublishMqttViewModel
import dev.caerbannog.core.mqtt.StreamInboundRoute
import dev.caerbannog.core.mqtt.UnavailableMqttMessagePublisher
import dev.caerbannog.core.publish.PublishMessageViewModel
import dev.caerbannog.core.publish.PublishTargetKind
import dev.caerbannog.core.publish.UnavailableMessagePublisher
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.ui.streams.EmptyMessageBrowserEvents
import dev.caerbannog.ui.publish.PublishEvents
import kotlinx.coroutines.flow.MutableStateFlow
import dev.caerbannog.core.connections.createStreamGate
import dev.caerbannog.core.connections.deleteStreamGate
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.InMemoryMigrationFileRepository
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.browser.PayloadViewKind
import dev.caerbannog.core.streams.StreamManagementViewModel
import dev.caerbannog.core.streams.UnavailableStreamManager
import dev.caerbannog.core.topology.QueueMessageInspectionViewModel
import dev.caerbannog.core.topology.UnavailableQueueMessageInspector
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.connections.ConnectionsScreen
import dev.caerbannog.ui.migrations.MigrationsScreen
import dev.caerbannog.ui.streams.StreamListEvents
import dev.caerbannog.ui.streams.StreamScreen
import dev.caerbannog.ui.streams.StreamsScreen
import dev.caerbannog.ui.topology.EmptyQueueInspectionEvents
import dev.caerbannog.ui.topology.QueueInspectionEvents
import dev.caerbannog.ui.topology.TopologyScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        when (screen) {
            AppDestination.Streams -> openStream?.let { StreamWindowContent(composition, control, it) } ?: StreamsWindowContent(composition, control)
            AppDestination.Topology -> TopologyWindowContent(composition, control)
            AppDestination.Migrations -> MigrationsWindowContent(composition, control)
            else -> ConnectionsScreen(composition.viewModel, control, composition.control, onNavigate = composition.navigation::open)
        }
    }
}

/** The stream screen (one stream open from the list) with its own one-second clock, which runs only while it is in the composition. */
@Composable
private fun StreamWindowContent(composition: AppComposition, control: ConnectionControlState, stream: String) {
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
            onPublished = {
                browserVm?.browseLastPage()
                composition.streamClients.refreshNow()
                composition.streamList.refreshNow()
            },
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
                inboundRoutes = res.value
                    .filter { it.destination == stream && it.source.isNotEmpty() }
                    .map { StreamInboundRoute(exchange = it.source, routingKey = it.routingKey, arguments = it.arguments) }
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
            onPublished = {
                browserVm?.browseLastPage()
                composition.streamClients.refreshNow()
                composition.streamList.refreshNow()
            },
        )
    }
    // Only a profile with an MQTT port can publish over MQTT; an amq.topic binding alone does not make the broker reachable by MQTT.
    val hasMqttOnStream = source?.hasMqtt == true
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
        onOpenPublishMqttDialog = if (hasMqttOnStream) {
            { suggestedTopic ->
                suggestedTopic?.let(publishMqttVm::setTopic)
                showPublishMqttDialog = true
            }
        } else {
            null
        },
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
private fun TopologyWindowContent(composition: AppComposition, control: ConnectionControlState) {
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
            onPublished = {
                composition.topology.refreshNow()
                composition.streamList.refreshNow()
            },
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
        onOpenPublishMqttDialog = if (source?.hasMqtt == true) ({ showPublishMqttDialog = true }) else null,
        onOpenStream = { streamName ->
            composition.navigation.open(AppDestination.Streams)
            composition.navigation.openStream(streamName)
        },
        queueInspectionState = queueInspectionState,
        queueInspectionEvents = queueInspectionEvents,
    )
}

/** The Migrations screen. */
@Composable
private fun MigrationsWindowContent(composition: AppComposition, control: ConnectionControlState) {
    val connections by composition.viewModel.state.collectAsState()
    val currentConn = connections.currentConnection
    val scope = rememberCoroutineScope()
    val source = composition.active.source
    val connName = currentConn?.name.orEmpty()
    val vhost = source?.vhost ?: "/"
    val done = currentConn?.status as? PermissionCheckStatus.Done
    val canConfigure = done?.result?.topologyUnlocked ?: false
    val controlLevel = dev.caerbannog.core.connections.TopologyControlLevel.FULL_CONTROL

    val fileRepo = remember { InMemoryMigrationFileRepository() }
    val historyRepo = remember { InMemoryMigrationHistoryRepository() }
    val executor = remember(composition.active) {
        object : TopologyMigrationExecutor {
            override suspend fun execute(action: MigrationAction): ActionResult {
                return ActionResult.Success
            }
        }
    }
    val runner = remember(historyRepo, executor) { MigrationRunner(historyRepo, executor) }
    val migrationsVm = remember(connName, vhost, canConfigure, controlLevel) {
        MigrationsViewModel(
            fileRepository = fileRepo,
            runner = runner,
            topologyCatalog = composition.active.source?.topology,
            profileName = connName,
            vhost = vhost,
            hasConfigurePermission = canConfigure,
            controlLevel = controlLevel,
            scope = scope,
        )
    }
    val state by migrationsVm.state.collectAsState()

    MigrationsScreen(
        state = state,
        onSelectVersion = migrationsVm::selectVersion,
        onApplyPending = { scope.launch { migrationsVm.applyPending() } },
        onApplySingle = { scope.launch { migrationsVm.applySingle(it) } },
        onImportJson = { scope.launch { migrationsVm.importFile(it) } },
        onExportTopology = { scope.launch { migrationsVm.exportCurrentTopology() } },
        onUpdateFile = { ver, json ->
            scope.launch {
                fileRepo.updateFile(ver, json)
                migrationsVm.loadMigrations()
            }
        },
        onValidateAgain = migrationsVm::loadMigrations,
        onDismissError = migrationsVm::dismissError,
        control = control,
        controlEvents = composition.control,
        onNavigate = composition.navigation::open,
    )
}

private const val TICK_MILLIS: Long = 1000
