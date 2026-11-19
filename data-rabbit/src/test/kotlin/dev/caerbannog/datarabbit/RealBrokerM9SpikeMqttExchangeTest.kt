package dev.caerbannog.datarabbit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * M9-00a spike, S-B2 (and S-B1 on a broker whose MQTT exchange is not the default): a throwaway broker configured with
 * `mqtt.exchange = custom.mqtt`. Own class, own container. Findings: docs/tasks/M9-00a-spike-results.md.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9SpikeMqttExchangeTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RealBrokerM9SpikeMqttExchangeTest {

    companion object {
        private val rabbit: RabbitMQContainer = m9SpikeBroker("fixtures/m9-00/s-b2-custom-mqtt-exchange.conf")
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { rabbit.start(); api = MgmtApi(rabbit) }

        @JvmStatic @AfterAll fun stop() = rabbit.stop()
    }

    private val scope = M9SpikeScope(MgmtApi(rabbit))

    @AfterEach fun cleanUp() = scope.close()

    @Test @Order(0)
    fun `the image is a 4x broker and its version is recorded`() = m9AssertBrokerIs4x(api)

    @Test @Order(1)
    fun `S-B2 without the exchange custom mqtt an MQTT publish and subscribe fail, the first MQTT connection does not declare it`() {
        val tag = System.nanoTime().toString(36)
        assertEquals(404, api.get("/api/exchanges/%2F/custom.mqtt").status, "custom.mqtt does not exist at the start")
        M9SpikeMqttClient(rabbit, "m9b2-pub0-$tag").connect().use { pub ->
            val stillMissing = api.get("/api/exchanges/%2F/custom.mqtt").status
            val publish = pub.publish("m9/b2/$tag", "no-exchange")
            val subscribe = M9SpikeMqttClient(rabbit, "m9b2-sub0-$tag").let { c -> try { c.connect(); c.subscribe("m9/b2/$tag") } catch (e: Exception) { "connect failed: ${e.message}" } finally { c.close() } }
            m9Note("S-B2", "no exchange custom.mqtt: connect accepted, exchange after connect: GET -> $stillMissing; publish QoS 1 -> ${publish ?: "accepted (puback)"}; subscribe -> ${subscribe ?: "accepted"}")
            assertEquals(404, stillMissing, "the MQTT connection does not declare the exchange")
            assertTrue(publish != null && publish.contains("Connection lost"), "a QoS 1 publish without the exchange loses the connection, got $publish")
            assertEquals("refused, reason codes [128]", subscribe, "a subscription without the exchange is refused with reason code 128 (unspecified error)")
        }
    }

    @Test @Order(2)
    fun `S-B2 with the exchange custom mqtt declared first, MQTT publishes go to it and not to amq topic`() {
        val tag = System.nanoTime().toString(36)
        val onDefault = scope.stream("m9b2-default")
        val onCustom = scope.stream("m9b2-custom")
        assertEquals(201, api.put("/api/exchanges/%2F/custom.mqtt", """{"type":"topic","durable":true,"auto_delete":false,"arguments":{}}""").status)
        try {
            assertEquals(201, scope.bind("amq.topic", onDefault, "m9.b2.$tag").status)
            assertEquals(201, scope.bind("amq.topic", onDefault, "m9.b2.control.$tag").status)
            assertEquals(201, scope.bind("custom.mqtt", onCustom, "m9.b2.$tag").status)
            assertEquals(201, scope.bind("custom.mqtt", onCustom, "m9.b2.control.$tag").status)
            M9SpikeMqttClient(rabbit, "m9b2-pub-$tag").connect().use { pub ->
                m9Note("S-B2", "publish QoS 1 with the exchange present: ${pub.publish("m9/b2/$tag", "mqtt-publish") ?: "accepted (puback)"}")
                // Positive controls: a management publish to each exchange, so each stream has a message that must arrive.
                api.post("/api/exchanges/%2F/amq.topic/publish", """{"properties":{},"routing_key":"m9.b2.control.$tag","payload":"control","payload_encoding":"string"}""")
                api.post("/api/exchanges/%2F/custom.mqtt/publish", """{"properties":{},"routing_key":"m9.b2.control.$tag","payload":"control","payload_encoding":"string"}""")
            }
            val defaultHas = m9StreamUntilControl(rabbit, onDefault, "control").map { it.payload }
            val customRaw = m9StreamUntilControl(rabbit, onCustom, "control")
            m9Note("S-B2", "stream bound on amq.topic holds $defaultHas; stream bound on custom.mqtt holds ${customRaw.map { it.payload }} (x-exchange: ${customRaw.first().annotations["x-exchange"]})")
            assertEquals(listOf("control"), defaultHas, "amq.topic receives no MQTT publish when mqtt.exchange is custom.mqtt")
            assertEquals(listOf("mqtt-publish", "control"), customRaw.map { it.payload })
            assertEquals("text:custom.mqtt", customRaw.first().annotations["x-exchange"])
            // A subscription is bound on custom.mqtt too.
            M9SpikeMqttClient(rabbit, "m9b2-sub-$tag").connect().use { sub ->
                assertNull(sub.subscribe("m9/b2sub/$tag"))
                val binding = api.get("/api/bindings/%2F").arr.map { it as JsonObject }
                    .single { (it["destination"] as JsonPrimitive).content.contains("m9b2-sub-$tag") && (it["source"] as JsonPrimitive).content != "" }
                m9Note("S-B2", "subscription binding source='${(binding["source"] as JsonPrimitive).content}' key='${(binding["routing_key"] as JsonPrimitive).content}'")
                assertEquals("custom.mqtt", (binding["source"] as JsonPrimitive).content)
            }
        } finally {
            api.delete("/api/exchanges/%2F/custom.mqtt")
        }
    }

    @Test @Order(3)
    fun `S-B1 on this broker too the management API does not name the MQTT exchange`() {
        listOf("/api/overview", "/api/nodes", "/api/global-parameters", "/api/vhosts").forEach { path ->
            val reply = api.get(path)
            val mentions = Regex("custom\\.mqtt").findAll(reply.body).count()
            m9Note("S-B1", "custom.mqtt broker: GET $path status=${reply.status} mentions of custom.mqtt=$mentions")
            assertEquals(0, mentions, "$path names the MQTT exchange")
        }
        val definitions = api.get("/api/definitions")
        val exchangeNames = (definitions.obj["exchanges"] as kotlinx.serialization.json.JsonArray).map { ((it as JsonObject)["name"] as JsonPrimitive).content }
        m9Note("S-B1", "custom.mqtt broker: custom.mqtt appears in definitions only as an ordinary exchange: ${"custom.mqtt" in exchangeNames} (exchanges: $exchangeNames)")
        val viaEval = rabbit.execInContainer("rabbitmqctl", "eval", "application:get_env(rabbitmq_mqtt, exchange).")
        m9Note("S-B1", "rabbitmqctl eval (not the management API): ${viaEval.stdout.trim()}")
        assertEquals("{ok,<<\"custom.mqtt\">>}", viaEval.stdout.trim())
    }
}
