package dev.caerbannog.core.connections

import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.StreamMessageReader
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.DeclareCheck
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.mqtt.MqttCatalog
import dev.caerbannog.core.mqtt.MqttClientRow
import dev.caerbannog.core.mqtt.MqttMessagePublisher
import dev.caerbannog.core.mqtt.PublishMqttRequest
import dev.caerbannog.core.mqtt.UnavailableMqttCatalog
import dev.caerbannog.core.mqtt.UnavailableMqttMessagePublisher
import dev.caerbannog.core.publish.MessagePublisher
import dev.caerbannog.core.publish.PublishMessageRequest
import dev.caerbannog.core.publish.PublishOutcome
import dev.caerbannog.core.publish.UnavailableMessagePublisher
import dev.caerbannog.core.streams.Capabilities
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.ConsumerRow
import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.PublisherRow
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.streams.StreamManager
import dev.caerbannog.core.streams.StreamOffsets
import dev.caerbannog.core.streams.StreamPage
import dev.caerbannog.core.streams.StreamSettings
import dev.caerbannog.core.streams.StreamSummary
import dev.caerbannog.core.streams.SuperStreamLayout
import dev.caerbannog.core.streams.UnavailableStreamManager
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.PolicyRow
import dev.caerbannog.core.topology.QueueDeclaration
import dev.caerbannog.core.topology.QueueInspectionResult
import dev.caerbannog.core.topology.QueueMessageInspector
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.core.topology.UnavailableQueueMessageInspector
import dev.caerbannog.core.topology.VhostRow

/**
 * What a screen may use of the held connection: its catalog and offsets, and the virtual host of the profile.
 * Not closable on purpose. Compare sources by reference (`===`): a new connection is a new source even for the
 * same profile.
 */
class StreamSource(
    val catalog: StreamCatalog,
    val offsets: StreamOffsets,
    val vhost: String,
    /** M2-08b: the read-only topology of the same held connection, guarded like [catalog]. */
    val topology: TopologyCatalog,
    /** M3-01: the stream message browser reader of the same held connection, guarded like [catalog]. */
    val messages: StreamMessageReader,
    /** M5-01: the test message publisher of the same held connection, guarded like [catalog]. */
    val publisher: MessagePublisher = UnavailableMessagePublisher,
    /** M5-03: the stream manager of the same held connection, guarded like [catalog]. */
    val manager: StreamManager = UnavailableStreamManager,
    /** M6-02: the MQTT catalog of the same held connection, guarded like [catalog]. */
    val mqtt: MqttCatalog = UnavailableMqttCatalog,
    /** M6-04: the MQTT publisher of the same held connection, guarded like [publisher]. */
    val mqttPublisher: MqttMessagePublisher = UnavailableMqttMessagePublisher,
    /** M7-08: the non-destructive queue message inspector of the same held connection, guarded like [publisher]. */
    val queueInspector: QueueMessageInspector = UnavailableQueueMessageInspector,
    /** M5-08a: the migration executor of the same held connection, guarded like [manager]; null when the connection has none. */
    val migrationExecutor: TopologyMigrationExecutor? = null,
    /** M9-05: the read-only check of the same held connection, guarded like [migrationExecutor]; null exactly when [migrationExecutor] is null. */
    val declareCheck: DeclareCheck? = null,
    /** M9-08: the planner's one-policy read of the same held connection, guarded like [declareCheck]; null exactly when [migrationExecutor] is null. */
    val policyReader: dev.caerbannog.core.planner.BrokerPolicyReader? = null,
    /** M5-08a R1: the fixed reason there is no [migrationExecutor], passed on as it is. */
    val migrationUnavailableReason: String? = null,
    /** M8-02a: the raw broker version text of the held connection; null when unknown. */
    val brokerVersion: String? = null,
    /** M8-02a: reads a live 3.x connection into a definitions document and facts; never null: a connection that is not a 3.x source refuses with a fixed text (rulings 2). */
    val definitionsSource: dev.caerbannog.core.migrate3x.DefinitionsSource = dev.caerbannog.core.migrate3x.UnavailableDefinitionsSource(""),
    /** M8-09: the shovel gateway of the same held connection; a connection that is not a held 4.x one refuses. */
    val shovels: dev.caerbannog.core.forwarding.ShovelGateway = dev.caerbannog.core.forwarding.UnavailableShovelGateway,
    /** Whether the profile has an MQTT port. The MQTT publisher object always exists; this says whether the user can use it. */
    val hasMqtt: Boolean = false,
    /** M10-02: the read-only access gateway of the same held connection; one that is not a held 4.x one refuses. */
    val access: dev.caerbannog.core.access.AccessGateway = dev.caerbannog.core.access.UnavailableAccessGateway,
)

// Proxy pattern: a source of a closed or superseded connection must never reach it (the connection may be gone).
internal class GuardedCatalog(private val inner: StreamCatalog, private val isCurrent: () -> Boolean) : StreamCatalog {
    private suspend fun <T> guarded(call: suspend () -> CatalogResult<T>): CatalogResult<T> =
        if (isCurrent()) call() else CatalogResult.Unreachable

    override suspend fun listStreams(request: StreamListRequest): CatalogResult<StreamPage> = guarded { inner.listStreams(request) }

    override suspend fun streamDetail(vhost: String, name: String): CatalogResult<StreamSummary> = guarded { inner.streamDetail(vhost, name) }

    override suspend fun publishers(vhost: String, stream: String): CatalogResult<List<PublisherRow>> = guarded { inner.publishers(vhost, stream) }

    override suspend fun publisherCounts(vhost: String): CatalogResult<Map<String, Int>> = guarded { inner.publisherCounts(vhost) }

    override suspend fun consumers(vhost: String, stream: String): CatalogResult<List<ConsumerRow>> = guarded { inner.consumers(vhost, stream) }

    override suspend fun superStreams(vhost: String): CatalogResult<List<SuperStreamLayout>> = guarded { inner.superStreams(vhost) }

    override suspend fun capabilities(): CatalogResult<Capabilities> = guarded { inner.capabilities() }
}

internal class GuardedOffsets(private val inner: StreamOffsets, private val isCurrent: () -> Boolean) : StreamOffsets {
    override suspend fun firstOffset(vhost: String, stream: String): FirstOffset =
        if (isCurrent()) inner.firstOffset(vhost, stream) else FirstOffset.Unavailable
}

internal class GuardedTopology(private val inner: TopologyCatalog, private val isCurrent: () -> Boolean) : TopologyCatalog {
    private suspend fun <T> guarded(call: suspend () -> CatalogResult<T>): CatalogResult<T> =
        if (isCurrent()) call() else CatalogResult.Unreachable

    override suspend fun vhosts(): CatalogResult<List<VhostRow>> = guarded { inner.vhosts() }

    override suspend fun queues(vhost: String, page: Int): CatalogResult<QueuePage> = guarded { inner.queues(vhost, page) }

    override suspend fun exchanges(vhost: String): CatalogResult<List<ExchangeRow>> = guarded { inner.exchanges(vhost) }

    override suspend fun bindings(vhost: String): CatalogResult<List<BindingRow>> = guarded { inner.bindings(vhost) }

    override suspend fun queueDetail(vhost: String, name: String): CatalogResult<QueueRow> = guarded { inner.queueDetail(vhost, name) }

    override suspend fun queueDeclarations(vhost: String): CatalogResult<List<QueueDeclaration>> = guarded { inner.queueDeclarations(vhost) }

    override suspend fun policies(vhost: String): CatalogResult<List<PolicyRow>> = guarded { inner.policies(vhost) }

    override suspend fun operatorPolicies(vhost: String): CatalogResult<List<PolicyRow>> = guarded { inner.operatorPolicies(vhost) }
}

internal class GuardedMessageReader(
    private val inner: StreamMessageReader,
    private val isCurrent: () -> Boolean,
) : StreamMessageReader {
    override suspend fun readPage(vhost: String, stream: String, start: BrowseStart, pageSize: Int): BrowseResult =
        if (isCurrent()) inner.readPage(vhost, stream, start, pageSize) else BrowseResult.Failed("Connection closed")
}

internal class GuardedPublisher(
    private val inner: MessagePublisher,
    private val isCurrent: () -> Boolean,
) : MessagePublisher {
    override suspend fun publish(request: PublishMessageRequest): PublishOutcome =
        if (isCurrent()) inner.publish(request) else PublishOutcome.Failed("Connection closed")
}

internal class GuardedStreamManager(
    private val inner: StreamManager,
    private val isCurrent: () -> Boolean,
) : StreamManager {
    // Forwarding `settings` matters: the interface default would silently drop them on every stream made through a real connection.
    override suspend fun createStream(vhost: String, name: String, settings: StreamSettings): StreamManagementResult =
        if (isCurrent()) inner.createStream(vhost, name, settings) else StreamManagementResult.Failed("Connection closed")

    override suspend fun deleteStream(vhost: String, name: String): StreamManagementResult =
        if (isCurrent()) inner.deleteStream(vhost, name) else StreamManagementResult.Failed("Connection closed")
}

/** Proxy for a migration executor: once the connection is no longer current nothing reaches the broker (same wording as the other guards). */
internal class GuardedMigrationExecutor(
    private val inner: TopologyMigrationExecutor,
    private val isCurrent: () -> Boolean,
) : TopologyMigrationExecutor {
    override suspend fun execute(action: MigrationAction): ActionResult =
        if (isCurrent()) inner.execute(action) else ActionResult.Failed("Connection closed")
}

/** Proxy for the check: once the connection is no longer current nothing is sent and the answer says why. */
internal class GuardedDeclareCheck(
    private val inner: DeclareCheck,
    private val isCurrent: () -> Boolean,
) : DeclareCheck {
    override suspend fun find(action: MigrationAction): BrokerFinding =
        if (isCurrent()) inner.find(action) else BrokerFinding.Unreadable("Connection closed")
}

/** Proxy for the policy read of the check: once the connection is no longer current nothing is sent and the policy is unreadable. */
internal class GuardedPolicyReader(
    private val inner: dev.caerbannog.core.planner.BrokerPolicyReader,
    private val isCurrent: () -> Boolean,
) : dev.caerbannog.core.planner.BrokerPolicyReader {
    override suspend fun read(vhost: String, name: String): dev.caerbannog.core.planner.PolicyReadResult =
        if (isCurrent()) inner.read(vhost, name) else dev.caerbannog.core.planner.PolicyReadResult.Unreadable
}

internal class GuardedMqttCatalog(
    private val inner: MqttCatalog,
    private val isCurrent: () -> Boolean,
) : MqttCatalog {
    private suspend fun <T> guarded(call: suspend () -> CatalogResult<T>): CatalogResult<T> =
        if (isCurrent()) call() else CatalogResult.Unreachable

    override suspend fun listClients(vhost: String?): CatalogResult<List<MqttClientRow>> =
        guarded { inner.listClients(vhost) }
}

internal class GuardedMqttPublisher(
    private val inner: MqttMessagePublisher,
    private val isCurrent: () -> Boolean,
) : MqttMessagePublisher {
    override suspend fun publish(request: PublishMqttRequest): PublishOutcome =
        if (isCurrent()) inner.publish(request) else PublishOutcome.Failed("Connection closed")
}

internal class GuardedAccessGateway(
    private val inner: dev.caerbannog.core.access.AccessGateway,
    private val isCurrent: () -> Boolean,
) : dev.caerbannog.core.access.AccessGateway {
    override suspend fun whoAmI(): dev.caerbannog.core.access.AccessResult<dev.caerbannog.core.access.WhoAmI> =
        if (isCurrent()) inner.whoAmI() else dev.caerbannog.core.access.AccessResult.Unreachable

    override suspend fun virtualHosts(): dev.caerbannog.core.access.AccessResult<List<String>> =
        if (isCurrent()) inner.virtualHosts() else dev.caerbannog.core.access.AccessResult.Unreachable
}

internal class GuardedQueueMessageInspector(
    private val inner: QueueMessageInspector,
    private val isCurrent: () -> Boolean,
) : QueueMessageInspector {
    override suspend fun inspectQueueMessages(vhost: String, queue: String, count: Int): QueueInspectionResult =
        if (isCurrent()) inner.inspectQueueMessages(vhost, queue, count) else QueueInspectionResult.Failed("Connection closed")
}


