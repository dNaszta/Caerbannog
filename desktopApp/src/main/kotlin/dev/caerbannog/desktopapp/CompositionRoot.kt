package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionControlModel
import dev.caerbannog.core.connections.ConnectionGateway
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.datarabbit.JavaKeyringSecretStore
import dev.caerbannog.datarabbit.JsonFileAppSettings
import dev.caerbannog.datarabbit.JsonFileConnectionProfileRepository
import dev.caerbannog.datarabbit.ManagementApiPermissionChecker
import dev.caerbannog.datarabbit.RabbitMqConnectionGateway
import dev.caerbannog.datarabbit.StreamAndManagementReachabilityChecker
import dev.caerbannog.datarabbit.X509CaCertificateValidator
import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.StreamListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    /** M2-05b compile stub: the model behind the app bar chip (the developer wires it for real). */
    val control: ConnectionControlModel,
    private val streamListScope: CoroutineScope,
) {
    /** Stops the stream list (its timer and loads), then closes the held connection, if any. Safe to call twice. */
    fun close() {
        try {
            streamList.close()
            streamListScope.cancel()
        } finally {
            active.close()
        }
    }
}

/**
 * Like [createConnectionsViewModel], plus the real [ConnectionGateway] behind an [ActiveConnection] that the view
 * model's Connect uses (the interim edit-panel Connect of M2-01 now opens a real held connection). One
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
    val streamList = StreamListViewModel(
        active = active,
        settings = appSettings,
        scope = streamListScope,
        clock = RefreshClock { System.currentTimeMillis() },
        reconnect = viewModel::connect,
    )
    val control = ConnectionControlModel(
        viewModel, active, streamList, streamListScope, RefreshClock { System.currentTimeMillis() }, onOpenConnections = {},
    )
    return AppComposition(viewModel, active, streamList, control, streamListScope)
}

/** Window close request: close the held connection first, then always exit, even if closing went wrong. */
fun handleCloseRequest(composition: AppComposition, exitApplication: () -> Unit) {
    try {
        composition.close()
    } finally {
        exitApplication()
    }
}
