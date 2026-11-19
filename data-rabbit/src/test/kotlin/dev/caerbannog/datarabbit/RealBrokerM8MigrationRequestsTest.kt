package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * M8-04 live test (@m8-04 @broker, "Typed arguments reach a real 4.x broker as numbers"): the requests that
 * [MigrationRequests] builds for the declare actions are sent to a real RabbitMQ 4 with the test's own HTTP client (the production
 * ManagementHttp has no PUT, and this test does not add one), and read back. It never reads a queue's messages (only `GET` of the
 * definitions). It records which `apply-to` values the broker accepts: any refused value is reported in the task's Status block and
 * the parser's list in docs/spec.md is corrected, not hidden.
 * Run with `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8MigrationRequestsTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM8MigrationRequestsTest {

    companion object {
        private val container: RabbitMQContainer = broker("rabbitmq:4-management")
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { container.start(); api = MgmtApi(container) }
        @JvmStatic @AfterAll fun stop() { container.stop() }
    }

    private val run = UUID.randomUUID().toString().take(8)
    private val cleanup = mutableListOf<String>()

    private fun unique(base: String) = "m804-$base-$run"

    /** Builds the request with the production code, sends it, and remembers how to delete what it created. */
    private fun send(action: MigrationAction, deletePath: String?): MgmtApi.Reply {
        val write = requireNotNull(MigrationRequests.requestFor(action)) { "no request for ${action.actionName}" }
        if (deletePath != null) cleanup += deletePath
        return api.call(write.method, write.path, write.body.toString())
    }

    @AfterEach fun deleteWhatWasCreated() {
        // Policies first, then bindings go with their queues and exchanges. Unique names, so nothing else is touched.
        cleanup.asReversed().forEach { runCatching { api.delete(it) } }
        cleanup.clear()
    }

    @Test
    fun `typed arguments reach a real 4x broker as numbers and the declared objects exist as declared`() {
        val queue = unique("orders")
        val exchange = unique("events")
        val destination = unique("dlx")
        val policy = unique("limits")
        val operator = unique("op-limit")

        val queueReply = send(
            MigrationAction.DeclareQueue(
                queue, "quorum",
                arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5), "x-message-ttl" to ArgumentValue.Whole(60000), "x-dead-letter-exchange" to ArgumentValue.Text(destination)),
            ),
            "/api/queues/%2F/$queue",
        )
        assertTrue(queueReply.ok, "queue: $queueReply")
        assertTrue(send(MigrationAction.DeclareExchange(destination, "direct"), "/api/exchanges/%2F/$destination").ok)
        val exchangeReply = send(
            MigrationAction.DeclareExchange(exchange, "topic", durable = true, autoDelete = false, internal = false, arguments = mapOf("alternate-exchange" to ArgumentValue.Text(destination))),
            "/api/exchanges/%2F/$exchange",
        )
        assertTrue(exchangeReply.ok, "exchange: $exchangeReply")
        val bindingReply = send(MigrationAction.DeclareBinding(exchange, destination, "dead", destinationType = BindingDestinationType.EXCHANGE), null)
        assertTrue(bindingReply.ok, "exchange-to-exchange binding: $bindingReply")
        val queueBinding = send(MigrationAction.DeclareBinding(exchange, queue, "order.created", arguments = mapOf("x-note" to ArgumentValue.Whole(1))), null)
        assertTrue(queueBinding.ok, "queue binding: $queueBinding")
        val policyReply = send(
            MigrationAction.DeclarePolicy(policy, "^${Regex.escape(queue)}$", mapOf("max-length" to ArgumentValue.Whole(1000), "overflow" to ArgumentValue.Text("reject-publish")), applyTo = "quorum_queues", priority = 7),
            "/api/policies/%2F/$policy",
        )
        assertTrue(policyReply.ok, "policy: $policyReply")
        val operatorReply = send(
            MigrationAction.DeclareOperatorPolicy(operator, "^${Regex.escape(queue)}$", mapOf("max-length" to ArgumentValue.Whole(100000)), applyTo = "queues"),
            "/api/operator-policies/%2F/$operator",
        )
        assertTrue(operatorReply.ok, "operator policy: $operatorReply")

        // Read back: definitions only, never the messages of a queue.
        val q = api.get("/api/queues/%2F/$queue").obj
        val arguments = q["arguments"]!!.jsonObject
        val limit = arguments["x-delivery-limit"]!!.jsonPrimitive
        assertFalse(limit.isString, "x-delivery-limit came back as the string \"${limit.content}\"")
        assertEquals("5", limit.content)
        assertFalse(arguments["x-message-ttl"]!!.jsonPrimitive.isString)
        assertEquals("quorum", arguments["x-queue-type"]!!.jsonPrimitive.content)
        assertEquals(destination, arguments["x-dead-letter-exchange"]!!.jsonPrimitive.content)
        assertEquals(true, q["durable"]!!.jsonPrimitive.content.toBoolean())

        val x = api.get("/api/exchanges/%2F/$exchange").obj
        assertEquals("topic", x["type"]!!.jsonPrimitive.content)
        assertEquals(destination, x["arguments"]!!.jsonObject["alternate-exchange"]!!.jsonPrimitive.content)

        val e2e = api.get("/api/bindings/%2F/e/$exchange/e/$destination").arr
        assertEquals(listOf("dead"), e2e.map { it.jsonObject["routing_key"]!!.jsonPrimitive.content })
        assertEquals("exchange", e2e.single().jsonObject["destination_type"]!!.jsonPrimitive.content)
        val qb = api.get("/api/bindings/%2F/e/$exchange/q/$queue").arr
        assertEquals(listOf("order.created"), qb.map { it.jsonObject["routing_key"]!!.jsonPrimitive.content })

        val p = api.get("/api/policies/%2F/$policy").obj
        assertEquals("quorum_queues", p["apply-to"]!!.jsonPrimitive.content)
        assertEquals("7", p["priority"]!!.jsonPrimitive.content)
        assertFalse(p["definition"]!!.jsonObject["max-length"]!!.jsonPrimitive.isString, "max-length came back as a string")
        assertEquals("1000", p["definition"]!!.jsonObject["max-length"]!!.jsonPrimitive.content)

        val op = api.get("/api/operator-policies/%2F/$operator").obj
        assertEquals("queues", op["apply-to"]!!.jsonPrimitive.content)
        assertFalse(op["definition"]!!.jsonObject["max-length"]!!.jsonPrimitive.isString)
    }

    @Test
    fun `the broker accepts every apply-to value the parser lists, recorded one by one`() {
        val values = listOf("all", "queues", "exchanges", "classic_queues", "quorum_queues", "streams")
        val accepted = linkedMapOf<String, String>()
        for (value in values) {
            val name = unique("applyto-$value".replace('_', '-'))
            // A pattern that matches nothing the broker has: the policy exists but changes no real object.
            val reply = send(MigrationAction.DeclarePolicy(name, "^never-matches-$run$", mapOf("max-length" to ArgumentValue.Whole(10)), applyTo = value), "/api/policies/%2F/$name")
            val readBack = if (reply.ok) api.get("/api/policies/%2F/$name").obj["apply-to"]?.jsonPrimitive?.content else null
            accepted[value] = "${reply.status} ${if (reply.ok) "accepted, read back as $readBack" else reply.reason()}"
        }
        println("M8-04 apply-to measured on rabbitmq:4-management: $accepted")
        val refused = values.filter { !accepted.getValue(it).contains("accepted") }
        assertTrue(refused.isEmpty(), "the broker refused apply-to values the parser accepts: $refused; all results: $accepted. Report them in the task Status block and correct the spec list.")
        for (value in values) assertTrue(accepted.getValue(value).endsWith("read back as $value"), "$value: ${accepted[value]}")
    }

    @Test
    fun `an operator policy with a non-limit key is refused by the broker, not by the app`() {
        val name = unique("op-bad")
        val reply = send(MigrationAction.DeclareOperatorPolicy(name, "^never-matches-$run$", mapOf("ha-mode" to ArgumentValue.Text("all"))), "/api/operator-policies/%2F/$name")
        assertFalse(reply.ok, "4.x accepts only limit keys in an operator policy (S-E): $reply")
    }

    @Test
    fun `a queue declared twice with different arguments is a conflict the executor must not treat as success`() {
        val queue = unique("conflict")
        assertTrue(send(MigrationAction.DeclareQueue(queue, "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5))), "/api/queues/%2F/$queue").ok)
        val second = send(MigrationAction.DeclareQueue(queue, "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(6))), null)
        assertEquals(400, second.status, "open point 2 of the brief: $second")
        assertTrue(send(MigrationAction.DeclareQueue(queue, "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5))), null).ok, "the same declaration again is idempotent")
    }
}
