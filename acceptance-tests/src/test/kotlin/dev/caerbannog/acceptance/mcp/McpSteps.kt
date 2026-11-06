package dev.caerbannog.acceptance.mcp

import dev.caerbannog.acceptance.connections.FakeBrokerReachabilityChecker
import dev.caerbannog.acceptance.connections.InMemoryConnectionProfileRepository
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
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
import dev.caerbannog.mcp.McpServer
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpSteps {
    private val profileRepository = InMemoryConnectionProfileRepository()
    private val reachabilityChecker = FakeBrokerReachabilityChecker()
    private val fakeStreamCatalog = FakeStreamCatalog()
    private val fakeStreamOffsets = FakeStreamOffsets()
    private val fakeTopologyCatalog = FakeTopologyCatalog()

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var server: McpServer
    private var lastResponseJson: JsonObject? = null
    private var lastToolResultText: String = ""
    private var lastToolIsError: Boolean = false

    @Before
    fun setUp() {
        server = McpServer(
            profileRepository = profileRepository,
            connectionChecker = reachabilityChecker,
            streamCatalogProvider = { _, _ -> fakeStreamCatalog },
            streamOffsetsProvider = { _, _ -> fakeStreamOffsets },
            topologyCatalogProvider = { _, _ -> fakeTopologyCatalog }
        )
    }

    @Given("the app holds a profile {string} for host {string} vhost {string} with TLS on")
    fun appHoldsProfileTlsOn(name: String, host: String, vhost: String) {
        val profile = ConnectionProfile(
            name = name,
            host = host,
            virtualHost = vhost,
            username = "guest",
            tls = true,
            topologyControlLevel = TopologyControlLevel.READ_ONLY
        )
        profileRepository.add(profile)
    }

    @Given("the app holds a profile {string} for host {string} vhost {string} with TLS off")
    fun appHoldsProfileTlsOff(name: String, host: String, vhost: String) {
        val profile = ConnectionProfile(
            name = name,
            host = host,
            virtualHost = vhost,
            username = "guest",
            tls = false,
            topologyControlLevel = TopologyControlLevel.READ_ONLY
        )
        profileRepository.add(profile)
    }

    @Given("the stream {string} on vhost {string} has {long} messages, first offset {long}, last offset {long}, retention {string}")
    fun theStreamOnVhostHas(streamName: String, vhost: String, count: Long, first: Long, last: Long, retention: String) {
        fakeStreamCatalog.addStream(
            StreamSummary(
                name = streamName,
                vhost = vhost,
                messages = count,
                consumers = 0,
                segments = 1,
                firstTimestamp = null,
                leader = "rabbit@node1",
                node = "rabbit@node1",
                members = listOf("rabbit@node1"),
                online = listOf("rabbit@node1"),
                retention = Retention(maxAge = retention)
            )
        )
        fakeStreamOffsets.setFirst(vhost, streamName, first)
    }

    @Given("the queue {string} on vhost {string} is a {string} queue with {long} messages")
    fun queueOnVhost(queueName: String, vhost: String, type: String, messages: Long) {
        fakeTopologyCatalog.addQueue(
            QueueRow(
                name = queueName,
                vhost = vhost,
                type = type,
                readyMessages = messages,
                unackedMessages = 0L,
                consumers = 1,
                leader = "rabbit@node1",
                members = listOf("rabbit@node1"),
                online = listOf("rabbit@node1"),
                policy = null,
                state = "running"
            )
        )
    }

    @Given("the exchange {string} on vhost {string} is a {string} exchange")
    fun exchangeOnVhost(exchangeName: String, vhost: String, type: String) {
        fakeTopologyCatalog.addExchange(
            ExchangeRow(
                name = exchangeName,
                vhost = vhost,
                type = type,
                durable = true,
                autoDelete = false,
                internal = false
            )
        )
    }

    @Given("the binding from {string} to {string} with routing key {string} exists")
    fun bindingExists(source: String, destination: String, routingKey: String) {
        fakeTopologyCatalog.addBinding(
            BindingRow(
                source = source,
                vhost = "/",
                destination = destination,
                destinationType = BindingDestinationType.QUEUE,
                routingKey = routingKey
            )
        )
    }

    @When("the client sends the MCP {string} request with protocol version {string}")
    fun sendInitialize(method: String, version: String) {
        val request = """
            {"jsonrpc": "2.0", "id": 1, "method": "$method", "params": {"protocolVersion": "$version", "capabilities": {}, "clientInfo": {"name": "test-client", "version": "1.0.0"}}}
        """.trimIndent()
        val response = server.handleRequest(request)
        assertNotNull(response)
        lastResponseJson = json.parseToJsonElement(response).jsonObject
    }

    @Then("the MCP server responds with protocol version {string}")
    fun assertProtocolVersion(expectedVersion: String) {
        val result = lastResponseJson?.get("result")?.jsonObject
        assertNotNull(result)
        assertEquals(expectedVersion, result["protocolVersion"]?.jsonPrimitive?.content)
    }

    @Then("the server info name is {string}")
    fun assertServerInfoName(expectedName: String) {
        val serverInfo = lastResponseJson?.get("result")?.jsonObject?.get("serverInfo")?.jsonObject
        assertNotNull(serverInfo)
        assertEquals(expectedName, serverInfo["name"]?.jsonPrimitive?.content)
    }

    @Then("the server advertises {string} capability")
    fun assertCapability(capability: String) {
        val capabilities = lastResponseJson?.get("result")?.jsonObject?.get("capabilities")?.jsonObject
        assertNotNull(capabilities)
        assertTrue(capabilities.containsKey(capability), "Expected capability $capability")
    }

    @When("the client requests the list of MCP tools")
    fun requestToolsList() {
        val request = """{"jsonrpc": "2.0", "id": 2, "method": "tools/list"}"""
        val response = server.handleRequest(request)
        assertNotNull(response)
        lastResponseJson = json.parseToJsonElement(response).jsonObject
    }

    @Then("the tools list contains {string}")
    fun assertToolExists(toolName: String) {
        val tools = lastResponseJson?.get("result")?.jsonObject?.get("tools")?.jsonArray
        assertNotNull(tools)
        val names = tools.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertTrue(names.contains(toolName), "Expected tool $toolName in $names")
    }

    @Then("the tools list does not contain any tool that reads or purges queue messages")
    fun assertNoQueueMessageTool() {
        val tools = lastResponseJson?.get("result")?.jsonObject?.get("tools")?.jsonArray
        assertNotNull(tools)
        val names = tools.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
        for (name in names) {
            val lower = name.lowercase()
            assertFalse(lower.contains("read_queue") || lower.contains("purge_queue") || lower.contains("get_message"),
                "Forbidden tool found: $name")
        }
    }

    @When("the client calls MCP tool {string}")
    fun callToolWithoutArgs(toolName: String) {
        callTool(toolName, "{}")
    }

    @When("the client calls MCP tool {string} with profile {string}")
    fun callToolWithProfile(toolName: String, profile: String) {
        callTool(toolName, """{"profile": "$profile"}""")
    }

    @When("the client calls MCP tool {string} with profile {string} and vhost {string}")
    fun callToolWithProfileAndVhost(toolName: String, profile: String, vhost: String) {
        callTool(toolName, """{"profile": "$profile", "vhost": "$vhost"}""")
    }

    private fun callTool(name: String, argsJson: String) {
        val request = """
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "$name", "arguments": $argsJson}}
        """.trimIndent()
        val response = server.handleRequest(request)
        assertNotNull(response)
        val respObj = json.parseToJsonElement(response).jsonObject
        lastResponseJson = respObj
        val result = respObj["result"]?.jsonObject
        if (result != null) {
            lastToolIsError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
            val contentArr = result["content"]?.jsonArray
            lastToolResultText = contentArr?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
        } else {
            lastToolIsError = true
            lastToolResultText = respObj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: ""
        }
    }

    @Then("the tool result lists profile {string} with TLS on")
    fun assertProfileTlsOn(profile: String) {
        assertTrue(lastToolResultText.contains(profile))
        assertTrue(lastToolResultText.contains("\"tls\":true") || lastToolResultText.contains("\"tls\": true") || lastToolResultText.contains("TLS: on"))
    }

    @Then("the tool result lists profile {string} with TLS off")
    fun assertProfileTlsOff(profile: String) {
        assertTrue(lastToolResultText.contains(profile))
        assertTrue(lastToolResultText.contains("\"tls\":false") || lastToolResultText.contains("\"tls\": false") || lastToolResultText.contains("TLS: off"))
    }

    @Then("the tool result contains no passwords or secret tokens")
    fun assertNoSecrets() {
        assertFalse(lastToolResultText.contains("password", ignoreCase = true))
        assertFalse(lastToolResultText.contains("secret", ignoreCase = true))
    }

    @Then("the tool result reports reachability {string}")
    fun assertReachability(status: String) {
        assertTrue(lastToolResultText.contains(status, ignoreCase = true), "Expected $status in $lastToolResultText")
    }

    @Then("the tool result reports topology level {string}")
    fun assertTopologyLevel(level: String) {
        assertTrue(lastToolResultText.contains(level, ignoreCase = true), "Expected $level in $lastToolResultText")
    }

    @Then("the tool result includes stream {string} with {long} messages and retention {string}")
    fun assertStreamResult(stream: String, count: Long, retention: String) {
        assertTrue(lastToolResultText.contains(stream))
        assertTrue(lastToolResultText.contains(count.toString()))
        assertTrue(lastToolResultText.contains(retention))
    }

    @Then("the tool result includes queue {string} of type {string} with {long} messages")
    fun assertQueueResult(queue: String, type: String, messages: Long) {
        assertTrue(lastToolResultText.contains(queue))
        assertTrue(lastToolResultText.contains(type))
        assertTrue(lastToolResultText.contains(messages.toString()))
    }

    @Then("the tool result includes exchange {string} of type {string}")
    fun assertExchangeResult(exchange: String, type: String) {
        assertTrue(lastToolResultText.contains(exchange))
        assertTrue(lastToolResultText.contains(type))
    }

    @When("the client calls an unknown MCP tool {string}")
    fun callUnknownTool(toolName: String) {
        val request = """{"jsonrpc": "2.0", "id": 99, "method": "unknown_rpc_method"}"""
        val response = server.handleRequest(request)
        assertNotNull(response)
        lastResponseJson = json.parseToJsonElement(response).jsonObject
    }

    @Then("the response is an MCP error with code {int} and message {string}")
    fun assertMcpError(expectedCode: Int, expectedMsg: String) {
        val error = lastResponseJson?.get("error")?.jsonObject
        assertNotNull(error)
        assertEquals(expectedCode, error["code"]?.jsonPrimitive?.intOrNull)
        assertEquals(expectedMsg, error["message"]?.jsonPrimitive?.content)
    }

    @Then("the tool result indicates an error {string}")
    fun assertToolError(expectedError: String) {
        assertTrue(lastToolIsError)
        assertTrue(lastToolResultText.contains(expectedError, ignoreCase = true),
            "Expected $expectedError in $lastToolResultText")
    }
}
