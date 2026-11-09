package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * M5-08a scope 2 (the writer): per action the exact method, path and body that reach the wire, and the status table to success or a FIXED
 * reason. The broker's body never appears in a reason: every body in this file carries a sentinel and every reason is checked for it.
 */
class MigrationWriterTest {

    private val sentinel = "SENTINEL-broker-text-9f3a"
    private val brokerBody = """{"error":"bad_request","reason":"$sentinel orders password=hunter2 amqp://user:secret@host"}"""

    private fun write(action: MigrationAction, engine: MockEngine): ActionResult =
        M508a.http(engine).use { http -> runBlocking { MigrationWriter(http, M508a.requests()).write(action) } }

    private fun sent(action: MigrationAction, status: Int = 200): M508a.Seen {
        val seen = mutableListOf<M508a.Seen>()
        write(action, M508a.answering(status, "", seen))
        return seen.single()
    }

    // ---- the exact request per action ------------------------------------------------------------------------------------

    @Test
    fun `a declared queue is one PUT with the request body of MigrationRequests, numbers as numbers`() {
        val action = MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5), "x-note" to ArgumentValue.Text("5")))
        val request = sent(action)
        assertEquals("PUT", request.method)
        assertEquals("/api/queues/%2F/orders", request.path)
        assertEquals(MigrationRequests.requestFor(action)!!.body, Json.parseToJsonElement(request.body))
        assertTrue(request.body.contains("\"x-delivery-limit\":5"), request.body)
        assertTrue(request.body.contains("\"x-note\":\"5\""), request.body)
    }

    @Test
    fun `an exchange, a policy and an operator policy are one PUT each`() {
        for ((action, path) in listOf(
            MigrationAction.DeclareExchange("events", "topic") to "/api/exchanges/%2F/events",
            MigrationAction.DeclarePolicy("limits", "^orders", mapOf("max-length" to ArgumentValue.Whole(500))) to "/api/policies/%2F/limits",
            MigrationAction.DeclareOperatorPolicy("op", "^orders", mapOf("max-length" to ArgumentValue.Whole(500))) to "/api/operator-policies/%2F/op",
        )) {
            val request = sent(action)
            assertEquals("PUT", request.method, action.actionName)
            assertEquals(path, request.path, action.actionName)
            assertEquals(MigrationRequests.requestFor(action)!!.body, Json.parseToJsonElement(request.body), action.actionName)
        }
    }

    @Test
    fun `a binding is one POST, to a queue or to an exchange`() {
        val toQueue = sent(MigrationAction.DeclareBinding("events", "orders", "order.created"))
        assertEquals("POST", toQueue.method)
        assertEquals("/api/bindings/%2F/e/events/q/orders", toQueue.path)
        val toExchange = sent(MigrationAction.DeclareBinding("events", "dlx", "dead", destinationType = BindingDestinationType.EXCHANGE))
        assertEquals("POST", toExchange.method)
        assertEquals("/api/bindings/%2F/e/events/e/dlx", toExchange.path)
    }

    @Test
    fun `the writer sends the one request of the action and nothing else, no GET and no policy read`() {
        for (action in listOf<MigrationAction>(
            MigrationAction.DeclareQueue("q"), MigrationAction.DeclareExchange("e"), MigrationAction.DeclareBinding("e", "q"),
            MigrationAction.DeclarePolicy("p", "^x"), MigrationAction.DeclareOperatorPolicy("p", "^x"), MigrationAction.DeleteQueue("q"),
        )) {
            val seen = mutableListOf<M508a.Seen>()
            write(action, M508a.answering(200, "", seen))
            assertEquals(1, seen.size, "${action.actionName}: ${seen.map { it.method + " " + it.path }}")
        }
    }

    @Test
    fun `deletes are DELETE of the item with no body`() {
        for ((action, path) in listOf(
            MigrationAction.DeleteQueue("old") to "/api/queues/%2F/old",
            MigrationAction.DeleteExchange("old.x") to "/api/exchanges/%2F/old.x",
            MigrationAction.DeletePolicy("old-policy") to "/api/policies/%2F/old-policy",
        )) {
            val request = sent(action, 204)
            assertEquals("DELETE", request.method, action.actionName)
            assertEquals(path, request.path, action.actionName)
            assertEquals("", request.body, action.actionName)
        }
    }

    @Test
    fun `a binding delete names the binding by its properties key, the tilde for an empty routing key`() {
        val empty = sent(MigrationAction.DeleteBinding("events", "old"), 204).path
        assertTrue(empty == "/api/bindings/%2F/e/events/q/old/~" || empty == "/api/bindings/%2F/e/events/q/old/%7E", "empty routing key: $empty")
        assertEquals("/api/bindings/%2F/e/events/q/old/order.created", sent(MigrationAction.DeleteBinding("events", "old", "order.created"), 204).path)
        assertEquals("DELETE", sent(MigrationAction.DeleteBinding("events", "old", "k"), 204).method)
    }

    @Test
    fun `names and the virtual host are encoded, a dot-only name is written with %2E and never as a dot segment`() {
        assertEquals("/api/queues/team%20a%2Fb/my%20queue", sent(MigrationAction.DeclareQueue("my queue", vhost = "team a/b")).path)
        val dots = sent(MigrationAction.DeclareQueue(".."), 405).path
        assertTrue(dots.endsWith("/%2E%2E"), dots)
        assertFalse(dots.split('/').any { it == ".." || it == "." }, dots)
    }

    // ---- the status table -------------------------------------------------------------------------------------------------

    private val declare = MigrationAction.DeclareQueue("orders")
    private val delete = MigrationAction.DeleteQueue("orders")

    @Test
    fun `200, 201 and 204 are success for a declare`() {
        for (status in listOf(200, 201, 204)) assertEquals(ActionResult.Success, write(declare, M508a.answering(status)), "status $status")
    }

    @Test
    fun `200 and 204 are success for a delete and 404 is a no-op success`() {
        for (status in listOf(200, 204, 404)) assertEquals(ActionResult.Success, write(delete, M508a.answering(status)), "status $status")
    }

    @Test
    fun `a 400 precondition failure is the conflict reason with the name`() {
        val body = """{"error":"bad_request","reason":"PRECONDITION_FAILED - inequivalent arg 'x-delivery-limit' for queue 'orders' in vhost '/': received '5' but current is '3'"}"""
        assertEquals(M508a.conflict("orders"), M508a.failedReason(write(declare, M508a.answering(400, body))))
        assertEquals(M508a.conflict("limits"), M508a.failedReason(write(MigrationAction.DeclareExchange("limits"), M508a.answering(400, body))))
    }

    @Test
    fun `any other 400, a 404 on a declare, 409 and a redirect are a fixed refusal, not a conflict`() {
        for (status in listOf(400, 404, 409, 302)) {
            val reason = M508a.failedReason(write(declare, M508a.answering(status, brokerBody)))
            assertTrue(reason.contains(M508a.REFUSED), "$status: $reason")
            assertFalse(reason.contains("different settings"), "$status looks like a conflict: $reason")
        }
    }

    @Test
    fun `401 and 403 are not allowed, for a declare and a delete`() {
        for (status in listOf(401, 403)) {
            for (action in listOf(declare, delete)) {
                assertTrue(M508a.failedReason(write(action, M508a.answering(status, brokerBody))).contains(M508a.NOT_ALLOWED), "$status ${action.actionName}")
            }
        }
    }

    @Test
    fun `5xx, a refused connection, a timeout and TLS failures mean the broker did not answer`() {
        val engines = mapOf(
            "500" to M508a.answering(500, brokerBody), "502" to M508a.answering(502, brokerBody), "503" to M508a.answering(503, brokerBody),
            "refused" to MockEngine { throw IOException("refused: $sentinel") },
            "timeout" to MockEngine { throw io.ktor.client.plugins.HttpRequestTimeoutException("http://x/$sentinel", 1) },
            "untrusted" to MockEngine { throw SSLException("handshake $sentinel", CertificateException("PKIX")) },
            "plain broker" to MockEngine { throw SSLException("Unrecognized SSL message, plaintext connection? $sentinel") },
        )
        for ((name, engine) in engines) {
            assertTrue(M508a.failedReason(write(declare, engine)).contains(M508a.NO_ANSWER), name)
        }
        assertTrue(M508a.failedReason(write(delete, M508a.answering(500, brokerBody))).contains(M508a.NO_ANSWER))
    }

    @Test
    fun `the broker's response body and exception text never appear in a reason`() {
        val statuses = listOf(400, 401, 403, 404, 409, 500, 503, 302)
        val results = mutableListOf<String>()
        for (status in statuses) {
            for (action in listOf(declare, delete, MigrationAction.DeclareBinding("e", "q"), MigrationAction.DeclarePolicy("p", "^x"))) {
                (write(action, M508a.answering(status, brokerBody)) as? ActionResult.Failed)?.let { results += it.reason }
            }
        }
        results += M508a.failedReason(write(declare, MockEngine { throw IOException(brokerBody) }))
        assertTrue(results.size > 20, "too few failures checked: ${results.size}")
        for (reason in results) {
            for (leaked in listOf(sentinel, "hunter2", "amqp://", "password", "secret", "bad_request", "PRECONDITION", "inequivalent")) {
                assertFalse(reason.contains(leaked), "the reason leaks \"$leaked\": $reason")
            }
        }
    }

    @Test
    fun `a name with dot, underscore and dash is echoed in the conflict reason, the vhost slash too`() {
        val precondition = """{"error":"bad_request","reason":"PRECONDITION_FAILED - inequivalent arg 'x'"}"""
        for (name in listOf("order-events", "orders.v2", "my_queue")) {
            assertEquals(M508a.conflict(name), M508a.failedReason(write(MigrationAction.DeclareQueue(name), M508a.answering(400, precondition))))
        }
    }

    @Test
    fun `a name that is not short and plain is not echoed in the conflict reason`() {
        val precondition = """{"error":"bad_request","reason":"PRECONDITION_FAILED - inequivalent arg 'x'"}"""
        for (name in listOf("a\"quote", "line\nbreak", "<script>x</script>", "x".repeat(65), "back\\slash", "a b")) {
            val reason = M508a.failedReason(write(MigrationAction.DeclareQueue(name), M508a.answering(400, precondition)))
            assertTrue(reason.contains("exists with different settings"), reason)
            assertFalse(reason.contains(name), "echoes the name: $reason")
            assertFalse(reason.contains("\n"))
        }
    }

    @Test
    fun `the writer never sends a stream action, it has no management request`() {
        val seen = mutableListOf<M508a.Seen>()
        val result = runCatching { write(MigrationAction.DeclareStream("audit"), M508a.answering(200, "", seen)) }
        assertTrue(seen.isEmpty(), "a stream action reached the management API: $seen")
        assertTrue(result.isFailure || result is Result<*> && (result.getOrNull() as? ActionResult.Failed) != null, "a stream action is not the writer's job")
    }
}
