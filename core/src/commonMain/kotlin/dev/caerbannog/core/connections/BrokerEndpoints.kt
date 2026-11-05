package dev.caerbannog.core.connections

/** The two ports of a profile. */
data class BrokerPorts(val stream: Int, val management: Int)

/**
 * M2-14: the one home of the four default port numbers and of the rule
 * "ports follow the TLS switch only while they are still the default of the old mode":
 *  - [defaultsFor] true is 5551 / 15671 (stream TLS, management HTTPS), false is 5552 / 15672 (plain).
 *  - [afterTlsSwitch]: [current] are the ports in the form, [tlsNow] is the NEW position of the switch. A port that
 *    equals the default of the OLD mode (the opposite of [tlsNow]) moves to the default of the new mode; any other
 *    port stays. Each port is decided on its own. A port the user typed that happens to equal the other mode's
 *    default counts as untouched (accepted, harmless).
 */
object BrokerEndpoints {
    fun defaultsFor(tls: Boolean): BrokerPorts =
        if (tls) BrokerPorts(stream = 5551, management = 15671) else BrokerPorts(stream = 5552, management = 15672)

    fun afterTlsSwitch(current: BrokerPorts, tlsNow: Boolean): BrokerPorts {
        val old = defaultsFor(!tlsNow)
        val new = defaultsFor(tlsNow)
        return BrokerPorts(
            stream = if (current.stream == old.stream) new.stream else current.stream,
            management = if (current.management == old.management) new.management else current.management,
        )
    }
}
