package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionControlModel
import dev.caerbannog.core.connections.ConnectionGateway
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.ConnectionsState
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.PermissionCheckStatus
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshTarget
import dev.caerbannog.core.streams.StreamClientsViewModel
import dev.caerbannog.core.streams.StreamListViewModel
import dev.caerbannog.core.topology.TopologyContext
import dev.caerbannog.core.topology.TopologyViewModel
import dev.caerbannog.datarabbit.JavaKeyringSecretStore
import dev.caerbannog.datarabbit.JsonFileAppSettings
import dev.caerbannog.datarabbit.JsonFileConnectionProfileRepository
import dev.caerbannog.datarabbit.ManagementApiPermissionChecker
import dev.caerbannog.datarabbit.RabbitMqConnectionGateway
import dev.caerbannog.datarabbit.StreamAndManagementReachabilityChecker
import dev.caerbannog.datarabbit.X509CaCertificateValidator
import dev.caerbannog.ui.appframe.AppDestination
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.io.File

/**
 * Composition root for the Connections screen: wires the real repository, reachability checker and
 * certificate validator into [ConnectionsViewModel]. `ui` never imports `data-rabbit`; this is the
 * only place that knows both. The parameters are the test seam: a temp file and an in-memory store
 * keep tests away from the real `~/.caerbannog` and the OS keychain.
 */
fun createConnectionsViewModel(
    profilesFile: File = JsonFileConnectionProfileRepository.defaultFile(),
    secretStore: SecretStore = JavaKeyringSecretStore(),
    permissionChecker: PermissionChecker = ManagementApiPermissionChecker(),
    reachabilityChecker: BrokerReachabilityChecker = StreamAndManagementReachabilityChecker(),
): ConnectionsViewModel = ConnectionsViewModel(
    repository = JsonFileConnectionProfileRepository(profilesFile),
    secretStore = secretStore,
    reachabilityChecker = reachabilityChecker,
    caCertificateValidator = X509CaCertificateValidator(),
    session = ConnectionSession(permissionChecker, secretStore),
)

/**
 * What the window needs: the view model and the one [ActiveConnection] it shares with the app (M2-03 part 2).
 * [close] ends the held connection; the window's close request calls it through [handleCloseRequest].
 */
class AppComposition(
    val viewModel: ConnectionsViewModel,
    val active: ActiveConnection,
    val streamList: StreamListViewModel,
    /** The Publishers and consumers tab (M2-06a); closed with the composition, followed by the control while a stream is open. */
    val streamClients: StreamClientsViewModel,
    /** The model behind the app bar chip and its popup; the window ticks it once a second for "Updated N s ago". */
    val control: ConnectionControlModel,
    /** Which screen the window shows; "Manage connections" and "New connection" in the chip popup bring Connections back. */
    val navigation: AppNavigation,
    /** The stream list screen's events, mapped onto [streamList]. */
    val streamEvents: StreamListEventsAdapter,
    /** The stream screen's events, mapped onto [streamClients] and [navigation] (M2-06b). */
    val streamClientsEvents: StreamClientsEventsAdapter,
    /** The read-only Topology screen's state holder (M2-08b); the screen and the drawer entry come with 08c. */
    val topology: TopologyViewModel,
    private val streamListScope: CoroutineScope,
) {
    /** Stops the stream list (its timer and loads), then closes the held connection, if any. Safe to call twice. */
    fun close() {
        try {
            // The scope cancel is what stops the list (its timer, loads and link follower are children of the scope) and the
            // chip model; the close() calls are for callers that keep the scope alive.
            streamList.close()
            streamClients.close()
            topology.close()
            control.close()
            streamListScope.cancel()
        } finally {
            active.close()
        }
    }
}

/**
 * Like [createConnectionsViewModel], plus the real [ConnectionGateway] behind an [ActiveConnection] that the view
 * model's Connect uses (the chip popup's Connect opens a real held connection). One
 * [ConnectionSession] serves both, so the permission check and the connection cannot disagree.
 */
fun createAppComposition(
    profilesFile: File = JsonFileConnectionProfileRepository.defaultFile(),
    secretStore: SecretStore = JavaKeyringSecretStore(),
    permissionChecker: PermissionChecker = ManagementApiPermissionChecker(),
    reachabilityChecker: BrokerReachabilityChecker = StreamAndManagementReachabilityChecker(),
    log: (String) -> Unit = { System.err.println(it) },
    gateway: ConnectionGateway = RabbitMqConnectionGateway(secretStore, log),
    appSettings: AppSettings = JsonFileAppSettings(JsonFileAppSettings.defaultFile(), log),
    uiDispatcher: CoroutineDispatcher = Dispatchers.Swing,
    navigation: AppNavigation = AppNavigation(),
): AppComposition {
    val session = ConnectionSession(permissionChecker, secretStore)
    val active = ActiveConnection(session, gateway)
    val viewModel = ConnectionsViewModel(
        repository = JsonFileConnectionProfileRepository(profilesFile),
        secretStore = secretStore,
        reachabilityChecker = reachabilityChecker,
        caCertificateValidator = X509CaCertificateValidator(),
        session = session,
        active = active,
    )
    // The composition root owns the scope; it ends with the window. The view model runs on the UI dispatcher (Swing in the app),
    // so its state can be read by Compose without a thread hop; tests pass their own dispatcher.
    val streamListScope = CoroutineScope(SupervisorJob() + uiDispatcher)
    val clock = RefreshClock { System.currentTimeMillis() }
    val streamList = StreamListViewModel(
        active = active,
        settings = appSettings,
        scope = streamListScope,
        clock = clock,
        reconnect = viewModel::connect,
    )
    val streamClients = StreamClientsViewModel(active, appSettings, streamListScope, clock, viewModel::connect)
    // Ruling 8: a stream open means the tab is the screen the control ages and refreshes; Disconnect of the held connection closes it,
    // a switch or a lost connection keeps it (the tab then shows the new broker's answer or its stale rows).
    val shownTarget: StateFlow<RefreshTarget> = navigation.selectedStream
        .map { if (it == null) streamList else streamClients }
        .stateIn(streamListScope, SharingStarted.Eagerly, if (navigation.selectedStream.value == null) streamList else streamClients)
    streamListScope.launch {
        active.state.collect { if (it is LinkState.Disconnected) navigation.closeStream() }
    }
    val topology = TopologyViewModel(
        active = active,
        settings = appSettings,
        scope = streamListScope,
        clock = clock,
        reconnect = viewModel::connect,
        context = viewModel.state.map(::topologyContext).stateIn(streamListScope, SharingStarted.Eagerly, topologyContext(viewModel.state.value)),
    )
    val control = ConnectionControlModel(
        viewModel, active, streamList, streamListScope, clock,
        onOpenConnections = { navigation.open(AppDestination.Connections) }, shownTarget = shownTarget,
    )
    return AppComposition(viewModel, active, streamList, streamClients, control, navigation, StreamListEventsAdapter(streamList), StreamClientsEventsAdapter(streamClients, navigation), topology, streamListScope)
}

// The banner of the Topology screen needs the connected profile (user, level) and what the permission check found, nothing else.
private fun topologyContext(state: ConnectionsState): TopologyContext? {
    val current = state.currentConnection ?: return null
    val profile = state.profiles.firstOrNull { it.name == current.name } ?: return null
    return TopologyContext(profile, (current.status as? PermissionCheckStatus.Done)?.result)
}

/** Window close request: close the held connection first, then always exit, even if closing went wrong. */
fun handleCloseRequest(composition: AppComposition, exitApplication: () -> Unit) {
    try {
        composition.close()
    } finally {
        exitApplication()
    }
}
