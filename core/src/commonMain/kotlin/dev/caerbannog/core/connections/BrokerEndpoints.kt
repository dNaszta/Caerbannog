package dev.caerbannog.core.connections

/** The two ports of a profile. */
data class BrokerPorts(val stream: Int, val management: Int)

/**
 * M2-14 compile stub (test developer, no behaviour). The one home of the four default port numbers and of the rule
 * "ports follow the TLS switch only while they are still the default of the old mode":
 *  - [defaultsFor] true is 5551 / 15671 (stream TLS, management HTTPS), false is 5552 / 15672 (plain).
 *  - [afterTlsSwitch]: [current] are the ports in the form, [tlsNow] is the NEW position of the switch. A port that
 *    equals the default of the OLD mode (the opposite of [tlsNow]) moves to the default of the new mode; any other
 *    port stays. Each port is decided on its own. A port the user typed that happens to equal the other mode's
 *    default counts as untouched (accepted, harmless).
 */
object BrokerEndpoints {
    // Stub = today's behaviour (TLS defaults for both modes, the ports never move), so a scenario fails on a wrong value, not on an exception.
    fun defaultsFor(tls: Boolean): BrokerPorts = BrokerPorts(stream = 5551, management = 15671)

    fun afterTlsSwitch(current: BrokerPorts, tlsNow: Boolean): BrokerPorts = current
}
