package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M5-08a round 1, F2: a binding is deleted by the properties key THE BROKER REPORTS, never by a key the app computes. The executor reads the list of
 * the bindings between source and destination, selects the entries whose routing key equals the action's and that have no arguments, and
 * sends one DELETE with that entry's `properties_key` as the 7th path segment, then reads back.
 *
 * MEASURED on 4.x (probe of round 1, the fake models it): the broker decodes the segment once and then reads the key out of it again (it drops `~`
 * and unquotes `+` and %XX). So the segment is the properties key with each `%` written as `%25` (`+` and `~` are sent as they are): sending the
 * properties key VERBATIM deleted the empty-key binding for the key `~`, the binding `a b` for the key `a+b`, and nothing for `x~y`.
 * The architect's note "do not encode it again" is therefore corrected here to "write `%` as `%25`"; the live test is the arbiter.
 */
class MigrationExecutorBindingDeleteTest {

    private val broker = FakeManagementBroker()
    private val seen = mutableListOf<M508a.Seen>()
    private val keys = listOf("", "~", "a+b", "x~y", " ", "/", "%", "a b", "é", "a.b")
    private val unidentified = "the binding could not be identified"

    init {
        broker.addExchange("/", "events", "direct"); broker.addQueue("/", "orders")
    }

    private fun exec(action: MigrationAction): ActionResult = runBlocking { M508a.executor(broker, seen = seen).execute(action) }
    private fun present() = broker.snapshot().lines().filter { it.startsWith("binding") }
    private fun wire() = seen.map { it.method + " " + it.path }
    private fun deletes() = seen.filter { it.method == "DELETE" }

    @Test
    fun `each key deletes exactly its own binding and leaves every other one, with one GET, one DELETE and a read-back`() {
        for (key in keys) {
            val b = FakeManagementBroker().apply { addExchange("/", "events", "direct"); addQueue("/", "orders"); keys.forEach { addBinding("/", "events", "orders", routingKey = it) } }
            val s = mutableListOf<M508a.Seen>()
            val result = runBlocking { M508a.executor(b, seen = s).execute(MigrationAction.DeleteBinding("events", "orders", key)) }
            assertEquals(ActionResult.Success, result, "delete [$key]")
            for (other in keys) assertEquals(other != key, b.hasBinding("/", "events", "orders", other), "after deleting [$key], binding [$other]")
            val methods = s.map { it.method }
            assertEquals(listOf("GET", "DELETE", "GET"), methods, "[$key]: ${s.map { it.method + " " + it.path }}")
            assertEquals("/api/bindings/%2F/e/events/q/orders", s[0].path)
        }
    }

    @Test
    fun `the DELETE segment is the properties key the broker reported with each percent written as 25, not a key the app computed`() {
        // what the broker lists as properties_key for each routing key (MEASURED quoting), and the segment that deletes that binding
        val expected = mapOf("" to "~", "~" to "%257E", "a+b" to "a%252Bb", "x~y" to "x%257Ey", " " to "+", "/" to "%252F", "%" to "%2525", "a b" to "a+b", "é" to "%25C3%25A9", "a.b" to "a.b")
        for ((key, segment) in expected) {
            val b = FakeManagementBroker().apply { addExchange("/", "events", "direct"); addQueue("/", "orders"); addBinding("/", "events", "orders", routingKey = key) }
            val s = mutableListOf<M508a.Seen>()
            runBlocking { M508a.executor(b, seen = s).execute(MigrationAction.DeleteBinding("events", "orders", key)) }
            assertEquals("/api/bindings/%2F/e/events/q/orders/$segment", s.single { it.method == "DELETE" }.path, "key [$key]")
        }
    }

    @Test
    fun `no matching binding is already gone, nothing is deleted and it succeeds`() {
        broker.addBinding("/", "events", "orders", routingKey = "other")
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteBinding("events", "orders", "k")))
        assertTrue(deletes().isEmpty(), wire().toString())
        assertEquals(1, present().size)
        seen.clear()
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteBinding("gone-x", "gone-q", "k")))
        assertTrue(deletes().isEmpty())
    }

    @Test
    fun `a binding with the same routing key but with arguments is not the one named, nothing is deleted`() {
        broker.addBinding("/", "events", "orders", routingKey = "k", arguments = mapOf("x-note" to JsonPrimitive(1)))
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteBinding("events", "orders", "k")))
        assertTrue(deletes().isEmpty(), wire().toString())
        assertTrue(broker.hasBinding("/", "events", "orders", "k"))
    }

    @Test
    fun `an entry with arguments next to the plain one does not stop the plain one from being deleted`() {
        broker.addBinding("/", "events", "orders", routingKey = "k")
        broker.addBinding("/", "events", "orders", routingKey = "k", arguments = mapOf("x-note" to JsonPrimitive(1)))
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteBinding("events", "orders", "k")))
        assertEquals(1, deletes().size)
        assertEquals(1, present().size, "only the one with arguments stays: ${present()}")
    }

    @Test
    fun `two matching entries send no DELETE and fail with the fixed text`() {
        val entry = """{"source":"events","vhost":"/","destination":"orders","destination_type":"queue","routing_key":"k","arguments":{},"properties_key":"k"}"""
        broker.answer("GET", "/api/bindings/%2F/e/events/q/orders", 200, "[$entry,$entry]")
        assertEquals(unidentified, M508a.failedReason(exec(MigrationAction.DeleteBinding("events", "orders", "k"))))
        assertTrue(deletes().isEmpty(), wire().toString())
    }

    @Test
    fun `a missing or unfitting properties key sends nothing and fails the same way`() {
        fun entry(props: String?) = """{"source":"events","vhost":"/","destination":"orders","destination_type":"queue","routing_key":"k","arguments":{}""" +
            (props?.let { ""","properties_key":"$it"""" } ?: "") + "}"
        for (props in listOf(null, "", "a b", "../x", "a/b", "é", "k?x=1", "k#f", "a\\u0000b", "%zz", "%2", "k\\n")) {
            broker.clearRules()
            broker.answer("GET", "/api/bindings/%2F/e/events/q/orders", 200, "[${entry(props)}]")
            seen.clear()
            assertEquals(unidentified, M508a.failedReason(exec(MigrationAction.DeleteBinding("events", "orders", "k"))), "properties_key $props")
            assertTrue(deletes().isEmpty(), "properties_key $props: ${wire()}")
        }
    }

    @Test
    fun `a properties_key that is not text, or a body that is not a binding list, sends nothing`() {
        for (body in listOf("""[{"routing_key":"k","arguments":{},"properties_key":5}]""", """[{"routing_key":"k","arguments":{},"properties_key":null}]""", "not json", "{}", "null", """[1,2]""", "")) {
            broker.clearRules(); broker.answer("GET", "/api/bindings/%2F/e/events/q/orders", 200, body); seen.clear()
            val result = exec(MigrationAction.DeleteBinding("events", "orders", "k"))
            assertTrue(result is ActionResult.Failed || body == "[1,2]" || body == "{}", "body $body gave $result")
            assertTrue(deletes().isEmpty(), "body $body: ${wire()}")
        }
    }

    @Test
    fun `a DELETE the broker acknowledges but did not do fails the read-back`() {
        broker.addBinding("/", "events", "orders", routingKey = "k")
        broker.answerContaining("DELETE", "/api/bindings/", 204)
        assertTrue(M508a.failedReason(exec(MigrationAction.DeleteBinding("events", "orders", "k"))).contains("still exists"))
        assertTrue(broker.hasBinding("/", "events", "orders", "k"))
    }

    @Test
    fun `the list cannot be read, so nothing is deleted`() {
        broker.addBinding("/", "events", "orders", routingKey = "k")
        for ((status, text) in listOf(401 to M508a.NOT_ALLOWED, 403 to M508a.NOT_ALLOWED, 500 to M508a.NO_ANSWER)) {
            broker.clearRules(); broker.answerContaining("GET", "/api/bindings/", status, "SENTINEL-list"); seen.clear()
            val reason = M508a.failedReason(exec(MigrationAction.DeleteBinding("events", "orders", "k")))
            assertTrue(reason.contains(text) && !reason.contains("SENTINEL"), "$status: $reason")
            assertTrue(deletes().isEmpty())
        }
        broker.clearRules(); broker.unreachable = true; seen.clear()
        assertTrue(M508a.failedReason(exec(MigrationAction.DeleteBinding("events", "orders", "k"))).contains(M508a.NO_ANSWER))
    }

    @Test
    fun `a delete of a binding never touches another source, destination or an exchange binding`() {
        broker.addExchange("/", "other", "direct"); broker.addQueue("/", "other-q")
        broker.addBinding("/", "events", "orders", routingKey = "k")
        broker.addBinding("/", "other", "orders", routingKey = "k")
        broker.addBinding("/", "events", "other-q", routingKey = "k")
        broker.addBinding("/", "events", "other", toExchange = true, routingKey = "k")
        assertEquals(ActionResult.Success, exec(MigrationAction.DeleteBinding("events", "orders", "k")))
        assertEquals(listOf(false, true, true, true), listOf(
            broker.hasBinding("/", "events", "orders", "k"), broker.hasBinding("/", "other", "orders", "k"),
            broker.hasBinding("/", "events", "other-q", "k"), broker.hasBinding("/", "events", "other", "k", toExchange = true),
        ))
        assertFalse(wire().any { it.contains("/e/other/") && it.startsWith("DELETE") })
    }

    // ---- the path builder takes the key as a parameter ---------------------------------------------------------------------

    @Test
    fun `bindingDeletePath appends the key it is given with each percent written as 25 and builds none itself`() {
        val action = MigrationAction.DeleteBinding("events", "orders", "this routing key is ignored")
        assertEquals("/api/bindings/%2F/e/events/q/orders/~", MigrationDeleteRequests.bindingDeletePath(action, "~"))
        assertEquals("/api/bindings/%2F/e/events/q/orders/a+b", MigrationDeleteRequests.bindingDeletePath(action, "a+b"))
        assertEquals("/api/bindings/%2F/e/events/q/orders/a%252Bb", MigrationDeleteRequests.bindingDeletePath(action, "a%2Bb"))
        assertEquals("/api/bindings/%2F/e/events/q/orders/%257E", MigrationDeleteRequests.bindingDeletePath(action, "%7E"))
        assertEquals("/api/bindings/%2F/e/events/q/orders/x%257Ey", MigrationDeleteRequests.bindingDeletePath(action, "x%7Ey"))
        assertEquals("/api/bindings/team%20a%2Fb/e/ev%20ents/q/my%20q/k", MigrationDeleteRequests.bindingDeletePath(MigrationAction.DeleteBinding("ev ents", "my q", "z", vhost = "team a/b"), "k"))
    }
}
