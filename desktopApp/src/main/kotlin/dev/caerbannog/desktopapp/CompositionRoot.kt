package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionControlModel
import dev.caerbannog.core.connections.ConnectionGateway
import dev.caerbannog.core.connections.ConnectionFeature
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.ConnectionsState
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.HeldLiveSourceProvider
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionCheckStatus
import dev.caerbannog.core.connections.SessionState
import dev.caerbannog.core.connections.availableFeatures
import dev.caerbannog.core.connections.permissionPill
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.migrate3x.LiveSourceProvider
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.migrations.MigrationFolderChooser
import dev.caerbannog.core.migrations.MigrationFolderPorts
import dev.caerbannog.core.migrations.MigrationFolderStore
import dev.caerbannog.core.migrations.MigrationHistoryRepository
import dev.caerbannog.core.migrations.NowProvider
import dev.caerbannog.core.planner.PlanDraftStore
import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshTarget
import dev.caerbannog.core.streams.StreamClientsViewModel
import dev.caerbannog.core.streams.StreamListViewModel
import dev.caerbannog.core.access.AccessContext
import dev.caerbannog.core.access.AccessViewModel
import dev.caerbannog.core.access.MatchPreviewViewModel
import dev.caerbannog.core.access.PermissionsViewModel
import dev.caerbannog.core.topology.TopologyContext
import dev.caerbannog.core.topology.TopologyViewModel
import dev.caerbannog.datarabbit.FilePlanDraftStore
import dev.caerbannog.datarabbit.FolderMigrationFileRepository
import dev.caerbannog.datarabbit.JavaKeyringSecretStore
import dev.caerbannog.datarabbit.JsonFileAppSettings
import dev.caerbannog.datarabbit.JsonFileConnectionProfileRepository
import dev.caerbannog.datarabbit.JsonFileMigrationHistoryRepository
import dev.caerbannog.datarabbit.ManagementApiPermissionChecker
import dev.caerbannog.datarabbit.RabbitMqConnectionGateway
import dev.caerbannog.datarabbit.StreamAndManagementReachabilityChecker
import dev.caerbannog.datarabbit.X509CaCertificateValidator
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.appframe.requiredFeature
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.io.File
import java.nio.file.Path

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
    /** The read-only Topology screen's state holder (M2-08b); its screen is drawn by `TopologyScreen` (M2-08c). */
    val topology: TopologyViewModel,
    /** The one migration history of the app: the Migrations screen reads it and the Connections screen renames and deletes its keys (one lock). */
    val migrationHistory: MigrationHistoryRepository,
    /** What the Migrations screen needs around its files: the remembered folder, the chooser, the folder repositories and [migrationHistory]. */
    val migrationFolderPorts: MigrationFolderPorts,
    /** The one planner draft of this machine (M9-03), a file of the app's own data and never in the migrations folder. */
    val planDraftStore: PlanDraftStore,
    /** The per-machine settings (the planner keeps its naming style there). */
    val appSettings: AppSettings,
    private val streamListScope: CoroutineScope,
    /** The file loader, dialogs and the in-memory held analysis of the Migrate from 3.x screen (M8-06a). */
    val migrateFromThree: MigrateFromThreeParts = MigrateFromThreeParts(),
    /** What the drawer offers: the features of the connected broker and the reason for the others (M8-02a); the window passes it to the frame. */
    val navigationFeatures: StateFlow<NavigationFeatures> = MutableStateFlow(NavigationFeatures.ALL),
    /** What the Migrate from 3.x screen may read live (the held 3.x connection), read only; None for a composition without one. */
    val liveSource: LiveSourceProvider = LiveSourceProvider.None,
    /** The read-only Access screen's state holder (M10-02); its screen is drawn by `AccessScreen`. */
    val access: AccessViewModel,
    /** The Permissions tab's state holder (M10-03), a sibling of [access] on the same screen. */
    val permissions: PermissionsViewModel,
    /** What the control refreshes and ages on the Access screen: both tabs. */
    internal val accessTarget: RefreshTarget,
    /** The match preview form's state holder (M10-04), under the Permissions tab's tables; it asks the pure matcher and sends nothing. */
    val matchPreview: MatchPreviewViewModel,
) {
    /** The match preview form's events, mapped onto [matchPreview]. */
    val matchPreviewEvents: MatchPreviewEventsAdapter = MatchPreviewEventsAdapter(matchPreview)

    /** The Permissions tab's events, mapped onto [permissions]. */
    val permissionsEvents: PermissionsEventsAdapter = PermissionsEventsAdapter(permissions)

    /** The Access screen's events, mapped onto [access]. */
    val accessEvents: AccessEventsAdapter = AccessEventsAdapter(access)

    /** The Topology screen's events, mapped onto [topology] (M2-08c). */
    val topologyEvents: TopologyEventsAdapter = TopologyEventsAdapter(topology)

    /** Stops the stream list (its timer and loads), then closes the held connection, if any. Safe to call twice. */
    fun close() {
        try {
            // The scope cancel is what stops the list (its timer, loads and link follower are children of the scope) and the
            // chip model; the close() calls are for callers that keep the scope alive.
            streamList.close()
            streamClients.close()
            topology.close()
            access.close()
            permissions.close()
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
    gateway: ConnectionGateway = RabbitMqConnectionGateway(
        secretStore, profiles = { name -> JsonFileConnectionProfileRepository(profilesFile).list().firstOrNull { it.name == name } }, log = log,
    ),
    appSettings: AppSettings = JsonFileAppSettings(JsonFileAppSettings.defaultFile(), log),
    uiDispatcher: CoroutineDispatcher = Dispatchers.Swing,
    navigation: AppNavigation = AppNavigation(),
    migrationHistoryFile: Path = Path.of(System.getProperty("user.home"), ".caerbannog", "migration-history.json"),
    migrationFolderChooser: MigrationFolderChooser = SwingMigrationFolderChooser(),
    migrateFromThree: MigrateFromThreeParts = MigrateFromThreeParts(),
    planDraftFile: Path = FilePlanDraftStore.defaultFile(),
): AppComposition {
    val session = ConnectionSession(permissionChecker, secretStore)
    val active = ActiveConnection(session, gateway)
    val migrationHistory = JsonFileMigrationHistoryRepository(migrationHistoryFile, NowProvider { System.currentTimeMillis() })
    val viewModel = ConnectionsViewModel(
        repository = JsonFileConnectionProfileRepository(profilesFile),
        secretStore = secretStore,
        reachabilityChecker = reachabilityChecker,
        caCertificateValidator = X509CaCertificateValidator(),
        session = session,
        active = active,
        historyKeys = migrationHistory,
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
    val topology = TopologyViewModel(
        active = active,
        settings = appSettings,
        scope = streamListScope,
        clock = clock,
        reconnect = viewModel::connect,
        context = viewModel.state.map(::topologyContext).stateIn(streamListScope, SharingStarted.Eagerly, topologyContext(viewModel.state.value)),
    )
    val access = AccessViewModel(
        active = active,
        settings = appSettings,
        scope = streamListScope,
        clock = clock,
        reconnect = viewModel::connect,
        context = viewModel.state.map(::accessContext).stateIn(streamListScope, SharingStarted.Eagerly, accessContext(viewModel.state.value)),
    )
    val permissions = PermissionsViewModel(
        active = active,
        settings = appSettings,
        scope = streamListScope,
        clock = clock,
        reconnect = viewModel::connect,
        context = viewModel.state.map(::accessContext).stateIn(streamListScope, SharingStarted.Eagerly, accessContext(viewModel.state.value)),
    )
    // The password for the redaction guard comes from the holder of the connection that is held now, per call; null when none is held.
    val matchPreview = MatchPreviewViewModel(permissions.matchInput, streamListScope, heldSecret = active::heldSecret)
    val accessTarget = AccessRefreshTarget(
        access, permissions,
        combine(access.updatedAtMillis, permissions.updatedAtMillis) { a, p -> listOfNotNull(a, p).maxOrNull() }.stateIn(streamListScope, SharingStarted.Eagerly, null),
    )
    // Ruling 8: a stream open means the tab is the screen the control ages and refreshes; Disconnect of the held connection closes it,
    // a switch or a lost connection keeps it (the tab then shows the new broker's answer or its stale rows).
    // M2-08c: the Topology screen is its own target; it is the shown screen, whatever stream was open before.
    fun targetOf(screen: AppDestination, stream: String?): RefreshTarget = when {
        screen == AppDestination.Topology -> topology
        screen == AppDestination.Access -> accessTarget
        stream == null -> streamList
        else -> streamClients
    }
    val shownTarget: StateFlow<RefreshTarget> = combine(navigation.screen, navigation.selectedStream, ::targetOf)
        .stateIn(streamListScope, SharingStarted.Eagerly, targetOf(navigation.screen.value, navigation.selectedStream.value))
    streamListScope.launch {
        active.state.collect { if (it is LinkState.Disconnected) navigation.closeStream() }
    }
    val control = ConnectionControlModel(
        viewModel, active, streamList, streamListScope, clock,
        onOpenConnections = { navigation.open(AppDestination.Connections) }, shownTarget = shownTarget,
        onOpenMigrateFromThree = { navigation.openMigrateFromThree(SourceKind.Live) },
    )
    val navigationFeatures = navigationFeatures(session, active, streamListScope)
    streamListScope.launch {
        // A destination the connection does not offer (a 3.x broker was just connected) is not left on screen: back to Connections.
        combine(navigation.screen, navigationFeatures) { screen, nav -> screen.isDisabledBy(nav) }.collect { disabled ->
            if (disabled) navigation.open(AppDestination.Connections)
        }
    }
    return AppComposition(viewModel, active, streamList, streamClients, control, navigation, StreamListEventsAdapter(streamList), StreamClientsEventsAdapter(streamClients, navigation), topology, migrationHistory, migrationFolderPorts(appSettings, migrationFolderChooser, migrationHistory),
        FilePlanDraftStore(planDraftFile, NowProvider { System.currentTimeMillis() }), appSettings, streamListScope, migrateFromThree, navigationFeatures, HeldLiveSourceProvider(active, session, streamListScope), access, permissions, accessTarget, matchPreview)
}

// The remembered folder lives in settings.json; a settings object of another kind (a test's) only remembers it until the app closes.
private fun migrationFolderPorts(settings: AppSettings, chooser: MigrationFolderChooser, history: MigrationHistoryRepository) =
    MigrationFolderPorts(
        store = settings as? MigrationFolderStore ?: SessionFolderStore(),
        chooser = chooser,
        repositoryFor = { FolderMigrationFileRepository(Path.of(it)) },
        history = history,
    )

private class SessionFolderStore : MigrationFolderStore {
    private val signal = MutableSharedFlow<String?>(extraBufferCapacity = 8)

    override var folder: String? = null
        set(value) {
            field = value
            signal.tryEmit(value)
        }

    override val changes: Flow<String?> get() = signal
}

// The banner of the Topology screen needs the connected profile (user, level) and what the permission check found, nothing else.
private fun topologyContext(state: ConnectionsState): TopologyContext? {
    val current = state.currentConnection ?: return null
    val profile = state.profiles.firstOrNull { it.name == current.name } ?: return null
    return TopologyContext(profile, (current.status as? PermissionCheckStatus.Done)?.result)
}

// The Not encrypted banner of the Access screen needs the connected profile's TLS switch, nothing else.
private fun accessContext(state: ConnectionsState): AccessContext? {
    val current = state.currentConnection ?: return null
    return state.profiles.firstOrNull { it.name == current.name }?.let(::AccessContext)
}

/** Window close request: close the held connection first, then always exit, even if closing went wrong. */
fun handleCloseRequest(composition: AppComposition, exitApplication: () -> Unit) {
    try {
        composition.close()
    } finally {
        exitApplication()
    }
}

/**
 * Registers a JVM shutdown hook that closes the held connection and stops background tasks on abnormal termination
 * (e.g. Cmd+Q on macOS, SIGTERM). [register] is an injection seam for unit tests to avoid touching [Runtime.addShutdownHook].
 */
fun registerShutdownHook(
    composition: AppComposition,
    register: (Thread) -> Unit = { hook ->
        try {
            Runtime.getRuntime().addShutdownHook(hook)
        } catch (_: IllegalStateException) {
            // JVM is already shutting down
        }
    },
): Thread {
    val hook = Thread({ composition.close() }, "caerbannog-shutdown")
    register(hook)
    return hook
}



/** What the window passes to `AppFrame(features, featuresDisabledReason)`; [reason] is null when every feature is on. */
data class NavigationFeatures(val features: Set<ConnectionFeature>, val reason: String?) {
    companion object {
        val ALL = NavigationFeatures(ConnectionFeature.entries.toSet(), null)
    }
}

private fun AppDestination.isDisabledBy(nav: NavigationFeatures): Boolean = requiredFeature()?.let { it !in nav.features } ?: false

// Only a connected broker limits the drawer: the result of a disconnected or not yet connected profile never does.
private fun navigationFeatures(session: ConnectionSession, active: ActiveConnection, scope: CoroutineScope): StateFlow<NavigationFeatures> {
    fun compute(link: LinkState, sessionState: SessionState): NavigationFeatures {
        val result = (sessionState.statuses[(link as? LinkState.Connected)?.name] as? PermissionCheckStatus.Done)?.result
        val features = availableFeatures(result)
        val reason = if (result is PermissionCheckResult.MigrationSource) permissionPill(PermissionCheckStatus.Done(result)).text else null
        return NavigationFeatures(features, reason)
    }
    return combine(active.state, session.state, ::compute)
        .stateIn(scope, SharingStarted.Eagerly, compute(active.state.value, session.state.value))
}
