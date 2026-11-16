package dev.caerbannog.datarabbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * M9-00a spike, S-A1 to S-A4 and S-B1: how a real RabbitMQ 4.x translates MQTT topic filters into binding keys on `amq.topic`,
 * which hand-written keys match which MQTT topics (stream and quorum queue), what happens with edge levels, and how QoS and
 * retain behave. Findings: docs/tasks/M9-00a-spike-results.md. Each assertion pins one measured fact.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9SpikeMqttRouteTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RealBrokerM9SpikeMqttRouteTest {

    companion object {
        private val rabbit: RabbitMQContainer = m9SpikeBroker()
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { rabbit.start(); api = MgmtApi(rabbit) }

        @JvmStatic @AfterAll fun stop() = rabbit.stop()

        /** MQTT filter to the key the broker is expected to store (measured in S-A1). */
        val translation = linkedMapOf(
            "sensors/+/temp" to "sensors.*.temp",
            "sensors/#" to "sensors.#",
            "#" to "#",
            "+" to "*",
            "a/b/c" to "a.b.c",
            "devices/+/answer" to "devices.*.answer",
        )

        val probeTopics = listOf(
            "sensors/room1/temp", "sensors/room1/hum", "sensors/temp", "sensors", "sensors/a/b/temp", "sensors/room1",
            "a/b/c", "a/b", "a/b/c/d", "x", "devices/d42/answer", "devices/d42/request", "devices/answer", "devices/d42/e7/answer",
        )
    }

    private val scope = M9SpikeScope(MgmtApi(rabbit))

    @org.junit.jupiter.api.AfterEach fun cleanUp() = scope.close()

    @Test @Order(0)
    fun `the image is a 4x broker and its version is recorded`() = m9AssertBrokerIs4x(api)

    @Test @Order(1)
    fun `S-A1 the broker translates MQTT filters into binding keys on amq topic`() {
        val tag = System.nanoTime().toString(36)
        val clients = translation.keys.mapIndexed { i, filter ->
            M9SpikeMqttClient(rabbit, "m9a1-$i-$tag").connect().also { assertNull(it.subscribe(filter), "subscribe $filter") }
        }
        try {
            val bindings = api.get("/api/bindings/%2F").arr
                .map { it as JsonObject }.filter { (it["source"] as JsonPrimitive).content == "amq.topic" }
                .filter { (it["destination"] as JsonPrimitive).content.contains("-$tag") }
            m9Fixture("s-a1-bindings-amq-topic-mqtt-subscriptions.json", JsonArray(bindings).toString())
            val seen = translation.keys.mapIndexed { i, filter ->
                val b = bindings.single { (it["destination"] as JsonPrimitive).content.contains("m9a1-$i-$tag") }
                filter to (b["routing_key"] as JsonPrimitive).content
            }.toMap()
            seen.forEach { (f, k) -> m9Note("S-A1", "filter '$f' -> key '$k'") }
            assertEquals(translation, seen, "translation of MQTT filters into binding keys")
            // The queue the broker creates for a QoS 1 subscription, and its type.
            val queue = (bindings.first()["destination"] as JsonPrimitive).content
            val q = api.get("/api/queues/%2F/$queue").obj
            m9Note("S-A1", "subscriber queue '${queue.replace(tag, "<tag>")}' type=${(q["type"] as? JsonPrimitive)?.content} durable=${q["durable"]} auto_delete=${q["auto_delete"]}")
            assertTrue(queue.startsWith("mqtt-subscription-m9a1-0-${tag}qos1"), "queue name pattern, got $queue")
        } finally {
            clients.forEach { it.close() }
        }
    }

    /** One stream and one quorum queue per hand-written key, plus a subscriber per MQTT filter; publishes every probe topic. */
    @Test @Order(2)
    fun `S-A2 hand-written keys match what the MQTT subscription of the same filter matches, in a stream and in a quorum queue`() {
        val tag = System.nanoTime().toString(36)
        val control = "CONTROL-$tag"
        val streams = translation.mapValues { (_, key) -> scope.stream("m9a2s").also { assertEquals(201, scope.bind("amq.topic", it, key).status); scope.bind("amq.topic", it, "m9.control$tag") } }.toMap()
        val quorums = translation.mapValues { (_, key) -> scope.quorum("m9a2q").also { assertEquals(201, scope.bind("amq.topic", it, key).status); scope.bind("amq.topic", it, "m9.control$tag") } }.toMap()
        val subs = translation.keys.mapIndexed { i, f ->
            f to M9SpikeMqttClient(rabbit, "m9a2-$i-$tag").connect().also { assertNull(it.subscribe(f)); assertNull(it.subscribe("m9/control$tag")) }
        }.toMap()
        M9SpikeMqttClient(rabbit, "m9a2-pub-$tag").connect().use { pub ->
            probeTopics.forEach { assertNull(pub.publish(it, it), "publish $it") }
            assertNull(pub.publish("m9/control$tag", control))
        }
        try {
            translation.keys.forEach { filter ->
                val s = m9StreamUntilControl(rabbit, streams.getValue(filter), control).map { it.payload }.filter { it != control }
                val q = m9PeekUntilControl(api, quorums.getValue(filter), control).map { it.payload }.filter { it != control }
                val m = subs.getValue(filter).also { c -> awaitUntil("control at $filter") { c.received.any { it.payload == control } } }
                    .received.map { it.payload }.filter { it != control }
                m9Note("S-A2", "filter '$filter' key '${translation[filter]}': stream=${s.sorted()} quorum=${q.sorted()} mqttSubscriber=${m.sorted()}")
                assertEquals(m.sorted(), s.sorted(), "stream bound with '${translation[filter]}' vs MQTT subscriber of '$filter'")
                assertEquals(m.sorted(), q.sorted(), "quorum queue bound with '${translation[filter]}' vs MQTT subscriber of '$filter'")
                assertEquals(expectedMatches.getValue(filter), s.sorted(), "match table for '$filter'")
            }
        } finally {
            subs.values.forEach { it.close() }
        }
    }

    private val expectedMatches: Map<String, List<String>> = mapOf(
        "sensors/+/temp" to listOf("sensors/room1/temp"),
        "sensors/#" to probeTopics0 { it.startsWith("sensors") },
        "#" to probeTopics0 { true },
        "+" to listOf("sensors", "x"),
        "a/b/c" to listOf("a/b/c"),
        "devices/+/answer" to listOf("devices/d42/answer"),
    )

    private fun probeTopics0(filter: (String) -> Boolean) = probeTopics.filter(filter).sorted()

    private class Edge(val label: String, val topic: String)

    private val edges = listOf(
        Edge("dot in a level", "sensors/v1.2/temp"), Edge("asterisk in a level", "sensors/a*b/temp"),
        Edge("empty level", "sensors//temp"), Edge("leading slash", "/sensors/temp"), Edge("trailing slash", "sensors/temp/"),
        Edge("space in a level", "sensors/a b/temp"), Edge("dollar SYS topic", "\$SYS/x"), Edge("dollar other topic", "\$foo/bar"),
    )

    /** What one edge topic showed: publish accepted, key seen, hand-written key matches, literal subscriber got it, connection survived. */
    private data class EdgeResult(
        val publish: String, val subscribeKey: String, val keySeen: String?, val handWrittenKeyMatched: Boolean,
        val literalSubscriberGotIt: Boolean, val subscriberStillConnected: Boolean,
    )

    @Test @Order(3)
    fun `S-A3 edge levels, accepted or refused, key seen on the stream message and round trip`() {
        val tag = System.nanoTime().toString(36)
        val control = "CONTROL-$tag"
        val catchAll = scope.stream("m9a3-all").also { assertEquals(201, scope.bind("amq.topic", it, "#").status) }
        // A stream per edge bound with the key a person would write by hand (slash to dot), to see whether it matches.
        val handWritten = edges.associate { e ->
            e.topic to scope.stream("m9a3-hw").also {
                assertEquals(201, scope.bind("amq.topic", it, e.topic.replace('/', '.')).status)
                assertEquals(201, scope.bind("amq.topic", it, "ctl.$tag").status)
            }
        }
        val bindingKeys = mutableMapOf<String, String>()
        val subs = edges.mapIndexed { i, e ->
            val c = M9SpikeMqttClient(rabbit, "m9a3-$i-$tag").connect()
            assertNull(c.subscribe(e.topic), "subscribe to ${e.topic}")
            assertNull(c.subscribe("ctl/$tag"), "control subscription")
            bindingKeys[e.topic] = api.get("/api/bindings/%2F").arr.map { it as JsonObject }
                .filter { (it["source"] as JsonPrimitive).content == "amq.topic" && (it["destination"] as JsonPrimitive).content.contains("m9a3-$i-$tag") && (it["routing_key"] as JsonPrimitive).content != "ctl.$tag" }
                .map { (it["routing_key"] as JsonPrimitive).content }.sorted().joinToString(" | ").ifEmpty { "no binding" }
            e.topic to c
        }.toMap()
        val publishResult = edges.associate { e ->
            e.topic to M9SpikeMqttClient(rabbit, "m9a3-p-${e.topic.hashCode().toUInt()}-$tag").let { c ->
                try { c.connect(); c.publish(e.topic, "edge:${e.topic}") ?: "accepted" } catch (ex: Exception) { "connection failed: ${ex.message}" } finally { c.close() }
            }
        }
        M9SpikeMqttClient(rabbit, "m9a3-ctl-$tag").connect().use { assertNull(it.publish("ctl/$tag", control)) }
        try {
            val raw = m9StreamUntilControl(rabbit, catchAll, control)
            val viaReader = m9ReadWithAppReader(rabbit, catchAll)
            // Positive control for the literal subscribers: they all get the control topic, except the one whose connection the edge message dropped.
            val controlSeen = subs.mapValues { (_, c) -> runCatching { awaitUntil("control at ${c.clientId}", 5) { c.received.any { it.payload == control } } }.isSuccess }
            m9Note("S-A3", "control reached the literal subscribers: $controlSeen")
            val results = edges.associate { e ->
                val msg = raw.firstOrNull { it.payload == "edge:${e.topic}" }
                val hw = m9StreamUntilControl(rabbit, handWritten.getValue(e.topic), control).any { it.payload == "edge:${e.topic}" }
                val readerKey = viaReader.firstOrNull { it.payload.decodeToString() == "edge:${e.topic}" }?.routingKey
                val sub = subs.getValue(e.topic)
                val r = EdgeResult(
                    publishResult.getValue(e.topic), bindingKeys.getValue(e.topic), msg?.annotations?.get("x-routing-key")?.removePrefix("text:"),
                    hw, sub.received.any { it.payload == "edge:${e.topic}" }, sub.isConnected,
                )
                m9Note("S-A3", "${e.label} topic='${e.topic}' $r reader='$readerKey' roundTripKeyToTopic=${r.keySeen?.replace('.', '/') == e.topic}")
                assertEquals(r.keySeen, readerKey, "the app's stream reader shows the same routing key for ${e.topic}")
                e.label to r
            }
            m9Fixture("s-a3-edge-levels.txt", results.entries.joinToString("\n") { "${it.key}: ${it.value}" })
            val expected = mapOf(
                // label to (key seen = the topic with '/' replaced by '.'; the broker keeps every other character as it is)
                "dot in a level" to EdgeResult("accepted", "sensors.v1.2.temp", "sensors.v1.2.temp", true, false, true),
                "asterisk in a level" to EdgeResult("accepted", "sensors.a*b.temp", "sensors.a*b.temp", true, false, false),
                "empty level" to EdgeResult("accepted", "sensors..temp", "sensors..temp", true, true, true),
                "leading slash" to EdgeResult("accepted", ".sensors.temp", ".sensors.temp", true, true, true),
                "trailing slash" to EdgeResult("accepted", "sensors.temp.", "sensors.temp.", true, true, true),
                "space in a level" to EdgeResult("accepted", "sensors.a b.temp", "sensors.a b.temp", true, true, true),
                "dollar SYS topic" to EdgeResult("accepted", "\$SYS.x", "\$SYS.x", true, true, true),
                "dollar other topic" to EdgeResult("accepted", "\$foo.bar", "\$foo.bar", true, true, true),
            )
            assertEquals(expected, results, "edge levels")
        } finally {
            subs.values.forEach { it.close() }
        }
    }

    @Test @Order(3)
    fun `S-A3 an MQTT subscriber of hash also gets a dollar topic, like a binding of hash on amq topic`() {
        val tag = System.nanoTime().toString(36)
        val stream = scope.stream("m9a3-dollar").also { assertEquals(201, scope.bind("amq.topic", it, "#").status) }
        M9SpikeMqttClient(rabbit, "m9a3d-sub-$tag").connect().use { sub ->
            assertNull(sub.subscribe("#"))
            M9SpikeMqttClient(rabbit, "m9a3d-pub-$tag").connect().use { pub ->
                assertNull(pub.publish("\$SYS/m9$tag", "dollar"))
                assertNull(pub.publish("plain/m9$tag", "control"))
            }
            awaitUntil("control at the # subscriber") { sub.received.any { it.payload == "control" } }
            val seen = sub.received.map { it.topic }
            m9Note("S-A3", "MQTT subscriber of '#' received the topics $seen; the stream bound with '#' received ${m9StreamUntilControl(rabbit, stream, "control").map { it.payload }}")
            assertEquals(listOf("\$SYS/m9$tag", "plain/m9$tag"), seen, "the broker does not keep dollar topics away from the filter '#' (MQTT 5 spec 4.7.2 says it should)")
        }
    }

    private fun utf(s: String): ByteArray = s.toByteArray().let { byteArrayOf((it.size shr 8).toByte(), it.size.toByte()) + it }

    private fun packet(first: Int, body: ByteArray): ByteArray = byteArrayOf(first.toByte(), body.size.toByte()) + body

    /** Reads one MQTT 3.1.1 packet: its type nibble and body. */
    private fun readPacket(input: java.io.InputStream): Pair<Int, ByteArray> {
        val first = input.read()
        check(first >= 0) { "connection closed" }
        var length = 0; var shift = 0
        while (true) { val b = input.read(); length = length or ((b and 0x7f) shl shift); if (b and 0x80 == 0) break; shift += 7 }
        return (first shr 4) to input.readNBytes(length)
    }

    /**
     * The `*` level, seen on the wire: a raw MQTT 3.1.1 socket (no client library) subscribes to `m9a3raw/#` and receives what the broker
     * sends for a publish to `m9a3raw/a*b`: the topic comes back as `m9a3raw/a+b`, which the Paho client refuses by closing the connection.
     */
    @Test @Order(3)
    fun `S-A3 the broker hands a topic with an asterisk level back to subscribers with a plus, seen on a raw socket`() {
        val tag = System.nanoTime().toString(36)
        java.net.Socket(rabbit.host, rabbit.getMappedPort(1883)).use { socket ->
            socket.soTimeout = 15_000
            val out = socket.getOutputStream(); val input = socket.getInputStream()
            val connect = utf("MQTT") + byteArrayOf(4, 0xC2.toByte(), 0, 30) + utf("m9raw-$tag") + utf("guest") + utf("guest")
            out.write(packet(0x10, connect)); out.flush()
            assertEquals(2 to listOf<Byte>(0, 0), readPacket(input).let { it.first to it.second.toList() }, "CONNACK accepted")
            out.write(packet(0x82, byteArrayOf(0, 1) + utf("m9a3raw/#") + byteArrayOf(0))); out.flush()
            assertEquals(9, readPacket(input).first, "SUBACK")
            M9SpikeMqttClient(rabbit, "m9a3raw-pub-$tag").connect().use { pub ->
                assertNull(pub.publish("m9a3raw/a*b", "star", qos = 1))
                assertNull(pub.publish("m9a3raw/plain", "control", qos = 1))
            }
            val topics = mutableListOf<String>()
            while (topics.size < 2) {
                val (type, body) = readPacket(input)
                check(type == 3) { "expected PUBLISH, got packet type $type" }
                val topicLength = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
                topics += String(body, 2, topicLength) + " <- " + String(body, 2 + topicLength, body.size - 2 - topicLength)
            }
            m9Note("S-A3", "raw MQTT 3.1.1 socket subscriber of 'm9a3raw/#' got $topics")
            assertEquals(listOf("m9a3raw/a+b <- star", "m9a3raw/plain <- control"), topics, "the topic of the delivered PUBLISH (a wildcard character in a topic name: a strict client such as Paho drops the connection)")
        }
    }

    @Test @Order(4)
    fun `S-A4 QoS 0, QoS 1 and retained publishes reach a stream through a hand-written binding`() {
        val tag = System.nanoTime().toString(36)
        val control = "CONTROL-$tag"
        val stream = scope.stream("m9a4")
        assertEquals(201, scope.bind("amq.topic", stream, "m9.a4.$tag").status)
        assertEquals(201, scope.bind("amq.topic", stream, "m9.a4.control.$tag").status)
        M9SpikeMqttClient(rabbit, "m9a4-pub-$tag").connect().use { pub ->
            assertNull(pub.publish("m9/a4/$tag", "qos0", qos = 0))
            assertNull(pub.publish("m9/a4/$tag", "qos1", qos = 1))
            assertNull(pub.publish("m9/a4/$tag", "retained", qos = 1, retain = true))
            assertNull(pub.publish("m9/a4/control/$tag", control))
        }
        val messages = m9StreamUntilControl(rabbit, stream, control).filter { it.payload != control }
        m9Note("S-A4", "stream received ${messages.map { it.payload }}; annotations of the retained one: ${messages.last().annotations}")
        assertEquals(listOf("qos0", "qos1", "retained"), messages.map { it.payload })
        // A later subscriber gets the retained message from the broker (positive control: the subscription works).
        M9SpikeMqttClient(rabbit, "m9a4-sub-$tag").connect().use { sub ->
            assertNull(sub.subscribe("m9/a4/$tag"))
            val got = sub.awaitReceived("retained message")
            m9Note("S-A4", "late subscriber received ${got.map { it.payload to it.retained }}")
            assertEquals(listOf("retained" to true), got.map { it.payload to it.retained })
        }
        // Clean up the retained message (an empty retained payload clears it).
        M9SpikeMqttClient(rabbit, "m9a4-clr-$tag").connect().use { it.publish("m9/a4/$tag", "", retain = true) }
    }

    @Test @Order(5)
    fun `S-B1 the name of the MQTT exchange is not published by the management API`() {
        val probes = listOf("/api/overview", "/api/nodes", "/api/global-parameters", "/api/definitions", "/api/vhosts", "/api/vhosts/%2F")
        probes.forEach { path ->
            val reply = api.get(path)
            assertTrue(reply.ok, "$path: $reply")
            val mentionsMqtt = Regex("(?i)mqtt").findAll(reply.body).map { it.range.first }.map { reply.body.substring(maxOf(0, it - 40), minOf(reply.body.length, it + 60)) }.toList()
            m9Note("S-B1", "GET $path status=${reply.status} bytes=${reply.body.length} mqttMentions=${mentionsMqtt.size} ${mentionsMqtt.take(3)}")
        }
        val nodeName = (api.get("/api/nodes").arr.first() as JsonObject)["name"].let { (it as JsonPrimitive).content }
        val nodeDetail = api.get("/api/nodes/$nodeName?columns=config_files,applications,enabled_plugins,environment")
        m9Note("S-B1", "GET /api/nodes/<node> status=${nodeDetail.status} keys=${(nodeDetail.json as? JsonObject)?.keys}")
        val fromExec = rabbit.execInContainer("rabbitmqctl", "eval", "application:get_env(rabbitmq_mqtt, exchange).")
        m9Note("S-B1", "rabbitmqctl eval (not the management API) -> ${fromExec.stdout.trim()}")
        val exchange = api.get("/api/exchanges/%2F/amq.topic")
        m9Note("S-B1", "amq.topic exists: ${exchange.status} type=${(exchange.json as? JsonObject)?.get("type")} internal=${(exchange.json as? JsonObject)?.get("internal")}")
    }
}
