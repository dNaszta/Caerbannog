package dev.caerbannog.datarabbit

import dev.caerbannog.core.access.AccessGateway
import dev.caerbannog.core.access.AccessResult
import dev.caerbannog.core.access.WhoAmI

/**
 * M10-02 contract (test developer, COMPILE-ONLY STUB: every call answers Unreachable until the gateway is built).
 * The [AccessGateway] over the management API of the held connection ([http], [requests]): GET only, the profile's own scheme and port, and every
 * string that leaves passes `SecretGuard` (with the held secret from [heldSecret], read per call, never stored). A known 3.x [brokerVersion] refuses
 * and sends nothing; an unknown version does not (same rule as `isMigrationSourceOnly`).
 */
@Suppress("UnusedPrivateProperty")
internal class ManagementAccessGateway(
    private val http: ManagementHttp,
    private val requests: ManagementRequests,
    private val brokerVersion: String?,
    private val heldSecret: () -> String? = { null },
) : AccessGateway {
    override suspend fun whoAmI(): AccessResult<WhoAmI> = AccessResult.Unreachable

    override suspend fun virtualHosts(): AccessResult<List<String>> = AccessResult.Unreachable
}
