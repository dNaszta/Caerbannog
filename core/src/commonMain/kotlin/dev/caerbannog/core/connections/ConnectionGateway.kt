package dev.caerbannog.core.connections

import dev.caerbannog.core.browser.StreamMessageReader
import dev.caerbannog.core.publish.MessagePublisher
import dev.caerbannog.core.publish.UnavailableMessagePublisher
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamManager
import dev.caerbannog.core.streams.StreamOffsets
import dev.caerbannog.core.streams.UnavailableStreamManager
import dev.caerbannog.core.topology.QueueMessageInspector
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.core.topology.UnavailableQueueMessageInspector

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

    /** M2-08b: queues, exchanges, bindings and vhosts over the management API of this connection (read-only). */
    val topology: TopologyCatalog

    /** M3-01: stream message browser reader over the stream protocol of this connection. */
    val messages: StreamMessageReader

    /** M5-01: test message publisher over stream and management protocols. */
    val publisher: MessagePublisher get() = UnavailableMessagePublisher

    /** M5-03: stream creator and deleter over stream and management protocols. */
    val manager: StreamManager get() = UnavailableStreamManager

    /** M6-02: MQTT topology and sessions over management API. */
    val mqtt: dev.caerbannog.core.mqtt.MqttCatalog get() = dev.caerbannog.core.mqtt.UnavailableMqttCatalog

    /** M6-04: MQTT publisher over MQTT protocol. */
    val mqttPublisher: dev.caerbannog.core.mqtt.MqttMessagePublisher get() = dev.caerbannog.core.mqtt.UnavailableMqttMessagePublisher

    /** M7-08: non-destructive queue message inspector over Management API in requeue mode. */
    val queueInspector: QueueMessageInspector get() = UnavailableQueueMessageInspector

    /**
     * M5-08a: the one object that may change topology for a migration over the management API of this connection; null for a
     * connection that has none (a 3.x source, M8-02). Default null like [manager].
     */
    val migrationExecutor: dev.caerbannog.core.migrations.TopologyMigrationExecutor? get() = null

    /** M9-05: the read-only check of this connection's management API; null exactly when [migrationExecutor] is null (a 3.x source, an unreadable version). */
    val declareCheck: dev.caerbannog.core.migrations.DeclareCheck? get() = null

    /** M9-08: the planner's read of one policy of this connection's broker, for the notes of the check; null exactly when [migrationExecutor] is null (never on a 3.x source). */
    val policyReader: dev.caerbannog.core.planner.BrokerPolicyReader? get() = null

    /** M5-08a R1: why [migrationExecutor] is null, a fixed text for the screen (3.x: "migration source only"; unknown version: "could not be read"); null when there is an executor. */
    val migrationUnavailableReason: String? get() = null

    /**
     * M10-04: the connection's password, read per call and never stored by the caller, for the redaction guard only (a typed name that holds it is not echoed).
     * Null for a holder that has none or one that is closed. Never log it, show it or put it in a state.
     */
    fun heldSecret(): String? = null

    /** M8-02a: the raw `rabbitmq_version` text read once when the connection opened; null when unknown. Not a secret. */
    val brokerVersion: String? get() = null

    /** M8-02a (implied by the brief, guardedSource needs it): the GET-only reads of a 3.x source connection; null for every other connection. */
    val threeXReads: dev.caerbannog.core.migrate3x.ThreeXReads? get() = null

    /** M8-09: the shovel gateway of a held 4.x connection; a connection that is not one refuses every operation (a 3.x broker is never a shovel destination). */
    val shovels: dev.caerbannog.core.forwarding.ShovelGateway get() = dev.caerbannog.core.forwarding.UnavailableShovelGateway

    /** M10-02: the read-only access gateway (whoami, virtual hosts) of a held 4.x connection; a 3.x connection refuses and sends nothing. */
    val access: dev.caerbannog.core.access.AccessGateway get() = dev.caerbannog.core.access.UnavailableAccessGateway

    /** M8-10: the read-only shovel status of a held 4.x connection (for the MCP server, which never gets [shovels]). */
    val shovelStatus: dev.caerbannog.core.forwarding.ShovelStatusReader get() = dev.caerbannog.core.forwarding.UnavailableShovelStatusReader
}
