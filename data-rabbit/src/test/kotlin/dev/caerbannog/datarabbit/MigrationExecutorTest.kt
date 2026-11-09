package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.fakes.FakeStreamManager
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import dev.caerbannog.core.streams.StreamManager
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M5-08a scope 2: the executor per the rerun table of docs/tasks/M5-08-real-migration-execution.md, against a scripted honest fake of the
 * management API (FakeManagementBroker): created, same settings, other settings, read-back, refusals, deletes, streams, and a rerun after a
 * failure at action k. Safety rules checked on every row: nothing is deleted unless the action is a delete, nothing is overwritten, the
 * broker's text is never in a reason.
 */
class MigrationExecutorTest {

    private val broker = FakeManagementBroker()
    private val delays = mutableListOf<Long>()
    private val seen = mutableListOf<M508a.Seen>()

    private fun executor(streams: StreamManager = broker.streamManager()) = M508a.executor(broker, streams, delays, seen)
    private fun exec(action: MigrationAction, streams: StreamManager = broker.streamManager()): ActionResult = runBlocking { executor(streams).execute(action) }
    private fun wire() = seen.map { it.method + " " + it.path }
    private fun writes() = seen.filter { it.method != "GET" }.map { it.method + " " + it.path }

    private val limit5 = mapOf("x-delivery-limit" to ArgumentValue.Whole(5))
    private val orders = MigrationAction.DeclareQueue("orders", "quorum", arguments = limit5)

    // ---- queues ----------------------------------------------------------------------------------------------------------

    @Test
    fun `a new queue is created with one PUT, read back with one GET, and nothing is deleted`() {
        assertEquals(ActionResult.Success, exec(orders))
        assertEquals(listOf("PUT /api/queues/%2F/orders", "GET /api/queues/%2F/orders"), wire())
        assertTrue(broker.hasQueue("/", "orders"))
        assertEquals(JsonPrimitive(5), broker.queueArguments("/", "orders")!!["x-delivery-limit"])
    }

    @Test
    fun `the number reaches the wire as a number and the text as text`() {
        exec(MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("n" to ArgumentValue.Whole(5), "t" to ArgumentValue.Text("5"))))
        val body = seen.first { it.method == "PUT" }.body
        assertTrue(body.contains("\"n\":5") && body.contains("\"t\":\"5\""), body)
    }

    @Test
    fun `a queue that exists with the same settings is a no-op`() {
        broker.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive(5)))
        val before = broker.snapshot()
        assertEquals(ActionResult.Success, exec(orders))
        assertEquals(before, broker.snapshot())
        assertTrue(broker.deletes.isEmpty())
    }

    @Test
    fun `a queue that exists with other arguments is a conflict, nothing is changed, deleted or recreated`() {
        broker.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive(3)))
        val before = broker.snapshot()
        assertEquals(M508a.conflict("orders"), M508a.failedReason(exec(orders)))
        assertEquals(before, broker.snapshot(), "the queue changed")
        assertEquals(listOf("PUT /api/queues/%2F/orders"), wire(), "exactly one PUT: no DELETE, no second PUT, no read-back after a conflict")
    }

    @Test
    fun `a 5 against the text 5 is a conflict too`() {
        broker.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive("5")))
        assertEquals(M508a.conflict("orders"), M508a.failedReason(exec(orders)))
    }

    @Test
    fun `a broker that says created and does not have the queue fails the read-back after one retry`() {
        broker.silentlyDoNotCreateQueue("/", "jobs")
        assertEquals("jobs was not found after it was declared", M508a.failedReason(exec(MigrationAction.DeclareQueue("jobs"))))
        assertEquals(listOf("PUT /api/queues/%2F/jobs", "GET /api/queues/%2F/jobs", "GET /api/queues/%2F/jobs"), wire())
        assertEquals(listOf(ReadBack.RETRY_DELAY_MILLIS), delays)
    }

    @Test
    fun `a refused PUT fails with a fixed reason and no read-back follows`() {
        val sentinel = "SENTINEL-403-body"
        broker.answerContaining("PUT", "/api/queues/", 403, sentinel)
        val reason = M508a.failedReason(exec(orders))
        assertTrue(reason.contains(M508a.NOT_ALLOWED), reason)
        assertFalse(reason.contains(sentinel))
        assertEquals(listOf("PUT /api/queues/%2F/orders"), wire())
    }

    @Test
    fun `a broker that is down gives the no answer reason, and nothing was changed`() {
        broker.unreachable = true
        assertTrue(M508a.failedReason(exec(orders)).contains(M508a.NO_ANSWER))
        assertFalse(broker.hasQueue("/", "orders"))
    }

    // ---- exchanges and bindings ---------------------------------------------------------------------------------------------

    private val events = MigrationAction.DeclareExchange("events", "topic")

    @Test
    fun `an exchange is created, the same is a no-op and other settings are a conflict`() {
        assertEquals(ActionResult.Success, exec(events))
        assertEquals(listOf("PUT /api/exchanges/%2F/events", "GET /api/exchanges/%2F/events"), wire())
        val before = broker.snapshot()
        assertEquals(ActionResult.Success, exec(events))
        assertEquals(before, broker.snapshot())
        assertEquals(M508a.conflict("events"), M508a.failedReason(exec(MigrationAction.DeclareExchange("events", "direct"))))
        assertEquals(M508a.conflict("events"), M508a.failedReason(exec(MigrationAction.DeclareExchange("events", "topic", durable = false))))
        assertEquals(before, broker.snapshot())
        assertTrue(broker.deletes.isEmpty())
    }

    @Test
    fun `a binding is created with one POST and read back, a repeat is a no-op`() {
        broker.addExchange("/", "events", "topic"); broker.addQueue("/", "orders")
        val binding = MigrationAction.DeclareBinding("events", "orders", "order.created")
        assertEquals(ActionResult.Success, exec(binding))
        assertEquals(listOf("POST /api/bindings/%2F/e/events/q/orders", "GET /api/bindings/%2F/e/events/q/orders"), wire())
        val before = broker.snapshot()
        assertEquals(ActionResult.Success, exec(binding))
        assertEquals(before, broker.snapshot())
        assertTrue(broker.hasBinding("/", "events", "orders", "order.created"))
    }

    @Test
    fun `an exchange-to-exchange binding goes to the exchange path and is read back there`() {
        broker.addExchange("/", "events", "topic"); broker.addExchange("/", "dlx")
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclareBinding("events", "dlx", "dead", destinationType = BindingDestinationType.EXCHANGE)))
        assertEquals(listOf("POST /api/bindings/%2F/e/events/e/dlx", "GET /api/bindings/%2F/e/events/e/dlx"), wire())
        assertTrue(broker.hasBinding("/", "events", "dlx", "dead", toExchange = true))
    }

    @Test
    fun `a binding to something that does not exist is refused by the broker and fails with a fixed reason`() {
        val reason = M508a.failedReason(exec(MigrationAction.DeclareBinding("nope", "nothing", "k")))
        assertTrue(reason.contains(M508a.REFUSED), reason)
        assertEquals(listOf("POST /api/bindings/%2F/e/nope/q/nothing"), wire())
    }

    // ---- policies --------------------------------------------------------------------------------------------------------------

    private fun limits(value: Long) = MigrationAction.DeclarePolicy("limits", "^orders", mapOf("max-length" to ArgumentValue.Whole(value)), applyTo = "queues")

    @Test
    fun `an absent policy is read first, then created, then read back`() {
        assertEquals(ActionResult.Success, exec(limits(500)))
        assertEquals(listOf("GET /api/policies/%2F/limits", "PUT /api/policies/%2F/limits", "GET /api/policies/%2F/limits"), wire())
        assertEquals(1, broker.policyWrites)
    }

    @Test
    fun `a policy that exists with the same settings is a no-op, no write of any kind`() {
        broker.addPolicy("/", "limits", "^orders", mapOf("max-length" to JsonPrimitive(500)), applyTo = "queues")
        assertEquals(ActionResult.Success, exec(limits(500)))
        assertEquals(0, broker.policyWrites, "an equal policy was written again")
        assertTrue(writes().isEmpty(), writes().toString())
    }

    @Test
    fun `a policy that exists with other settings is a conflict and is never written over`() {
        broker.addPolicy("/", "limits", "^orders", mapOf("max-length" to JsonPrimitive(500)), applyTo = "queues")
        val before = broker.snapshot()
        assertEquals(M508a.conflict("limits"), M508a.failedReason(exec(limits(1000))))
        assertEquals(0, broker.policyWrites, "a different policy was overwritten")
        assertEquals(before, broker.snapshot())
        assertTrue(writes().isEmpty(), writes().toString())
    }

    @Test
    fun `a policy that cannot be read is never written`() {
        broker.answer("GET", "/api/policies/%2F/limits", 500, "SENTINEL-500")
        assertTrue(M508a.failedReason(exec(limits(500))).contains(M508a.NO_ANSWER))
        broker.clearRules(); broker.answer("GET", "/api/policies/%2F/limits", 403, "SENTINEL-403")
        assertTrue(M508a.failedReason(exec(limits(500))).contains(M508a.NOT_ALLOWED))
        assertEquals(0, broker.policyWrites)
        assertTrue(writes().isEmpty(), writes().toString())
    }

    @Test
    fun `an operator policy goes the same way on its own path`() {
        val op = MigrationAction.DeclareOperatorPolicy("op", "^orders", mapOf("max-length" to ArgumentValue.Whole(500)), applyTo = "queues")
        assertEquals(ActionResult.Success, exec(op))
        assertEquals(listOf("GET /api/operator-policies/%2F/op", "PUT /api/operator-policies/%2F/op", "GET /api/operator-policies/%2F/op"), wire())
        seen.clear()
        assertEquals(ActionResult.Success, exec(op))
        assertTrue(writes().isEmpty(), "an equal operator policy was written: ${writes()}")
        assertEquals(M508a.conflict("op"), M508a.failedReason(exec(MigrationAction.DeclareOperatorPolicy("op", "^orders", mapOf("max-length" to ArgumentValue.Whole(9)), applyTo = "queues"))))
        assertEquals(1, broker.policyWrites)
    }

    @Test
    fun `a policy and an operator policy with the same name are different resources`() {
        broker.addPolicy("/", "same", "^x", mapOf("max-length" to JsonPrimitive(1)), operator = false)
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclareOperatorPolicy("same", "^x", mapOf("max-length" to ArgumentValue.Whole(2)))))
        assertTrue(broker.hasPolicy("/", "same", operator = true))
    }

    // ---- streams (through the StreamManager) ------------------------------------------------------------------------------------

    @Test
    fun `a stream is created by the stream manager, read back from the broker, and the management API is only read`() {
        val manager = FakeStreamManager()
        broker.addQueue("/", "audit", type = "stream") // what the real manager would have made
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclareStream("audit"), manager))
        assertEquals(listOf("/" to "audit"), manager.createdStreams)
        assertTrue(writes().isEmpty(), writes().toString())
        assertEquals(listOf("GET /api/queues/%2F/audit"), wire())
    }

    @Test
    fun `a stream that already exists is a no-op, the real manager reports STREAM_ALREADY_EXISTS as success`() {
        broker.addQueue("/", "audit", type = "stream")
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclareStream("audit")))
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclareStream("audit")))
        assertTrue(writes().isEmpty())
    }

    @Test
    fun `a stream the manager refused or failed gives a fixed reason without the manager's text`() {
        val sentinel = "SENTINEL-manager-text"
        val refusing = FakeStreamManager().apply { refuseCreateWith = "Broker refused to create stream 'audit': $sentinel" }
        val failing = FakeStreamManager().apply { failCreateWith = "Stream management unavailable $sentinel" }
        for (manager in listOf(refusing, failing)) {
            val reason = M508a.failedReason(exec(MigrationAction.DeclareStream("audit"), manager))
            assertFalse(reason.contains(sentinel), reason)
            assertTrue(reason.isNotBlank())
        }
        assertTrue(seen.none { it.method != "GET" })
    }

    @Test
    fun `a manager that says success but the broker has no stream fails the read-back`() {
        assertEquals("audit was not found after it was declared", M508a.failedReason(exec(MigrationAction.DeclareStream("audit"), FakeStreamManager())))
    }

    @Test
    fun `a queue with the stream's name that is not a stream is a conflict, not a success`() {
        broker.addQueue("/", "audit", type = "quorum")
        assertTrue(M508a.failedReason(exec(MigrationAction.DeclareStream("audit"), FakeStreamManager())).contains("different settings"))
    }

    @Test
    fun `a stream is deleted through the manager only, and an absent stream is a no-op`() {
        broker.addQueue("/", "old", type = "stream")
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteStream("old")))
        assertFalse(broker.hasQueue("/", "old"))
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteStream("old")), "deleting what is already gone")
        assertTrue(broker.deletes.isEmpty(), "a stream was deleted over HTTP: ${broker.deletes}")
    }

    @Test
    fun `any other refusal to delete a stream fails with a fixed reason`() {
        broker.addQueue("/", "old", type = "stream")
        val manager = FakeStreamManager().apply { refuseDeleteWith = "Broker refused to delete stream 'old': ACCESS_REFUSED SENTINEL-del" }
        val reason = M508a.failedReason(exec(MigrationAction.DeleteStream("old"), manager))
        assertFalse(reason.contains("SENTINEL-del"), reason)
        assertTrue(broker.hasQueue("/", "old"))
    }

    // ---- deletes -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a queue delete is one DELETE and a read-back that sees 404`() {
        broker.addQueue("/", "old")
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteQueue("old")))
        assertEquals(listOf("DELETE /api/queues/%2F/old", "GET /api/queues/%2F/old"), wire())
        assertFalse(broker.hasQueue("/", "old"))
    }

    @Test
    fun `deleting something that is already gone is a no-op for every kind`() {
        for (action in listOf<MigrationAction>(
            MigrationAction.DeleteQueue("gone"), MigrationAction.DeleteExchange("gone"), MigrationAction.DeletePolicy("gone"),
            MigrationAction.DeleteBinding("gone-x", "gone-q", "k"),
        )) assertEquals(ActionResult.Success, exec(action), action.actionName)
    }

    @Test
    fun `an exchange, a policy and a binding delete reach the broker as the right DELETE`() {
        broker.addExchange("/", "events"); broker.addQueue("/", "orders"); broker.addPolicy("/", "p", "^x", emptyMap())
        broker.addBinding("/", "events", "orders", routingKey = "k"); broker.addBinding("/", "events", "orders", routingKey = "")
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteBinding("events", "orders", "k")))
        assertFalse(broker.hasBinding("/", "events", "orders", "k"))
        assertTrue(broker.hasBinding("/", "events", "orders", ""), "the binding with the other routing key must stay")
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteBinding("events", "orders")))
        assertFalse(broker.hasBinding("/", "events", "orders", ""))
        assertEquals(ActionResult.Success, exec(MigrationAction.DeletePolicy("p")))
        assertFalse(broker.hasPolicy("/", "p"))
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteExchange("events")))
        assertFalse(broker.hasExchange("/", "events"))
        assertTrue(broker.hasQueue("/", "orders"), "only the named items were deleted")
    }

    @Test
    fun `a refused delete fails and a delete the broker acknowledged but did not do fails the read-back`() {
        broker.addQueue("/", "old")
        broker.answer("DELETE", "/api/queues/%2F/old", 403, "SENTINEL-del-403")
        val refused = M508a.failedReason(exec(MigrationAction.DeleteQueue("old")))
        assertTrue(refused.contains(M508a.NOT_ALLOWED) && !refused.contains("SENTINEL"), refused)
        broker.clearRules(); broker.answer("DELETE", "/api/queues/%2F/old", 204)
        assertEquals("old still exists after it was deleted", M508a.failedReason(exec(MigrationAction.DeleteQueue("old"))))
        assertTrue(broker.hasQueue("/", "old"))
    }

    // ---- the rules across the table ------------------------------------------------------------------------------------------------

    private val declares: List<MigrationAction> = listOf(
        orders, events, MigrationAction.DeclareBinding("events", "orders", "k"), limits(500),
        MigrationAction.DeclareOperatorPolicy("op", "^orders", mapOf("max-length" to ArgumentValue.Whole(5))), MigrationAction.DeclareStream("audit"),
    )

    private fun scenarios(): Map<String, (FakeManagementBroker) -> Unit> = mapOf(
        "fresh broker" to { },
        "everything exists with other settings" to { b ->
            b.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive(3))); b.addExchange("/", "events", "direct")
            b.addPolicy("/", "limits", "^other", mapOf("max-length" to JsonPrimitive(1))); b.addPolicy("/", "op", "^other", mapOf("max-length" to JsonPrimitive(1)), operator = true)
            b.addQueue("/", "audit", type = "quorum")
        },
        "writes refused" to { b -> b.answerContaining("PUT", "/api/", 403); b.answerContaining("POST", "/api/", 403) },
        "broker errors" to { b -> b.answerContaining("PUT", "/api/", 500); b.answerContaining("POST", "/api/", 500); b.answerContaining("GET", "/api/", 500) },
        "broker down" to { b -> b.unreachable = true },
    )

    @Test
    fun `no request is a DELETE unless the action is a delete, whatever the outcome`() {
        for ((name, setup) in scenarios()) {
            for (action in declares) {
                val b = FakeManagementBroker().also(setup)
                runBlocking { M508a.executor(b, b.streamManager(), mutableListOf()).execute(action) }
                assertTrue(b.deletes.isEmpty(), "$name / ${action.actionName}: ${b.deletes}")
            }
        }
    }

    @Test
    fun `a delete action sends exactly one DELETE and only for its own item`() {
        val deletes = listOf<Pair<MigrationAction, String>>(
            MigrationAction.DeleteQueue("old") to "/api/queues/%2F/old", MigrationAction.DeleteExchange("old") to "/api/exchanges/%2F/old",
            MigrationAction.DeletePolicy("old") to "/api/policies/%2F/old",
        )
        for ((action, path) in deletes) {
            val b = FakeManagementBroker().apply { addQueue("/", "old"); addQueue("/", "keep"); addExchange("/", "old"); addExchange("/", "keep"); addPolicy("/", "old", "^x", emptyMap()) }
            runBlocking { M508a.executor(b).execute(action) }
            assertEquals(listOf(path), b.deletes.map { it.path }, action.actionName)
        }
    }

    @Test
    fun `no declare ever overwrites an existing different resource`() {
        val b = FakeManagementBroker()
        scenarios().getValue("everything exists with other settings")(b)
        val before = b.snapshot()
        for (action in declares) runBlocking { M508a.executor(b, b.streamManager()).execute(action) }
        assertEquals(before.lines().filter { !it.startsWith("binding") }, b.snapshot().lines().filter { !it.startsWith("binding") }, "a different resource was changed")
        assertEquals(0, b.policyWrites)
    }

    @Test
    fun `no reason in the whole table contains the broker's text`() {
        val sentinel = "SENTINEL-broker-everything"
        val reasons = mutableListOf<String>()
        for (status in listOf(400, 401, 403, 404, 409, 500, 503)) {
            for (action in declares + listOf(MigrationAction.DeleteQueue("q"), MigrationAction.DeleteBinding("e", "q", "k"))) {
                val b = FakeManagementBroker().apply { answerContaining("PUT", "/api/", status, sentinel); answerContaining("POST", "/api/", status, sentinel); answerContaining("GET", "/api/", status, sentinel); answerContaining("DELETE", "/api/", status, sentinel) }
                (runBlocking { M508a.executor(b, FakeStreamManager().apply { failCreateWith = sentinel }).execute(action) } as? ActionResult.Failed)?.let { reasons += it.reason }
            }
        }
        assertTrue(reasons.size > 30, "only ${reasons.size} failures checked")
        reasons.forEach { assertFalse(it.contains(sentinel), it) }
    }

    // ---- rerun after a failure at action k ---------------------------------------------------------------------------------------

    private fun migration() = listOf(
        MigrationAction.DeclareQueue("orders", "quorum", arguments = limit5),
        MigrationAction.DeclareExchange("events", "topic"),
        MigrationAction.DeclareBinding("events", "orders", "order.created"),
        MigrationAction.DeclarePolicy("limits", "^orders", mapOf("max-length" to ArgumentValue.Whole(500)), applyTo = "queues"),
    )

    /** The runner's loop: stop at the first failure. */
    private fun runAll(b: FakeManagementBroker, actions: List<MigrationAction>): Pair<Int?, ActionResult?> {
        val executor = M508a.executor(b)
        actions.forEachIndexed { index, action ->
            val result = runBlocking { executor.execute(action) }
            if (result is ActionResult.Failed) return (index + 1) to result
        }
        return null to null
    }

    private fun cleanRunSnapshot(): String = FakeManagementBroker().also { assertEquals(null to null, runAll(it, migration())) }.snapshot()

    @Test
    fun `a rerun after a failure at action k repeats the earlier actions as no-ops, retries k and finishes the migration`() {
        val actions = migration()
        val failingPaths = listOf("PUT /api/queues/%2F/orders", "PUT /api/exchanges/%2F/events", "POST /api/bindings/%2F/e/events/q/orders", "PUT /api/policies/%2F/limits")
        for (k in 1..actions.size) {
            val b = FakeManagementBroker()
            val (method, path) = failingPaths[k - 1].split(" ")
            b.answer(method, path, 500, "SENTINEL-first-run")
            val (failedAt, result) = runAll(b, actions)
            assertEquals(k, failedAt, "first run, k=$k")
            assertTrue(M508a.failedReason(result!!).contains(M508a.NO_ANSWER), "k=$k")

            b.clearRules()
            val mark = b.requests.size
            assertEquals(null to null, runAll(b, actions), "second run, k=$k")
            assertEquals(cleanRunSnapshot(), b.snapshot(), "k=$k: the broker must end as after a clean run")
            assertTrue(b.deletes.isEmpty(), "k=$k: a rerun deleted something: ${b.deletes}")
            assertTrue(b.policyWrites <= 1, "k=$k: the policy was written ${b.policyWrites} times")
            assertTrue(b.requests.drop(mark).none { it.method == "DELETE" })
        }
    }

    @Test
    fun `a rerun after the broker changed in between fails at the changed resource and changes nothing`() {
        val actions = migration()
        val b = FakeManagementBroker()
        b.answer("PUT", "/api/exchanges/%2F/events", 500)
        assertEquals(2, runAll(b, actions).first)
        b.clearRules()
        b.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive(1))) // someone changed it
        val (failedAt, result) = runAll(b, actions)
        assertEquals(1, failedAt)
        assertEquals(M508a.conflict("orders"), M508a.failedReason(result!!))
        assertFalse(b.hasExchange("/", "events"), "nothing after the conflict ran")
        assertEquals(JsonPrimitive(1), b.queueArguments("/", "orders")!!["x-delivery-limit"])
    }

    @Test
    fun `a migration applied twice changes nothing the second time and writes the policy once`() {
        val b = FakeManagementBroker()
        assertEquals(null to null, runAll(b, migration()))
        val before = b.snapshot()
        assertEquals(null to null, runAll(b, migration()))
        assertEquals(before, b.snapshot())
        assertEquals(1, b.policyWrites)
        assertTrue(b.deletes.isEmpty())
    }
}
