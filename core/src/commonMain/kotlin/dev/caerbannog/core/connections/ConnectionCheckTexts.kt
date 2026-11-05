package dev.caerbannog.core.connections

/**
 * M2-14: the message the Connections screen shows for a [ConnectionCheckResult] of a profile whose TLS switch is
 * [tls]. Until M2-14 these strings lived in the UI; they move here so the scenarios can assert them. The texts of the results
 * that exist today stay exactly as they are (pinned in `ConnectionCheckTextsTest`); new in M2-14:
 *  - BothReachable with TLS off: "Both endpoints are reachable. This connection is not encrypted."
 *  - TlsRefused (only ever produced with TLS on): "The broker did not answer the TLS handshake. It may not use TLS; turn off 'Use TLS' in this connection's settings if that is intended."
 * With TLS off no text contains "TLS" or "certificate".
 */
fun checkResultMessage(result: ConnectionCheckResult, tls: Boolean): String = when (result) {
    is ConnectionCheckResult.BothReachable ->
        if (tls) "Connection test passed. The stream endpoint and the management API are both reachable."
        else "Both endpoints are reachable. This connection is not encrypted."
    is ConnectionCheckResult.EndpointUnreachable ->
        "The ${result.endpoint.name.lowercase()} endpoint could not be reached: ${result.reason}"
    is ConnectionCheckResult.ManagementPluginMissing ->
        "Enable the \"${result.pluginName}\" plugin on the broker."
    is ConnectionCheckResult.TlsRefused -> ConnectionFailureReason.NO_TLS_ANSWER
}
