package dev.caerbannog.core.connections

/**
 * Proves a [ConnectionProfile]'s stream (5551) and management (15671) endpoints are reachable over
 * TLS. This is a reachability probe only: "did the TLS handshake and the HTTP call succeed at all" -
 * it never reads or interprets permissions (that is M1-03's job), so any HTTP response from the
 * management API, including 401, counts as reachable.
 */
interface BrokerReachabilityChecker {
    suspend fun check(profile: ConnectionProfile): ConnectionCheckResult
}
