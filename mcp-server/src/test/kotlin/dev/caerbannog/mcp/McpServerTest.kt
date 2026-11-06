package dev.caerbannog.mcp

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileRepository
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.streams.Retention
import dev.caerbannog.core.streams.StreamSummary
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueueRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class McpServerTest {

    private class TestProfileRepo : ConnectionProfileRepository {
        val profiles = mutableListOf<ConnectionProfile>()
        override fun list(): List<ConnectionProfile> = profiles
        override fun add(profile: ConnectionProfile): SaveConnectionResult {
            profiles.add(profile)
            return SaveConnectionResult.Saved(profile)
        }
        override fun update(profile: ConnectionProfile): SaveConnectionResult = SaveConnectionResult.Saved(profile)
        override fun delete(name: String) { profiles.removeAll { it.name == name } }
    }

    private class TestReachabilityChecker : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult = ConnectionCheckResult.BothReachable
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val repo = TestProfileRepo()
    private val checker = TestReachabilityChecker()
    private val streamCatalog = FakeStreamCatalog()
    private val streamOffsets = FakeStreamOffsets()
    private val topologyCatalog = FakeTopologyCatalog()

    private val server = McpServer(
        profileRepository = repo,
        connectionChecker = checker,
        streamCatalogProvider = { _, _ -> streamCatalog },
        streamOffsetsProvider = { _, _ -> streamOffsets },
        topologyCatalogProvider = { _, _ -> topologyCatalog }
    )

    @Test
    fun `initialize handshake returns protocol version and tool capability`() {
        val req = """{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2024-11-05"}}"""
        val resp = server.handleRequest(req)
        assertNotNull(resp)
        val obj = json.parseToJsonElement(resp!!).jsonObject
        val result = obj["result"]?.jsonObject
        assertNotNull(result)
        assertEquals("2024-11-05", result?.get("protocolVersion")?.jsonPrimitive?.content)
        assertEquals("caerbannog-mcp", result?.get("serverInfo")?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertTrue(result?.get("capabilities")?.jsonObject?.containsKey("tools") == true)
    }

    @Test
    fun `tools list exposes Caerbannog tools and zero queue message read tools`() {
        val req = """{"jsonrpc": "2.0", "id": 2, "method": "tools/list"}"""
        val resp = server.handleRequest(req)
        assertNotNull(resp)
        val obj = json.parseToJsonElement(resp!!).jsonObject
        val tools = obj["result"]?.jsonObject?.get("tools")?.jsonArray
        assertNotNull(tools)
        val toolNames = tools!!.map { it.jsonObject["name"]?.jsonPrimitive?.content }

        assertTrue(toolNames.contains("caerbannog_list_profiles"))
        assertTrue(toolNames.contains("caerbannog_check_connection"))
        assertTrue(toolNames.contains("caerbannog_list_streams"))
        assertTrue(toolNames.contains("caerbannog_get_stream_details"))
        assertTrue(toolNames.contains("caerbannog_list_queues"))
        assertTrue(toolNames.contains("caerbannog_list_exchanges"))
        assertTrue(toolNames.contains("caerbannog_list_bindings"))

        for (name in toolNames) {
            val lower = name.orEmpty().lowercase()
            assertFalse(lower.contains("read_queue") || lower.contains("purge") || lower.contains("get_message"))
        }
    }

    @Test
    fun `list profiles never leaks passwords`() {
        repo.add(
            ConnectionProfile(
                name = "test-prof",
                host = "rmq.test",
                virtualHost = "/",
                username = "admin",
                tls = true,
                topologyControlLevel = TopologyControlLevel.READ_ONLY
            )
        )

        val req = """{"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "caerbannog_list_profiles", "arguments": {}}}"""
        val resp = server.handleRequest(req)
        assertNotNull(resp)
        val text = json.parseToJsonElement(resp!!).jsonObject["result"]?.jsonObject
            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()

        assertTrue(text.contains("test-prof"))
        assertTrue(text.contains("\"tls\":true") || text.contains("\"tls\": true"))
        assertFalse(text.contains("password", ignoreCase = true))
    }

    @Test
    fun `list streams includes offsets and retention`() {
        repo.add(
            ConnectionProfile(
                name = "stream-prof",
                host = "localhost",
                virtualHost = "/",
                username = "user",
                tls = true
            )
        )
        streamCatalog.addStream(
            StreamSummary(
                name = "events",
                vhost = "/",
                messages = 500L,
                consumers = 2,
                segments = 1,
                firstTimestamp = null,
                leader = "rabbit@node1",
                node = "rabbit@node1",
                members = listOf("rabbit@node1"),
                online = listOf("rabbit@node1"),
                retention = Retention(maxAge = "3D")
            )
        )
        streamOffsets.setFirst("/", "events", 100L)

        val req = """{"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "caerbannog_list_streams", "arguments": {"profile": "stream-prof", "vhost": "/"}}}"""
        val resp = server.handleRequest(req)
        assertNotNull(resp)
        val text = json.parseToJsonElement(resp!!).jsonObject["result"]?.jsonObject
            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()

        assertTrue(text.contains("events"))
        assertTrue(text.contains("\"firstOffset\":100") || text.contains("\"firstOffset\": 100"))
        assertTrue(text.contains("\"lastOffset\":599") || text.contains("\"lastOffset\": 599"))
        assertTrue(text.contains("3D"))
    }

    @Test
    fun `stdio transport reads line requests and writes line responses`() {
        val inputLines = listOf(
            """{"jsonrpc": "2.0", "id": 10, "method": "ping"}""",
            """{"jsonrpc": "2.0", "method": "notifications/initialized"}""",
            """{"jsonrpc": "2.0", "id": 11, "method": "tools/list"}"""
        ).joinToString("\n") + "\n"

        val inputStream = ByteArrayInputStream(inputLines.toByteArray(Charsets.UTF_8))
        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream)

        server.runStdio(inputStream, printStream)

        val outputLines = outputStream.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
        assertEquals(2, outputLines.size, "Notification should not produce output line")

        for (line in outputLines) {
            val jsonElement = json.parseToJsonElement(line).jsonObject
            assertEquals("2.0", jsonElement["jsonrpc"]?.jsonPrimitive?.content)
        }
    }
}
