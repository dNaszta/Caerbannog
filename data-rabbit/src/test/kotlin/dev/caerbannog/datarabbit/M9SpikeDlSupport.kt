package dev.caerbannog.datarabbit

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.OffsetSpecification
import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.core.connections.ConnectionProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * M9-00b spike support (`RealBrokerM9SpikeDeadLetterTest`, `RealBrokerM9SpikeStreamDeadLetterTest`; docs/tasks/M9-00b-spike-results.md). Private to
 * these two classes' files; it uses `MgmtApi`, `awaitUntil` (M8SpikeSupport.kt), `brokerWithPlugins` (RabbitContainers.kt), `withAmqp`
 * (M8SpikeAmqpSupport.kt) unchanged.
 */
internal object M9SpikeDl {
    val IMAGE: String = BrokerImages.CURRENT_4X

    fun newBroker(): RabbitMQContainer =
        brokerWithPlugins(IMAGE, "rabbitmq_stream", "rabbitmq_stream_management").also { it.addExposedPort(5552) }

    fun assertFourX(api: MgmtApi) {
        val version = api.get("/api/overview").obj["rabbitmq_version"]!!.jsonPrimitive.content
        assertTrue(version.startsWith("4."), "${BrokerImages.CURRENT_4X} answered version $version; the M9-00b facts are measured on 4.x")
        println("M9SPIKE version=$version erlang=${api.get("/api/overview").obj["erlang_version"]}")
    }

    fun fixture(name: String): String =
        checkNotNull(M9SpikeDl::class.java.getResource("/fixtures/m9-00/$name")) { "missing fixture $name" }.readText()

    fun note(s: String) = println("M9SPIKE $s")

    fun detail(api: MgmtApi, queue: String): JsonObject = api.get("/api/queues/%2F/$queue").obj

    fun effective(api: MgmtApi, queue: String): JsonObject? =
        detail(api, queue)["effective_policy_definition"]?.takeIf { it !is JsonNull }?.jsonObject

    fun policyOf(api: MgmtApi, queue: String): String? =
        detail(api, queue)["policy"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    /** The message count the management API reports (statistics lag up to 5 s: poll). */
    fun messages(api: MgmtApi, queue: String): Int = detail(api, queue)["messages"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

    /** One queue message as the management `get` shows it (the requeue peek, the same call as `ManagementQueueMessageInspector`). */
    fun peekFull(api: MgmtApi, queue: String, count: Int = 50): List<JsonObject> =
        api.post("/api/queues/%2F/$queue/get", """{"count":$count,"ackmode":"ack_requeue_true","encoding":"auto"}""").let {
            check(it.ok) { "peek $queue failed: $it" }
            (it.json as JsonArray).map { m -> m.jsonObject }
        }

    fun headersOf(m: JsonObject): JsonObject = m["properties"]!!.jsonObject["headers"]?.takeIf { it !is JsonNull }?.jsonObject ?: JsonObject(emptyMap())

    /** Bounded wait until [queue] shows at least [n] messages to the peek (no statistics lag: a `get` is immediate). */
    fun awaitPeek(api: MgmtApi, queue: String, n: Int = 1, seconds: Long = 40): List<JsonObject> {
        var last = emptyList<JsonObject>()
        awaitUntil("$n message(s) in $queue (saw ${last.size})", seconds) { last = peekFull(api, queue); last.size >= n }
        return last
    }

    /** Polls [seconds] seconds on the clock and fails if the peek of [queue] ever shows something; call it AFTER a positive control. */
    fun assertStaysEmpty(api: MgmtApi, queue: String, what: String, seconds: Long = 6) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        awaitUntil("settle window: $what", seconds + 30) {
            check(peekFull(api, queue).isEmpty()) { "something arrived in $queue although nothing should: $what" }
            System.nanoTime() >= end
        }
    }

    /** Waits for [positive] (a control of the same batch), then watches [stillAbsent] for [settleSeconds] on the clock. */
    fun assertStaysAbsent(what: String, settleSeconds: Long = 8, positive: () -> Boolean, stillAbsent: () -> Boolean) {
        awaitUntil("positive control for: $what", timeoutSeconds = 90, condition = positive)
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(settleSeconds)
        awaitUntil("settle window for: $what", timeoutSeconds = settleSeconds + 30) {
            check(stillAbsent()) { "it appeared although it must not: $what" }
            System.nanoTime() >= end
        }
    }

    /** Waits until the broker reports [key] in the queue's effective policy (a policy needs about 6 s; poll up to 90 s). */
    fun awaitEffective(api: MgmtApi, queue: String, key: String = "dead-letter-exchange") =
        awaitUntil("policy key $key effective on $queue", 90) { effective(api, queue)?.containsKey(key) == true }

    /** Takes one message off [queue] with basicGet and returns its tag, or fails after [seconds]. */
    fun getOne(ch: Channel, queue: String, seconds: Long = 30): com.rabbitmq.client.GetResponse {
        var r: com.rabbitmq.client.GetResponse? = null
        awaitUntil("a message to get from $queue", seconds) { r = ch.basicGet(queue, false); r != null }
        return r!!
    }

    fun rejectOne(conn: Connection, queue: String) {
        val ch = conn.createChannel()
        val r = getOne(ch, queue)
        ch.basicReject(r.envelope.deliveryTag, false)
        ch.close()
    }

    fun persistent(expirationMs: Long? = null): AMQP.BasicProperties =
        AMQP.BasicProperties.Builder().deliveryMode(2).also { if (expirationMs != null) it.expiration(expirationMs.toString()) }.build()

    /** The app's stream reader on a stream: what the Messages screen would show. */
    fun readWithApp(container: RabbitMQContainer, stream: String): List<StreamMessage> = runBlocking {
        val profile = ConnectionProfile(
            name = "m9b", host = container.host, virtualHost = "/", username = container.adminUsername,
            streamPort = container.getMappedPort(5552), managementPort = container.getMappedPort(15672), tls = false,
        )
        val transport = BrokerTransport.from(profile)
        StreamEnvironments(profile, container.adminPassword, transport).use { envs ->
            ManagementHttp(TlsSupport.buildHttpClient(transport, 15_000), container.adminUsername, container.adminPassword).use { http ->
                val reader = RabbitMqStreamMessageReader(envs, ManagementStreamCatalog(http, ManagementRequests.forProfile(profile)))
                val result = reader.readPage("/", stream, BrowseStart.First, pageSize = 100)
                assertInstanceOf(BrowseResult.Success::class.java, result, "got $result").messages
            }
        }
    }

    /** One stream message through the raw stream client, annotations included (which `StreamMessage` does not carry whole). */
    data class RawStreamMessage(val body: String, val annotations: Map<String, Any?>, val properties: Map<String, Any?>, val application: Map<String, Any?>)

    fun readRaw(container: RabbitMQContainer, stream: String, expect: Int, seconds: Long = 40): List<RawStreamMessage> {
        val seen = CopyOnWriteArrayList<RawStreamMessage>()
        Environment.builder().host(container.host).port(container.getMappedPort(5552))
            .username(container.adminUsername).password(container.adminPassword).build().use { env ->
            env.consumerBuilder().stream(stream).offset(OffsetSpecification.first()).noTrackingStrategy()
                .messageHandler { _, m ->
                    seen += RawStreamMessage(
                        String(m.bodyAsBinary),
                        m.messageAnnotations?.entries?.associate { it.key to render(it.value) } ?: emptyMap(),
                        m.properties?.let { p -> mapOf("messageId" to p.messageId, "contentType" to p.contentType, "subject" to p.subject, "to" to p.to, "creationTime" to p.creationTime) } ?: emptyMap(),
                        m.applicationProperties?.entries?.associate { it.key to render(it.value) } ?: emptyMap(),
                    )
                }.build().use {
                    awaitUntil("$expect message(s) read from stream $stream (saw ${seen.size})", seconds) { seen.size >= expect }
                }
        }
        return seen.toList()
    }

    private fun render(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.associate { it.key.toString() to render(it.value) }
        is List<*> -> v.map { render(it) }
        is Array<*> -> v.map { render(it) }
        is ByteArray -> String(v)
        else -> v
    }

    /** Every resource a test created, deleted in reverse at the end (policies first). */
    class Cleanup(private val api: MgmtApi) {
        private val paths = mutableListOf<String>()
        fun queue(n: String) = n.also { paths += "/api/queues/%2F/$it" }
        fun exchange(n: String) = n.also { paths += "/api/exchanges/%2F/$it" }
        fun policy(n: String) = n.also { paths += "/api/policies/%2F/$it" }
        fun run() { paths.asReversed().forEach { runCatching { api.delete(it) } }; paths.clear() }
    }
}
