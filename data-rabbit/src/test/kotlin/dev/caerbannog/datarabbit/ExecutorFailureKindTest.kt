package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.fakes.FakeStreamManager
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.FailureKind
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * M5-08c1 scope 1: the executor says what KIND of failure it found, from facts, never from text. A conflict is a queue or exchange the broker
 * answered 400 for while the resource exists, or a policy that exists with other settings; every other failure is [FailureKind.Other].
 */
class ExecutorFailureKindTest {

    private val broker = FakeManagementBroker()
    private val seen = mutableListOf<M508a.Seen>()

    private fun exec(action: MigrationAction, streams: dev.caerbannog.core.streams.StreamManager = broker.streamManager()): ActionResult.Failed =
        assertIs<ActionResult.Failed>(runBlocking { M508a.executor(broker, streams, seen = seen).execute(action) })

    private val orders = MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5)))
    private fun limits(value: Long) = MigrationAction.DeclarePolicy("limits", "^orders", mapOf("max-length" to ArgumentValue.Whole(value)), applyTo = "queues")

    // ---- Conflict ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a queue that exists with other settings is a Conflict`() {
        broker.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive(3)))

        val failed = exec(orders)

        assertEquals(FailureKind.Conflict, failed.kind, "reason was: ${failed.reason}")
        assertEquals(M508a.conflict("orders"), failed.reason)
    }

    @Test
    fun `an exchange that exists with other settings is a Conflict`() {
        broker.addExchange("/", "events", "direct")

        val failed = exec(MigrationAction.DeclareExchange("events", "topic"))

        assertEquals(FailureKind.Conflict, failed.kind, "reason was: ${failed.reason}")
    }

    @Test
    fun `a policy that exists with other settings is a Conflict`() {
        broker.addPolicy("/", "limits", "^orders", mapOf("max-length" to JsonPrimitive(500)), applyTo = "queues")

        val failed = exec(limits(1000))

        assertEquals(FailureKind.Conflict, failed.kind, "reason was: ${failed.reason}")
    }

    @Test
    fun `an operator policy that exists with other settings is a Conflict`() {
        broker.addPolicy("/", "op", "^orders", mapOf("max-length" to JsonPrimitive(500)), applyTo = "queues", operator = true)

        val failed = exec(MigrationAction.DeclareOperatorPolicy("op", "^orders", mapOf("max-length" to ArgumentValue.Whole(900)), applyTo = "queues"))

        assertEquals(FailureKind.Conflict, failed.kind, "reason was: ${failed.reason}")
    }

    @Test
    fun `a 400 for a queue that exists is a Conflict whatever the body says`() {
        broker.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive(3)))
        broker.answer("PUT", "/api/queues/%2F/orders", 400, """{"error":"bad_request","reason":"something nobody matches"}""")

        assertEquals(FailureKind.Conflict, exec(orders).kind)
    }

    // ---- Other: every other failure --------------------------------------------------------------------------------------------------

    @Test
    fun `a 400 for a queue that does not exist is Other, the resource is not there to conflict with`() {
        broker.answer("PUT", "/api/queues/%2F/orders", 400, """{"error":"bad_request","reason":"PRECONDITION_FAILED - inequivalent arg"}""")

        val failed = exec(orders)

        assertEquals(FailureKind.Other, failed.kind, "the word PRECONDITION_FAILED has no effect: ${failed.reason}")
    }

    @Test
    fun `a 400 for an unknown exchange type is Other`() {
        assertEquals(FailureKind.Other, exec(MigrationAction.DeclareExchange("e2", "nosuch")).kind)
    }

    @Test
    fun `a 400 whose resource cannot be read afterwards is Other`() {
        broker.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive(3)))
        broker.answerContaining("GET", "/api/queues/%2F/orders", 500, "boom")

        assertEquals(FailureKind.Other, exec(orders).kind)
    }

    @Test
    fun `a refused write, a missing permission and a broker that is down are Other`() {
        broker.answerContaining("PUT", "/api/queues/", 403, "no")
        assertEquals(FailureKind.Other, exec(orders).kind)

        broker.clearRules()
        broker.unreachable = true
        assertEquals(FailureKind.Other, exec(orders).kind)
    }

    @Test
    fun `a policy that cannot be read or that the broker refuses is Other`() {
        broker.answer("GET", "/api/policies/%2F/limits", 500, "x")
        assertEquals(FailureKind.Other, exec(limits(500)).kind)

        broker.clearRules()
        broker.answer("PUT", "/api/policies/%2F/limits", 400, "not recognised policy settings")
        assertEquals(FailureKind.Other, exec(limits(500)).kind)
    }

    @Test
    fun `a broker that says created but does not have the queue is Other`() {
        broker.silentlyDoNotCreateQueue("/", "jobs")

        assertEquals(FailureKind.Other, exec(MigrationAction.DeclareQueue("jobs")).kind)
    }

    @Test
    fun `a stream failure is Other`() {
        val refusing = FakeStreamManager().apply { refuseCreateWith = "refused" }
        assertEquals(FailureKind.Other, exec(MigrationAction.DeclareStream("audit"), refusing).kind)
    }

    @Test
    fun `a queue with the stream's name that is not a stream is Other, as the brief says every other failure is`() {
        // Found with different settings AFTER the declare (read-back), not the broker's 400 on the declare: the brief names two conflict cases only.
        broker.addQueue("/", "audit", type = "quorum")

        assertEquals(FailureKind.Other, exec(MigrationAction.DeclareStream("audit"), FakeStreamManager()).kind)
    }

    @Test
    fun `an action that cannot be sent is Other`() {
        assertEquals(FailureKind.Other, exec(MigrationAction.DeclareQueue("")).kind)
    }

    @Test
    fun `the reasons are those of before, the kind is added and no text changes`() {
        broker.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive(3)))
        assertEquals("orders exists with different settings", exec(orders).reason)
        assertEquals(ExecutorReasons.REFUSED, exec(MigrationAction.DeclareExchange("e2", "nosuch")).reason)
    }
}
