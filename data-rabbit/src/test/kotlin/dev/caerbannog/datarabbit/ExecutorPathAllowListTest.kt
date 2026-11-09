package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.streams.StreamManager
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M5-08a scope 3 and mutation (h): the executor can only produce requests to queues, exchanges, bindings, policies and operator policies
 * (plus GETs of the same), and never a message path (`/get`, `/contents`, `/purge`), a raw dot segment or a raw slash inside a name. Two halves:
 * the allow-list function against a table of allowed and forbidden requests, and every action x every hostile name through the executor.
 */
class ExecutorPathAllowListTest {

    // ---- the allow-list itself ---------------------------------------------------------------------------------------

    @Test
    fun `the requests the executor needs are allowed`() {
        for ((method, path) in listOf(
            "GET" to "/api/queues/%2F/orders", "PUT" to "/api/queues/%2F/orders", "DELETE" to "/api/queues/%2F/orders",
            "GET" to "/api/exchanges/%2F/events", "PUT" to "/api/exchanges/%2F/events", "DELETE" to "/api/exchanges/%2F/events",
            "GET" to "/api/policies/%2F/limits", "PUT" to "/api/policies/%2F/limits", "DELETE" to "/api/policies/%2F/limits",
            "GET" to "/api/operator-policies/%2F/op", "PUT" to "/api/operator-policies/%2F/op", "DELETE" to "/api/operator-policies/%2F/op",
            "GET" to "/api/bindings/%2F/e/events/q/orders", "POST" to "/api/bindings/%2F/e/events/q/orders",
            "GET" to "/api/bindings/%2F/e/events/e/dlx", "POST" to "/api/bindings/%2F/e/events/e/dlx",
            "DELETE" to "/api/bindings/%2F/e/events/q/orders/~", "DELETE" to "/api/bindings/%2F/e/events/q/orders/order.created",
            "PUT" to "/api/queues/team%20a%2Fb/my%20queue", "PUT" to "/api/queues/%2F/%2E%2E%2E",
        )) assertTrue(ExecutorPathAllowList.isAllowed(method, path), "$method $path must be allowed")
    }

    @Test
    fun `message paths are never allowed, whatever the method`() {
        for (method in listOf("GET", "POST", "PUT", "DELETE")) {
            for (path in listOf(
                "/api/queues/%2F/orders/get", "/api/queues/%2F/orders/contents", "/api/queues/%2F/orders/purge",
                "/api/queues/%2F/orders/get/", "/api/queues/%2F/orders/GET", "/api/queues/%2F/orders/contents/x",
                "/api/exchanges/%2F/events/publish", "/api/queues/%2F/orders/bindings",
            )) assertFalse(ExecutorPathAllowList.isAllowed(method, path), "$method $path must be refused")
        }
    }

    @Test
    fun `get, contents and purge are names like any other when they stand in a name position`() {
        for (word in listOf("get", "contents", "purge")) {
            assertTrue(ExecutorPathAllowList.isAllowed("PUT", "/api/queues/%2F/$word"), word)
            assertTrue(ExecutorPathAllowList.isAllowed("DELETE", "/api/exchanges/%2F/$word"), word)
            assertTrue(ExecutorPathAllowList.isAllowed("GET", "/api/bindings/%2F/e/$word/q/$word"), word)
            assertTrue(ExecutorPathAllowList.isAllowed("DELETE", "/api/bindings/%2F/e/$word/q/$word/$word"), word)
            // ... but never as a suffix after a name
            assertFalse(ExecutorPathAllowList.isAllowed("POST", "/api/queues/%2F/$word/$word"), word)
            assertFalse(ExecutorPathAllowList.isAllowed("GET", "/api/queues/%2F/orders/$word"), word)
        }
    }

    @Test
    fun `a plus is allowed ONLY in the properties key segment of a binding DELETE`() {
        assertTrue(ExecutorPathAllowList.isAllowed("DELETE", "/api/bindings/%2F/e/events/q/orders/a+b"))
        assertTrue(ExecutorPathAllowList.isAllowed("DELETE", "/api/bindings/%2F/e/events/q/orders/+"))
        assertTrue(ExecutorPathAllowList.isAllowed("DELETE", "/api/bindings/%2F/e/events/q/orders/a%252Bb"))
        for ((method, path) in listOf(
            "PUT" to "/api/queues/%2F/a+b", "GET" to "/api/queues/%2F/a+b", "DELETE" to "/api/queues/%2F/a+b", "PUT" to "/api/exchanges/%2F/a+b",
            "GET" to "/api/policies/%2F/a+b", "PUT" to "/api/operator-policies/%2F/a+b", "GET" to "/api/queues/a+b/q",
            "POST" to "/api/bindings/%2F/e/ev+ents/q/orders", "GET" to "/api/bindings/%2F/e/events/q/or+ders", "GET" to "/api/bindings/%2F/e/events/e/or+ders",
            "DELETE" to "/api/bindings/%2F/e/ev+ents/q/orders/k", "DELETE" to "/api/bindings/%2F/e/events/q/or+ders/k", "DELETE" to "/api/bindings/+/e/events/q/orders/k",
        )) assertFalse(ExecutorPathAllowList.isAllowed(method, path), "$method $path: a plus outside the properties key segment must be refused")
    }

    @Test
    fun `every other character is still refused in the properties key segment`() {
        for (bad in listOf(" ", "a b", "\u00e9", "a/b", "a?b", "a#b", "a\\b", "a;b", "a=b", "a&b", "a,b", "a:b", "a@b", "a!b", "a\$b", "a'b", "a(b", "a)b", "a[b", "a]b", "a{b", "a}b", "a<b", "a>b", "a|b", "a^b", "a`b", "a\"b", "%2", "%zz", "%", "a%", "", "..", ".")) {
            assertFalse(ExecutorPathAllowList.isAllowed("DELETE", "/api/bindings/%2F/e/events/q/orders/$bad"), "properties key segment [$bad] must be refused")
        }
    }

    @Test
    fun `other API areas and wrong verbs are refused`() {
        for ((method, path) in listOf(
            "GET" to "/api/overview", "GET" to "/api/definitions", "POST" to "/api/definitions", "GET" to "/api/users", "PUT" to "/api/users/x",
            "DELETE" to "/api/vhosts/x", "PUT" to "/api/vhosts/x", "GET" to "/api/connections", "DELETE" to "/api/connections/x",
            "PUT" to "/api/parameters/shovel/%2F/s", "POST" to "/api/exchanges/%2F/amq.default/publish", "GET" to "/api/queues/%2F",
            "GET" to "/api/queues", "GET" to "/api/bindings/%2F", "POST" to "/api/queues/%2F/orders", "POST" to "/api/exchanges/%2F/e",
            "PUT" to "/api/bindings/%2F/e/events/q/orders", "DELETE" to "/api/bindings/%2F/e/events/q/orders", "DELETE" to "/api/bindings/%2F/e/events/x/orders/k",
            "PATCH" to "/api/queues/%2F/orders", "HEAD" to "/api/queues/%2F/orders", "OPTIONS" to "/api/queues/%2F/orders",
            "GET" to "/", "GET" to "", "GET" to "api/queues/%2F/orders", "GET" to "/apiX/queues/%2F/orders", "GET" to "/api/queues//orders",
            "GET" to "/api/queues/%2F/orders?x=1", "GET" to "/api/queues/%2F/orders#frag", "GET" to "/api/queues/%2F/..", "GET" to "/api/queues/%2F/.",
            "PUT" to "/api/queues/../exchanges/%2F/x", "GET" to "/api/queues/%2F/orders/", "GET" to "//api/queues/%2F/orders",
        )) assertFalse(ExecutorPathAllowList.isAllowed(method, path), "$method $path must be refused")
    }

    // ---- every action x every hostile name ----------------------------------------------------------------------------

    private val hostileNames = listOf(
        "orders", "", " ", "a b", "a/b", "/", "..", ".", "...", "a/../b", "%2F", "%2e%2e", "a%b", "a?b=c", "a#b", "a\\b", "ünï", "日本語",
        "a\nb", "a\u0000b", "<script>", "x".repeat(300), "a+b", "'q'", "\"dq\"", "~", "a~b", "-", "a&b", "a;b", "get", "contents", "purge",
    )

    private fun actionsWith(name: String, vhost: String): List<MigrationAction> = listOf(
        MigrationAction.DeclareQueue(name, vhost = vhost, arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5))),
        MigrationAction.DeclareExchange(name, "topic", vhost = vhost),
        MigrationAction.DeclareBinding(name, name, name, vhost = vhost),
        MigrationAction.DeclareBinding(name, name, name, vhost = vhost, destinationType = BindingDestinationType.EXCHANGE),
        MigrationAction.DeclarePolicy(name, "^x", mapOf("max-length" to ArgumentValue.Whole(5)), vhost = vhost),
        MigrationAction.DeclareOperatorPolicy(name, "^x", mapOf("max-length" to ArgumentValue.Whole(5)), vhost = vhost),
        MigrationAction.DeclareStream(name, vhost = vhost),
        MigrationAction.DeleteQueue(name, vhost = vhost),
        MigrationAction.DeleteExchange(name, vhost = vhost),
        MigrationAction.DeleteBinding(name, name, name, vhost = vhost),
        MigrationAction.DeleteBinding(name, name, vhost = vhost),
        MigrationAction.DeletePolicy(name, vhost = vhost),
        MigrationAction.DeleteStream(name, vhost = vhost),
    )

    private val noStreams = object : StreamManager {
        override suspend fun createStream(vhost: String, name: String) = StreamManagementResult.Success
        override suspend fun deleteStream(vhost: String, name: String) = StreamManagementResult.Success
    }

    private fun structurallyFine(method: String, path: String): String? {
        val segments = path.split('/')
        if (segments.firstOrNull() != "") return "does not start with a slash"
        val s = segments.drop(1)
        if (s.any { it.isEmpty() }) return "empty segment"
        if (s.any { it == "." || it == ".." }) return "raw dot segment"
        if (path.any { it == '?' || it == '#' || it == '\\' || it.code < 0x20 || it == ' ' || it.code > 0x7e }) return "unencoded character"
        val expected = when (s.getOrNull(1)) {
            "queues", "exchanges", "policies", "operator-policies" -> listOf(4)
            "bindings" -> listOf(7, 8)
            else -> return "collection ${s.getOrNull(1)} is not allowed"
        }
        if (s.firstOrNull() != "api" || s.size !in expected) return "${s.size} segments instead of $expected"
        if (s[1] == "bindings" && (s[3] != "e" || s[5] != "q" && s[5] != "e")) return "binding literals e/q|e are not where they belong"
        if (s[1] == "bindings" && s.size == 8 && method != "DELETE") return "a properties key outside a DELETE"
        if (s[1] != "bindings" && method == "POST") return "POST outside a binding"
        return null
    }

    @Test
    fun `every action with every hostile name only ever produces allowed paths, and never throws`() {
        var requestsChecked = 0
        for (hostile in hostileNames) {
            for ((label, actions) in listOf("name" to actionsWith(hostile, "/"), "vhost" to actionsWith("orders", hostile))) {
                for (action in actions) {
                    val seen = mutableListOf<M508a.Seen>()
                    val executor = ManagementTopologyMigrationExecutor(M508a.http(M508a.answering(200, "{}", seen)), M508a.requests(), noStreams) { }
                    runBlocking { executor.execute(action) } // the result may be a failure; it must not throw
                    for (request in seen) {
                        requestsChecked++
                        val where = "${action.actionName} $label=${hostile.take(20).replace("\n", "\\n")} -> ${request.method} ${request.path}"
                        assertTrue(ExecutorPathAllowList.isAllowed(request.method, request.path), "not allowed: $where")
                        assertEquals(null, structurallyFine(request.method, request.path), where)
                    }
                }
            }
        }
        assertTrue(requestsChecked > 500, "only $requestsChecked requests were checked; the executor did not send what it should")
    }

    @Test
    fun `a queue, exchange or policy named get, contents or purge is declared and deleted like any other`() {
        for (word in listOf("get", "contents", "purge")) {
            val broker = dev.caerbannog.core.migrations.fakes.FakeManagementBroker()
            val seen = mutableListOf<M508a.Seen>()
            val executor = M508a.executor(broker, seen = seen)
            runBlocking {
                assertEquals(dev.caerbannog.core.migrations.ActionResult.Success, executor.execute(MigrationAction.DeclareQueue(word)))
                assertEquals(dev.caerbannog.core.migrations.ActionResult.Success, executor.execute(MigrationAction.DeclareExchange(word)))
                assertEquals(dev.caerbannog.core.migrations.ActionResult.Success, executor.execute(MigrationAction.DeleteQueue(word)))
            }
            assertTrue(seen.map { it.method + " " + it.path }.containsAll(listOf("PUT /api/queues/%2F/$word", "PUT /api/exchanges/%2F/$word", "DELETE /api/queues/%2F/$word")), seen.toString())
            assertTrue(broker.hasExchange("/", word) && !broker.hasQueue("/", word))
        }
    }

    @Test
    fun `a stream action sends no PUT, POST or DELETE at all`() {
        for (action in listOf(MigrationAction.DeclareStream("audit"), MigrationAction.DeleteStream("audit"))) {
            val seen = mutableListOf<M508a.Seen>()
            val executor = ManagementTopologyMigrationExecutor(M508a.http(M508a.answering(200, "{}", seen)), M508a.requests(), noStreams) { }
            runBlocking { executor.execute(action) }
            assertTrue(seen.none { it.method != "GET" }, seen.map { it.method + " " + it.path }.toString())
        }
    }
}
