package dev.caerbannog.mcp

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileRepository
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M5-07 call site #8 (docs/tasks/M5-07-nesting-guard-for-untrusted-json.md): one request line nested ten thousand levels deep answers
 * the JSON-RPC error -32700 with the fixed message "Parse error" (limit 64), never a StackOverflowError that would kill the server
 * process (not caught here on purpose), and the next request is answered. Nothing of the request is echoed.
 */
class McpServerNestingTest {

    private class Repo : ConnectionProfileRepository {
        override fun list(): List<ConnectionProfile> = emptyList()
        override fun add(profile: ConnectionProfile): SaveConnectionResult = SaveConnectionResult.Saved(profile)
        override fun update(profile: ConnectionProfile): SaveConnectionResult = SaveConnectionResult.Saved(profile)
        override fun delete(name: String) {}
    }

    private class Checker : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult = ConnectionCheckResult.BothReachable
    }

    private val server = McpServer(
        profileRepository = Repo(),
        connectionChecker = Checker(),
        streamCatalogProvider = { _, _ -> FakeStreamCatalog() },
        streamOffsetsProvider = { _, _ -> FakeStreamOffsets() },
        topologyCatalogProvider = { _, _ -> FakeTopologyCatalog() },
    )

    private fun arrays(depth: Int) = "[".repeat(depth) + "]".repeat(depth)
    private fun objects(depth: Int) = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)

    private fun assertParseError(response: String?) {
        assertNotNull(response, "a request that cannot be parsed is answered, not dropped")
        val obj = Json.parseToJsonElement(response!!).jsonObject
        val error = obj["error"]?.jsonObject
        assertNotNull(error, "expected an error object in $response")
        assertEquals(-32700, error!!["code"]?.jsonPrimitive?.int)
        assertEquals("Parse error", error["message"]?.jsonPrimitive?.content)
        assertTrue(obj["id"] == null || obj["id"] == JsonNull, "the id of an unparsable request is null or absent: $response")
        assertFalse(response.contains("[[["), "nothing of the request is echoed")
        assertFalse(response.contains("\"a\":{"), "nothing of the request is echoed")
    }

    private fun assertPingAnswered() {
        val resp = server.handleRequest("""{"jsonrpc":"2.0","id":7,"method":"ping"}""")
        assertNotNull(resp)
        val obj = Json.parseToJsonElement(resp!!).jsonObject
        assertEquals(7, obj["id"]?.jsonPrimitive?.int)
        assertNotNull(obj["result"])
    }

    @Test
    fun `params nested ten thousand levels deep answer -32700 Parse error and the next ping is answered`() {
        assertParseError(server.handleRequest("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":${arrays(10_000)}}"""))
        assertPingAnswered()
    }

    @Test
    fun `an id nested ten thousand levels deep is refused the same way`() {
        assertParseError(server.handleRequest("""{"jsonrpc":"2.0","id":${objects(10_000)},"method":"ping"}"""))
        assertPingAnswered()
    }

    @Test
    fun `a whole line of ten thousand opening brackets is a Parse error and not the generic internal error`() {
        val response = server.handleRequest("[".repeat(10_000))
        assertParseError(response)
        assertFalse(response!!.contains("-32603"))
    }

    @Test
    fun `64 levels of params are still accepted and 65 are refused`() {
        // the request object is level 1, params are the next levels
        val ok = server.handleRequest("""{"jsonrpc":"2.0","id":1,"method":"ping","params":${arrays(63)}}""")
        assertNotNull(ok)
        assertNotNull(Json.parseToJsonElement(ok!!).jsonObject["result"], "63 levels of params plus the request object is 64: accepted, got $ok")
        assertParseError(server.handleRequest("""{"jsonrpc":"2.0","id":1,"method":"ping","params":${arrays(64)}}"""))
    }

    @Test
    fun `brackets inside a string do not make a request too deep`() {
        val resp = server.handleRequest("""{"jsonrpc":"2.0","id":1,"method":"ping","params":{"note":"${"[".repeat(10_000)}"}}""")
        assertNotNull(resp)
        assertNotNull(Json.parseToJsonElement(resp!!).jsonObject["result"], "got ${resp.take(200)}")
    }
}
