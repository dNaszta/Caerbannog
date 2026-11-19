package dev.caerbannog.datarabbit

import com.rabbitmq.client.MessageProperties
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * M8-13 spike S-O: exclusive queues and consumers, redelivery and the delivery limit with real consumers, global QoS,
 * on RabbitMQ 3.13 and 4.x, with a plain AMQP 0-9-1 client (test-only dependency `com.rabbitmq:amqp-client`).
 * Findings: docs/tasks/M8-00-spike-results.md, section "S-O". The assertions pin what was measured, not what the docs say.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8SpikeDeliveryTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM8SpikeDeliveryTest {

    companion object {
        private val old: RabbitMQContainer = broker("rabbitmq:3.13-management")
        private val new: RabbitMQContainer = broker("rabbitmq:4-management")
        private val quorum = mapOf<String, Any>("x-queue-type" to "quorum")

        @JvmStatic @BeforeAll fun start() { old.start(); new.start() }
        @JvmStatic @AfterAll fun stop() { old.stop(); new.stop() }

        /** A consumer that nacks (or rejects) with requeue every delivery of one poison message and acks at [cap] to end the loop. */
        private fun requeueLoop(c: RabbitMQContainer, queue: String, args: Map<String, Any>, useReject: Boolean, cap: Int = 60): Pair<List<Delivery>, Int> {
            val api = MgmtApi(c)
            return withAmqp(c) { conn ->
                val ch = conn.createChannel()
                ch.queueDeclare(queue, true, false, false, args)
                ch.confirmSelect()
                ch.basicPublish("", queue, MessageProperties.PERSISTENT_TEXT_PLAIN, "poison".toByteArray())
                ch.waitForConfirmsOrDie(10_000)
                val cch = conn.createChannel()
                cch.basicQos(1)
                var count = 0
                val consumer = RecordingConsumer(cch) { chan, env ->
                    count++
                    when {
                        count >= cap -> chan.basicAck(env.deliveryTag, false)
                        useReject -> chan.basicReject(env.deliveryTag, true)
                        else -> chan.basicNack(env.deliveryTag, false, true)
                    }
                }
                cch.basicConsume(queue, false, consumer)
                var last = -1
                var idle = 0
                awaitUntil("deliveries to settle on $queue", 60) {
                    val n = consumer.seen.size
                    if (n == last) idle++ else { idle = 0; last = n }
                    idle >= 8
                }
                consumer.seen.toList() to api.peek(queue).size
            }
        }

        /** Opens a new connection per attempt, takes the one message without acking, drops the connection; counts how often it came back. */
        private fun connectionDropLoop(c: RabbitMQContainer, queue: String, args: Map<String, Any>, attempts: Int = 30): Pair<Int, Int> {
            withAmqp(c) { conn ->
                val ch = conn.createChannel()
                ch.queueDeclare(queue, true, false, false, args)
                ch.confirmSelect()
                ch.basicPublish("", queue, MessageProperties.PERSISTENT_BASIC, "x".toByteArray())
                ch.waitForConfirmsOrDie(10_000)
            }
            var deliveries = 0
            for (i in 1..attempts) {
                val got = withAmqp(c) { conn ->
                    val cch = conn.createChannel()
                    val cons = RecordingConsumer(cch)
                    cch.basicConsume(queue, false, cons)
                    try { awaitUntil("delivery $i on $queue", 6) { cons.seen.isNotEmpty() }; true } catch (_: IllegalStateException) { false }
                }
                if (!got) break
                deliveries++
            }
            return deliveries to MgmtApi(c).peek(queue).size
        }
    }

    // ---- exclusive queues and exclusive consumers ----

    @Test fun `exclusive classic queue is accepted, reported transient, never exported and goes with its connection`() {
        for ((label, c) in listOf("3.13" to old, "4.x" to new)) {
            val api = MgmtApi(c)
            withAmqp(c, "owner") { conn ->
                val ch = conn.createChannel()
                ch.queueDeclare("x-durable-asked", true, true, false, null)
                ch.queueDeclare("x-transient-asked", false, true, false, null)
                // A fresh queue has no statistics for a few seconds; wait for the owner field instead of sleeping.
                awaitUntil("$label stats for exclusive queues", 40) { api.get("/api/queues/%2F/x-durable-asked").obj["owner_pid_details"] != null }
                val g = api.get("/api/queues/%2F/x-durable-asked").obj
                assertEquals("true", g["exclusive"].toString(), label)
                assertEquals("false", g["durable"].toString(), "$label: declared durable, the broker reports it as not durable")
                val exported = api.get("/api/definitions").obj["queues"].toString()
                assertTrue(!exported.contains("x-durable-asked") && !exported.contains("x-transient-asked"), "$label: exclusive queues are never in the export")
                withAmqp(c, "other") { other ->
                    val refused = refusal { other.createChannel().let { it.basicConsume("x-durable-asked", true, RecordingConsumer(it)) } }
                    assertTrue(refused != null && refused.contains("405") && refused.contains("RESOURCE_LOCKED"), "$label: $refused")
                }
            }
            awaitUntil("$label exclusive queue deleted with its connection", 30) { api.get("/api/queues/%2F/x-durable-asked").status == 404 }
        }
    }

    @Test fun `exclusive quorum and stream queues are refused on both versions`() {
        for ((label, c) in listOf("3.13" to old, "4.x" to new)) {
            withAmqp(c) { conn ->
                for ((q, args) in listOf("xq-quorum" to quorum, "xq-stream" to mapOf<String, Any>("x-queue-type" to "stream"))) {
                    val refused = refusal { conn.createChannel().queueDeclare(q, true, true, false, args) }
                    assertTrue(refused != null && refused.contains("406") && refused.contains("invalid property 'exclusive-owner'"), "$label $q: $refused")
                }
            }
        }
    }

    @Test fun `exclusive consumer is enforced on a classic queue but silently not on a quorum queue`() {
        for ((label, c) in listOf("3.13" to old, "4.x" to new)) {
            val api = MgmtApi(c)
            withAmqp(c, "setup") { conn ->
                conn.createChannel().queueDeclare("xc-classic", true, false, false, null)
                conn.createChannel().queueDeclare("xc-quorum", true, false, false, quorum)
            }
            withAmqp(c, "first") { first ->
                val ch1 = first.createChannel()
                val ch2 = first.createChannel()
                assertNull(refusal { ch1.basicConsume("xc-classic", false, "ex-classic", false, true, null, RecordingConsumer(ch1)) }, label)
                assertNull(refusal { ch2.basicConsume("xc-quorum", false, "ex-quorum", false, true, null, RecordingConsumer(ch2)) }, "$label: the exclusive flag on a quorum queue is accepted")
                withAmqp(c, "second") { second ->
                    val refused = refusal { second.createChannel().let { it.basicConsume("xc-classic", false, RecordingConsumer(it)) } }
                    assertTrue(refused != null && refused.contains("403") && refused.contains("in exclusive use"), "$label classic: $refused")
                    val ch = second.createChannel()
                    assertNull(refusal { ch.basicConsume("xc-quorum", false, "plain-second", false, false, null, RecordingConsumer(ch)) },
                        "$label: a second consumer on a quorum queue with an exclusive consumer is accepted (exclusivity not enforced)")
                    awaitUntil("$label two consumers on xc-quorum listed", 40) {
                        api.get("/api/consumers/%2F").arr.count { (it as JsonObject)["queue"].toString().contains("xc-quorum") } >= 2
                    }
                }
            }
        }
    }

    // ---- redelivery and the delivery limit ----

    @Test fun `3_13 has no default delivery limit on a quorum queue and an explicit limit counts nack and reject`() {
        for (reject in listOf(false, true)) {
            val how = if (reject) "reject" else "nack"
            val (classic, classicLeft) = requeueLoop(old, "d313-classic-$how", emptyMap(), reject)
            assertEquals(60, classic.size, "3.13 classic $how"); assertEquals(0, classicLeft)
            val (quorumDefault, defaultLeft) = requeueLoop(old, "d313-quorum-$how", quorum, reject)
            assertEquals(60, quorumDefault.size, "3.13 quorum without limit, $how: no default limit"); assertEquals(0, defaultLeft)
            val (limited, limitedLeft) = requeueLoop(old, "d313-limit5-$how", quorum + mapOf("x-delivery-limit" to 5), reject)
            assertEquals(6, limited.size, "3.13 limit 5, $how: first delivery + 5 redeliveries"); assertEquals(0, limitedLeft)
        }
    }

    @Test fun `3_13 connection loss is not limited on quorum without an explicit limit`() {
        val (drops, left) = connectionDropLoop(old, "d313-drop-quorum", quorum)
        assertEquals(30, drops); assertEquals(1, left)
    }

    @Test fun `4_x default limit of 20 applies to reject and connection loss but nack with requeue is never counted`() {
        val (rejectDefault, rejectLeft) = requeueLoop(new, "d4-default-reject", quorum, useReject = true)
        assertEquals(21, rejectDefault.size, "4.x default limit 20: 1 delivery + 20 redeliveries, then the message is gone")
        assertEquals(0, rejectLeft, "the message is dropped silently when there is no dead-letter exchange")
        assertEquals(20L, rejectDefault.last().deliveryCount, "x-delivery-count counts redeliveries on 4.x")
        val (rejectFive, _) = requeueLoop(new, "d4-limit5-reject", quorum + mapOf("x-delivery-limit" to 5), useReject = true)
        assertEquals(6, rejectFive.size)

        val (nackDefault, nackLeft) = requeueLoop(new, "d4-default-nack", quorum, useReject = false)
        assertEquals(60, nackDefault.size, "4.x: basic.nack with requeue=true never reaches the limit (stopped by the loop's own cap)")
        assertEquals(0, nackLeft)
        assertTrue(nackDefault.all { it.deliveryCount == null }, "no x-delivery-count on nack redeliveries")
        val (nackFive, _) = requeueLoop(new, "d4-limit5-nack", quorum + mapOf("x-delivery-limit" to 5), useReject = false)
        assertEquals(60, nackFive.size, "an explicit limit of 5 does not stop nack either")

        val (drops, dropsLeft) = connectionDropLoop(new, "d4-drop-quorum", quorum)
        assertEquals(21, drops, "connection loss without ack counts like reject")
        assertEquals(0, dropsLeft)
        val (classicDrops, classicLeft) = connectionDropLoop(new, "d4-drop-classic", emptyMap())
        assertEquals(30, classicDrops, "a classic queue redelivers without limit"); assertEquals(1, classicLeft)
        val (classicNack, classicNackLeft) = requeueLoop(new, "d4-classic-nack", emptyMap(), useReject = false)
        assertEquals(60, classicNack.size); assertEquals(0, classicNackLeft)
    }

    @Test fun `a message past the limit is dead-lettered with reason delivery_limit when a dead-letter exchange exists`() {
        for ((label, c) in listOf("3.13" to old, "4.x" to new)) {
            val api = MgmtApi(c)
            withAmqp(c) { conn ->
                val ch = conn.createChannel()
                ch.queueDeclare("dead", true, false, false, quorum)
                ch.exchangeDeclare("dlx", "direct", true)
                ch.queueBind("dead", "dlx", "k")
            }
            val (seen, left) = requeueLoop(c, "d-dlx", quorum + mapOf("x-delivery-limit" to 3, "x-dead-letter-exchange" to "dlx", "x-dead-letter-routing-key" to "k"), useReject = true)
            assertEquals(4, seen.size, label); assertEquals(0, left, label)
            awaitUntil("$label dead-lettered message visible", 30) { api.peek("dead").size == 1 }
            val dead = api.post("/api/queues/%2F/dead/get", """{"count":1,"ackmode":"ack_requeue_true","encoding":"auto"}""").body
            assertTrue(dead.contains("\"x-first-death-reason\":\"delivery_limit\""), "$label: $dead")
        }
    }

    @Test fun `4_x policy delivery-limit is effective and shown, and the default 20 is reported once statistics exist`() {
        val api = MgmtApi(new)
        api.put("/api/policies/%2F/dl-pol", """{"pattern":"^d4-pol-","apply-to":"queues","priority":1,"definition":{"delivery-limit":3}}""")
        val (viaReject, _) = requeueLoop(new, "d4-pol-reject", quorum, useReject = true)
        assertEquals(4, viaReject.size)
        val (viaNack, _) = requeueLoop(new, "d4-pol-nack", quorum, useReject = false)
        assertEquals(60, viaNack.size)
        awaitUntil("policy visible on d4-pol-reject", 40) { api.get("/api/queues/%2F/d4-pol-reject").obj["policy"].toString() == "\"dl-pol\"" }
        assertEquals("{\"delivery-limit\":3}", api.get("/api/queues/%2F/d4-pol-reject").obj["effective_policy_definition"].toString())
        val (_, _) = requeueLoop(new, "d4-nolimit-shown", quorum, useReject = true)
        // The field arrives with the queue's statistics, a few seconds after declaring (it is absent before that).
        awaitUntil("delivery_limit reported for d4-nolimit-shown", 60) { api.get("/api/queues/%2F/d4-nolimit-shown").obj["delivery_limit"] != null }
        assertEquals("20", api.get("/api/queues/%2F/d4-nolimit-shown").obj["delivery_limit"].toString(), "4.x reports the effective default")
    }

    // ---- global QoS ----

    @Test fun `global qos is refused on a quorum queue by 3_13 but accepted and silently per consumer on 4_x`() {
        withAmqp(old) { conn -> conn.createChannel().queueDeclare("g313-quorum", true, false, false, quorum) }
        withAmqp(old) { conn ->
            val ch = conn.createChannel()
            val refused = refusal { ch.basicQos(5, true); ch.basicConsume("g313-quorum", false, RecordingConsumer(ch)) }
            assertTrue(refused != null && refused.contains("540") && refused.contains("does not support global qos"), "3.13: $refused")
        }
        assertEquals(listOf(3, 0), unackedAcrossTwoConsumers(old, "classic", global = true), "3.13 classic: the global limit is shared by the channel")
        assertEquals(listOf(3, 3), unackedAcrossTwoConsumers(old, "quorum", global = false))

        for (type in listOf("classic", "quorum")) {
            assertEquals(listOf(3, 3), unackedAcrossTwoConsumers(new, type, global = true), "4.x $type: global=true behaves per consumer")
            assertEquals(listOf(3, 3), unackedAcrossTwoConsumers(new, type, global = false), "4.x $type")
        }
        val flag = MgmtApi(new).get("/api/deprecated-features").arr.map { it as JsonObject }.first { it["name"].toString() == "\"global_qos\"" }
        assertEquals("\"denied_by_default\"", flag["deprecation_phase"].toString())
    }

    /** Two consumers on one channel, prefetch 3, 10 messages waiting in each queue: how many unacked deliveries each got. */
    private fun unackedAcrossTwoConsumers(c: RabbitMQContainer, type: String, global: Boolean): List<Int> {
        val args = if (type == "quorum") quorum else emptyMap()
        val tag = "$type-$global"
        return withAmqp(c, "glob") { conn ->
            val ch = conn.createChannel()
            val qa = "glob-a-$tag"; val qb = "glob-b-$tag"
            ch.queueDeclare(qa, true, false, false, args); ch.queueDeclare(qb, true, false, false, args)
            ch.confirmSelect()
            repeat(10) { ch.basicPublish("", qa, null, "x".toByteArray()); ch.basicPublish("", qb, null, "x".toByteArray()) }
            ch.waitForConfirmsOrDie(10_000)
            val cch = conn.createChannel()
            cch.basicQos(3, global)
            val ca = RecordingConsumer(cch); val cb = RecordingConsumer(cch)
            cch.basicConsume(qa, false, ca); cch.basicConsume(qb, false, cb)
            // The limit holds further deliveries back; wait until the numbers stop changing instead of a fixed sleep.
            var last = -1; var idle = 0
            awaitUntil("deliveries to settle ($tag)", 30) {
                val n = ca.seen.size + cb.seen.size
                if (n == last && n > 0) idle++ else { idle = 0; last = n }
                idle >= 6
            }
            listOf(ca.seen.size, cb.seen.size)
        }
    }
}
