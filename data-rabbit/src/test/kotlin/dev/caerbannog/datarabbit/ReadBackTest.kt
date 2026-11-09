package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import dev.caerbannog.core.topology.BindingDestinationType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * M5-08a scope 2 (read-back): after each action the resource is read back with allowed GETs. Absent is retried ONCE after the injected
 * delay (no sleeps in any test: the delay is recorded, not slept); a mismatch is final; numbers are numbers (`5` is not `"5"`).
 */
class ReadBackTest {

    private val broker = FakeManagementBroker()
    private val delays = mutableListOf<Long>()
    private val seen = mutableListOf<M508a.Seen>()

    private fun verify(action: MigrationAction, engine: MockEngine = M508a.engineOver(broker, seen)): ActionResult =
        M508a.http(engine).use { http -> runBlocking { ReadBack(http, M508a.requests(), { delays += it }).verify(action) } }

    private fun gets() = seen.map { it.method + " " + it.path }

    // ---- present, missing --------------------------------------------------------------------------------------------------

    @Test
    fun `a queue that is there as declared is verified with one GET of its detail`() {
        broker.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive(5)))
        assertEquals(ActionResult.Success, verify(MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5)))))
        assertEquals(listOf("GET /api/queues/%2F/orders"), gets())
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `a missing queue is retried once after the named delay, then fails with the fixed reason`() {
        val reason = M508a.failedReason(verify(MigrationAction.DeclareQueue("jobs")))
        assertEquals("jobs was not found after it was declared", reason)
        assertEquals(listOf("GET /api/queues/%2F/jobs", "GET /api/queues/%2F/jobs"), gets(), "exactly one retry")
        assertEquals(listOf(ReadBack.RETRY_DELAY_MILLIS), delays, "one delay, the named constant")
    }

    @Test
    fun `the retry delay is a named constant that is not zero`() {
        assertTrue(ReadBack.RETRY_DELAY_MILLIS > 0, "a retry without a delay is not a retry")
    }

    @Test
    fun `a queue that appears on the retry is verified and the run goes on`() {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            val reply = if (calls == 1) FakeManagementBroker.Reply(404, """{"error":"Object Not Found"}""")
            else FakeManagementBroker.Reply(200, """{"name":"jobs","type":"quorum","durable":true,"auto_delete":false,"arguments":{"x-queue-type":"quorum"}}""")
            respond(reply.body, HttpStatusCode.fromValue(reply.status), headersOf("Content-Type", "application/json"))
        }
        assertEquals(ActionResult.Success, verify(MigrationAction.DeclareQueue("jobs"), engine))
        assertEquals(2, calls)
        assertEquals(1, delays.size)
    }

    // ---- mismatch ----------------------------------------------------------------------------------------------------------

    @Test
    fun `an argument that is the text 5 where 5 was declared is a mismatch, final, no retry`() {
        broker.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive("5")))
        val reason = M508a.failedReason(verify(MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5)))))
        assertEquals("orders was found with different settings after it was declared", reason)
        assertEquals(1, seen.size, "a mismatch is not retried")
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `a different value, a missing declared argument, another type and another durability are mismatches`() {
        broker.addQueue("/", "q1", arguments = mapOf("x-delivery-limit" to JsonPrimitive(3)))
        broker.addQueue("/", "q2")
        broker.addQueue("/", "q3", type = "classic")
        broker.addQueue("/", "q4", durable = false)
        for (action in listOf(
            MigrationAction.DeclareQueue("q1", "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5))),
            MigrationAction.DeclareQueue("q2", "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5))),
            MigrationAction.DeclareQueue("q3", "quorum"),
            MigrationAction.DeclareQueue("q4", "quorum", durable = true),
        )) {
            assertTrue(M508a.failedReason(verify(action)).contains("different settings"), action.description)
        }
    }

    @Test
    fun `an argument the broker added that was not declared is fine`() {
        broker.addQueue("/", "orders", arguments = mapOf("x-max-length" to JsonPrimitive(10)))
        assertEquals(ActionResult.Success, verify(MigrationAction.DeclareQueue("orders", "quorum")))
    }

    @Test
    fun `whole and decimal numbers of the same value verify, texts and flags are typed`() {
        broker.addQueue("/", "orders", arguments = mapOf("a" to JsonPrimitive(5.0), "b" to JsonPrimitive(true), "c" to JsonPrimitive("x")))
        assertEquals(ActionResult.Success, verify(MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("a" to ArgumentValue.Whole(5), "b" to ArgumentValue.Flag(true), "c" to ArgumentValue.Text("x")))))
        assertTrue(M508a.failedReason(verify(MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("b" to ArgumentValue.Text("true"))))).contains("different settings"))
    }

    // ---- other kinds -------------------------------------------------------------------------------------------------------

    @Test
    fun `an exchange is verified on type, durable, auto_delete, internal and arguments`() {
        broker.addExchange("/", "events", "topic", durable = true, autoDelete = false, internal = false, arguments = mapOf("alternate-exchange" to JsonPrimitive("dlx")))
        assertEquals(ActionResult.Success, verify(MigrationAction.DeclareExchange("events", "topic", arguments = mapOf("alternate-exchange" to ArgumentValue.Text("dlx")))))
        for (wrong in listOf(
            MigrationAction.DeclareExchange("events", "direct", arguments = mapOf("alternate-exchange" to ArgumentValue.Text("dlx"))),
            MigrationAction.DeclareExchange("events", "topic", durable = false, arguments = mapOf("alternate-exchange" to ArgumentValue.Text("dlx"))),
            MigrationAction.DeclareExchange("events", "topic", autoDelete = true, arguments = mapOf("alternate-exchange" to ArgumentValue.Text("dlx"))),
            MigrationAction.DeclareExchange("events", "topic", internal = true, arguments = mapOf("alternate-exchange" to ArgumentValue.Text("dlx"))),
        )) {
            assertTrue(M508a.failedReason(verify(wrong)).contains("different settings"), wrong.toString())
        }
        assertTrue(M508a.failedReason(verify(MigrationAction.DeclareExchange("missing"))).contains("was not found after it was declared"))
    }

    @Test
    fun `a binding is found in the list of its source to its destination, by routing key and arguments`() {
        broker.addExchange("/", "events", "topic"); broker.addQueue("/", "orders"); broker.addExchange("/", "dlx")
        broker.addBinding("/", "events", "orders", routingKey = "order.created", arguments = mapOf("x-note" to JsonPrimitive(1)))
        broker.addBinding("/", "events", "dlx", toExchange = true, routingKey = "dead")
        assertEquals(ActionResult.Success, verify(MigrationAction.DeclareBinding("events", "orders", "order.created", arguments = mapOf("x-note" to ArgumentValue.Whole(1)))))
        assertEquals("GET /api/bindings/%2F/e/events/q/orders", gets().last())
        assertEquals(ActionResult.Success, verify(MigrationAction.DeclareBinding("events", "dlx", "dead", destinationType = BindingDestinationType.EXCHANGE)))
        assertEquals("GET /api/bindings/%2F/e/events/e/dlx", gets().last())
    }

    @Test
    fun `a binding with another routing key or other arguments is not the declared binding`() {
        broker.addExchange("/", "events", "topic"); broker.addQueue("/", "orders")
        broker.addBinding("/", "events", "orders", routingKey = "other")
        assertTrue(M508a.failedReason(verify(MigrationAction.DeclareBinding("events", "orders", "order.created"))).contains("was not found after it was declared"))
        assertTrue(M508a.failedReason(verify(MigrationAction.DeclareBinding("events", "orders", "other", arguments = mapOf("x" to ArgumentValue.Whole(1))))).contains("was not found after it was declared"))
    }

    @Test
    fun `a policy and an operator policy are verified by GET and compared`() {
        broker.addPolicy("/", "limits", "^orders", mapOf("max-length" to JsonPrimitive(500)), applyTo = "queues")
        broker.addPolicy("/", "op", "^orders", mapOf("max-length" to JsonPrimitive(500)), applyTo = "queues", operator = true)
        assertEquals(ActionResult.Success, verify(MigrationAction.DeclarePolicy("limits", "^orders", mapOf("max-length" to ArgumentValue.Whole(500)), applyTo = "queues")))
        assertEquals("GET /api/policies/%2F/limits", gets().last())
        assertEquals(ActionResult.Success, verify(MigrationAction.DeclareOperatorPolicy("op", "^orders", mapOf("max-length" to ArgumentValue.Whole(500)), applyTo = "queues")))
        assertEquals("GET /api/operator-policies/%2F/op", gets().last())
        assertTrue(M508a.failedReason(verify(MigrationAction.DeclarePolicy("limits", "^orders", mapOf("max-length" to ArgumentValue.Whole(600)), applyTo = "queues"))).contains("different settings"))
        assertTrue(M508a.failedReason(verify(MigrationAction.DeclarePolicy("nope", "^orders"))).contains("was not found after it was declared"))
    }

    @Test
    fun `a stream is verified as a queue of type stream, by the queue detail`() {
        broker.addQueue("/", "audit", type = "stream")
        broker.addQueue("/", "notastream", type = "quorum")
        assertEquals(ActionResult.Success, verify(MigrationAction.DeclareStream("audit")))
        assertEquals("GET /api/queues/%2F/audit", gets().last())
        assertTrue(M508a.failedReason(verify(MigrationAction.DeclareStream("notastream"))).contains("different settings"))
        assertTrue(M508a.failedReason(verify(MigrationAction.DeclareStream("gone"))).contains("was not found after it was declared"))
    }

    // ---- deletes ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a delete is verified by a 404, and a resource that is still there fails after one retry`() {
        assertEquals(ActionResult.Success, verify(MigrationAction.DeleteQueue("old")))
        assertTrue(delays.isEmpty())
        broker.addQueue("/", "stuck")
        assertEquals("stuck still exists after it was deleted", M508a.failedReason(verify(MigrationAction.DeleteQueue("stuck"))))
        assertEquals(1, delays.size)
        broker.addExchange("/", "stuck.x")
        assertTrue(M508a.failedReason(verify(MigrationAction.DeleteExchange("stuck.x"))).contains("still exists"))
    }

    @Test
    fun `a binding delete is verified by the binding list not containing it`() {
        broker.addExchange("/", "events"); broker.addQueue("/", "old")
        assertEquals(ActionResult.Success, verify(MigrationAction.DeleteBinding("events", "old", "k")))
        broker.addBinding("/", "events", "old", routingKey = "k")
        assertTrue(M508a.failedReason(verify(MigrationAction.DeleteBinding("events", "old", "k"))).contains("still exists"))
    }

    // ---- failures of the read itself -------------------------------------------------------------------------------------

    @Test
    fun `401 and 403 are not allowed, 5xx and a lost connection are no answer, and the body is never copied`() {
        val sentinel = "SENTINEL-read-back-body"
        for (status in listOf(401, 403)) assertTrue(M508a.failedReason(verify(MigrationAction.DeclareQueue("q"), M508a.answering(status, sentinel))).contains(M508a.NOT_ALLOWED), "$status")
        for (status in listOf(500, 503)) assertTrue(M508a.failedReason(verify(MigrationAction.DeclareQueue("q"), M508a.answering(status, sentinel))).contains(M508a.NO_ANSWER), "$status")
        assertTrue(M508a.failedReason(verify(MigrationAction.DeclareQueue("q"), MockEngine { throw IOException(sentinel) })).contains(M508a.NO_ANSWER))
        for (status in listOf(200, 401, 500)) assertFalse(M508a.failedReason(verify(MigrationAction.DeclareQueue("q"), M508a.answering(if (status == 200) 500 else status, sentinel))).contains(sentinel))
    }

    @Test
    fun `a 200 with a body that is not the resource is a failure, never an exception`() {
        for (body in listOf("", "not json", "[]", "null", """{"name":"q"}""")) {
            val result = verify(MigrationAction.DeclareQueue("q"), M508a.answering(200, body))
            assertTrue(result is ActionResult.Failed, "body \"$body\" gave $result")
        }
    }

    @Test
    fun `read-back only ever sends GET`() {
        broker.addQueue("/", "orders"); broker.addExchange("/", "events"); broker.addPolicy("/", "p", "^x", emptyMap())
        for (action in listOf<MigrationAction>(
            MigrationAction.DeclareQueue("orders"), MigrationAction.DeclareExchange("events"), MigrationAction.DeclarePolicy("p", "^x"),
            MigrationAction.DeclareBinding("events", "orders"), MigrationAction.DeleteQueue("x"), MigrationAction.DeclareStream("s"),
        )) verify(action)
        assertTrue(seen.all { it.method == "GET" }, seen.filter { it.method != "GET" }.toString())
    }
}
