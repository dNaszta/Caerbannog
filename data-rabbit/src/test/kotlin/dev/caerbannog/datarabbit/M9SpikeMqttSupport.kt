package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Address
import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.OffsetSpecification
import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.core.connections.ConnectionProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.paho.mqttv5.client.IMqttMessageListener
import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.MqttSubscription
import org.eclipse.paho.mqttv5.common.packet.MqttProperties
import org.eclipse.paho.mqttv5.common.packet.UserProperty
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/*
 * M9-00a spike helpers. Private to this spike in spirit (every name starts with M9SpikeMqtt or m9), kept in one file
 * so the four spike classes share them; the other M9 spikes have their own helpers. Facts are written in
 * docs/tasks/M9-00a-spike-results.md. Throwaway Testcontainers brokers with the default guest user only.
 */

internal const val M9_IMAGE = "rabbitmq:4-management"

/** A 4.x broker with the stream, stream management and MQTT plugins, plain ports 5552 (stream) and 1883 (MQTT) published. */
internal fun m9SpikeBroker(config: String? = null): RabbitMQContainer =
    brokerWithPlugins(M9_IMAGE, "rabbitmq_stream", "rabbitmq_stream_management", "rabbitmq_mqtt")
        .apply {
            addExposedPorts(5552, 1883)
            if (config != null) withRabbitMQConfig(org.testcontainers.utility.MountableFile.forClasspathResource(config))
        }

/** Writes a measured line to stdout and, when M9_NOTES_FILE is set, appends it to that file (used to collect the results). */
internal fun m9Note(item: String, line: String) {
    val text = "[$item] $line"
    println(text)
    System.getenv("M9_NOTES_FILE")?.let { File(it).appendText(text + "\n") }
}

/** Writes a fixture (a broker answer of the throwaway container) when M9_FIXTURE_DIR is set. */
internal fun m9Fixture(name: String, content: String) {
    System.getenv("M9_FIXTURE_DIR")?.let { File(it, name).writeText(content.trimEnd() + "\n") }
}

internal data class M9SpikeMqttReceived(
    val topic: String,
    val payload: String,
    val qos: Int,
    val retained: Boolean,
    val userProperties: List<Pair<String, String>>,
    val correlationData: String?,
    val responseTopic: String?,
    val contentType: String?,
)

/** A Paho MQTT 5 client against the plain MQTT port of a throwaway broker. Subscriptions collect what they receive. */
internal class M9SpikeMqttClient(private val rabbit: RabbitMQContainer, val clientId: String) : AutoCloseable {
    private val client = MqttClient("tcp://${rabbit.host}:${rabbit.getMappedPort(1883)}", clientId, MemoryPersistence())
    val received = ConcurrentLinkedQueue<M9SpikeMqttReceived>()
    val isConnected: Boolean get() = client.isConnected

    fun connect(cleanStart: Boolean = true): M9SpikeMqttClient {
        client.connect(MqttConnectionOptions().apply {
            isCleanStart = cleanStart
            connectionTimeout = 10
            userName = "guest"
            password = "guest".toByteArray()
        })
        return this
    }

    /** Subscribes; returns null when accepted, or the reason codes the broker answered. */
    fun subscribe(filter: String, qos: Int = 1): String? = try {
        val listener = IMqttMessageListener { topic, message ->
            received.add(
                M9SpikeMqttReceived(
                    topic, message.payload.decodeToString(), message.qos, message.isRetained,
                    message.properties?.userProperties.orEmpty().map { it.key to it.value },
                    message.properties?.correlationData?.decodeToString(), message.properties?.responseTopic,
                    message.properties?.contentType,
                ),
            )
        }
        // The (String, int, listener) overload recurses forever in Paho 1.2.5; the array overload works.
        val token = client.subscribe(arrayOf(MqttSubscription(filter, qos)), arrayOf(listener))
        val codes = token.reasonCodes?.toList().orEmpty()
        if (codes.all { it < 128 }) null else "refused, reason codes $codes"
    } catch (e: MqttException) {
        "refused: reason ${e.reasonCode} ${e.message}"
    } catch (e: IllegalArgumentException) {
        "refused by the Paho client before sending: ${e.message}"
    }

    /** Publishes and waits for the acknowledgement; returns null when accepted, or the failure text. */
    fun publish(
        topic: String,
        payload: String,
        qos: Int = 1,
        retain: Boolean = false,
        userProperties: List<Pair<String, String>> = emptyList(),
        correlationData: ByteArray? = null,
        responseTopic: String? = null,
        contentType: String? = null,
    ): String? = try {
        val message = MqttMessage(payload.toByteArray()).apply {
            this.qos = qos
            isRetained = retain
            if (userProperties.isNotEmpty() || correlationData != null || responseTopic != null || contentType != null) {
                properties = MqttProperties().apply {
                    if (userProperties.isNotEmpty()) this.userProperties = userProperties.map { UserProperty(it.first, it.second) }
                    this.correlationData = correlationData
                    this.responseTopic = responseTopic
                    this.contentType = contentType
                }
            }
        }
        client.publish(topic, message)
        null
    } catch (e: MqttException) {
        "refused: reason ${e.reasonCode} ${e.message}"
    } catch (e: IllegalArgumentException) {
        "refused by the Paho client before sending: ${e.message}"
    }

    fun awaitReceived(what: String, count: Int = 1, timeoutSeconds: Long = 30): List<M9SpikeMqttReceived> {
        awaitUntil("$what on $clientId", timeoutSeconds) { received.size >= count }
        return received.toList()
    }

    override fun close() {
        runCatching { if (client.isConnected) client.disconnectForcibly(1000) }
        runCatching { client.close() }
    }
}

/** What a stream message carries, read with the stream client (the raw facts). */
internal data class M9SpikeRaw(
    val payload: String,
    val properties: Map<String, String>,
    val applicationProperties: Map<String, String>,
    val annotations: Map<String, String>,
)

private fun m9Env(rabbit: RabbitMQContainer): Environment {
    val host = rabbit.host
    val port = rabbit.getMappedPort(5552)
    return Environment.builder().host(host).port(port).username("guest").password("guest")
        .addressResolver { Address(host, port) }.build()
}

/** Puts a message straight into a stream through the stream protocol (not through an exchange). */
internal fun m9PutIntoStream(rabbit: RabbitMQContainer, stream: String, payload: String) {
    m9Env(rabbit).use { env ->
        val confirmed = java.util.concurrent.CountDownLatch(1)
        env.producerBuilder().stream(stream).build().use { p ->
            p.send(p.messageBuilder().addData(payload.toByteArray()).build()) { confirmed.countDown() }
            check(confirmed.await(30, TimeUnit.SECONDS)) { "direct stream publish was not confirmed" }
        }
    }
}

/**
 * Reads a stream from the first offset with an unnamed, non-tracking consumer until the message with [control] as payload
 * has been seen (the control is the last message published, so everything that arrived before it is in the result).
 * Fails when the control does not arrive: "nothing arrived" is only ever asserted after this positive control.
 */
internal fun m9StreamUntilControl(rabbit: RabbitMQContainer, stream: String, control: String, timeoutSeconds: Long = 30): List<M9SpikeRaw> {
    val messages = java.util.Collections.synchronizedList(mutableListOf<M9SpikeRaw>())
    val done = java.util.concurrent.CountDownLatch(1)
    m9Env(rabbit).use { env ->
        env.consumerBuilder().stream(stream).offset(OffsetSpecification.first()).noTrackingStrategy()
            .messageHandler { _, m ->
                val p = m.properties
                val props = linkedMapOf<String, String>()
                fun put(k: String, v: Any?) { if (v != null) props[k] = m9Show(v) }
                if (p != null) {
                    put("message-id", p.messageId); put("user-id", p.userId); put("to", p.to); put("subject", p.subject)
                    put("reply-to", p.replyTo); put("correlation-id", p.correlationId); put("content-type", p.contentType)
                    put("content-encoding", p.contentEncoding); put("absolute-expiry-time", p.absoluteExpiryTime)
                    put("creation-time", p.creationTime); put("group-id", p.groupId)
                }
                val body = m.bodyAsBinary?.decodeToString().orEmpty()
                messages.add(
                    M9SpikeRaw(
                        body, props,
                        m.applicationProperties.orEmpty().mapValues { (_, v) -> m9Show(v) },
                        m.messageAnnotations.orEmpty().mapValues { (_, v) -> m9Show(v) },
                    ),
                )
                if (body == control) done.countDown()
            }.build().use {
                check(done.await(timeoutSeconds, TimeUnit.SECONDS)) {
                    "control message '$control' never arrived in stream $stream; got ${messages.map { it.payload }}"
                }
            }
    }
    return messages.toList()
}

private fun m9Show(value: Any?): String = when (value) {
    null -> "null"
    is ByteArray -> if (value.all { it in 32..126 }) "bytes:" + value.decodeToString() else "bytes:0x" + value.joinToString("") { "%02x".format(it) }
    is String -> "text:$value"
    is Boolean -> "boolean:$value"
    is Number -> "${value.javaClass.simpleName}:$value"
    else -> "${value.javaClass.simpleName}:$value"
}

/** The app's own stream reader on a plain profile: the only app code the spike exercises (x-routing-key, x-exchange). */
internal fun m9ReadWithAppReader(rabbit: RabbitMQContainer, stream: String): List<StreamMessage> = runBlocking {
    val profile = ConnectionProfile(
        name = "m9-spike", host = rabbit.host, virtualHost = "/", username = "guest",
        streamPort = rabbit.getMappedPort(5552), managementPort = rabbit.getMappedPort(15672), tls = false,
    )
    val transport = BrokerTransport.from(profile)
    StreamEnvironments(profile, "guest", transport).use { envs ->
        val mgmtHttp = ManagementHttp(TlsSupport.buildHttpClient(transport, 15_000), "guest", "guest")
        mgmtHttp.use {
            val reader = RabbitMqStreamMessageReader(envs, ManagementStreamCatalog(mgmtHttp, ManagementRequests.forProfile(profile)))
            val result = reader.readPage("/", stream, BrowseStart.First, pageSize = 100)
            assertInstanceOf(BrowseResult.Success::class.java, result, "got $result").messages
        }
    }
}

/** A message read from a queue through the management API (requeue peek), reduced to what the spike judges. */
internal data class M9SpikeQueued(val payload: String, val routingKey: String, val exchange: String, val properties: JsonObject)

/** Peeks (ack_requeue_true) until the message with [control] as payload is visible; returns everything seen. Throwaway broker only. */
internal fun m9PeekUntilControl(api: MgmtApi, queue: String, control: String, timeoutSeconds: Long = 30): List<M9SpikeQueued> {
    var seen = emptyList<M9SpikeQueued>()
    awaitUntil("control '$control' visible in queue $queue", timeoutSeconds) {
        seen = m9Peek(api, queue)
        seen.any { it.payload == control }
    }
    return seen
}

internal fun m9Peek(api: MgmtApi, queue: String): List<M9SpikeQueued> {
    val reply = api.post("/api/queues/%2F/$queue/get", """{"count":1000,"ackmode":"ack_requeue_true","encoding":"auto"}""")
    check(reply.ok) { "peek of $queue failed: $reply" }
    return (reply.json as JsonArray).map {
        val o = it as JsonObject
        M9SpikeQueued(
            (o["payload"] as JsonPrimitive).content, (o["routing_key"] as? JsonPrimitive)?.content.orEmpty(),
            (o["exchange"] as? JsonPrimitive)?.content.orEmpty(), (o["properties"] as? JsonObject) ?: JsonObject(emptyMap()),
        )
    }
}

/** Creates queues, exchanges and bindings with unique names and deletes them again (best effort) when closed. */
internal class M9SpikeScope(private val api: MgmtApi) : AutoCloseable {
    private val queues = mutableListOf<String>()
    private val exchanges = mutableListOf<String>()
    private val tag = System.nanoTime().toString(36)

    private var counter = 0
    fun name(prefix: String) = "$prefix-$tag-${counter++}"

    fun stream(prefix: String): String = name(prefix).also {
        val r = api.declareQueue(it, """{"x-queue-type":"stream"}"""); check(r.ok) { "declare stream $it: $r" }; queues += it
    }

    fun quorum(prefix: String): String = name(prefix).also {
        val r = api.declareQueue(it, """{"x-queue-type":"quorum"}"""); check(r.ok) { "declare quorum $it: $r" }; queues += it
    }

    fun exchange(prefix: String, type: String): String = name(prefix).also {
        val r = api.put("/api/exchanges/%2F/$it", """{"type":"$type","durable":true,"auto_delete":false,"arguments":{}}"""); check(r.ok) { "declare exchange $it: $r" }; exchanges += it
    }

    fun bind(exchange: String, queue: String, key: String, arguments: String = "{}"): MgmtApi.Reply =
        api.post("/api/bindings/%2F/e/$exchange/q/$queue", """{"routing_key":${JsonPrimitive(key)},"arguments":$arguments}""")

    override fun close() {
        queues.forEach { runCatching { api.delete("/api/queues/%2F/$it") } }
        exchanges.forEach { runCatching { api.delete("/api/exchanges/%2F/$it") } }
    }
}

internal fun m9AssertBrokerIs4x(api: MgmtApi) {
    val overview = api.get("/api/overview")
    check(overview.ok) { "overview failed: $overview" }
    val version = (overview.obj["rabbitmq_version"] as JsonPrimitive).content
    check(version.startsWith("4.")) { "the spike is written for a 4.x broker, got $version" }
    m9Note("broker", "rabbitmq_version=$version erlang=${(overview.obj["erlang_version"] as? JsonPrimitive)?.content} product=${(overview.obj["product_version"] as? JsonPrimitive)?.content}")
}
