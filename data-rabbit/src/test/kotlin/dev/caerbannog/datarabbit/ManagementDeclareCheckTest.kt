package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import dev.caerbannog.core.topology.BindingDestinationType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M9-05: `ManagementDeclareCheck(reader)` answers, for one action, what the broker has, with ONE GET through the executor's reader (so through
 * `ExecutorPathAllowList`) and the comparison the executor already has (`DeclaredResourceMatcher`, `ConflictCheck.comparePolicy`); it adds no
 * path and no rule. Status table: 404 absent, 200 compared, 401 and 403 and every failure unreadable with the executor's fixed text
 * (`ExecutorReasons.forReply`). The measured rule for arguments is M9-05b: here a queue or exchange is compared as `DeclaredResourceMatcher`
 * does today (type and flags equal, every DECLARED argument present with the same type and value, extra arguments on the broker ignored).
 * A delete action is not compared: it is present (Same) or Absent. The broker's body and any exception text never reach a result.
 */
class ManagementDeclareCheckTest {

    private val broker = FakeManagementBroker()
    private val seen = mutableListOf<M508a.Seen>()
    private val limit5 = mapOf("x-delivery-limit" to ArgumentValue.Whole(5))
    private val sentinel = "SENTINEL-body-text-5e1d"

    private fun check(engine: MockEngine = M508a.engineOver(broker, seen)) = ManagementDeclareCheck(ResourceReader(M508a.http(engine), M508a.requests()))
    private fun find(action: MigrationAction, engine: MockEngine = M508a.engineOver(broker, seen)): BrokerFinding = runBlocking { check(engine).find(action) }
    private fun wire() = seen.map { it.method + " " + it.path }

    private val unreadableNoAnswer = BrokerFinding.Unreadable(ExecutorReasons.NO_ANSWER)
    private val unreadableNotAllowed = BrokerFinding.Unreadable(ExecutorReasons.NOT_ALLOWED)

    // ---- queues ----

    @Test
    fun `a queue that is not on the broker is absent, with one GET`() {
        assertEquals(BrokerFinding.Absent, find(MigrationAction.DeclareQueue("orders")))
        assertEquals(listOf("GET /api/queues/%2F/orders"), wire())
    }

    @Test
    fun `a queue with the same type, flags and arguments is the same`() {
        broker.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive(5)))
        assertEquals(BrokerFinding.Same, find(MigrationAction.DeclareQueue("orders", arguments = limit5)))
    }

    @Test
    fun `a queue of another type, other flags, another argument value or another argument type is different`() {
        broker.addQueue("/", "classic-one", type = "classic")
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareQueue("classic-one")))
        broker.addQueue("/", "transient", durable = false)
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareQueue("transient")))
        broker.addQueue("/", "auto", autoDelete = true)
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareQueue("auto")))
        broker.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive(3)))
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareQueue("orders", arguments = limit5)))
        broker.addQueue("/", "texty", arguments = mapOf("x-delivery-limit" to JsonPrimitive("5")))
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareQueue("texty", arguments = limit5)), "5 against the text 5")
    }

    @Test
    fun `a declared argument the broker does not have is different`() {
        broker.addQueue("/", "orders")
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareQueue("orders", arguments = limit5)))
    }

    @Test
    fun `an extra argument that the queue type does not honour is ignored`() {
        broker.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive(5), "x-max-priority" to JsonPrimitive(3)))
        assertEquals(BrokerFinding.Same, find(MigrationAction.DeclareQueue("orders", arguments = limit5)), "x-max-priority means nothing on a quorum queue")
    }

    @Test
    fun `an honoured argument on the broker that the declare lacks is different, because the broker would answer 400 (M9-05b rule 3)`() {
        broker.addQueue("/", "orders", arguments = mapOf("x-delivery-limit" to JsonPrimitive(5), "x-max-length" to JsonPrimitive(10)))
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareQueue("orders", arguments = limit5)), "x-max-length is honoured by a quorum queue")
        broker.addQueue("/", "bare", arguments = mapOf("x-delivery-limit" to JsonPrimitive(3)))
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareQueue("bare")), "no arguments declared, one honoured on the broker")
        broker.addExchange("/", "events", arguments = mapOf("alternate-exchange" to JsonPrimitive("ae")))
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareExchange("events")), "alternate-exchange is the one argument an exchange compares")
        broker.addExchange("/", "audit", arguments = mapOf("x-foo" to JsonPrimitive("ae")))
        assertEquals(BrokerFinding.Same, find(MigrationAction.DeclareExchange("audit")), "any other exchange argument is ignored")
        assertTrue(wire().all { it.startsWith("GET ") }, "still one GET per step: ${wire()}")
    }

    @Test
    fun `a bare queue declare against a product-made stream is different, a stream step is still only the type`() {
        broker.addQueue("/", "telemetry", type = "stream", arguments = mapOf("x-queue-leader-locator" to JsonPrimitive("balanced")))
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareQueue("telemetry", "stream")), "the broker's 400 says so")
        assertEquals(BrokerFinding.Same, find(MigrationAction.DeclareStream("telemetry")), "a stream step does not compare arguments")
    }

    @Test
    fun `a classic, a quorum and a stream queue made by the executor read same right after the apply`() {
        val executor = M508a.executor(broker)
        val made = listOf(
            MigrationAction.DeclareQueue("c1", "classic", arguments = mapOf("x-max-length" to ArgumentValue.Whole(10))),
            MigrationAction.DeclareQueue("c2", "classic"),
            MigrationAction.DeclareQueue("q1", "quorum", arguments = limit5),
            MigrationAction.DeclareQueue("q2", "quorum"),
            MigrationAction.DeclareQueue("s1", "stream", arguments = mapOf("x-max-length-bytes" to ArgumentValue.Whole(1000))),
            MigrationAction.DeclareStream("s2"),
        )
        for (action in made) assertEquals(dev.caerbannog.core.migrations.ActionResult.Success, runBlocking { executor.execute(action) }, action.description)
        for (action in made) assertEquals(BrokerFinding.Same, find(action), "${action.description} after the apply")
    }

    // ---- streams ----

    @Test
    fun `a stream is the same when the item is a stream and different when it is another kind of queue`() {
        broker.addQueue("/", "telemetry", type = "stream")
        broker.addQueue("/", "jobs", type = "quorum")
        broker.addQueue("/", "old", type = "classic")
        assertEquals(BrokerFinding.Same, find(MigrationAction.DeclareStream("telemetry")))
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareStream("jobs")))
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareStream("old")))
        assertEquals(BrokerFinding.Absent, find(MigrationAction.DeclareStream("nothing")))
        assertTrue(wire().all { it.startsWith("GET /api/queues/%2F/") }, "a stream is read as a queue item: ${wire()}")
    }

    @Test
    fun `a quorum queue declared over a stream is different`() {
        broker.addQueue("/", "orders", type = "stream")
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareQueue("orders")))
    }

    // ---- exchanges ----

    @Test
    fun `an exchange is compared by type and flags`() {
        broker.addExchange("/", "events", type = "fanout")
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareExchange("events", type = "direct")))
        broker.addExchange("/", "audit", type = "direct")
        assertEquals(BrokerFinding.Same, find(MigrationAction.DeclareExchange("audit", type = "direct")))
        broker.addExchange("/", "hidden", internal = true)
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareExchange("hidden")))
        assertEquals(BrokerFinding.Absent, find(MigrationAction.DeclareExchange("nowhere")))
    }

    // ---- bindings ----

    private val bind = MigrationAction.DeclareBinding("events", "orders", "order.created")

    @Test
    fun `a binding with the same key is the same, with another key or none is absent, with other arguments is absent`() {
        broker.addExchange("/", "events"); broker.addQueue("/", "orders")
        assertEquals(BrokerFinding.Absent, find(bind), "no binding at all")
        broker.addBinding("/", "events", "orders", routingKey = "order.paid")
        assertEquals(BrokerFinding.Absent, find(bind), "another key between the same two is absent")
        broker.addBinding("/", "events", "orders", routingKey = "order.created", arguments = mapOf("x" to JsonPrimitive(1)))
        assertEquals(BrokerFinding.Absent, find(bind), "same key with other arguments is another binding")
        broker.addBinding("/", "events", "orders", routingKey = "order.created")
        assertEquals(BrokerFinding.Same, find(bind))
        assertTrue(wire().all { it == "GET /api/bindings/%2F/e/events/q/orders" }, "one GET of the list between the two: ${wire()}")
    }

    @Test
    fun `a binding to an exchange is read at the exchange destination path`() {
        broker.addExchange("/", "events"); broker.addExchange("/", "audit")
        broker.addBinding("/", "events", "audit", toExchange = true, routingKey = "k")
        val toExchange = MigrationAction.DeclareBinding("events", "audit", "k", destinationType = BindingDestinationType.EXCHANGE)
        assertEquals(BrokerFinding.Same, find(toExchange))
        assertEquals(listOf("GET /api/bindings/%2F/e/events/e/audit"), wire())
    }

    @Test
    fun `a binding of missing ends is absent, with no write`() {
        assertEquals(BrokerFinding.Absent, find(bind))
        assertTrue(broker.writes.isEmpty())
    }

    // ---- policies ----

    private val limits = MigrationAction.DeclarePolicy("limits", "^orders", mapOf("max-length" to ArgumentValue.Whole(1000)))

    @Test
    fun `a policy is compared by ConflictCheck, absent same or different`() {
        assertEquals(BrokerFinding.Absent, find(limits))
        broker.addPolicy("/", "limits", "^orders", mapOf("max-length" to JsonPrimitive(1000)))
        assertEquals(BrokerFinding.Same, find(limits))
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclarePolicy("limits", "^orders", mapOf("max-length" to ArgumentValue.Whole(500)))))
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclarePolicy("limits", "^invoices", mapOf("max-length" to ArgumentValue.Whole(1000)))))
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclarePolicy("limits", "^orders", mapOf("max-length" to ArgumentValue.Whole(1000)), applyTo = "queues")))
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclarePolicy("limits", "^orders", mapOf("max-length" to ArgumentValue.Whole(1000)), priority = 3)))
        assertTrue(broker.writes.isEmpty() && broker.policyWrites == 0, "the check wrote a policy")
    }

    @Test
    fun `an operator policy is read from the operator policies and compared the same way`() {
        val op = MigrationAction.DeclareOperatorPolicy("caps", "^orders", mapOf("max-length" to ArgumentValue.Whole(10)))
        assertEquals(BrokerFinding.Absent, find(op))
        broker.addPolicy("/", "caps", "^orders", mapOf("max-length" to JsonPrimitive(10)), operator = true)
        assertEquals(BrokerFinding.Same, find(op))
        assertEquals(listOf("GET /api/operator-policies/%2F/caps", "GET /api/operator-policies/%2F/caps"), wire())
    }

    // ---- deletes are not compared ----

    @Test
    fun `a delete is present when the item is there, whatever its settings, and absent when it is not`() {
        broker.addQueue("/", "legacy", type = "classic", durable = false)
        assertEquals(BrokerFinding.Same, find(MigrationAction.DeleteQueue("legacy")))
        assertEquals(BrokerFinding.Absent, find(MigrationAction.DeleteQueue("gone")))
        broker.addExchange("/", "old-events", type = "fanout")
        assertEquals(BrokerFinding.Same, find(MigrationAction.DeleteExchange("old-events")))
        assertEquals(BrokerFinding.Absent, find(MigrationAction.DeleteExchange("nope")))
        broker.addQueue("/", "old-stream", type = "stream")
        assertEquals(BrokerFinding.Same, find(MigrationAction.DeleteStream("old-stream")))
        assertEquals(BrokerFinding.Absent, find(MigrationAction.DeleteStream("no-stream")))
        broker.addPolicy("/", "old-policy", "^x", mapOf("max-length" to JsonPrimitive(1)))
        assertEquals(BrokerFinding.Same, find(MigrationAction.DeletePolicy("old-policy")))
        assertEquals(BrokerFinding.Absent, find(MigrationAction.DeletePolicy("no-policy")))
        assertTrue(wire().all { it.startsWith("GET ") })
        assertTrue(broker.deletes.isEmpty())
    }

    @Test
    fun `a delete of a binding is present only when a binding with that key is in the list`() {
        broker.addExchange("/", "events"); broker.addQueue("/", "orders")
        val drop = MigrationAction.DeleteBinding("events", "orders", "order.created")
        assertEquals(BrokerFinding.Absent, find(drop), "an empty list")
        broker.addBinding("/", "events", "orders", routingKey = "order.paid")
        assertEquals(BrokerFinding.Absent, find(drop), "another key")
        broker.addBinding("/", "events", "orders", routingKey = "order.created")
        assertEquals(BrokerFinding.Same, find(drop))
    }

    // ---- a 200 with a JSON list is not an item (M9-05b): the answer for the names "." and ".." ----

    private val listBody = """[{"name":"orders","vhost":"/"},{"name":"invoices","vhost":"/"}]"""

    private val itemActions = listOf(
        MigrationAction.DeclareQueue("orders"), MigrationAction.DeclareExchange("events"), MigrationAction.DeclareStream("telemetry"),
        MigrationAction.DeclarePolicy("limits", "^orders"), MigrationAction.DeclareOperatorPolicy("caps", "^orders"),
        MigrationAction.DeleteQueue("q"), MigrationAction.DeleteExchange("x"), MigrationAction.DeleteStream("s"), MigrationAction.DeletePolicy("p"),
    )

    @Test
    fun `a 200 with a JSON list on an item path is unreadable with the broker did not answer, for every declare kind and every delete`() {
        for (action in itemActions) for (body in listOf(listBody, "[]")) {
            assertEquals(unreadableNoAnswer, find(action, M508a.answering(200, body)), "${action.description} with [$body]")
        }
    }

    @Test
    fun `the names dot and dot dot answered by the honest fake are unreadable for a queue, an exchange and a stream, with GET only`() {
        broker.addQueue("/", "orders"); broker.addExchange("/", "events")
        for (name in listOf(".", "..")) {
            for (action in listOf(MigrationAction.DeclareQueue(name), MigrationAction.DeclareExchange(name), MigrationAction.DeclareStream(name),
                MigrationAction.DeleteQueue(name), MigrationAction.DeleteExchange(name), MigrationAction.DeleteStream(name))) {
                assertEquals(unreadableNoAnswer, find(action), "${action.description}")
            }
        }
        assertTrue(wire().isNotEmpty() && wire().all { it.startsWith("GET ") }, "GET only: ${wire()}")
        assertTrue(broker.writes.isEmpty())
    }

    @Test
    fun `a list is still the normal answer for a binding, and the list of a binding never reads as unreadable`() {
        broker.addExchange("/", "events"); broker.addQueue("/", "orders")
        broker.addBinding("/", "events", "orders", routingKey = "order.created")
        assertEquals(BrokerFinding.Same, find(bind))
        assertEquals(BrokerFinding.Same, find(MigrationAction.DeleteBinding("events", "orders", "order.created")))
    }

    @Test
    fun `an object that is not a list on an item path is still compared, never unreadable`() {
        val object200 = """{"name":"orders","vhost":"/","type":"quorum","durable":true,"auto_delete":false,"arguments":{"x-queue-type":"quorum"}}"""
        assertEquals(BrokerFinding.Same, find(MigrationAction.DeclareQueue("orders"), M508a.answering(200, object200)))
    }

    // ---- the status table ----

    private fun actions() = listOf(
        MigrationAction.DeclareQueue("orders"), MigrationAction.DeclareExchange("events"), MigrationAction.DeclareStream("telemetry"),
        MigrationAction.DeclarePolicy("limits", "^orders"), MigrationAction.DeclareOperatorPolicy("caps", "^orders"), bind,
        MigrationAction.DeleteQueue("q"), MigrationAction.DeleteExchange("x"), MigrationAction.DeleteStream("s"), MigrationAction.DeletePolicy("p"),
        MigrationAction.DeleteBinding("events", "orders", "k"),
    )

    @Test
    fun `404 is absent for every kind of action`() {
        for (action in actions()) assertEquals(BrokerFinding.Absent, find(action, M508a.answering(404, "Not Found")), action.description)
    }

    @Test
    fun `401 and 403 are unreadable with the user is not allowed, for every kind of action`() {
        for (status in listOf(401, 403)) for (action in actions()) {
            assertEquals(unreadableNotAllowed, find(action, M508a.answering(status, """{"error":"$sentinel"}""")), "$status ${action.description}")
        }
    }

    @Test
    fun `a 5xx is unreadable with the broker did not answer, and its body is never in the result`() {
        for (status in listOf(500, 502, 503)) for (action in actions()) {
            val found = find(action, M508a.answering(status, """{"error":"$sentinel"}"""))
            assertEquals(unreadableNoAnswer, found, "$status ${action.description}")
            assertFalse(found.toString().contains(sentinel))
        }
    }

    @Test
    fun `another status the check has no meaning for is unreadable too, never absent`() {
        for (action in actions()) {
            val found = find(action, M508a.answering(400, """{"error":"$sentinel"}"""))
            assertTrue(found is BrokerFinding.Unreadable, "${action.description}: $found")
            assertFalse(found.toString().contains(sentinel))
        }
    }

    @Test
    fun `a timeout and a refused connection are unreadable with the broker did not answer`() {
        val timeout = MockEngine { throw HttpRequestTimeoutException("http://broker.test", 1) }
        val refused = MockEngine { throw java.net.ConnectException("connection refused: $sentinel") }
        for (engine in listOf(timeout, refused)) for (action in actions()) {
            val found = find(action, engine)
            assertEquals(unreadableNoAnswer, found, action.description)
            assertFalse(found.toString().contains(sentinel))
        }
    }

    @Test
    fun `a 200 with a body that is not the expected shape is never absent and never same, for a declare`() {
        val garbage = listOf("not json $sentinel", "", "[1,2", "\"text\"", "{\"type\":", "null", "42")
        for (body in garbage) for (action in actions().filter { !it.isDestructive }) {
            val found = find(action, M508a.answering(200, body))
            assertTrue(found == BrokerFinding.Different || found is BrokerFinding.Unreadable || (action is MigrationAction.DeclareBinding && found == BrokerFinding.Absent),
                "${action.description} with body [$body]: $found")
            assertFalse(found.toString().contains(sentinel), "the body reached the result")
        }
    }

    @Test
    fun `a 200 for a delete of a queue, exchange, stream or policy is present whatever the body says`() {
        val deletes = listOf(MigrationAction.DeleteQueue("q"), MigrationAction.DeleteExchange("x"), MigrationAction.DeleteStream("s"), MigrationAction.DeletePolicy("p"))
        for (action in deletes) assertEquals(BrokerFinding.Same, find(action, M508a.answering(200, "garbage $sentinel")), action.description)
    }

    @Test
    fun `a body with a sentinel never reaches a result whatever the status`() {
        for (status in listOf(200, 400, 401, 403, 404, 500)) for (action in actions()) {
            val found = find(action, M508a.answering(status, """{"name":"$sentinel","arguments":{"$sentinel":"$sentinel"},"error":"$sentinel"}"""))
            assertFalse(found.toString().contains(sentinel), "$status ${action.description}: $found")
        }
    }

    // ---- allow-list, names, nothing else ----

    @Test
    fun `a name that cannot stand in a path is refused before anything is sent and is unreadable`() {
        val found = find(MigrationAction.DeclareQueue(""))
        assertEquals(BrokerFinding.Unreadable(ExecutorReasons.NOT_A_REQUEST), found)
        assertEquals(emptyList<String>(), wire(), "a request for an empty name was sent")
    }

    @Test
    fun `the test seam over a client reads with a GET and the profile's credentials only`() {
        broker.addQueue("/", "orders")
        val engine = M508a.engineOver(broker, seen)
        val over = ManagementDeclareCheck.overClient(HttpClient(engine), "http://broker.test:15672", "svc", "pw")
        assertEquals(BrokerFinding.Same, runBlocking { over.find(MigrationAction.DeclareQueue("orders")) })
        assertEquals(listOf("GET /api/queues/%2F/orders"), wire())
        assertTrue(seen.single().authorization.orEmpty().startsWith("Basic "))
    }
}
