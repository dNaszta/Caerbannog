package dev.caerbannog.datarabbit

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.Network
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * M8-00 spike, part 2 (S-F, S-J): a dynamic Shovel on a 4.x broker pulling from a 3.13 broker over a Docker network (alias `old`).
 * Findings are written in docs/tasks/M8-00-spike-results.md. Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8SpikeShovelTest*'`.
 */
@Tag("broker")
@Tag("spike")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM8SpikeShovelTest {

    companion object {
        private val network: Network = Network.newNetwork()
        private val old: RabbitMQContainer = broker(BrokerImages.LEGACY_3X).withNetwork(network).withNetworkAliases("old")
        private val new: RabbitMQContainer = brokerWithPlugins(BrokerImages.CURRENT_4X, "rabbitmq_shovel", "rabbitmq_shovel_management").withNetwork(network).withNetworkAliases("new")
        private lateinit var o: MgmtApi
        private lateinit var n: MgmtApi

        @JvmStatic @BeforeAll fun start() {
            old.start(); new.start()
            o = MgmtApi(old); n = MgmtApi(new)
            o.put("/api/users/mon", """{"password":"mon-pass","tags":"monitoring"}""")
            n.put("/api/users/svc", """{"password":"svc-pass","tags":"management,policymaker"}""")
            n.put("/api/users/mon", """{"password":"mon-pass","tags":"monitoring"}""")
            n.put("/api/users/adm", """{"password":"adm-pass","tags":"administrator"}""")
            n.put("/api/permissions/%2F/adm", """{"configure":".*","write":".*","read":".*"}""")
            n.put("/api/permissions/%2F/svc", """{"configure":".*","write":".*","read":".*"}""")
        }

        @JvmStatic @AfterAll fun stop() { new.stop(); old.stop(); network.close() }
    }

    private fun shovelBody(srcQueue: String, destQueue: String, deleteAfter: String, srcUri: String = "amqp://guest:guest@old:5672") =
        """{"value":{"src-protocol":"amqp091","src-uri":"$srcUri","src-queue":"$srcQueue","dest-protocol":"amqp091","dest-uri":"amqp://","dest-queue":"$destQueue","src-delete-after":"$deleteAfter","ack-mode":"on-confirm"}}"""

    private fun shovelNamed(name: String) = n.get("/api/shovels").arr.map { it.jsonObject }.firstOrNull { it["name"]!!.jsonPrimitive.content == name }

    @Test
    fun `S-F the shovel plugin is detected by GET api shovels, 404 without it and 200 with it`() {
        assertEquals(404, o.get("/api/shovels").status, "3.13 without the shovel plugins")
        assertEquals(200, n.get("/api/shovels").status, "4.x with rabbitmq_shovel_management")
    }

    @Test
    fun `S-F and S-J queue-length moves the messages present when the shovel connects, keeps order and properties, removes itself, and leaves the source queue`() {
        o.declareQueue("ship")
        n.declareQueue("ship-q", """{"x-queue-type":"quorum"}""")
        repeat(120) { o.publish("ship", "body-$it", """{"delivery_mode":2,"content_type":"text/plain","message_id":"id-$it","headers":{"h":"v$it"}}""") }
        awaitUntil("120 messages on the source") { o.get("/api/queues/%2F/ship").obj["messages"]?.jsonPrimitive?.content == "120" }

        val put = n.put("/api/parameters/shovel/%2F/caerbannog-migrate-ship", shovelBody("ship", "ship-q", "queue-length"))
        assertEquals(201, put.status, put.toString())
        repeat(20) { o.publish("ship", "extra-$it") } // a publisher that keeps running while the shovel starts (S-J)

        awaitUntil("the shovel finished and removed its own parameter", 90) { n.get("/api/parameters/shovel/%2F/caerbannog-migrate-ship").status == 404 }
        val onDest = n.peek("ship-q")
        val onSource = o.peek("ship")
        assertEquals(140, onDest.size + onSource.size, "nothing lost, nothing duplicated: dest ${onDest.size} + source ${onSource.size}")
        assertTrue(onDest.size >= 120, "at least the 120 that were there at PUT time moved: ${onDest.size}")
        assertTrue(onSource.isNotEmpty(), "queue-length stops at the length seen when the shovel connected: later publishes stay on the source")
        assertEquals((0 until 120).map { "body-$it" }, onDest.take(120), "order preserved")
        val first = n.post("/api/queues/%2F/ship-q/get", """{"count":1,"ackmode":"ack_requeue_true","encoding":"auto"}""").arr[0].jsonObject
        val props = first["properties"]!!.jsonObject
        assertEquals("id-0", props["message_id"]!!.jsonPrimitive.content)
        assertEquals("text/plain", props["content_type"]!!.jsonPrimitive.content)
        assertEquals("v0", props["headers"]!!.jsonObject["h"]!!.jsonPrimitive.content)
        assertEquals(200, o.get("/api/queues/%2F/ship").status, "the source queue is never deleted")
        n.drain("ship-q"); o.drain("ship")
    }

    @Test
    fun `S-F a running shovel reports itself without the password, while the parameter and the definitions show it in clear`() {
        o.declareQueue("ship2"); n.declareQueue("ship2-q", """{"x-queue-type":"quorum"}""")
        repeat(10) { o.publish("ship2", "b$it") }
        awaitUntil("10 on source") { o.get("/api/queues/%2F/ship2").obj["messages"]?.jsonPrimitive?.content == "10" }
        assertEquals(201, n.put("/api/parameters/shovel/%2F/shape-ok", shovelBody("ship2", "ship2-q", "never")).status)
        try {
            awaitUntil("shovel running and forwarded 10") { shovelNamed("shape-ok")?.get("forwarded")?.jsonPrimitive?.content == "10" }
            val s = shovelNamed("shape-ok")!!
            assertEquals("running", s["state"]!!.jsonPrimitive.content)
            assertEquals("dynamic", s["type"]!!.jsonPrimitive.content)
            assertEquals("amqp://old:5672", s["src_uri"]!!.jsonPrimitive.content, "GET /api/shovels redacts the credentials")
            assertEquals("amqp://", s["dest_uri"]!!.jsonPrimitive.content, "a credential-free local destination URI works")
            assertEquals(setOf("ship2", "ship2-q"), setOf(s["src_queue"]!!.jsonPrimitive.content, s["dest_queue"]!!.jsonPrimitive.content))
            assertTrue(s.keys.containsAll(setOf("node", "timestamp", "name", "vhost", "pending", "remaining", "remaining_unacked", "forwarded", "src_protocol", "dest_protocol", "blocked_status")), s.keys.toString())
            val param = n.get("/api/parameters/shovel/%2F/shape-ok")
            assertTrue(param.body.contains("amqp://guest:guest@old:5672"), "the parameter endpoint returns the URI WITH the password")
            assertTrue(n.get("/api/definitions").body.contains("amqp://guest:guest@old:5672"), "definitions export it too")
            assertTrue(n.get("/api/parameters/shovel").body.contains("guest:guest"))
        } finally {
            assertEquals(204, n.delete("/api/parameters/shovel/%2F/shape-ok").status)
        }
        awaitUntil("shovel gone from the status list") { shovelNamed("shape-ok") == null }
        assertEquals(200, o.get("/api/queues/%2F/ship2").status, "removing the shovel keeps the source queue")
        n.drain("ship2-q")
    }

    @Test
    fun `S-F a shovel with a wrong source password is created anyway and only its state says terminated`() {
        o.declareQueue("ship3")
        val put = n.put("/api/parameters/shovel/%2F/shape-bogus", shovelBody("ship3", "ship3-q", "queue-length", "amqp://guest:wrong-password@old:5672"))
        assertEquals(201, put.status, "no validation of the source at PUT time")
        try {
            awaitUntil("terminated") { shovelNamed("shape-bogus")?.get("state")?.jsonPrimitive?.content == "terminated" }
            assertTrue(shovelNamed("shape-bogus")!!["reason"]!!.jsonPrimitive.content.contains("failed to connect to source"))
        } finally {
            n.delete("/api/parameters/shovel/%2F/shape-bogus")
            n.delete("/api/queues/%2F/ship3-q")
        }
    }

    @Test
    fun `S-F a missing destination queue is created by the shovel as a durable CLASSIC queue, so the app must check it first`() {
        o.declareQueue("ship4")
        repeat(3) { o.publish("ship4", "m$it") }
        awaitUntil("3 on source") { o.get("/api/queues/%2F/ship4").obj["messages"]?.jsonPrimitive?.content == "3" }
        assertEquals(404, n.get("/api/queues/%2F/ship4-missing").status)
        assertEquals(201, n.put("/api/parameters/shovel/%2F/shape-missing", shovelBody("ship4", "ship4-missing", "queue-length")).status)
        awaitUntil("the shovel declared the destination") { n.get("/api/queues/%2F/ship4-missing").status == 200 }
        val q = n.get("/api/queues/%2F/ship4-missing").obj
        assertEquals("classic", q["type"]!!.jsonPrimitive.content)
        assertEquals("true", q["durable"]!!.jsonPrimitive.content)
        n.delete("/api/parameters/shovel/%2F/shape-missing")
        n.delete("/api/queues/%2F/ship4-missing")
        o.drain("ship4")
    }

    @Test
    fun `S-F creating a shovel needs the policymaker or administrator tag, reading status needs monitoring or administrator`() {
        o.declareQueue("ship5")
        val body = shovelBody("ship5", "ship5-q", "queue-length")
        try {
            // M8-15 (measured 2026-11-19): from 4.4.1 a policymaker may not use the direct destination "amqp://"; before 4.4.1 it could.
            val refused = n.put("/api/parameters/shovel/%2F/tag-svc", body, "svc", "svc-pass")
            assertEquals(400, refused.status, "management+policymaker is refused the direct destination on 4.4.1")
            assertTrue(refused.body.contains("without a hostname or a username"), "the broker words the app matches: ${refused.body}")
            assertEquals(201, n.put("/api/parameters/shovel/%2F/tag-adm", body, "adm", "adm-pass").status, "an administrator may create")
            assertEquals(401, n.get("/api/shovels", "svc", "svc-pass").status, "but management+policymaker may not read the status list")
            assertEquals(401, n.put("/api/parameters/shovel/%2F/tag-mon", body, "mon", "mon-pass").status, "monitoring may not create")
            assertEquals(200, n.get("/api/shovels", "mon", "mon-pass").status, "monitoring may read the status list")
        } finally {
            n.delete("/api/parameters/shovel/%2F/tag-svc")
            n.delete("/api/parameters/shovel/%2F/tag-mon")
            n.delete("/api/parameters/shovel/%2F/tag-adm")
            n.delete("/api/queues/%2F/ship5-q")
        }
        val whoami = n.get("/api/whoami", "svc", "svc-pass").obj["tags"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("policymaker" in whoami, "whoami lists the tags, so the app can tell before it tries")
    }
}
