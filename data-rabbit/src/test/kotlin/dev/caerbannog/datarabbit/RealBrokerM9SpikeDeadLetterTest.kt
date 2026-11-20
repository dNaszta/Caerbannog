package dev.caerbannog.datarabbit

import com.rabbitmq.client.ConfirmListener
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * M9-00b spike, part 1 (S-C1 to S-C6, S-C8, S-C9): dead-lettering of a queue set by POLICY (and by argument) on a real RabbitMQ 4.x broker:
 * which cause arrives, at-most-once against at-least-once, what publishers see when the queue is full, a missing dead-letter exchange, a classic
 * queue, a direct dead-letter exchange. Findings: docs/tasks/M9-00b-spike-results.md. Part 2 (a stream as the target, S-C7, and S-D): `RealBrokerM9SpikeStreamDeadLetterTest`.
 * A policy shows on a queue after about 6 s (M9-00c), so every wait is a bounded poll. A "nothing arrived" is always preceded by a positive control.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9SpikeDeadLetterTest*'`.
 */
@Tag("broker")
@Tag("spike")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM9SpikeDeadLetterTest {

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

    @AfterEach fun clean() = cl.run()

    /** A source queue, a fanout (or other) dead-letter exchange, a dead-letter queue bound to it. The policy is NOT set here. */
    private inner class Rig(val tag: String, srcArgs: String? = quorum, srcType: String = "quorum", dlxType: String = "fanout", bindKey: String = "", dlqArgs: String = quorum) {
        val src = cl.queue(unique("$tag-src"))
        val dlx = cl.exchange(unique("$tag-dlx"))
        val dlq = cl.queue(unique("$tag-dlq"))
        init {
            assertEquals(201, api.declareQueue(dlq, dlqArgs).status)
            assertEquals(201, api.put("/api/exchanges/%2F/$dlx", """{"type":"$dlxType","durable":true}""").status)
            assertEquals(201, api.post("/api/bindings/%2F/e/$dlx/q/$dlq", """{"routing_key":"$bindKey"}""").status)
            if (srcArgs != null) assertEquals(201, api.declareQueue(src, srcArgs).also { M9SpikeDl.note("declare $srcType $src") }.status)
        }
        fun policy(definition: String, applyTo: String = "queues", name: String = cl.policy("p-$src"), wait: String? = "dead-letter-exchange"): MgmtApi.Reply {
            val reply = api.put("/api/policies/%2F/$name", """{"pattern":"^$src$","apply-to":"$applyTo","priority":0,"definition":$definition}""")
            if (reply.ok && wait != null) M9SpikeDl.awaitEffective(api, src, wait)
            return reply
        }
        fun unbind() = assertEquals(204, api.delete("/api/bindings/%2F/e/$dlx/q/$dlq/${bindingProps()}").status)
        private fun bindingProps(): String =
            api.get("/api/bindings/%2F/e/$dlx/q/$dlq").arr.first().jsonObject["properties_key"]!!.jsonPrimitive.content
        fun bind(key: String = "") = assertEquals(201, api.post("/api/bindings/%2F/e/$dlx/q/$dlq", """{"routing_key":"$key"}""").status)
    }

    private fun dlxPolicy(r: Rig, extra: String = "") = """{"dead-letter-exchange":"${r.dlx}"$extra}"""

    private fun death(m: JsonObject): String {
        val h = M9SpikeDl.headersOf(m)
        val first = h["x-first-death-reason"]?.jsonPrimitive?.content
        val q = h["x-first-death-queue"]?.jsonPrimitive?.content
        val x = h["x-first-death-exchange"]?.jsonPrimitive?.content
        return "reason=$first queue=$q exchange=$x headers=${h.keys}"
    }

    @Test
    fun `The 4_x image is a 4_x broker`() = M9SpikeDl.assertFourX(api)

    // ---- S-C1: arrival per cause, by policy on a queue that exists without arguments ----

    @Test
    fun `S-C1 a policy with dead-letter-exchange on a queue without arguments, one row per cause`() {
        // expired
        val ex = Rig("c1x")
        ex.policy(dlxPolicy(ex))
        M9SpikeDl.note("S-C1 effective(expired rig)=${M9SpikeDl.effective(api, ex.src)} policy=${M9SpikeDl.policyOf(api, ex.src)}")
        assertEquals(JsonPrimitive(ex.dlx), M9SpikeDl.effective(api, ex.src)!!["dead-letter-exchange"])
        assertEquals("c1x-src-$run".let { "p-m9b-$it" }, M9SpikeDl.policyOf(api, ex.src))
        val pub = api.publish(ex.src, "expires", """{"delivery_mode":2,"expiration":"1000"}""")
        assertTrue(pub.body.contains("\"routed\":true"), "publish: $pub")
        val expired = M9SpikeDl.awaitPeek(api, ex.dlq)
        M9SpikeDl.note("S-C1 expired -> ${death(expired.single())}")
        assertEquals("expired", M9SpikeDl.headersOf(expired.single())["x-first-death-reason"]?.jsonPrimitive?.content)

        // rejected without requeue
        val rj = Rig("c1r")
        rj.policy(dlxPolicy(rj))
        assertTrue(api.publish(rj.src, "rejected").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, rj.src) }
        val rejected = M9SpikeDl.awaitPeek(api, rj.dlq)
        M9SpikeDl.note("S-C1 rejected -> ${death(rejected.single())}")
        assertEquals("rejected", M9SpikeDl.headersOf(rejected.single())["x-first-death-reason"]?.jsonPrimitive?.content)

        // over the delivery limit (the broker default, 20 in 4.x, no delivery-limit set)
        val dl = Rig("c1d")
        dl.policy(dlxPolicy(dl))
        assertTrue(api.publish(dl.src, "poison").body.contains("\"routed\":true"))
        var deliveries = 0
        withAmqp(container) { conn ->
            val ch = conn.createChannel()
            while (deliveries < 40) {
                val got = run {
                    var r: com.rabbitmq.client.GetResponse? = null
                    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (r == null && System.nanoTime() < end) r = ch.basicGet(dl.src, false)
                    r
                } ?: break
                deliveries++
                ch.basicReject(got.envelope.deliveryTag, true) // a nack with requeue is never counted on 4.4 (M8-00 S-O)
            }
        }
        M9SpikeDl.note("S-C1 delivery limit: deliveries=$deliveries")
        assertEquals(21, deliveries, "the default delivery limit is 20 redeliveries: 1 + 20 deliveries")
        val limited = M9SpikeDl.awaitPeek(api, dl.dlq)
        M9SpikeDl.note("S-C1 delivery limit: deliveries before it left=$deliveries -> ${death(limited.single())}")
        assertEquals("delivery_limit", M9SpikeDl.headersOf(limited.single())["x-first-death-reason"]?.jsonPrimitive?.content)

        // dropped by max-length (default overflow drop-head)
        val ml = Rig("c1m")
        ml.policy(dlxPolicy(ml, ""","max-length":1"""))
        assertTrue(api.publish(ml.src, "first").body.contains("\"routed\":true"))
        assertTrue(api.publish(ml.src, "second").body.contains("\"routed\":true"))
        val dropped = M9SpikeDl.awaitPeek(api, ml.dlq)
        M9SpikeDl.note("S-C1 max-length drop -> ${death(dropped.single())} payload=${dropped.single()["payload"]} effective=${M9SpikeDl.effective(api, ml.src)}")
        assertEquals("maxlen", M9SpikeDl.headersOf(dropped.single())["x-first-death-reason"]?.jsonPrimitive?.content)
        assertEquals("\"first\"", dropped.single()["payload"].toString(), "drop-head drops the oldest")
        awaitUntil("source holds only the newest", 30) { api.peek(ml.src) == listOf("second") }
    }

    // ---- S-C2: by argument, and both ----

    @Test
    fun `S-C2 an argument alone dead-letters, and an argument beats a policy on the same queue`() {
        val a = Rig("c2a", srcArgs = null)
        val ra = api.declareQueue(a.src, """{"x-queue-type":"quorum","x-dead-letter-exchange":"${a.dlx}"}""")
        assertEquals(201, ra.status, "$ra")
        assertTrue(api.publish(a.src, "by-arg").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, a.src) }
        val byArg = M9SpikeDl.awaitPeek(api, a.dlq)
        M9SpikeDl.note("S-C2 argument alone -> ${death(byArg.single())} policy=${M9SpikeDl.policyOf(api, a.src)}")

        // both: argument names exchange A, a policy names exchange B
        val both = Rig("c2b", srcArgs = null)
        val otherDlx = cl.exchange(unique("c2b-dlx-policy"))
        val otherDlq = cl.queue(unique("c2b-dlq-policy"))
        assertEquals(201, api.declareQueue(otherDlq, quorum).status)
        assertEquals(201, api.put("/api/exchanges/%2F/$otherDlx", """{"type":"fanout","durable":true}""").status)
        assertEquals(201, api.post("/api/bindings/%2F/e/$otherDlx/q/$otherDlq", """{"routing_key":""}""").status)
        assertEquals(201, api.declareQueue(both.src, """{"x-queue-type":"quorum","x-dead-letter-exchange":"${both.dlx}"}""").status)
        val reply = api.put("/api/policies/%2F/${cl.policy("p-${both.src}")}", """{"pattern":"^${both.src}$","apply-to":"queues","priority":0,"definition":{"dead-letter-exchange":"$otherDlx"}}""")
        assertEquals(201, reply.status)
        M9SpikeDl.awaitEffective(api, both.src)
        M9SpikeDl.note("S-C2 both: effective=${M9SpikeDl.effective(api, both.src)} policy=${M9SpikeDl.policyOf(api, both.src)} arguments=${M9SpikeDl.detail(api, both.src)["arguments"]}")
        assertTrue(api.publish(both.src, "both").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, both.src) }
        // the winner shows first; then the positive control that the other route is alive: dead-letter straight into it
        awaitUntil("a dead letter arrives in one of the two queues", 40) {
            M9SpikeDl.peekFull(api, both.dlq).isNotEmpty() || M9SpikeDl.peekFull(api, otherDlq).isNotEmpty()
        }
        val inArgument = M9SpikeDl.peekFull(api, both.dlq).size
        val inPolicy = M9SpikeDl.peekFull(api, otherDlq).size
        M9SpikeDl.note("S-C2 both: arrived in the ARGUMENT's exchange queue=$inArgument, in the POLICY's exchange queue=$inPolicy")
        assertEquals(1, inArgument, "an argument beats a policy of the same key (the dead letter went to the argument's exchange)")
        // B stays empty for a settle window, THEN the positive control shows B's route is alive (a message sent straight into B's exchange arrives in B's queue); a dead letter that had been routed to B would have shown in the window
        assertEquals(0, inPolicy, "the policy's exchange must not get the dead letter when the argument names another exchange")
        M9SpikeDl.assertStaysEmpty(api, otherDlq, "settle window: the policy's exchange B gets no dead letter while the argument names A")
        assertTrue(api.post("/api/exchanges/%2F/$otherDlx/publish", """{"properties":{},"routing_key":"","payload":"control-b","payload_encoding":"string"}""").body.contains("\"routed\":true"))
        assertEquals(listOf("control-b"), M9SpikeDl.awaitPeek(api, otherDlq).map { it["payload"].toString().trim('"') }, "B's route works and holds only the control")
        assertEquals(1, M9SpikeDl.peekFull(api, both.dlq).size, "A still holds exactly the one dead letter")
    }

    // ---- S-C3 / S-C4: at-least-once ----

    /** True while the source shows the signature of a held dead letter: one message counted, none ready, none unacknowledged (a requeue peek sees nothing). */
    private fun heldSignature(queue: String): Boolean = M9SpikeDl.detail(api, queue).let {
        it["messages"]?.jsonPrimitive?.content == "1" && it["messages_ready"]?.jsonPrimitive?.content == "0" && it["messages_unacknowledged"]?.jsonPrimitive?.content == "0"
    }

    /** Dead-letters "a" into a target that holds one message (classic, max-length 1, reject-publish), then "b", which the full target refuses.
     *  Returns true when the source holds b afterwards (the held signature appears within 25 s; statistics lag up to 5 s), false when it dropped it
     *  (then a control dead letter "c" proves the route is alive and b is not in the target). */
    private fun heldAfterRefusal(r: Rig): Boolean {
        assertTrue(api.publish(r.src, "a").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, r.src) }
        assertEquals(listOf("a"), M9SpikeDl.awaitPeek(api, r.dlq).map { it["payload"].toString().trim('"') })
        assertTrue(api.publish(r.src, "b").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, r.src) }
        var held = false
        try { awaitUntil("the source holds the refused b", 25) { heldSignature(r.src) }; held = true } catch (_: IllegalStateException) { }
        if (!held) {
            assertEquals(0, M9SpikeDl.messages(api, r.src), "the source is empty: b was dropped")
            assertEquals(listOf("a"), api.drain(r.dlq, 5))
            assertTrue(api.publish(r.src, "c").body.contains("\"routed\":true"))
            withAmqp(container) { M9SpikeDl.rejectOne(it, r.src) }
            awaitUntil("the control dead letter c arrives", 40) { api.peek(r.dlq).isNotEmpty() }
            assertEquals(listOf("c"), api.peek(r.dlq), "b must not appear next to the control c")
        }
        return held
    }

    private val fullTarget = """{"x-queue-type":"classic","x-max-length":1,"x-overflow":"reject-publish"}"""
    private fun alPolicy(r: Rig, strategyAndOverflow: String) = r.policy(dlxPolicy(r, strategyAndOverflow))

    @Test
    fun `S-C3 at-least-once by policy with reject-publish is accepted and takes effect, a dead letter arrives`() {
        val al = Rig("c3al")
        val reply = alPolicy(al, ""","dead-letter-strategy":"at-least-once","overflow":"reject-publish"""")
        M9SpikeDl.note("S-C3 PUT=${reply.status} effective=${M9SpikeDl.effective(api, al.src)} policy=${M9SpikeDl.policyOf(api, al.src)}")
        assertEquals(201, reply.status)
        assertEquals(JsonPrimitive("at-least-once"), M9SpikeDl.effective(api, al.src)!!["dead-letter-strategy"])
        assertEquals(JsonPrimitive("reject-publish"), M9SpikeDl.effective(api, al.src)!!["overflow"])
        assertTrue(api.publish(al.src, "one").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, al.src) }
        val arrived = M9SpikeDl.awaitPeek(api, al.dlq)
        M9SpikeDl.note("S-C3 at-least-once, route works -> ${death(arrived.single())}")
        assertEquals("rejected", M9SpikeDl.headersOf(arrived.single())["x-first-death-reason"]?.jsonPrimitive?.content)
        awaitUntil("source empty after delivery", 30) { M9SpikeDl.messages(api, al.src) == 0 }
        M9SpikeDl.assertStaysEmpty(api, al.src, "source after a successful at-least-once dead-letter")
    }

    @Test
    fun `S-C3 a target that refuses the dead letter, at-least-once keeps it, at-most-once loses it`() {
        val al = Rig("c3full-al", dlqArgs = fullTarget)
        alPolicy(al, ""","dead-letter-strategy":"at-least-once","overflow":"reject-publish"""")
        val kept = heldAfterRefusal(al)
        M9SpikeDl.note("S-C3 target full, at-least-once: b held in the source=$kept")
        assertTrue(kept, "at-least-once keeps the refused dead letter")

        val am = Rig("c3full-am", dlqArgs = fullTarget)
        alPolicy(am, "")
        val held = heldAfterRefusal(am)
        M9SpikeDl.note("S-C3 target full, at-most-once: b held in the source=$held")
        assertFalse(held, "at-most-once drops the refused dead letter")
    }

    @Test
    fun `S-C3 a target without a binding, at-least-once holds the message as a count a peek cannot see, at-most-once loses it`() {
        val al = Rig("c3unb-al")
        alPolicy(al, ""","dead-letter-strategy":"at-least-once","overflow":"reject-publish"""")
        al.unbind()
        assertTrue(api.publish(al.src, "held").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, al.src) }
        awaitUntil("the source counts one message that is neither ready nor unacknowledged", 40) {
            val d = M9SpikeDl.detail(api, al.src)
            d["messages"]?.jsonPrimitive?.content == "1" && d["messages_ready"]?.jsonPrimitive?.content == "0" && d["messages_unacknowledged"]?.jsonPrimitive?.content == "0"
        }
        M9SpikeDl.note("S-C3 at-least-once, NO binding: messages=1 ready=0 unacked=0; peek of the source=${api.peek(al.src)} dlq=${api.peek(al.dlq)}")
        assertEquals(emptyList<String>(), api.peek(al.src), "a requeue peek cannot see the held message")
        // positive control: the route comes back and a new dead letter flows; the held one does not follow
        al.bind()
        assertTrue(api.publish(al.src, "poke").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, al.src) }
        awaitUntil("the control dead letter arrives", 40) { api.peek(al.dlq).isNotEmpty() }
        // the held message is NOT in the target before the retry: the target holds exactly the control (a held message is never ready in the
        // source, so a check of the source proves nothing). The control was dead-lettered AFTER the held one, so if the held one were going to
        // arrive first or at once it would show here.
        assertEquals(listOf("poke"), api.peek(al.dlq), "the target holds only the control; the held message did not follow it")
        assertTrue(heldSignature(al.src), "the held message is still in the source (messages=1, ready 0, unacked 0)")
        val d = M9SpikeDl.detail(api, al.src)
        M9SpikeDl.note("S-C3 at-least-once, binding back and a new dead letter delivered: target=${api.peek(al.dlq)} source messages=${d["messages"]} ready=${d["messages_ready"]} unacked=${d["messages_unacknowledged"]}")

        val am = Rig("c3unb-am")
        am.policy(dlxPolicy(am))
        am.unbind()
        assertTrue(api.publish(am.src, "gone").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, am.src) }
        awaitUntil("at-most-once: the source is empty", 40) { M9SpikeDl.messages(api, am.src) == 0 }
        am.bind()
        assertTrue(api.publish(am.src, "three").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, am.src) }
        awaitUntil("only the later dead letter arrived", 40) { api.peek(am.dlq) == listOf("three") }
        M9SpikeDl.note("S-C3 at-most-once, NO binding: message gone from the source; after rebind only the new one arrives")
    }

    @Test
    fun `S-C4 at-least-once without reject-publish, by policy and by argument, which strategy is in effect`() {
        // by policy, no overflow
        val p = Rig("c4p", dlqArgs = fullTarget)
        val reply = alPolicy(p, ""","dead-letter-strategy":"at-least-once"""")
        M9SpikeDl.note("S-C4 policy without overflow: PUT=${reply.status} effective=${M9SpikeDl.effective(api, p.src)}")
        assertEquals(201, reply.status)
        assertEquals(JsonPrimitive("at-least-once"), M9SpikeDl.effective(api, p.src)!!["dead-letter-strategy"])
        val outP = heldAfterRefusal(p)
        M9SpikeDl.note("S-C4 policy without overflow, full target: held=$outP")
        assertFalse(outP, "policy dead-letter-strategy at-least-once WITHOUT overflow behaves as at-most-once: the refused dead letter is dropped, not held")

        // by policy, overflow drop-head
        val d = Rig("c4d", dlqArgs = fullTarget)
        val rd = alPolicy(d, ""","dead-letter-strategy":"at-least-once","overflow":"drop-head"""")
        M9SpikeDl.note("S-C4 policy with overflow drop-head: PUT=${rd.status} effective=${M9SpikeDl.effective(api, d.src)}")
        val outD = heldAfterRefusal(d)
        M9SpikeDl.note("S-C4 policy with overflow drop-head, full target: held=$outD")
        assertEquals(201, rd.status, "$rd")
        assertFalse(outD, "policy at-least-once with overflow drop-head behaves as at-most-once: the refused dead letter is dropped, not held")

        // by argument, no x-overflow
        val a = Rig("c4a", srcArgs = null, dlqArgs = fullTarget)
        val ra = api.declareQueue(a.src, """{"x-queue-type":"quorum","x-dead-letter-exchange":"${a.dlx}","x-dead-letter-strategy":"at-least-once"}""")
        M9SpikeDl.note("S-C4 argument without x-overflow: ${ra.status} ${ra.body.take(300)} arguments=${if (ra.ok) M9SpikeDl.detail(api, a.src)["arguments"] else "-"}")
        assertEquals(201, ra.status, "the argument form without x-overflow must be accepted (it was refused: $ra)")
        val outA = heldAfterRefusal(a)
        M9SpikeDl.note("S-C4 argument without x-overflow, full target: held=$outA")
        assertFalse(outA, "arguments x-dead-letter-strategy at-least-once WITHOUT x-overflow behave as at-most-once: the refused dead letter is dropped, not held")

        // by argument with x-overflow reject-publish
        val b = Rig("c4b", srcArgs = null, dlqArgs = fullTarget)
        val rb = api.declareQueue(b.src, """{"x-queue-type":"quorum","x-dead-letter-exchange":"${b.dlx}","x-dead-letter-strategy":"at-least-once","x-overflow":"reject-publish"}""")
        M9SpikeDl.note("S-C4 argument with x-overflow reject-publish: ${rb.status} arguments=${if (rb.ok) M9SpikeDl.detail(api, b.src)["arguments"] else "-"}")
        assertEquals(201, rb.status)
        val outB = heldAfterRefusal(b)
        M9SpikeDl.note("S-C4 argument with reject-publish, full target: held=$outB")
        assertTrue(outB, "positive control: at-least-once by argument with reject-publish keeps the message")
    }

    // ---- S-C5: what publishers see when the queue is full ----

    @Test
    fun `S-C5 reject-publish on a full quorum queue, the limit is exceeded by one, then publishers are refused`() {
        val q = cl.queue(unique("c5"))
        assertEquals(201, api.declareQueue(q, """{"x-queue-type":"quorum","x-max-length":2,"x-overflow":"reject-publish"}""").status)
        val confirms = mutableListOf<String>()
        withAmqp(container) { conn ->
            val ch = conn.createChannel()
            ch.confirmSelect()
            for (i in 1..6) {
                ch.basicPublish("", q, true, M9SpikeDl.persistent(), "m$i".toByteArray())
                confirms += "m$i:" + if (ch.waitForConfirms(10_000)) "ack" else "nack"
            }
        }
        M9SpikeDl.note("S-C5 quorum max-length 2: AMQP confirms=$confirms queue=${api.peek(q)}")
        assertEquals(listOf("m1:ack", "m2:ack", "m3:ack", "m4:nack", "m5:nack", "m6:nack"), confirms)
        assertEquals(listOf("m1", "m2", "m3"), api.peek(q), "a quorum queue with max-length 2 holds three messages")
        val fourth = api.publish(q, "m7")
        M9SpikeDl.note("S-C5 management publish when full: ${fourth.status} ${fourth.body.take(200)}")
        assertEquals(400, fourth.status)
        assertTrue("Unable to publish message. Check queue limits." in fourth.body, "$fourth")
        // positive control: after one message is taken the queue takes a publish again
        assertEquals(listOf("m1"), api.drain(q, 1))
        awaitUntil("room again", 20) { api.publish(q, "m8").ok }
        assertEquals(listOf("m2", "m3", "m8"), api.peek(q))
    }

    @Test
    fun `S-C5 the same quorum limit set by POLICY holds three and refuses the rest`() {
        val q = cl.queue(unique("c5p"))
        assertEquals(201, api.declareQueue(q, """{"x-queue-type":"quorum"}""").status)
        val reply = api.put("/api/policies/%2F/${cl.policy("p-$q")}", """{"pattern":"^$q$","apply-to":"quorum_queues","priority":0,"definition":{"max-length":2,"overflow":"reject-publish"}}""")
        assertEquals(201, reply.status, "$reply")
        M9SpikeDl.awaitEffective(api, q, "max-length")
        assertEquals(JsonPrimitive("reject-publish"), M9SpikeDl.effective(api, q)!!["overflow"])
        val confirms = mutableListOf<String>()
        withAmqp(container) { conn ->
            val ch = conn.createChannel()
            ch.confirmSelect()
            for (i in 1..6) {
                ch.basicPublish("", q, true, M9SpikeDl.persistent(), "m$i".toByteArray())
                confirms += "m$i:" + if (ch.waitForConfirms(10_000)) "ack" else "nack"
            }
        }
        M9SpikeDl.note("S-C5 quorum max-length 2 BY POLICY: AMQP confirms=$confirms queue=${api.peek(q)}")
        assertEquals(listOf("m1:ack", "m2:ack", "m3:ack", "m4:nack", "m5:nack", "m6:nack"), confirms)
        assertEquals(listOf("m1", "m2", "m3"), api.peek(q), "a quorum queue with max-length 2 set by policy holds three messages")
    }

    @Test
    fun `S-C5 the same limit on a classic queue is exact, and drop-head (the default) keeps the newest`() {
        val c = cl.queue(unique("c5c"))
        assertEquals(201, api.declareQueue(c, """{"x-queue-type":"classic","x-max-length":2,"x-overflow":"reject-publish"}""").status)
        val answers = (1..4).map { api.publish(c, "m$it") }
        M9SpikeDl.note("S-C5 classic max-length 2: ${answers.map { it.status }} queue=${api.peek(c)}")
        assertEquals(listOf(200, 200, 400, 400), answers.map { it.status })
        assertEquals(listOf("m1", "m2"), api.peek(c))
        val d = cl.queue(unique("c5d"))
        assertEquals(201, api.declareQueue(d, """{"x-queue-type":"quorum","x-max-length":2}""").status)
        repeat(4) { assertTrue(api.publish(d, "m${it + 1}").body.contains("\"routed\":true")) }
        // one outcome, seen in 3 of 3 runs on 4.4.0 (M9-00b-b); a quorum queue with drop-head holds exactly the limit, not limit + 1
        awaitUntil("drop-head keeps the newest two", 30) { api.peek(d) == listOf("m3", "m4") }
        assertEquals(listOf("m3", "m4"), api.peek(d), "still the newest two after the settle")
        M9SpikeDl.note("S-C5 default overflow (drop-head), quorum max-length 2, four publishes accepted: queue=${api.peek(d)}")
    }

    // ---- S-C6: a dead-letter exchange that does not exist ----

    @Test
    fun `S-C6 a policy that names a dead-letter exchange that does not exist`() {
        val r = Rig("c6")
        val missing = unique("c6-missing")
        val reply = r.policy("""{"dead-letter-exchange":"$missing"}""")
        M9SpikeDl.note("S-C6 PUT=${reply.status} effective=${M9SpikeDl.effective(api, r.src)}")
        assertEquals(201, reply.status)
        assertEquals(JsonPrimitive(missing), M9SpikeDl.effective(api, r.src)!!["dead-letter-exchange"])
        assertEquals(404, api.get("/api/exchanges/%2F/$missing").status, "the exchange really does not exist")
        assertTrue(api.publish(r.src, "lost?").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, r.src) }
        awaitUntil("the rejected message leaves the source queue", 40) { M9SpikeDl.messages(api, r.src) == 0 && api.peek(r.src).isEmpty() }
        // then the exchange is created and bound: the earlier dead letter must not appear, a later one does (positive control)
        assertEquals(201, api.put("/api/exchanges/%2F/${cl.exchange(missing)}", """{"type":"fanout","durable":true}""").status)
        assertEquals(201, api.post("/api/bindings/%2F/e/$missing/q/${r.dlq}", """{"routing_key":""}""").status)
        assertTrue(api.publish(r.src, "later").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, r.src) }
        awaitUntil("only the later dead letter arrives", 40) { api.peek(r.dlq) == listOf("later") }
        M9SpikeDl.note("S-C6 at-most-once, missing exchange: message lost; after the exchange exists a new one arrives, the old does not (dlq=${api.peek(r.dlq)})")

        // at-least-once with a missing exchange: the dead letter is held (the signature), not dropped
        val al = Rig("c6al")
        val ghost = unique("c6-ghost")
        al.policy("""{"dead-letter-exchange":"$ghost","dead-letter-strategy":"at-least-once","overflow":"reject-publish"}""")
        assertTrue(api.publish(al.src, "kept?").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, al.src) }
        awaitUntil("the source holds the dead letter (messages=1, ready 0, unacked 0)", 40) { heldSignature(al.src) }
        M9SpikeDl.note("S-C6 at-least-once, missing exchange: held (messages=1, ready 0, unacked 0), a peek of the source=${api.peek(al.src)}")
        assertEquals(emptyList<String>(), api.peek(al.src))
    }

    // ---- S-C3: the retry of a held dead letter is slow ----

    @Test
    fun `S-C3 a held dead letter is delivered by a slow retry once the target can take it`() {
        // three causes of "cannot take it", each with at-least-once; all rejected first, all repaired together, then one shared wait
        val full = Rig("c3rt-full", dlqArgs = fullTarget)
        val unbound = Rig("c3rt-unb")
        val missing = Rig("c3rt-miss")
        val ghost = unique("c3rt-ghost")
        alPolicy(full, ""","dead-letter-strategy":"at-least-once","overflow":"reject-publish"""")
        alPolicy(unbound, ""","dead-letter-strategy":"at-least-once","overflow":"reject-publish"""")
        missing.policy("""{"dead-letter-exchange":"$ghost","dead-letter-strategy":"at-least-once","overflow":"reject-publish"}""")
        full.also { assertTrue(api.publish(it.src, "a").body.contains("\"routed\":true")); withAmqp(container) { c -> M9SpikeDl.rejectOne(c, it.src) } }
        assertEquals(1, M9SpikeDl.awaitPeek(api, full.dlq).size)
        unbound.unbind()
        listOf(full to "b", unbound to "u", missing to "m").forEach { (r, body) ->
            assertTrue(api.publish(r.src, body).body.contains("\"routed\":true"))
            withAmqp(container) { c -> M9SpikeDl.rejectOne(c, r.src) }
        }
        awaitUntil("all three sources hold one dead letter", 40) { listOf(full, unbound, missing).all { heldSignature(it.src) } }
        // repair: make room, bind again, create the exchange and bind the queue to it
        assertEquals(listOf("a"), api.drain(full.dlq, 5))
        unbound.bind()
        assertEquals(201, api.put("/api/exchanges/%2F/${cl.exchange(ghost)}", """{"type":"fanout","durable":true}""").status)
        assertEquals(201, api.post("/api/bindings/%2F/e/$ghost/q/${missing.dlq}", """{"routing_key":""}""").status)
        val start = System.nanoTime()
        val seenAt = mutableMapOf<String, Long>()
        awaitUntil("every held dead letter is delivered by the retry (seen so far: $seenAt)", 400) {
            listOf(full to "b", unbound to "u", missing to "m").forEach { (r, body) ->
                if (r.dlq !in seenAt && api.peek(r.dlq).contains(body)) seenAt[r.dlq] = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)
            }
            seenAt.size == 3
        }
        // Observations of the retry, all RabbitMQ 4.4.0, measured from the repair (R5 of the review, recorded honestly, NOT asserted):
        // this test once at 175 s (all three kinds at that second); throwaway probes: 165 to 170 s once, nothing within 40 s and nothing within 60 s.
        // Four observations: only "not within one minute, within three" is supported; the interval and what the timer counts from are unknown.
        // The poll step of this test is one `peek` per kind per loop (a few hundred ms), so seenAt has about one second of resolution.
        M9SpikeDl.note("S-C3 retry of a held dead letter: seenAt seconds after the repair until it arrived: full target=${seenAt[full.dlq]}, unbound=${seenAt[unbound.dlq]}, missing exchange=${seenAt[missing.dlq]}")
        awaitUntil("the sources are empty once delivered", 40) { listOf(full, unbound, missing).all { M9SpikeDl.messages(api, it.src) == 0 } }
    }

    // ---- S-C8: a classic queue ----

    @Test
    fun `S-C8 a classic queue with the same policy, and dead-letter-strategy on a classic queue by policy`() {
        val c = Rig("c8", srcArgs = """{"x-queue-type":"classic"}""", srcType = "classic")
        c.policy(dlxPolicy(c))
        M9SpikeDl.note("S-C8 classic, dlx policy: effective=${M9SpikeDl.effective(api, c.src)}")
        assertTrue(api.publish(c.src, "rej").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, c.src) }
        val arrived = M9SpikeDl.awaitPeek(api, c.dlq)
        M9SpikeDl.note("S-C8 classic at-most-once rejected -> ${death(arrived.single())}")
        assertTrue(api.publish(c.src, "exp", """{"delivery_mode":2,"expiration":"1000"}""").body.contains("\"routed\":true"))
        val both = M9SpikeDl.awaitPeek(api, c.dlq, 2)
        M9SpikeDl.note("S-C8 classic expired -> ${both.map { M9SpikeDl.headersOf(it)["x-first-death-reason"]?.jsonPrimitive?.content }}")

        // dead-letter-strategy on a classic queue by policy: accepted, but the whole policy does not bind (M9-00c) so nothing dead-letters
        val s = Rig("c8s", srcArgs = """{"x-queue-type":"classic"}""", srcType = "classic")
        val reply = s.policy(dlxPolicy(s, ""","dead-letter-strategy":"at-least-once","overflow":"reject-publish""""), wait = null)
        M9SpikeDl.note("S-C8 classic with at-least-once policy: PUT=${reply.status}")
        assertEquals(201, reply.status)
        // positive control for 'nothing happens': a plain dlx policy on a classic queue of the same batch is effective
        val ctrl = Rig("c8c", srcArgs = """{"x-queue-type":"classic"}""", srcType = "classic")
        ctrl.policy(dlxPolicy(ctrl))
        assertTrue(api.publish(s.src, "never").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, s.src) }
        awaitUntil("source empty after reject", 40) { api.peek(s.src).isEmpty() }
        M9SpikeDl.assertStaysEmpty(api, s.dlq, "classic queue with a policy that carries dead-letter-strategy: the policy does not bind, nothing dead-letters")
        M9SpikeDl.note("S-C8 classic at-least-once policy: policy=${M9SpikeDl.policyOf(api, s.src)} effective=${M9SpikeDl.effective(api, s.src)}; reject lost the message, dlq empty")
    }

    // ---- S-C9: a direct dead-letter exchange without dead-letter-routing-key ----

    @Test
    fun `S-C9 a direct dead-letter exchange without dead-letter-routing-key keeps the routing key of the publish`() {
        val r = Rig("c9", dlxType = "direct", bindKey = "orders.created")
        val byName = cl.queue(unique("c9-by-name"))
        assertEquals(201, api.declareQueue(byName, quorum).status)
        assertEquals(201, api.post("/api/bindings/%2F/e/${r.dlx}/q/$byName", """{"routing_key":"${r.src}"}""").status)
        assertEquals(201, api.post("/api/bindings/%2F/e/amq.direct/q/${r.src}", """{"routing_key":"orders.created"}""").status)
        r.policy(dlxPolicy(r))
        val pub = api.post("/api/exchanges/%2F/amq.direct/publish", """{"properties":{"delivery_mode":2},"routing_key":"orders.created","payload":"k","payload_encoding":"string"}""")
        assertTrue(pub.body.contains("\"routed\":true"), "$pub")
        withAmqp(container) { M9SpikeDl.rejectOne(it, r.src) }
        val got = M9SpikeDl.awaitPeek(api, r.dlq)
        M9SpikeDl.note("S-C9 routing key of the dead letter: routing_key=${got.single()["routing_key"]} exchange=${got.single()["exchange"]} ${death(got.single())} x-death=${M9SpikeDl.headersOf(got.single())["x-death"]}")
        assertEquals("orders.created", got.single()["routing_key"]!!.jsonPrimitive.content)
        // control: the queue bound under the SOURCE QUEUE's name did not get it
        M9SpikeDl.assertStaysEmpty(api, byName, "the dead letter keeps the original routing key, not the queue name")

        // control: with dead-letter-routing-key the dead letter carries that key
        val k = Rig("c9k", dlxType = "direct", bindKey = "dead")
        k.policy(dlxPolicy(k, ""","dead-letter-routing-key":"dead""""))
        assertTrue(api.publish(k.src, "k2").body.contains("\"routed\":true"))
        withAmqp(container) { M9SpikeDl.rejectOne(it, k.src) }
        val keyed = M9SpikeDl.awaitPeek(api, k.dlq)
        M9SpikeDl.note("S-C9 with dead-letter-routing-key=dead: routing_key=${keyed.single()["routing_key"]}")
        assertEquals("dead", keyed.single()["routing_key"]!!.jsonPrimitive.content)
    }
}
