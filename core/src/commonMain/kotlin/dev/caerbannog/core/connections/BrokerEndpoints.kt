package dev.caerbannog.core.connections

/** The ports of a profile. [mqtt] is null when the MQTT plugin is not enabled for this profile. */
data class BrokerPorts(val stream: Int, val management: Int, val mqtt: Int? = null)

/**
 * M2-14 / M6-01: the home of default port numbers and the rule
 * "ports follow the TLS switch only while they are still the default of the old mode":
 *  - [defaultsFor] true is 5551 / 15671 (stream TLS, management HTTPS), false is 5552 / 15672 (plain).
 *  - [defaultMqttPort] is 8883 for TLS, 1883 for plain.
 *  - [afterTlsSwitch]: [current] are the ports in the form, [tlsNow] is the NEW position of the switch. A port that
 *    equals the default of the OLD mode moves to the default of the new mode; any other port stays.
 */
object BrokerEndpoints {
    fun defaultMqttPort(tls: Boolean): Int = if (tls) 8883 else 1883

    fun defaultsFor(tls: Boolean, enableMqtt: Boolean = false): BrokerPorts =
        if (tls) {
            BrokerPorts(stream = 5551, management = 15671, mqtt = if (enableMqtt) 8883 else null)
        } else {
            BrokerPorts(stream = 5552, management = 15672, mqtt = if (enableMqtt) 1883 else null)
        }

    fun afterTlsSwitch(current: BrokerPorts, tlsNow: Boolean): BrokerPorts {
        val oldStream = if (tlsNow) 5552 else 5551
        val newStream = if (tlsNow) 5551 else 5552
        val oldMgmt = if (tlsNow) 15672 else 15671
        val newMgmt = if (tlsNow) 15671 else 15672
        val oldMqtt = if (tlsNow) 1883 else 8883
        val newMqtt = if (tlsNow) 8883 else 1883

        return BrokerPorts(
            stream = if (current.stream == oldStream) newStream else current.stream,
            management = if (current.management == oldMgmt) newMgmt else current.management,
            mqtt = current.mqtt?.let { if (it == oldMqtt) newMqtt else it },
        )
    }
}
