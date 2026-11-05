package dev.caerbannog.ui.connections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.caerbannog.core.connections.BrokerEndpoints
import dev.caerbannog.core.connections.BrokerPorts
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.DeclaredPermissions
import dev.caerbannog.core.connections.DeclaredPermissionsEdit
import dev.caerbannog.core.connections.TopologyControlLevel

/**
 * The edit panel's fields for one profile on screen ([selected] null = a new connection). Created per selected
 * profile, so nothing leaks from one profile to the next. Holds no repository and calls no broker; it exists so
 * the "which CA path do I send" rule in [toInput] is testable without a composition.
 */
class ConnectionFormState(selected: ConnectionProfile?) {
    var name: String by mutableStateOf(selected?.name.orEmpty())
    var host: String by mutableStateOf(selected?.host.orEmpty())
    var virtualHost: String by mutableStateOf(selected?.virtualHost ?: "/")
    var username: String by mutableStateOf(selected?.username.orEmpty())
    var password: String by mutableStateOf("")
    var caCertificatePath: String by mutableStateOf(selected?.caCertificatePath.orEmpty())
    var tls: Boolean by mutableStateOf(selected?.tls ?: true)

    /** Follow the TLS switch; the ports themselves are read-only (see [BrokerEndpoints.afterTlsSwitch]). */
    var ports: BrokerPorts by mutableStateOf(
        selected?.let { BrokerPorts(it.streamPort, it.managementPort) } ?: BrokerEndpoints.defaultsFor(tls = true),
    )

    /** The CA path is validated on focus loss or Save, and only if it changed since the last validation. */
    var caEdited: Boolean by mutableStateOf(false)
    var caHadFocus: Boolean by mutableStateOf(false)
    var caValidatedPath: String? by mutableStateOf(null)

    var level: TopologyControlLevel by mutableStateOf(selected?.topologyControlLevel ?: TopologyControlLevel.READ_ONLY)
    var declares: Boolean by mutableStateOf(selected?.declaredPermissions != null)
    var declaredConfigure: Boolean by mutableStateOf(selected?.declaredPermissions?.configure ?: false)
    var declaredWrite: Boolean by mutableStateOf(selected?.declaredPermissions?.write ?: false)

    /**
     * The CA path to send: a new profile always sends the typed value (nothing is stored yet); a saved profile sends
     * it only when it changed and was not just validated on focus loss (which already stored a valid one), otherwise
     * null (keep) so the file is not read twice.
     */
    private fun caPathToSend(saved: ConnectionProfile?): String? {
        val send = saved == null ||
            (caCertificatePath != saved.caCertificatePath.orEmpty() && caCertificatePath != caValidatedPath)
        return if (send) caCertificatePath else null
    }

    /**
     * The Save input; a blank password is null (keep the stored one). [saved] is the profile as saved now, not as it
     * was when this form was created (a focus-loss validation may have stored a path since); null = a new profile.
     */
    fun toInput(saved: ConnectionProfile?): ConnectionProfileInput = ConnectionProfileInput(
        name = name,
        host = host,
        virtualHost = virtualHost,
        username = username,
        password = password.ifBlank { null },
        streamPort = ports.stream,
        managementPort = ports.management,
        tls = tls,
        caCertificatePath = caPathToSend(saved),
        topologyControlLevel = level,
        declaredPermissions = if (declares) {
            DeclaredPermissionsEdit.Set(DeclaredPermissions(configure = declaredConfigure, write = declaredWrite))
        } else {
            DeclaredPermissionsEdit.Clear
        },
    )
}
