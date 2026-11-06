package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.Producer
import dev.caerbannog.core.publish.MessagePublisher
import dev.caerbannog.core.publish.PublishMessageRequest
import dev.caerbannog.core.publish.PublishOutcome
import dev.caerbannog.core.publish.PublishTargetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Real RabbitMQ message publisher executing test message sends:
 * - Streams: native AMQP 1.0 messages published via RabbitMQ Stream Client producer.
 * - Exchanges: HTTP POST via RabbitMQ Management API (`/api/exchanges/{vhost}/{exchange}/publish`).
 * - Queues: HTTP POST to the default exchange (`amq.default`) with routing key = queue name.
 */
class RabbitMqMessagePublisher internal constructor(
    private val environments: StreamEnvironments,
    private val http: ManagementHttp,
    private val requests: ManagementRequests,
) : MessagePublisher {

    override suspend fun publish(request: PublishMessageRequest): PublishOutcome = withContext(Dispatchers.IO) {
        when (request.target.kind) {
            PublishTargetKind.STREAM -> publishStream(request)
            PublishTargetKind.EXCHANGE -> publishExchange(request)
            PublishTargetKind.QUEUE -> publishQueue(request)
        }
    }

    private fun publishStream(request: PublishMessageRequest): PublishOutcome {
        val env = try {
            environments.environmentFor(request.vhost)
        } catch (e: Exception) {
            return PublishOutcome.Failed("Failed to connect to stream protocol: ${e.message}")
        }
        val producer: Producer = try {
            env.producerBuilder().stream(request.target.name).build()
        } catch (e: Exception) {
            return PublishOutcome.Refused("Stream '${request.target.name}' refused: ${e.message}")
        }

        val messageBuilder = producer.messageBuilder().addData(request.payload)
        val messageId = request.properties["messageId"]
        val contentType = request.properties["contentType"] ?: request.properties["content-type"]
        if (messageId != null || contentType != null) {
            val props = messageBuilder.properties()
            if (messageId != null) props.messageId(messageId)
            if (contentType != null) props.contentType(contentType)
            props.messageBuilder()
        }
        if (request.applicationProperties.isNotEmpty()) {
            val appProps = messageBuilder.applicationProperties()
            request.applicationProperties.forEach { (k, v) -> appProps.entry(k, v) }
            appProps.messageBuilder()
        }

        val msg = messageBuilder.build()
        val latch = CountDownLatch(1)
        val confirmed = AtomicBoolean(false)
        val errorMessage = AtomicReference<String?>(null)

        try {
            producer.send(msg) { confirmation ->
                if (confirmation.isConfirmed) {
                    confirmed.set(true)
                } else {
                    errorMessage.set("Broker did not confirm stream message publish")
                }
                latch.countDown()
            }
        } catch (e: Exception) {
            runCatching { producer.close() }
            return PublishOutcome.Refused("Stream publish refused by broker: ${e.message}")
        }

        val finished = latch.await(10, TimeUnit.SECONDS)
        runCatching { producer.close() }

        if (!finished) {
            return PublishOutcome.Failed("Stream publish confirmation timed out after 10 seconds")
        }
        if (!confirmed.get()) {
            return PublishOutcome.Refused(errorMessage.get() ?: "Message was rejected by the broker")
        }
        return PublishOutcome.Success("Message published to stream '${request.target.name}'")
    }

    private suspend fun publishExchange(request: PublishMessageRequest): PublishOutcome {
        val vhost = request.vhost
        val exchange = request.target.name
        val routingKey = request.target.routingKey.orEmpty()
        return postManagementPublish(vhost, exchange, routingKey, request)
    }

    private suspend fun publishQueue(request: PublishMessageRequest): PublishOutcome {
        val vhost = request.vhost
        val queue = request.target.name
        // In RabbitMQ Management API, publishing directly to a queue routes through amq.default
        // with routing_key = queue name.
        return postManagementPublish(vhost, "amq.default", queue, request)
    }

    private suspend fun postManagementPublish(
        vhost: String,
        exchange: String,
        routingKey: String,
        request: PublishMessageRequest,
    ): PublishOutcome {
        val payloadStr = request.payload.decodeToString()
        val jsonBody = buildJsonObject {
            putJsonObject("properties") {
                request.properties["messageId"]?.let { put("message_id", it) }
                (request.properties["contentType"] ?: request.properties["content-type"])?.let { put("content_type", it) }
                if (request.applicationProperties.isNotEmpty()) {
                    putJsonObject("headers") {
                        request.applicationProperties.forEach { (k, v) -> put(k, v) }
                    }
                }
            }
            put("routing_key", routingKey)
            put("payload", payloadStr)
            put("payload_encoding", "string")
        }.toString()

        val path = requests.publishExchange(vhost, exchange)
        return when (val reply = http.post(path, jsonBody)) {
            is ManagementReply.Response -> {
                when (reply.status) {
                    200 -> {
                        try {
                            val parsed = Json.parseToJsonElement(reply.body).jsonObject
                            val routed = parsed["routed"]?.jsonPrimitive?.content?.toBoolean() ?: true
                            val targetDesc = when (request.target.kind) {
                                PublishTargetKind.EXCHANGE -> "exchange '${request.target.name}'"
                                PublishTargetKind.QUEUE -> "queue '${request.target.name}'"
                                else -> request.target.name
                            }
                            if (routed) {
                                PublishOutcome.Success("Message published to $targetDesc")
                            } else {
                                PublishOutcome.Success("Message published to $targetDesc (unrouted)")
                            }
                        } catch (_: Exception) {
                            PublishOutcome.Success("Message published")
                        }
                    }
                    401 -> PublishOutcome.Refused("User does not have write access to virtual host '$vhost'")
                    404 -> PublishOutcome.Refused("Target not found on virtual host '$vhost'")
                    else -> PublishOutcome.Failed("Broker returned HTTP ${reply.status}: ${reply.body.take(100)}")
                }
            }
            ManagementReply.Unreachable -> PublishOutcome.Failed("Broker management API unreachable")
            ManagementReply.CertificateRejected -> PublishOutcome.Failed("Broker certificate rejected")
            ManagementReply.NoTlsAnswer -> PublishOutcome.Failed("Broker refused TLS connection")
        }
    }
}
