package dev.caerbannog.datarabbit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * M9-00a spike, S-A5 to S-A8: can a stream feed an MQTT subscription, where do the correlation id (MQTT 5 user property),
 * the correlation data and the response topic of an MQTT publish land (stream message and quorum queue), and does one
 * publish fan out to a device-style subscription and a request stream. Findings: docs/tasks/M9-00a-spike-results.md.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9SpikeMqttRequestResponseTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RealBrokerM9SpikeMqttRequestResponseTest {

    companion object {
        private val rabbit: RabbitMQContainer = m9SpikeBroker()
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { rabbit.start(); api = MgmtApi(rabbit) }

        @JvmStatic @AfterAll fun stop() = rabbit.stop()
    }

    private val scope = M9SpikeScope(MgmtApi(rabbit))

    @AfterEach fun cleanUp() = scope.close()

    @Test @Order(0)
    fun `the image is a 4x broker and its version is recorded`() = m9AssertBrokerIs4x(api)

    @Test @Order(1)
    fun `S-A5 a message put into a stream through the stream protocol never reaches an MQTT subscriber of the same topic`() {
        val tag = System.nanoTime().toString(36)
        val stream = scope.stream("m9a5")
        assertEquals(201, scope.bind("amq.topic", stream, "m9.a5.$tag").status)
        M9SpikeMqttClient(rabbit, "m9a5-sub-$tag").connect().use { sub ->
            assertNull(sub.subscribe("m9/a5/$tag"))
            m9PutIntoStream(rabbit, stream, "direct-into-stream")
            M9SpikeMqttClient(rabbit, "m9a5-pub-$tag").connect().use { assertNull(it.publish("m9/a5/$tag", "control")) }
            sub.awaitReceived("the control publish")
            awaitUntil("control at the subscriber") { sub.received.any { it.payload == "control" } }
            val inStream = m9StreamUntilControl(rabbit, stream, "control").map { it.payload }
            m9Note("S-A5", "stream holds $inStream; MQTT subscriber received ${sub.received.map { it.payload }}")
            assertEquals(listOf("direct-into-stream", "control"), inStream, "positive control: both messages are in the stream")
            assertEquals(listOf("control"), sub.received.map { it.payload }, "the message put into the stream did not reach the MQTT subscriber")
        }
    }

    private fun describe(m: M9SpikeRaw) = "properties=${m.properties} applicationProperties=${m.applicationProperties} annotations=${m.annotations}"

    private fun describe(q: M9SpikeQueued) = "routingKey=${q.routingKey} exchange=${q.exchange} properties=${q.properties}"

    @Test @Order(2)
    fun `S-A6 a correlation id sent as an MQTT 5 user property lands as an application property`() {
        val tag = System.nanoTime().toString(36)
        val stream = scope.stream("m9a6s")
        val queue = scope.quorum("m9a6q")
        listOf(stream, queue).forEach { assertEquals(201, scope.bind("amq.topic", it, "devices.*.answer").status) }
        M9SpikeMqttClient(rabbit, "m9a6-pub-$tag").connect().use { pub ->
            assertNull(pub.publish("devices/d42/answer", "with-correlation-id", userProperties = listOf("correlation-id" to "corr-1")))
            assertNull(pub.publish("devices/d42/answer", "with-other-key", userProperties = listOf("request-id" to "req-2")))
            assertNull(pub.publish("devices/d42/answer", "with-both", userProperties = listOf("correlation-id" to "corr-3", "request-id" to "req-3")))
            assertNull(pub.publish("devices/d42/answer", "control"))
        }
        val raw = m9StreamUntilControl(rabbit, stream, "control")
        val queued = m9PeekUntilControl(api, queue, "control")
        raw.forEach { m9Note("S-A6", "stream '${it.payload}': ${describe(it)}") }
        queued.forEach { m9Note("S-A6", "quorum '${it.payload}': ${describe(it)}") }
        val appReader = m9ReadWithAppReader(rabbit, stream)
        appReader.forEach { m9Note("S-A6", "app reader '${it.payload.decodeToString()}': properties=${it.properties} applicationProperties=${it.applicationProperties} exchange=${it.exchange} routingKey=${it.routingKey}") }
        val routeAnnotations = mapOf("x-routing-key" to "text:devices.d42.answer", "x-exchange" to "text:amq.topic")
        // Stream: a user property is an application property (text); nothing lands in the standard properties; the route is in the annotations.
        assertEquals(
            mapOf(
                "with-correlation-id" to mapOf("correlation-id" to "text:corr-1"), "with-other-key" to mapOf("request-id" to "text:req-2"),
                "with-both" to mapOf("correlation-id" to "text:corr-3", "request-id" to "text:req-3"), "control" to emptyMap(),
            ),
            raw.associate { it.payload to it.applicationProperties }, "application properties of the stream messages",
        )
        assertEquals(List(4) { emptyMap<String, String>() }, raw.map { it.properties }, "standard properties of the stream messages")
        assertEquals(List(4) { routeAnnotations }, raw.map { it.annotations }, "annotations of the stream messages")
        // The app's reader shows the same user properties under applicationProperties, and the route as exchange and routingKey.
        assertEquals(
            mapOf("correlation-id" to "corr-1"), appReader.single { it.payload.decodeToString() == "with-correlation-id" }.applicationProperties.mapValues { it.value.toString() },
        )
        // Quorum queue (peek): a user property is an entry of the `headers` table, nothing else is set (no correlation_id).
        assertEquals(
            mapOf(
                "with-correlation-id" to """{"correlation-id":"corr-1"}""", "with-other-key" to """{"request-id":"req-2"}""",
                "with-both" to """{"correlation-id":"corr-3","request-id":"req-3"}""", "control" to "null",
            ),
            queued.associate { it.payload to it.properties["headers"].toString() }, "headers of the queue messages",
        )
        assertEquals(List(4) { null }, queued.map { it.properties["correlation_id"] }, "no correlation_id property on the queue messages")
        m9Fixture("s-a6-stream-and-queue-messages.txt", (raw.map { "stream '${it.payload}': ${describe(it)}" } + queued.map { "quorum '${it.payload}': ${describe(it)}" }).joinToString("\n"))
    }

    @Test @Order(3)
    fun `S-A7 MQTT 5 correlation data, response topic and content type`() {
        val tag = System.nanoTime().toString(36)
        val stream = scope.stream("m9a7s")
        val queue = scope.quorum("m9a7q")
        listOf(stream, queue).forEach { assertEquals(201, scope.bind("amq.topic", it, "devices.*.answer").status) }
        M9SpikeMqttClient(rabbit, "m9a7-pub-$tag").connect().use { pub ->
            assertNull(pub.publish("devices/d42/answer", "text-correlation-data", correlationData = "corr-bytes-1".toByteArray(), responseTopic = "devices/d42/reply", contentType = "application/json", userProperties = listOf("trace" to "t1")))
            assertNull(pub.publish("devices/d42/answer", "binary-correlation-data", correlationData = byteArrayOf(1, 2, 3, 0xff.toByte()), responseTopic = "devices/d42/reply"))
            assertNull(pub.publish("devices/d42/answer", "control"))
        }
        val raw = m9StreamUntilControl(rabbit, stream, "control")
        val queued = m9PeekUntilControl(api, queue, "control")
        raw.forEach { m9Note("S-A7", "stream '${it.payload}': ${describe(it)}") }
        queued.forEach { m9Note("S-A7", "quorum '${it.payload}': ${describe(it)}") }
        m9ReadWithAppReader(rabbit, stream).forEach { m9Note("S-A7", "app reader '${it.payload.decodeToString()}': properties=${it.properties} applicationProperties=${it.applicationProperties}") }
        val byPayload = raw.associateBy { it.payload }
        // Stream: correlation data is the standard correlation-id property (binary), the response topic is reply-to as an AMQP address
        // with the dots of the key, the content type is the standard content-type; creation-time and absolute-expiry-time are present as 0.
        assertEquals("bytes:corr-bytes-1", byPayload.getValue("text-correlation-data").properties["correlation-id"])
        assertEquals("bytes:0x010203ff", byPayload.getValue("binary-correlation-data").properties["correlation-id"])
        assertEquals("text:/exchanges/amq.topic/devices.d42.reply", byPayload.getValue("text-correlation-data").properties["reply-to"])
        assertEquals("text:/exchanges/amq.topic/devices.d42.reply", byPayload.getValue("binary-correlation-data").properties["reply-to"])
        assertEquals("text:application/json", byPayload.getValue("text-correlation-data").properties["content-type"])
        assertEquals(mapOf("trace" to "text:t1"), byPayload.getValue("text-correlation-data").applicationProperties)
        // Quorum queue: correlation data that is UTF-8 becomes the correlation_id property, binary data a header with a base64 note;
        // the response topic is the header x-reply-to-topic with the dots of the key.
        val q = queued.associateBy { it.payload }
        assertEquals("corr-bytes-1", (q.getValue("text-correlation-data").properties["correlation_id"] as JsonPrimitive).content)
        assertEquals("""{"trace":"t1","x-reply-to-topic":"devices.d42.reply"}""", q.getValue("text-correlation-data").properties["headers"].toString())
        assertEquals(null, q.getValue("binary-correlation-data").properties["correlation_id"])
        assertEquals(
            """{"x-correlation-id":"Not UTF-8, base64 is: AQID/w==","x-reply-to-topic":"devices.d42.reply"}""",
            q.getValue("binary-correlation-data").properties["headers"].toString(),
        )
        m9Fixture("s-a7-stream-and-queue-messages.txt", (raw.map { "stream '${it.payload}': ${describe(it)}" } + queued.map { "quorum '${it.payload}': ${describe(it)}" }).joinToString("\n"))
    }

    @Test @Order(4)
    fun `S-A8 one publish reaches a device style MQTT subscription and a request stream`() {
        val tag = System.nanoTime().toString(36)
        val requests = scope.stream("m9a8")
        assertEquals(201, scope.bind("amq.topic", requests, "devices.*.request").status)
        M9SpikeMqttClient(rabbit, "m9a8-device-$tag").connect().use { device ->
            assertNull(device.subscribe("devices/+/request"))
            val queue = api.get("/api/bindings/%2F").arr.map { it as JsonObject }
                .single { (it["source"] as JsonPrimitive).content == "amq.topic" && (it["destination"] as JsonPrimitive).content.contains("m9a8-device-$tag") }
            m9Note("S-A8", "device subscription binding: key '${(queue["routing_key"] as JsonPrimitive).content}' on queue type classic named mqtt-subscription-<client>qos1")
            M9SpikeMqttClient(rabbit, "m9a8-pub-$tag").connect().use { pub ->
                assertNull(pub.publish("devices/d42/request", "req-1"))
                assertNull(pub.publish("devices/d42/request", "req-2"))
            }
            // Also a publish through the management API on amq.topic with the same key.
            val viaApi = api.post("/api/exchanges/%2F/amq.topic/publish", """{"properties":{},"routing_key":"devices.d42.request","payload":"req-3-api","payload_encoding":"string"}""")
            m9Note("S-A8", "management publish to amq.topic: $viaApi")
            M9SpikeMqttClient(rabbit, "m9a8-pub2-$tag").connect().use { assertNull(it.publish("devices/d42/request", "control")) }
            awaitUntil("control at the device") { device.received.any { it.payload == "control" } }
            val inStream = m9StreamUntilControl(rabbit, requests, "control").map { it.payload }
            val atDevice = device.received.map { it.payload }
            m9Note("S-A8", "request stream holds $inStream; device subscription received $atDevice")
            assertEquals(listOf("req-1", "req-2", "req-3-api", "control"), inStream)
            assertEquals(listOf("req-1", "req-2", "req-3-api", "control"), atDevice)
        }
    }
}
