package dev.caerbannog.datarabbit

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import com.rabbitmq.client.ShutdownSignalException
import org.testcontainers.containers.RabbitMQContainer
import java.util.concurrent.CopyOnWriteArrayList

/** M8-13 (S-O, S-T) spike helper: a plain AMQP 0-9-1 connection to a Testcontainers broker. Test-only, never product code. */
internal fun amqpConnection(container: RabbitMQContainer, name: String = "spike"): Connection =
    ConnectionFactory().apply {
        host = container.host
        port = container.amqpPort
        username = "guest"
        password = "guest"
        isAutomaticRecoveryEnabled = false
    }.newConnection(name)

/** Runs [action]; returns the broker's refusal text (reply code and text, one line) or null when the broker accepted it. */
internal fun refusal(action: () -> Unit): String? = try {
    action(); null
} catch (e: Exception) {
    val signal = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<ShutdownSignalException>().firstOrNull()
    (signal?.reason?.toString() ?: e.toString()).replace("\n", " ")
}

/** One delivery seen by a consumer. [deliveryCount] is the `x-delivery-count` header (quorum queues only), null when absent. */
internal data class Delivery(val body: String, val redelivered: Boolean, val deliveryCount: Long?)

/** A consumer that records every delivery and then calls [onDelivery] (nack, ack, nothing). */
internal class RecordingConsumer(
    channel: Channel,
    private val onDelivery: (Channel, Envelope) -> Unit = { _, _ -> },
) : DefaultConsumer(channel) {
    val seen = CopyOnWriteArrayList<Delivery>()
    override fun handleDelivery(consumerTag: String, envelope: Envelope, properties: AMQP.BasicProperties, body: ByteArray) {
        seen += Delivery(String(body), envelope.isRedeliver, (properties.headers?.get("x-delivery-count") as? Number)?.toLong())
        onDelivery(channel, envelope)
    }
}

/** Opens a connection, runs [block], and closes it even when the broker already closed it (a refused channel operation can do that). */
internal fun <T> withAmqp(container: RabbitMQContainer, name: String = "spike", block: (Connection) -> T): T {
    val conn = amqpConnection(container, name)
    try {
        return block(conn)
    } finally {
        try { conn.close() } catch (_: Exception) { /* already closed by the broker */ }
    }
}
