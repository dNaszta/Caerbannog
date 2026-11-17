package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Environment
import dev.caerbannog.core.browser.PropertyValueText
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M9-00b spike, part 2: a stream as the dead-letter TARGET (S-C7) and streams with dead-lettering settings (S-D1 to S-D3) on a real RabbitMQ 4.x
 * broker. S-D1 to S-D3 are the three facts behind the one broker sentence of the response-stream note (M9-07). Findings:
 * docs/tasks/M9-00b-spike-results.md; fixture `s-c7-stream-message.json`. Part 1: `RealBrokerM9SpikeDeadLetterTest`.
 * A policy shows on a queue after about 6 s (M9-00c); every wait is a bounded poll; "nothing arrived" follows a positive control.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9SpikeStreamDeadLetterTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM9SpikeStreamDeadLetterTest {

    companion object {
        private val container: RabbitMQContainer = M9SpikeDl.newBroker()
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { container.start(); api = MgmtApi(container) }
        @JvmStatic @AfterAll fun stop() { container.stop() }
    }

    private val run = UUID.randomUUID().toString().take(6)
    private val cl by lazy { M9SpikeDl.Cleanup(api) }
    private fun unique(base: String) = "m9b-$base-$run"
    private val quorum = """{"x-queue-type":"quorum"}"""
    /** A stream as the product makes it (M9-00c: the stream manager adds the leader locator; a management PUT without it is a 400). */
    private fun streamArgs(extra: String = "") = """{"x-queue-type":"stream","x-queue-leader-locator":"balanced"$extra}"""

    @AfterEach fun clean() = cl.run()

    private fun env(): Environment =
        Environment.builder().host(container.host).port(container.getMappedPort(5552)).username(container.adminUsername).password(container.adminPassword).build()

    private fun dlxWithQueue(tag: String): Pair<String, String> {
        val dlx = cl.exchange(unique("$tag-dlx"))
        val dlq = cl.queue(unique("$tag-dlq"))
        assertEquals(201, api.declareQueue(dlq, quorum).status)
        assertEquals(201, api.put("/api/exchanges/%2F/$dlx", """{"type":"fanout","durable":true}""").status)
        assertEquals(201, api.post("/api/bindings/%2F/e/$dlx/q/$dlq", """{"routing_key":""}""").status)
        return dlx to dlq
    }

    @Test
    fun `The floating tag 4-management is a 4_x broker`() = M9SpikeDl.assertFourX(api)

    // ---- S-C7: a stream as the dead-letter target ----

    private fun streamTarget(tag: String, policyExtra: String): Triple<String, String, String> {
        val dlx = cl.exchange(unique("$tag-dlx"))
        val stream = cl.queue(unique("$tag-stream"))
        val src = cl.queue(unique("$tag-src"))
        assertEquals(201, api.put("/api/exchanges/%2F/$dlx", """{"type":"fanout","durable":true}""").status)
        assertEquals(201, api.declareQueue(stream, streamArgs()).status)
        assertEquals(201, api.post("/api/bindings/%2F/e/$dlx/q/$stream", """{"routing_key":""}""").status)
        assertEquals(201, api.declareQueue(src, quorum).status)
        val pol = cl.policy("p-$src")
        assertEquals(201, api.put("/api/policies/%2F/$pol", """{"pattern":"^$src$","apply-to":"queues","priority":0,"definition":{"dead-letter-exchange":"$dlx"$policyExtra}}""").status)
        M9SpikeDl.awaitEffective(api, src)
        return Triple(dlx, stream, src)
    }

    private fun describe(m: M9SpikeDl.RawStreamMessage) =
        "body=${m.body} annotations=${m.annotations} properties=${m.properties.filterValues { it != null }} application=${m.application}"

    @Test
    fun `S-C7 a stream bound to the dead-letter exchange receives the dead letter (at-most-once), and what the stream message carries`() {
        val (_, stream, src) = streamTarget("c7am", "")
        assertTrue(api.publish(src, "to-the-stream", """{"delivery_mode":2,"headers":{"order":"42"},"content_type":"text/plain","message_id":"m-1"}""").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, src) }
        val raw = M9SpikeDl.readRaw(container, stream, 1)
        M9SpikeDl.note("S-C7 at-most-once raw: ${describe(raw.single())}")
        awaitUntil("the stream shows one message in the management statistics", 40) { M9SpikeDl.messages(api, stream) == 1 }
        val app = M9SpikeDl.readWithApp(container, stream).single()
        M9SpikeDl.note("S-C7 at-most-once app reader: exchange=${app.exchange} routingKey=${app.routingKey} properties=${app.properties} applicationProperties=${app.applicationProperties}")
        assertEquals("to-the-stream", raw.single().body)
        assertEquals("to-the-stream", app.payload.decodeToString())
        // M9-00b-c: through the app's reader, every annotation and property reads as text, and x-opt-deaths names the queue and the reason
        for ((key, value) in app.properties + app.applicationProperties) {
            val text = PropertyValueText.of(value)
            assertTrue(!text.startsWith("[L") && !text.startsWith("[B@") && !text.contains("java.lang.Object"), "property $key reads as an array identity: $text")
        }
        val deaths = PropertyValueText.of(app.properties["x-opt-deaths"])
        assertTrue(deaths.contains("queue: $src") && deaths.contains("reason: rejected"), "x-opt-deaths text was: $deaths")
        // the fixture is the measured shape of one dead letter in a stream
        val fixture = kotlinx.serialization.json.Json.parseToJsonElement(M9SpikeDl.fixture("s-c7-stream-message.json")).jsonObject
        assertEquals(fixture["annotationKeys"]!!.toString(), raw.single().annotations.keys.sorted().joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }, "annotation keys differ from the fixture")
        assertEquals(fixture["applicationKeys"]!!.toString(), raw.single().application.keys.sorted().joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }, "application property keys differ from the fixture")
    }

    @Test
    fun `S-C7 at-least-once with a stream as the target`() {
        val (_, stream, src) = streamTarget("c7al", ""","dead-letter-strategy":"at-least-once","overflow":"reject-publish"""")
        M9SpikeDl.note("S-C7 at-least-once effective=${M9SpikeDl.effective(api, src)}")
        assertTrue(api.publish(src, "al-to-stream").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, src) }
        val raw = M9SpikeDl.readRaw(container, stream, 1)
        M9SpikeDl.note("S-C7 at-least-once raw: ${describe(raw.single())}")
        assertEquals("al-to-stream", raw.single().body)
        awaitUntil("source empty after delivery", 30) { M9SpikeDl.messages(api, src) == 0 }
    }

    @Test
    fun `S-C7 an expired message and a stream target`() {
        val (_, stream, src) = streamTarget("c7ex", "")
        assertTrue(api.publish(src, "expires", """{"delivery_mode":2,"expiration":"1000"}""").body.contains("\"routed\":true"))
        val raw = M9SpikeDl.readRaw(container, stream, 1)
        M9SpikeDl.note("S-C7 expired raw: ${describe(raw.single())}")
        assertEquals("expires", raw.single().body)
        awaitUntil("the stream shows one message in the management statistics", 40) { M9SpikeDl.messages(api, stream) == 1 }
        val deaths = PropertyValueText.of(M9SpikeDl.readWithApp(container, stream).single().properties["x-opt-deaths"])
        assertTrue(deaths.contains("reason: expired") && deaths.contains("ttl: "), "M9-00b-c: x-opt-deaths text was: $deaths")
    }

    // ---- S-D1: a stream declared with x-dead-letter-exchange ----

    @Test
    fun `S-D1 a stream declared with x-dead-letter-exchange`() {
        val (dlx, dlq) = dlxWithQueue("d1")
        val stream = cl.queue(unique("d1-stream"))
        val reply = api.declareQueue(stream, streamArgs(""","x-dead-letter-exchange":"$dlx""""))
        M9SpikeDl.note("S-D1 management PUT stream with x-dead-letter-exchange: ${reply.status} ${reply.body.take(300)}")
        if (reply.ok) {
            val detail = M9SpikeDl.detail(api, stream)
            M9SpikeDl.note("S-D1 stored arguments=${detail["arguments"]} type=${detail["type"]}")
        }
        // the same through AMQP queue.declare (what any AMQP client does)
        val viaAmqp = cl.queue(unique("d1-amqp"))
        val refused = refusal {
            withAmqp(container) { c ->
                c.createChannel().queueDeclare(viaAmqp, true, false, false, mapOf("x-queue-type" to "stream", "x-dead-letter-exchange" to dlx))
            }
        }
        M9SpikeDl.note("S-D1 AMQP queue.declare stream with x-dead-letter-exchange: ${refused ?: "accepted"}; arguments=${if (refused == null) M9SpikeDl.detail(api, viaAmqp)["arguments"] else "-"}")
        // positive control: the dead-letter queue and exchange work (a message sent to the exchange arrives)
        assertTrue(api.post("/api/exchanges/%2F/$dlx/publish", """{"properties":{},"routing_key":"","payload":"control","payload_encoding":"string"}""").body.contains("\"routed\":true"))
        assertEquals(listOf("control"), M9SpikeDl.awaitPeek(api, dlq).map { it["payload"].toString().trim('"') })
    }

    // ---- S-D2: a dead-letter-exchange policy that applies to streams ----

    @Test
    fun `S-D2 a dead-letter-exchange policy for streams is accepted and stays out of effect`() {
        val (dlx, _) = dlxWithQueue("d2")
        // one target stream per policy, so `apply-to: all` is independent of `apply-to: streams` (review finding R4)
        val targets = listOf("streams", "all").associateWith { cl.queue(unique("d2-stream-$it")) }
        val control = cl.queue(unique("d2-control"))
        (targets.values + control).forEach { assertEquals(201, api.declareQueue(it, streamArgs()).status) }
        targets.forEach { (applyTo, target) ->
            val name = cl.policy("p-d2-$applyTo-$run")
            val reply = api.put("/api/policies/%2F/$name", """{"pattern":"^$target$","apply-to":"$applyTo","priority":0,"definition":{"dead-letter-exchange":"$dlx"}}""")
            M9SpikeDl.note("S-D2 PUT policy apply-to=$applyTo dead-letter-exchange: ${reply.status} ${reply.body.take(200)}")
            assertEquals(201, reply.status)
        }
        // positive control: a retention policy on another stream of the same batch takes effect
        val retention = cl.policy("p-d2-control-$run")
        assertEquals(201, api.put("/api/policies/%2F/$retention", """{"pattern":"^$control$","apply-to":"streams","priority":0,"definition":{"max-length-bytes":5000000}}""").status)
        M9SpikeDl.assertStaysAbsent(
            "a dead-letter-exchange policy must not show on a stream (apply-to streams and apply-to all, each on its own stream)",
            positive = { M9SpikeDl.effective(api, control)?.containsKey("max-length-bytes") == true },
            stillAbsent = { targets.values.all { M9SpikeDl.effective(api, it).isNullOrEmpty() && M9SpikeDl.policyOf(api, it) == null } },
        )
        targets.forEach { (applyTo, target) ->
            val d = M9SpikeDl.detail(api, target)
            M9SpikeDl.note("S-D2 stream (apply-to $applyTo) after the policies took ~effect time: policy=${d["policy"]} effective_policy_definition=${d["effective_policy_definition"]} arguments=${d["arguments"]}")
        }
    }

    // ---- S-D3: a stream filled beyond a tiny retention, with the policy and the argument ----

    @Test
    fun `S-D3 retention removes messages from a stream and nothing reaches the dead-letter exchange`() {
        val (dlx, dlq) = dlxWithQueue("d3")
        val stream = cl.queue(unique("d3-stream"))
        // tiny retention by arguments: segments of 100 kB, at most about 300 kB kept
        val created = api.declareQueue(stream, streamArgs(""","x-max-length-bytes":300000,"x-stream-max-segment-size-bytes":100000"""))
        assertEquals(201, created.status, "$created")
        val policy = cl.policy("p-d3-$run")
        assertEquals(201, api.put("/api/policies/%2F/$policy", """{"pattern":"^$stream$","apply-to":"streams","priority":0,"definition":{"dead-letter-exchange":"$dlx"}}""").status)
        val messages = 3000
        val payload = ByteArray(1000) { 'x'.code.toByte() }
        env().use { e ->
            val done = CountDownLatch(messages)
            e.producerBuilder().stream(stream).build().use { p ->
                repeat(messages) { p.send(p.messageBuilder().addData(payload).build()) { done.countDown() } }
                assertTrue(done.await(60, TimeUnit.SECONDS), "not all $messages publishes were confirmed")
            }
            // retention is evaluated when a segment rolls over; wait until it has really removed the head
            var first = 0L
            awaitUntil("the stream's first offset moves past 0 (retention removed messages)", 60) {
                first = e.queryStreamStats(stream).firstOffset(); first > 0
            }
            val stats = e.queryStreamStats(stream)
            M9SpikeDl.note("S-D3 after $messages messages of 1000 bytes: firstOffset=${stats.firstOffset()} committedChunkId=${stats.committedChunkId()} (so ${stats.firstOffset()} messages were removed by retention)")
            assertTrue(stats.firstOffset() > 0)
        }
        M9SpikeDl.assertStaysEmpty(api, dlq, "retention removed messages from the stream and the dead-letter queue stayed empty", seconds = 10)
        val d = M9SpikeDl.detail(api, stream)
        M9SpikeDl.note("S-D3 stream policy=${d["policy"]} effective=${d["effective_policy_definition"]} arguments=${d["arguments"]}")
        // positive control: the dead-letter exchange and its queue are alive
        assertTrue(api.post("/api/exchanges/%2F/$dlx/publish", """{"properties":{},"routing_key":"","payload":"control","payload_encoding":"string"}""").body.contains("\"routed\":true"))
        assertEquals(listOf("control"), M9SpikeDl.awaitPeek(api, dlq).map { it["payload"].toString().trim('"') })
    }
}
