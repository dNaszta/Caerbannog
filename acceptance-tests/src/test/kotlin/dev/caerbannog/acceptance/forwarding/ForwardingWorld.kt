package dev.caerbannog.acceptance.forwarding

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.forwarding.DestinationQueueType
import dev.caerbannog.core.forwarding.ForwardingContext
import dev.caerbannog.core.forwarding.ForwardingRequest
import dev.caerbannog.core.forwarding.ForwardingService
import dev.caerbannog.core.forwarding.ForwardingSource
import dev.caerbannog.core.forwarding.ForwardingState
import dev.caerbannog.core.forwarding.Prepared
import dev.caerbannog.core.forwarding.ShovelAddress
import dev.caerbannog.core.forwarding.ShovelSourceCredentials
import dev.caerbannog.core.forwarding.StartResult
import dev.caerbannog.core.forwarding.fakes.FakeShovelGateway

/** M8-07: one scenario's state. Plain fields; the service and the planner are the real ones, the broker is the fake. */
internal object ForwardingWorld {
    var gateway = FakeShovelGateway()
    var level = TopologyControlLevel.DECLARE_AND_EDIT
    var tls = true
    var credentials: ShovelSourceCredentials? = null
    var prepared: Prepared? = null
    var started: StartResult? = null
    var state: ForwardingState? = null
    var callsBefore = 0

    val legacy: ConnectionProfile get() = ConnectionProfile("legacy-eu", "legacy.example", "/", "svc-reader", tls = tls)

    val service: ForwardingService get() = ForwardingService(gateway) { ForwardingContext(level, topologyUnlocked = true) }

    /** The source address the dialog would show: from the profile switch, the host as the profile has it, the typed user when there is one. */
    fun request(queue: String = "orders"): ForwardingRequest =
        ForwardingRequest(
            ForwardingSource("legacy-eu", ShovelAddress.forProfile(legacy, credentials = credentials), "/", queue, credentials),
            "new-eu", "/", queue,
        )

    fun reset() {
        gateway = FakeShovelGateway()
        level = TopologyControlLevel.DECLARE_AND_EDIT
        tls = true
        credentials = null
        prepared = null
        started = null
        state = null
        callsBefore = 0
        gateway.destinationQueue("/", "orders", DestinationQueueType.QUORUM) // overwritten by the Background steps
    }
}
