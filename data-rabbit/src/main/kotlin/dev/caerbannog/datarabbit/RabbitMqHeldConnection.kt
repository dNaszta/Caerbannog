package dev.caerbannog.datarabbit

import dev.caerbannog.core.browser.StreamMessageReader
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.publish.MessagePublisher
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamManager
import dev.caerbannog.core.streams.StreamOffsets
import dev.caerbannog.core.streams.UnavailableStreamManager
import dev.caerbannog.core.topology.TopologyCatalog
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
