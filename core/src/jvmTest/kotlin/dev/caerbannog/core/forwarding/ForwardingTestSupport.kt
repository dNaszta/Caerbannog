package dev.caerbannog.core.forwarding

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.forwarding.fakes.FakeShovelGateway
import dev.caerbannog.core.migrate3x.SecretValue

/** M8-07: small builders shared by the forwarding tests. */
internal object Fwd {
    const val SENTINEL = "s3cret-SENTINEL"

    fun profile(tls: Boolean = true, username: String = "profile-user") =
        ConnectionProfile("legacy-eu", "legacy.example", "/", username, tls = tls, topologyControlLevel = TopologyControlLevel.READ_ONLY)

    fun creds(user: String = "mover", password: String = SENTINEL) = ShovelSourceCredentials(user, SecretValue.of(password))

    fun request(
        queue: String = "orders",
        tls: Boolean = true,
        never: Boolean = false,
        credentials: ShovelSourceCredentials? = null,
        destinationQueue: String = queue,
    ): ForwardingRequest {
        val address = ShovelAddress(tls, credentials?.username ?: "profile-user", "legacy.example", if (tls) 5671 else 5672, "/")
        return ForwardingRequest(
            ForwardingSource("legacy-eu", address, "/", queue, credentials), "new-eu", "/", destinationQueue,
            if (never) SrcDeleteAfter.NEVER else SrcDeleteAfter.QUEUE_LENGTH,
        )
    }

    val tagsOk: Set<String> = setOf("policymaker", "monitoring")

    fun facts(
        ready: Long = 120,
        destination: DestinationQueue = DestinationQueue.Exists(DestinationQueueType.QUORUM),
        plugins: Boolean = true,
        tags: Set<String> = tagsOk,
        existing: ShovelStatus? = null,
        brokerVersion: String? = null,
    ) = DryRunResult(QueueCounts(ready, 0), destination, plugins, tags, existing, brokerVersion)

    fun context(level: TopologyControlLevel = TopologyControlLevel.DECLARE_AND_EDIT, unlocked: Boolean = true) = ForwardingContext(level, unlocked)

    fun status(request: ForwardingRequest = request(), state: ShovelRunState = ShovelRunState.RUNNING, reason: String? = null, forwarded: Long? = null, remaining: Long? = null) =
        ShovelStatus(request.shovel, state, reason, forwarded, remaining)

    /** A gateway that knows the source "orders" (120 ready) and a quorum destination "orders"; the user has the tags to forward. */
    fun gateway(): FakeShovelGateway = FakeShovelGateway().apply {
        sourceQueue("legacy-eu", "/", "orders", 120)
        destinationQueue("/", "orders", DestinationQueueType.QUORUM)
        userTags("policymaker", "monitoring")
    }
}
