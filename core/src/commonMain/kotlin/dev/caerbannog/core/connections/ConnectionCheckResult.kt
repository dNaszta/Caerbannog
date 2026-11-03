package dev.caerbannog.core.connections

/**
 * Outcome of a [BrokerReachabilityChecker] check. A sealed type rather than a boolean/string so each
 * distinct outcome the Connections screen renders (plain success, naming which endpoint failed, TLS
 * refused, a missing plugin) has its own shape instead of a flag that switches behaviour.
 */
sealed interface ConnectionCheckResult {

    /** Both the stream endpoint and the management API answered over TLS. */
    data object BothReachable : ConnectionCheckResult

    /** [endpoint] could not be reached; [reason] carries the broker/network error text verbatim. */
    data class EndpointUnreachable(val endpoint: Endpoint, val reason: String) : ConnectionCheckResult

    /** The management API answered, but [pluginName] is not enabled on the broker. */
    data class ManagementPluginMissing(val pluginName: String) : ConnectionCheckResult

    /** The broker does not offer TLS at all; the app never connects without it. */
    data object TlsRefused : ConnectionCheckResult
}
