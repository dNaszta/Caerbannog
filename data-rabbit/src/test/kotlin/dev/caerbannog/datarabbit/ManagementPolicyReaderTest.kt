package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import dev.caerbannog.core.planner.PolicyReadResult
import dev.caerbannog.core.topology.PolicyRow
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * M9-08 slice 2, written first: `ManagementBrokerPolicyReader(ResourceReader) : BrokerPolicyReader` in data-rabbit, the implementation of the planner's port that reads
 * ONE policy: `GET /api/policies/{vhost}/{name}` through the executor's `ResourceReader` and so through `ExecutorPathAllowList` (never the list, never an operator policy,
 * never a path under a queue's name, GET only), parsed with the row parser the export already has (`ManagementJson.parsePolicies` over the one object). Every failure
 * (404, 401, 403, 5xx, silence, a body that is not a policy, a name that cannot be a request, a definition the model refuses = `definitionComplete` false, M9-14b)
 * is [PolicyReadResult.Unreadable]: the broker's body and exception text never reach a result. Test-only seam for the desktop wiring and the acceptance glue, like
 * `ManagementDeclareCheck.overClient`: `ManagementBrokerPolicyReader.overClient(client, baseUrl, username, password)`.
 */
class ManagementPolicyReaderTest {
    private val broker = FakeManagementBroker()
    private val seen = mutableListOf<M508a.Seen>()

    private fun reader(engine: MockEngine = M508a.engineOver(broker, seen)) = ManagementBrokerPolicyReader(ResourceReader(M508a.http(engine), M508a.requests()))
    private fun read(vhost: String, name: String, engine: MockEngine = M508a.engineOver(broker, seen)): PolicyReadResult = runBlocking { reader(engine).read(vhost, name) }
    private fun wire() = seen.map { it.method + " " + it.path }

    private val limits = mapOf("max-length" to JsonPrimitive(5), "overflow" to JsonPrimitive("reject-publish"))

    @Test
    fun `a policy of the broker is found with its priority, apply-to, pattern and typed definition, with one GET of the item`() {
        broker.addPolicy("/", "limits", "^orders", limits, applyTo = "quorum_queues", priority = 7)
        val found = read("/", "limits")
        assertEquals(
            PolicyReadResult.Found(
                PolicyRow("limits", "/", "^orders", "quorum_queues", 7, mapOf("max-length" to ArgumentValue.Whole(5), "overflow" to ArgumentValue.Text("reject-publish"))),
            ),
            found,
        )
        assertEquals(listOf("GET /api/policies/%2F/limits"), wire())
    }

    @Test
    fun `the answer the real broker gave (fixture of RabbitMQ 4_4_0) is read`() {
        val body = FixtureFiles.text("m9-00/m9-08-policy-item.json")
        val seenHere = mutableListOf<M508a.Seen>()
        val found = read("/", "any", M508a.answering(200, body, seenHere)) as PolicyReadResult.Found
        assertEquals(3, found.row.priority)
        assertEquals("quorum_queues", found.row.applyTo)
        assertEquals(mapOf("max-length" to ArgumentValue.Whole(10)), found.row.definition)
        assertTrue(found.row.definitionComplete)
        assertEquals(listOf("GET /api/policies/%2F/any"), seenHere.map { it.method + " " + it.path })
    }

    @Test
    fun `a negative priority and a text definition are read as they are`() {
        broker.addPolicy("/", "retention", "^telemetry$", mapOf("max-age" to JsonPrimitive("7D")), applyTo = "streams", priority = -3)
        val row = (read("/", "retention") as PolicyReadResult.Found).row
        assertEquals(-3, row.priority)
        assertEquals(mapOf("max-age" to ArgumentValue.Text("7D")), row.definition)
    }

    @Test
    fun `a policy that is not there (404) is unreadable`() {
        assertEquals(PolicyReadResult.Unreadable, read("/", "nothing"))
        assertEquals(listOf("GET /api/policies/%2F/nothing"), wire())
    }

    @Test
    fun `a refusal, a server error and silence are unreadable`() {
        listOf(401, 403, 500, 502, 503).forEach { status ->
            assertEquals(PolicyReadResult.Unreadable, read("/", "limits", M508a.answering(status, "{\"error\":\"x\",\"reason\":\"SECRET-REASON-5e1d\"}")), "status $status")
        }
        assertEquals(PolicyReadResult.Unreadable, read("/", "limits", MockEngine { throw IOException("connection reset SECRET-TEXT-5e1d") }))
    }

    @Test
    fun `a body that is not a policy is unreadable, never an exception`() {
        listOf("", "not json", "[]", "[{\"name\":\"limits\"}]", """[{"vhost":"/","name":"limits","pattern":"x","apply-to":"all","priority":1,"definition":{}}]""", "{}", "{\"name\":\"limits\"}", "null", "\"x\"", "5", """{"vhost":"/","name":"limits","pattern":"x","apply-to":"all","priority":"high","definition":{}}""").forEach {
            assertEquals(PolicyReadResult.Unreadable, read("/", "limits", M508a.answering(200, it)), "body <$it>")
        }
    }

    @Test
    fun `a definition the model refuses makes the policy unreadable for the note (M9-14b, definitionComplete false)`() {
        val deep = "[".repeat(40) + "]".repeat(40)
        val body = """{"vhost":"/","name":"limits","pattern":"^orders","apply-to":"all","priority":1,"definition":{"max-length":5,"nested":$deep}}"""
        assertEquals(PolicyReadResult.Unreadable, read("/", "limits", M508a.answering(200, body)))
    }

    @Test
    fun `a name is encoded into one path segment`() {
        broker.addPolicy("/", "a b", "x", limits)
        read("/", "a b")
        read("/", "a/b")
        read("/", "100%")
        assertEquals(listOf("GET /api/policies/%2F/a%20b", "GET /api/policies/%2F/a%2Fb", "GET /api/policies/%2F/100%25"), wire())
    }

    @Test
    fun `a virtual host with a slash or a space is encoded`() {
        read("my/vhost", "limits")
        read("uat eu", "limits")
        assertEquals(listOf("GET /api/policies/my%2Fvhost/limits", "GET /api/policies/uat%20eu/limits"), wire())
    }

    @Test
    fun `an empty name cannot be a request, nothing is sent`() {
        assertEquals(PolicyReadResult.Unreadable, read("/", ""))
        assertEquals(emptyList<String>(), wire(), "the allow-list refused it before any request")
    }

    @Test
    fun `a dot name is sent as an encoded item path and the list the broker answers for it is unreadable (M9-05 measured it, a list is not the item)`() {
        listOf(".", "..").forEach { name ->
            seen.clear()
            val listBody = """[{"vhost":"/","name":"limits","pattern":"x","apply-to":"all","priority":1,"definition":{}}]"""
            assertEquals(PolicyReadResult.Unreadable, read("/", name, M508a.answering(200, listBody, seen)), "name <$name>")
            assertEquals(listOf("GET /api/policies/%2F/" + name.replace(".", "%2E")), wire(), "name <$name>: one GET of an encoded item path, never the list path")
        }
    }

    @Test
    fun `every request is a GET of one policy item, never the list, an operator policy or a queue`() {
        broker.addPolicy("/", "limits", "^orders", limits)
        listOf("limits", "missing", "a b", "get", "queues").forEach { read("/", it) }
        assertTrue(seen.isNotEmpty())
        val shape = Regex("^/api/policies/[^/]+/[^/]+$")
        seen.forEach { assertEquals("GET", it.method, "${it.method} ${it.path}"); assertTrue(shape.matches(it.path), "${it.path} is not an item of /api/policies") }
        assertFalse(seen.any { "operator-policies" in it.path || "/api/queues" in it.path })
        assertEquals(emptyList<Any>(), broker.writes)
        assertEquals(0, broker.policyWrites)
    }

    @Test
    fun `a policy named like a path suffix is a name like any other`() {
        broker.addPolicy("/", "get", "x", limits)
        assertTrue(read("/", "get") is PolicyReadResult.Found)
        assertEquals(listOf("GET /api/policies/%2F/get"), wire())
    }
}
