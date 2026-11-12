package dev.caerbannog.core.connections

/** The ports of a profile. [mqtt] is null when the MQTT plugin is not enabled for this profile. */
data class BrokerPorts(val stream: Int, val management: Int, val mqtt: Int? = null)

/** Which of the profile's ports a field or rule is about. */
enum class PortKind { STREAM, MANAGEMENT, MQTT }

/**
 * M2-14 / M6-01: the home of default port numbers and the rule
 * "ports follow the TLS switch only while they are still the default of the old mode":
 *  - [defaultsFor] true is 5551 / 15671 (stream TLS, management HTTPS), false is 5552 / 15672 (plain).
 *  - [defaultMqttPort] is 8883 for TLS, 1883 for plain.
 *  - [afterTlsSwitch]: [current] are the ports in the form, [tlsNow] is the NEW position of the switch. A port that
 *    equals the default of the OLD mode moves to the default of the new mode; any other port stays. The ports are
 *    editable (a second broker on the same host uses e.g. 15673 and 5553), and such a port never moves.
 */
object BrokerEndpoints {
    fun defaultMqttPort(tls: Boolean): Int = if (tls) 8883 else 1883

    fun defaultsFor(tls: Boolean, enableMqtt: Boolean = false): BrokerPorts =
        if (tls) {
            BrokerPorts(stream = 5551, management = 15671, mqtt = if (enableMqtt) 8883 else null)
        } else {
            BrokerPorts(stream = 5552, management = 15672, mqtt = if (enableMqtt) 1883 else null)
        }

    /** One port after the TLS switch; see the class comment for the rule. */
    fun portAfterTlsSwitch(kind: PortKind, port: Int, tlsNow: Boolean): Int {
        val (tlsDefault, plainDefault) = when (kind) {
            PortKind.STREAM -> 5551 to 5552
            PortKind.MANAGEMENT -> 15671 to 15672
            PortKind.MQTT -> 8883 to 1883
        }
        val oldDefault = if (tlsNow) plainDefault else tlsDefault
        val newDefault = if (tlsNow) tlsDefault else plainDefault
        return if (port == oldDefault) newDefault else port
    }

    fun afterTlsSwitch(current: BrokerPorts, tlsNow: Boolean): BrokerPorts = BrokerPorts(
        stream = portAfterTlsSwitch(PortKind.STREAM, current.stream, tlsNow),
        management = portAfterTlsSwitch(PortKind.MANAGEMENT, current.management, tlsNow),
        mqtt = current.mqtt?.let { portAfterTlsSwitch(PortKind.MQTT, it, tlsNow) },
    )
}

/** The result of reading the text typed into a port field. */
sealed interface PortParseResult {
    data class Valid(val port: Int) : PortParseResult

    /** [message] is plain language for the inline error under the field. */
    data class Invalid(val message: String) : PortParseResult
}

/** Validation of a typed port: digits only, 1 to 65535. No trimming and no fallback to a default. */
object PortInput {
    const val INVALID_MESSAGE: String = "Enter a port number from 1 to 65535."

    fun parse(text: String): PortParseResult {
        if (text.isEmpty() || !text.all { it in '0'..'9' }) return PortParseResult.Invalid(INVALID_MESSAGE)
        // A very long digit string overflows Int; toIntOrNull turns that into "invalid" as well.
        val value = text.toIntOrNull() ?: return PortParseResult.Invalid(INVALID_MESSAGE)
        return if (value in 1..65535) PortParseResult.Valid(value) else PortParseResult.Invalid(INVALID_MESSAGE)
    }
}
