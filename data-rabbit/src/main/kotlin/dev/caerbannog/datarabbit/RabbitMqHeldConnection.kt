package dev.caerbannog.datarabbit

import dev.caerbannog.core.browser.StreamMessageReader
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.mqtt.MqttCatalog
import dev.caerbannog.core.mqtt.UnavailableMqttCatalog
import dev.caerbannog.core.publish.MessagePublisher
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamManager
import dev.caerbannog.core.streams.StreamOffsets
import dev.caerbannog.core.streams.UnavailableStreamManager
import dev.caerbannog.core.topology.QueueMessageInspector
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.core.topology.UnavailableQueueMessageInspector
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The per-connection holder (D6): owns the HTTP client and the stream environments through [resources].
 * [close] closes each exactly once, never throws, and is idempotent. When one close throws, only the exception
 * CLASS NAME is logged: a message or stack trace could carry a host, a URL or the password.
 */
class RabbitMqHeldConnection(
    override val catalog: StreamCatalog,
    override val offsets: StreamOffsets,
    override val topology: TopologyCatalog,
    override val messages: StreamMessageReader,
    override val publisher: MessagePublisher,
    override val manager: StreamManager = UnavailableStreamManager,
    private val resources: List<AutoCloseable>,
    private val log: (String) -> Unit,
    override val mqtt: MqttCatalog = UnavailableMqttCatalog,
    override val mqttPublisher: dev.caerbannog.core.mqtt.MqttMessagePublisher = dev.caerbannog.core.mqtt.UnavailableMqttMessagePublisher,
    override val queueInspector: QueueMessageInspector = UnavailableQueueMessageInspector,
    /** M5-08a: the management migration executor of this connection (the gateway passes it); null when none. */
    override val migrationExecutor: dev.caerbannog.core.migrations.TopologyMigrationExecutor? = null,
    /** M5-08a R1: the fixed reason there is no executor (3.x, or the version could not be read). */
    override val migrationUnavailableReason: String? = null,
    /** The displayable version text the gateway read from the overview; null when unreadable. */
    override val brokerVersion: String? = null,
    /** The GET-only reads of a 3.x connection; null for 4.x and unknown versions. */
    override val threeXReads: dev.caerbannog.core.migrate3x.ThreeXReads? = null,
    /** M8-09: the management shovel gateway of a 4.x connection; the unavailable one for a 3.x source. */
    override val shovels: dev.caerbannog.core.forwarding.ShovelGateway = dev.caerbannog.core.forwarding.UnavailableShovelGateway,
    override val shovelStatus: dev.caerbannog.core.forwarding.ShovelStatusReader = dev.caerbannog.core.forwarding.UnavailableShovelStatusReader,
) : HeldConnection {
    private val closed = AtomicBoolean(false)

    // The one broad catch of the holder: every resource must get its close, whatever the previous one threw.
    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        for (resource in resources) {
            try {
                resource.close()
            } catch (e: Exception) {
                log("Closing a connection resource failed: ${e.javaClass.name}")
            }
        }
    }
}
