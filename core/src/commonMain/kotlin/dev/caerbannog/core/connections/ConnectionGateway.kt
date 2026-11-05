package dev.caerbannog.core.connections

import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamOffsets

/**
 * Opens the clients of one connection profile (management API over HTTPS, stream client lazily). Gateway
 * pattern: core does not know Ktor or the stream client; `data-rabbit` implements it (M2-03 part 2), tests use
 * a fake. Never called without a stored password by [ActiveConnection]; an implementation still reports a
 * missing password as [OpenResult.Failed] because the store can change between the check and the open.
 */
interface ConnectionGateway {
    /** Returns [OpenResult]; failures the user can act on are values, not exceptions. */
    suspend fun open(profile: ConnectionProfile): OpenResult
}

/** Outcome of [ConnectionGateway.open]. */
sealed interface OpenResult {
    /** The clients are open; whoever receives this owns the connection and must close it. */
    data class Opened(val connection: HeldConnection) : OpenResult

    /** Nothing was left open. [reason] is plain text for the user (see [ConnectionFailureReason]), never a secret. */
    data class Failed(val reason: String) : OpenResult
}

/** One open connection to a broker. [close] is idempotent and releases every client and secret reference. */
interface HeldConnection : AutoCloseable {
    /** Stream list and details over the management API of this connection. */
    val catalog: StreamCatalog

    /** First offsets over the stream protocol of this connection. */
    val offsets: StreamOffsets

    /**
     * M2-08b: queues, exchanges, bindings and vhosts over the management API of this connection.
     * COMPILE STUB by the test developer: the default answers `Unreachable` to everything. The developer removes the default and gives
     * every implementation its real catalog (`RabbitMqHeldConnection`, the fake, the anonymous holders of the desktopApp tests).
     */
    val topology: dev.caerbannog.core.topology.TopologyCatalog get() = dev.caerbannog.core.topology.UnavailableTopology
}
