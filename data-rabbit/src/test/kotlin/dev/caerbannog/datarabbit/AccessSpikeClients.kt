package dev.caerbannog.datarabbit

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.ShutdownSignalException
import com.rabbitmq.stream.Address
import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.OffsetSpecification
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.eclipse.paho.mqttv5.common.MqttException
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.util.concurrent.TimeUnit

/*
 * M10-00 spike clients: AMQP 0-9-1, stream and MQTT fixtures against a throwaway broker. The rule "the app's own process
 * never reads queue messages" concerns product code; these run on a throwaway container and are never copied there.
 */

/** A user the spike can sign in as: an internal user (password) or a token user (the JWT is the password). */
internal data class Persona(val label: String, val user: String, val secret: String, val token: Boolean, val vhost: String) {
    val basic: AccessAuth.Basic get() = AccessAuth.Basic(user, secret)
    val bearer: AccessAuth.Bearer get() = AccessAuth.Bearer(secret)
}

/** One throwaway broker plus the management client of it. */
internal class AccessEnv(val name: String, val container: RabbitMQContainer, val tlsManagement: Boolean = false) {
    val host: String get() = container.host
    val http = AccessHttp("http://${container.host}:${container.getMappedPort(15672)}")
    val guest = AccessAuth.Basic("guest", "guest")
    val httpsOrNull: AccessHttp? = if (tlsManagement) AccessHttp("https://${container.host}:${container.getMappedPort(15671)}", trustAll = true) else null

    fun admin(method: String, path: String, body: String? = null): AccessReply = http.call(method, path, guest, body)

    fun encode(vhost: String): String = java.net.URLEncoder.encode(vhost, Charsets.UTF_8)

    fun addVhost(vhost: String) {
        check(admin("PUT", "/api/vhosts/${encode(vhost)}").ok) { "$name: vhost $vhost not created" }
        check(admin("PUT", "/api/permissions/${encode(vhost)}/guest", """{"configure":".*","write":".*","read":".*"}""").ok)
    }

    fun addUser(user: String, tags: String, vhost: String?, configure: String = ".*", write: String = ".*", read: String = ".*") {
        check(admin("PUT", "/api/users/$user", """{"password":"$ACCESS_PASSWORD","tags":"$tags"}""").ok) { "$name: user $user not created" }
        if (vhost != null) setPermissions(user, vhost, configure, write, read)
    }

    fun setPermissions(user: String, vhost: String, configure: String, write: String, read: String): AccessReply =
        admin("PUT", "/api/permissions/${encode(vhost)}/$user", buildJsonObject {
            put("configure", configure); put("write", write); put("read", read)
        }.toString())

    fun setTopicPermissions(user: String, vhost: String, exchange: String, write: String, read: String): AccessReply =
        admin("PUT", "/api/topic-permissions/${encode(vhost)}/$user", buildJsonObject {
            put("exchange", exchange); put("write", write); put("read", read)
        }.toString())

    fun deleteUser(user: String) { admin("DELETE", "/api/users/$user") }

    fun amqpPort(): Int = container.amqpPort
    fun streamPort(): Int = container.getMappedPort(5552)
    fun mqttPort(): Int = container.getMappedPort(1883)

    /** Polls [path] as admin until [condition] holds, bounded; statistics lag is about 5 s (spec "Statistics lag"). */
    fun awaitJson(what: String, path: String, seconds: Long = 45, condition: (AccessReply) -> Boolean): AccessReply {
        var last = AccessReply(0, "")
        awaitUntil(what, seconds) { last = http.get(path, guest); last.ok && condition(last) }
        return last
    }
}

/** Opens an AMQP connection as [user]/[secret] to [vhost]. */
internal fun accessAmqp(env: AccessEnv, user: String, secret: String, vhost: String, connectionName: String): Connection =
    ConnectionFactory().apply {
        host = env.host; port = env.amqpPort()
        username = user; password = secret; virtualHost = vhost
        isAutomaticRecoveryEnabled = false
        connectionTimeout = 10_000
    }.newConnection(connectionName)

/** The result of an operation under a permission: what the broker did. */
internal fun outcomeOf(action: () -> Unit): String = try {
    action(); "allowed"
} catch (e: Throwable) {
    val signal = generateSequence(e) { it.cause }.filterIsInstance<ShutdownSignalException>().firstOrNull()
    val text = (signal?.reason?.toString() ?: "${e.javaClass.simpleName}: ${e.message}").replace(Regex("\\s+"), " ")
    "refused: " + text.take(220)
}

/** Opens a connection and channel as the persona, runs [block] on the channel, closes everything. "allowed" or "refused: ...". */
internal fun amqpOp(env: AccessEnv, user: String, secret: String, vhost: String, block: (Channel) -> Unit): String {
    var conn: Connection? = null
    return try {
        conn = try {
            accessAmqp(env, user, secret, vhost, "spike-op")
        } catch (e: Throwable) {
            return "connect-failed: " + "${e.javaClass.simpleName}: ${e.message}".replace(Regex("\\s+"), " ").take(220)
        }
        outcomeOf { block(conn.createChannel()) }
    } finally {
        try { conn?.close() } catch (_: Exception) { /* closed by the broker already */ }
    }
}

/** Publish through [exchange] and wait for the broker's confirm: a write refusal closes the channel. */
internal fun Channel.publishConfirmed(exchange: String, routingKey: String) {
    confirmSelect()
    basicPublish(exchange, routingKey, null, "spike".toByteArray())
    waitForConfirmsOrDie(5_000)
}

/** Stream operations as [user]: connect, then optionally create / publish / consume a stream. Returns "allowed" or the refusal text. */
internal fun streamOp(env: AccessEnv, user: String, secret: String, op: (Environment) -> Unit): String {
    var environment: Environment? = null
    return try {
        environment = Environment.builder().host(env.host).port(env.streamPort()).username(user).password(secret)
            .addressResolver { Address(env.host, env.streamPort()) }.build()
        outcomeOf { op(environment) }
    } catch (e: Throwable) {
        "connect-failed: " + "${e.javaClass.simpleName}: ${e.message}".replace(Regex("\\s+"), " ").take(220)
    } finally {
        try { environment?.close() } catch (_: Exception) { /* ignore */ }
    }
}

internal fun Environment.publishOne(stream: String) {
    producerBuilder().stream(stream).build().use { producer ->
        val latch = java.util.concurrent.CountDownLatch(1)
        producer.send(producer.messageBuilder().addData("spike".toByteArray()).build()) { latch.countDown() }
        check(latch.await(5, TimeUnit.SECONDS)) { "no publish confirm in 5 s" }
    }
}

internal fun Environment.consumeFirst(stream: String) {
    consumerBuilder().stream(stream).offset(OffsetSpecification.first()).messageHandler { _, _ -> }.build().close()
}

/** An MQTT 5 client as [user]; [block] gets the connected client. "allowed" or "refused: reason code and text". */
internal fun mqttOp(env: AccessEnv, user: String, secret: String, clientId: String, block: (MqttClient) -> Unit): String {
    val client = MqttClient("tcp://${env.host}:${env.mqttPort()}", clientId, MemoryPersistence())
    return try {
        try {
            client.connect(MqttConnectionOptions().apply {
                isCleanStart = true; connectionTimeout = 10
                userName = user; password = secret.toByteArray()
            })
        } catch (e: MqttException) {
            return "connect-failed: code ${e.reasonCode} ${e.message}"
        }
        try { block(client); "allowed" } catch (e: MqttException) { "refused: code ${e.reasonCode} ${e.message}" }
    } finally {
        try { if (client.isConnected) client.disconnect() } catch (_: Exception) { /* ignore */ }
        try { client.close(true) } catch (_: Exception) { /* ignore */ }
    }
}

/** Live clients of one persona kept open for the endpoint measurements: AMQP consumer and publisher, stream publisher and consumer, MQTT subscriber. */
internal class LiveClients(private val env: AccessEnv, val persona: Persona, private val suffix: String, private val withStreamAndMqtt: Boolean = true) : AutoCloseable {
    private val closers = mutableListOf<() -> Unit>()
    val queue = "q.$suffix"
    val exchange = "x.$suffix"
    val stream = "st.$suffix"
    val amqpName = "spike-amqp-$suffix"

    fun open(mqttUser: String = persona.user): LiveClients {
        val vhost = persona.vhost
        val conn = accessAmqp(env, persona.user, persona.secret, vhost, amqpName)
        closers += { runCatching { conn.close() } }
        val ch = conn.createChannel()
        ch.queueDeclare(queue, true, false, false, null)
        ch.exchangeDeclare(exchange, "fanout", false, true, null)
        ch.queueBind(queue, exchange, "")
        ch.basicConsume(queue, true, "consumer-$suffix", object : DefaultConsumer(ch) {})
        val pub = conn.createChannel()
        pub.publishConfirmed(exchange, "")
        if (withStreamAndMqtt) {
            val environment = Environment.builder().host(env.host).port(env.streamPort()).username(persona.user).password(persona.secret)
                .addressResolver { Address(env.host, env.streamPort()) }.build()
            closers += { runCatching { environment.close() } }
            environment.streamCreator().stream(stream).create()
            val producer = environment.producerBuilder().stream(stream).build()
            closers += { runCatching { producer.close() } }
            producer.send(producer.messageBuilder().addData("spike".toByteArray()).build()) { }
            val consumer = environment.consumerBuilder().stream(stream).offset(OffsetSpecification.first()).messageHandler { _, _ -> }.build()
            closers += { runCatching { consumer.close() } }
            val mqtt = MqttClient("tcp://${env.host}:${env.mqttPort()}", "mqtt-$suffix", MemoryPersistence())
            mqtt.connect(MqttConnectionOptions().apply { isCleanStart = true; userName = mqttUser; password = persona.secret.toByteArray() })
            closers += { runCatching { mqtt.disconnect() }; runCatching { mqtt.close(true) } }
            mqtt.subscribe("spike/$suffix", 0)
        }
        return this
    }

    override fun close() { closers.asReversed().forEach { it() }; closers.clear() }
}

/** Selected fields of one connection object, for the facts file (names only, never the whole body). */
internal fun JsonObject.field(name: String): String = this[name]?.let { (it as? JsonPrimitive)?.content ?: it.toString() } ?: "<absent>"

internal fun JsonObject.keyList(): String = keys.sorted().joinToString(",")

internal fun buildStringArray(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
