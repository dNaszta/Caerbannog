package dev.caerbannog.datarabbit

import com.rabbitmq.client.MessageProperties
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * M8-13 spike S-T: `x-max-in-memory-length` and `x-max-in-memory-bytes` on quorum queues, 3.13 and 4.x.
 * Findings: docs/tasks/M8-00-spike-results.md, section "S-T". Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8SpikeInMemoryLimitTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM8SpikeInMemoryLimitTest {

    companion object {
        private val old: RabbitMQContainer = broker("rabbitmq:3.13-management")
        private val new: RabbitMQContainer = broker("rabbitmq:4-management")
        private val quorum = mapOf<String, Any>("x-queue-type" to "quorum")
        private val both get() = listOf("3.13" to old, "4.x" to new)

        @JvmStatic @BeforeAll fun start() { old.start(); new.start() }
        @JvmStatic @AfterAll fun stop() { old.stop(); new.stop() }
    }

    @Test fun `a quorum queue declared with the in-memory arguments is accepted and the arguments are echoed and exported`() {
        for ((label, c) in both) {
            val api = MgmtApi(c)
            withAmqp(c) { conn ->
                assertNull(refusal { conn.createChannel().queueDeclare("t-lim", true, false, false, quorum + mapOf("x-max-in-memory-length" to 10, "x-max-in-memory-bytes" to 1000)) }, label)
                assertNull(refusal { conn.createChannel().queueDeclare("t-zero", true, false, false, quorum + mapOf("x-max-in-memory-length" to 0)) }, "$label: zero is accepted")
                assertNull(refusal { conn.createChannel().queueDeclare("t-bytes", true, false, false, quorum + mapOf("x-max-in-memory-bytes" to 1000)) }, "$label: bytes alone is accepted")
            }
            awaitUntil("$label stats for t-lim", 40) { api.get("/api/queues/%2F/t-lim").obj["type"] != null }
            val g = api.get("/api/queues/%2F/t-lim").obj
            assertEquals("{\"x-max-in-memory-bytes\":1000,\"x-max-in-memory-length\":10,\"x-queue-type\":\"quorum\"}", g["arguments"].toString(), label)
            assertTrue(api.get("/api/definitions").obj["queues"].toString().contains("x-max-in-memory-length"), "$label: kept in the export")
            val imported = """{"queues":[{"name":"t-imp","vhost":"/","durable":true,"auto_delete":false,"arguments":{"x-queue-type":"quorum","x-max-in-memory-length":10}}]}"""
            assertEquals(204, api.post("/api/definitions", imported).status, label)
        }
    }

    @Test fun `bad values are refused and a classic queue refuses the arguments`() {
        for ((label, c) in both) {
            withAmqp(c) { conn ->
                val negative = refusal { conn.createChannel().queueDeclare("t-neg", true, false, false, quorum + mapOf("x-max-in-memory-length" to -5)) }
                assertTrue(negative != null && negative.contains("value_negative"), "$label: $negative")
                val text = refusal { conn.createChannel().queueDeclare("t-str", true, false, false, quorum + mapOf("x-max-in-memory-length" to "abc")) }
                assertTrue(text != null && text.contains("expected integer"), "$label: $text")
                val classic = refusal { conn.createChannel().queueDeclare("t-classic", true, false, false, mapOf("x-max-in-memory-length" to 10)) }
                assertTrue(classic != null && classic.contains("rabbit_classic_queue"), "$label: $classic")
                val different = refusal { conn.createChannel().let { it.queueDeclare("t-same", true, false, false, quorum); conn.createChannel().queueDeclare("t-same", true, false, false, quorum + mapOf("x-max-in-memory-length" to 10)) } }
                assertTrue(different != null && different.contains("inequivalent arg"), "$label: adding the argument later is an inequivalent redeclare: $different")
            }
        }
    }

    @Test fun `a policy with the in-memory keys is accepted and applied, as arguments are, with no visible effect on memory`() {
        for ((label, c) in both) {
            val api = MgmtApi(c)
            val queues = mapOf(
                "m-plain-1" to quorum, "m-plain-2" to quorum,
                "m-lim-1" to quorum + mapOf("x-max-in-memory-length" to 1, "x-max-in-memory-bytes" to 1),
                "m-lim-2" to quorum + mapOf("x-max-in-memory-length" to 1, "x-max-in-memory-bytes" to 1),
            )
            withAmqp(c) { conn ->
                val ch = conn.createChannel()
                for ((q, a) in queues) ch.queueDeclare(q, true, false, false, a)
                ch.confirmSelect()
                val body = ByteArray(4096) { 'y'.code.toByte() }
                repeat(2000) { for (q in queues.keys) ch.basicPublish("", q, MessageProperties.PERSISTENT_BASIC, body) }
                ch.waitForConfirmsOrDie(60_000)
            }
            awaitUntil("$label 2000 messages on every queue", 60) { queues.keys.all { api.get("/api/queues/%2F/$it").obj["messages"]?.toString() == "2000" } }
            awaitUntil("$label memory statistics", 30) { queues.keys.all { api.get("/api/queues/%2F/$it").obj["memory"] != null } }
            val stats = queues.keys.associateWith { api.get("/api/queues/%2F/$it").obj }
            for ((q, o) in stats) {
                assertEquals("0", o["messages_ram"].toString(), "$label $q: messages_ram is 0 for every quorum queue, with or without the limit")
                assertEquals("0", o["message_bytes_ram"].toString(), "$label $q")
            }
            // Memory differs between two identical queues by more than the limits could explain, so it is noise, not an effect.
            val mem = stats.mapValues { it.value["memory"].toString().toLong() }
            assertTrue(mem.values.all { it in 50_000L..1_000_000L }, "$label: $mem")

            assertEquals(201, api.put("/api/policies/%2F/mem2", """{"pattern":"^m-plain-1$","apply-to":"queues","priority":1,"definition":{"max-in-memory-length":1}}""").status, label)
            awaitUntil("$label policy applied to m-plain-1", 40) { api.get("/api/queues/%2F/m-plain-1").obj["policy"].toString() == "\"mem2\"" }
            assertEquals("{\"max-in-memory-length\":1}", api.get("/api/queues/%2F/m-plain-1").obj["effective_policy_definition"].toString(), label)
            assertEquals("{\"x-queue-type\":\"quorum\"}", api.get("/api/queues/%2F/m-plain-1").obj["arguments"].toString(), "$label: a policy does not rewrite the arguments")
            assertEquals(201, api.put("/api/operator-policies/%2F/opmem", """{"pattern":"^none$","apply-to":"queues","priority":1,"definition":{"max-in-memory-length":10}}""").status, "$label operator policy")
        }
    }
}
