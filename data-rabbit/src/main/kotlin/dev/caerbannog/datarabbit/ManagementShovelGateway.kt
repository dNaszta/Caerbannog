package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.forwarding.DryRunOutcome
import dev.caerbannog.core.forwarding.ForwardingRequest
import dev.caerbannog.core.forwarding.RemoveOutcome
import dev.caerbannog.core.forwarding.ShovelGateway
import dev.caerbannog.core.forwarding.ShovelRef
import dev.caerbannog.core.forwarding.StartOutcome
import dev.caerbannog.core.forwarding.StatusOutcome

/** The longest shovel name the broker accepts (measured by the live test; M8-08 STUB value). */
internal const val MAX_SHOVEL_NAME_LENGTH: Int = 255 // M8-08 STUB

/**
 * M8-08 STUB: the [ShovelGateway] over the management API of the held 4.x connection ([http], [requests]); the source probe uses a one-shot client
 * from [sourceHttp] (source profile and password). Every operation answers a failure until built.
 */
internal class ManagementShovelGateway(
    private val http: ManagementHttp,
    private val requests: ManagementRequests,
    private val profiles: (String) -> ConnectionProfile?,
    private val secrets: SecretStore,
    private val sourceHttp: (ConnectionProfile, String) -> ManagementHttp = { profile, password ->
        ManagementHttp(TlsSupport.buildHttpClient(BrokerTransport.from(profile), 10_000), profile.username, password)
    },
) : ShovelGateway {
    override suspend fun dryRun(request: ForwardingRequest): DryRunOutcome = DryRunOutcome.Failed("M8-08 STUB")
    override suspend fun start(request: ForwardingRequest): StartOutcome = StartOutcome.Failed("M8-08 STUB")
    override suspend fun status(shovel: ShovelRef): StatusOutcome = StatusOutcome.Failed("M8-08 STUB")
    override suspend fun remove(shovel: ShovelRef): RemoveOutcome = RemoveOutcome.Failed("M8-08 STUB")
}
