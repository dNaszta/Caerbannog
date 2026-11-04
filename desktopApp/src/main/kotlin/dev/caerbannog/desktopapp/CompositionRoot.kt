package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.datarabbit.JavaKeyringSecretStore
import dev.caerbannog.datarabbit.JsonFileConnectionProfileRepository
import dev.caerbannog.datarabbit.StreamAndManagementReachabilityChecker
import dev.caerbannog.datarabbit.X509CaCertificateValidator
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
): ConnectionsViewModel = ConnectionsViewModel(
    repository = JsonFileConnectionProfileRepository(profilesFile),
    secretStore = secretStore,
    reachabilityChecker = StreamAndManagementReachabilityChecker(),
    caCertificateValidator = X509CaCertificateValidator(),
)
