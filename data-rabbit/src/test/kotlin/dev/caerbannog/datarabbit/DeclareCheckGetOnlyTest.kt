package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M9-05 safety: every request of every outcome of the check is a GET of a shape the executor's allow-list allows, with no body, and nothing
 * under a queue's name. A queue called `get`, `contents` or `purge` is a name like any other (CLAUDE.md: the only read of queue messages is the
 * requeue peek; `GET /api/queues/{vhost}/{name}/get` is that read path and the check must never build it). The guard itself is proved sensitive.
 */
class DeclareCheckGetOnlyTest {

    private val seen = mutableListOf<M508a.Seen>()

    /** The guard: a recorded request is a GET without a body, the allow-list accepts it, and a queue item path has exactly three segments after /api. */
    private fun assertGetOnly(requests: List<M508a.Seen>, where: String) {
        for (r in requests) {
            assertEquals("GET", r.method, "$where: ${r.method} ${r.path}")
            assertEquals("", r.body, "$where: a body was sent with ${r.path}")
            assertTrue(ExecutorPathAllowList.isAllowed("GET", r.path), "$where: ${r.path} is not an allowed GET shape")
            val segments = r.path.removePrefix("/api/").split('/')
            if (segments.first() == "queues") assertEquals(3, segments.size, "$where: a path under a queue's name: ${r.path}")
        }
    }

    private fun checkOver(broker: FakeManagementBroker) = ManagementDeclareCheck(ResourceReader(M508a.http(M508a.engineOver(broker, seen)), M508a.requests()))

    private fun everyKind(queue: String) = listOf(
        MigrationAction.DeclareQueue(queue, arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5))), MigrationAction.DeclareExchange("events"),
        MigrationAction.DeclareStream("telemetry"), MigrationAction.DeclarePolicy("limits", "^orders", mapOf("max-length" to ArgumentValue.Whole(1))),
        MigrationAction.DeclareOperatorPolicy("caps", "^orders"), MigrationAction.DeclareBinding("events", queue, "k"),
        MigrationAction.DeclareBinding("events", "audit", "k", destinationType = BindingDestinationType.EXCHANGE),
        MigrationAction.DeleteQueue(queue), MigrationAction.DeleteExchange("events"), MigrationAction.DeleteStream("telemetry"),
        MigrationAction.DeletePolicy("limits"), MigrationAction.DeleteBinding("events", queue, "k"),
    )

    @Test
    fun `an empty broker and a full broker, for every kind of action, send only allowed GETs`() {
        for (queue in listOf("orders", "get", "contents", "purge", "GET", "a b", "a/b", "q?x=1", "q#f", "100%", "naïve", "..a")) {
            for (full in listOf(false, true)) {
                val broker = FakeManagementBroker()
                if (full) {
                    broker.addQueue("/", queue); broker.addExchange("/", "events"); broker.addExchange("/", "audit"); broker.addQueue("/", "telemetry", type = "stream")
                    broker.addPolicy("/", "limits", "^orders", mapOf("max-length" to JsonPrimitive(1))); broker.addPolicy("/", "caps", "^orders", emptyMap(), operator = true)
                    broker.addBinding("/", "events", queue, routingKey = "k"); broker.addBinding("/", "events", "audit", toExchange = true, routingKey = "k")
                }
                seen.clear()
                runBlocking { everyKind(queue).forEach { checkOver(broker).find(it) } }
                assertGetOnly(seen, "queue [$queue], full=$full")
                assertTrue(broker.writes.isEmpty(), "the broker saw a write: ${broker.writes}")
                assertEquals(emptyList<Any>(), broker.requests.filter { it.isWrite })
            }
        }
    }

    @Test
    fun `a queue named get, contents or purge is read as an item, one GET with three segments`() {
        for (name in listOf("get", "contents", "purge")) {
            seen.clear()
            runBlocking { checkOver(FakeManagementBroker()).find(MigrationAction.DeclareQueue(name)) }
            assertEquals(listOf("GET /api/queues/%2F/$name"), seen.map { it.method + " " + it.path })
        }
    }

    @Test
    fun `every failure outcome sends only GETs too, and a name that cannot be a path sends nothing`() {
        for (status in listOf(401, 403, 404, 500, 400, 405)) {
            seen.clear()
            runBlocking { everyKind("get").forEach { ManagementDeclareCheck(ResourceReader(M508a.http(M508a.answering(status, "x", seen)), M508a.requests())).find(it) } }
            assertGetOnly(seen, "status $status")
        }
        seen.clear()
        runBlocking { assertEquals(BrokerFinding.Unreadable(ExecutorReasons.NOT_A_REQUEST), checkOver(FakeManagementBroker()).find(MigrationAction.DeclareQueue(""))) }
        assertEquals(0, seen.size, "an empty name reached the wire: ${seen.map { it.path }}")
    }

    @Test
    fun `a dot name is sent encoded, never as a dot segment`() {
        for (name in listOf(".", "..")) {
            seen.clear()
            runBlocking { checkOver(FakeManagementBroker()).find(MigrationAction.DeclareQueue(name)) }
            assertGetOnly(seen, "name [$name]")
            assertTrue(seen.none { it.path.split('/').any { segment -> segment == "." || segment == ".." } }, "a raw dot segment was sent: ${seen.map { it.path }}")
        }
    }

    @Test
    fun `the guard is sensitive, it would see a PUT, a DELETE, a POST, a body and a path under a queue's name`() {
        fun seen(method: String, path: String, body: String = "") = listOf(M508a.Seen(method, path, null, null, body))
        assertThrows(AssertionError::class.java) { assertGetOnly(seen("PUT", "/api/queues/%2F/orders", "{}"), "put") }
        assertThrows(AssertionError::class.java) { assertGetOnly(seen("DELETE", "/api/queues/%2F/orders"), "delete") }
        assertThrows(AssertionError::class.java) { assertGetOnly(seen("POST", "/api/bindings/%2F/e/a/q/b", "{}"), "post") }
        assertThrows(AssertionError::class.java) { assertGetOnly(seen("GET", "/api/queues/%2F/orders", "{}"), "body") }
        assertThrows(AssertionError::class.java) { assertGetOnly(seen("GET", "/api/queues/%2F/orders/get"), "peek path") }
        assertThrows(AssertionError::class.java) { assertGetOnly(seen("GET", "/api/queues/%2F/orders/contents"), "contents path") }
        assertThrows(AssertionError::class.java) { assertGetOnly(seen("GET", "/api/queues/%2F/orders/purge"), "purge path") }
        assertThrows(AssertionError::class.java) { assertGetOnly(seen("GET", "/api/overview/x"), "outside the allow-list") }
        assertFalse(runCatching { assertGetOnly(seen("GET", "/api/queues/%2F/get"), "a queue named get") }.isFailure, "a queue named get is a plain item")
    }
}
