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
import dev.caerbannog.core.connections.PortInput
import dev.caerbannog.core.connections.PortKind
import dev.caerbannog.core.connections.PortParseResult
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
    var verifyHostName: Boolean by mutableStateOf(selected?.verifyHostName ?: false)

    var enableMqtt: Boolean by mutableStateOf(selected?.mqttPort != null)

    /** The last valid port values; what [toInput] sends. */
    private var validPorts: BrokerPorts by mutableStateOf(
        selected?.let { BrokerPorts(it.streamPort, it.managementPort, it.mqttPort) }
            ?: BrokerEndpoints.defaultsFor(tls = true, enableMqtt = false),
    )

    /** What the user sees in the port fields; may be invalid while typing (see [portError]). */
    var streamPortText: String by mutableStateOf(validPorts.stream.toString())
        private set
    var managementPortText: String by mutableStateOf(validPorts.management.toString())
        private set
    var mqttPortText: String by mutableStateOf(validPorts.mqtt?.toString().orEmpty())
        private set

    /** The ports that go into [toInput]. Setting them replaces the field texts; typing goes through [onPortTextChange]. */
    var ports: BrokerPorts
        get() = validPorts
        set(value) {
            validPorts = value
            streamPortText = value.stream.toString()
            managementPortText = value.management.toString()
            mqttPortText = value.mqtt?.toString().orEmpty()
        }

    private fun textOf(kind: PortKind): String = when (kind) {
        PortKind.STREAM -> streamPortText
        PortKind.MANAGEMENT -> managementPortText
        PortKind.MQTT -> mqttPortText
    }

    /** The inline error of one port field, or null when it is valid (or the MQTT field is not shown). */
    fun portError(kind: PortKind): String? {
        if (kind == PortKind.MQTT && !enableMqtt) return null
        return (PortInput.parse(textOf(kind)) as? PortParseResult.Invalid)?.message
    }

    /** Save is blocked while any shown port field is invalid. */
    val portsValid: Boolean
        get() = PortKind.entries.all { portError(it) == null }

    /** The user typed [text] into a port field; a valid value also becomes the port that is saved. */
    fun onPortTextChange(kind: PortKind, text: String) {
        when (kind) {
            PortKind.STREAM -> streamPortText = text
            PortKind.MANAGEMENT -> managementPortText = text
            PortKind.MQTT -> mqttPortText = text
        }
        val valid = (PortInput.parse(text) as? PortParseResult.Valid)?.port ?: return
        validPorts = when (kind) {
            PortKind.STREAM -> validPorts.copy(stream = valid)
            PortKind.MANAGEMENT -> validPorts.copy(management = valid)
            PortKind.MQTT -> validPorts.copy(mqtt = valid)
        }
    }

    /**
     * The TLS switch alone decides TLS. A valid port that still equals the default of the old mode moves to the
     * default of the new mode; an edited port, and any text that is not a valid port yet, is left alone.
     */
    fun switchTls(now: Boolean) {
        tls = now
        PortKind.entries.forEach { kind ->
            if (kind == PortKind.MQTT && !enableMqtt) return@forEach
            val valid = (PortInput.parse(textOf(kind)) as? PortParseResult.Valid)?.port ?: return@forEach
            val moved = BrokerEndpoints.portAfterTlsSwitch(kind, valid, now)
            if (moved != valid) onPortTextChange(kind, moved.toString())
        }
    }

    fun toggleMqtt(enabled: Boolean) {
        enableMqtt = enabled
        ports = validPorts.copy(mqtt = if (enabled) (validPorts.mqtt ?: BrokerEndpoints.defaultMqttPort(tls)) else null)
    }

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
        mqttPort = if (enableMqtt) ports.mqtt ?: BrokerEndpoints.defaultMqttPort(tls) else null,
        clearMqtt = !enableMqtt && (saved?.mqttPort != null),
        tls = tls,
        verifyHostName = verifyHostName,
        caCertificatePath = caPathToSend(saved),
        topologyControlLevel = level,
        declaredPermissions = if (declares) {
            DeclaredPermissionsEdit.Set(DeclaredPermissions(configure = declaredConfigure, write = declaredWrite))
        } else {
            DeclaredPermissionsEdit.Clear
        },
    )
}
