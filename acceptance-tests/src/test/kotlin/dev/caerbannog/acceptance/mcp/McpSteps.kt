package dev.caerbannog.acceptance.mcp

import dev.caerbannog.acceptance.connections.FakeBrokerReachabilityChecker
import dev.caerbannog.acceptance.connections.InMemoryConnectionProfileRepository
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeMqttCatalog
import dev.caerbannog.core.connections.fakes.FakeMqttMessagePublisher
import dev.caerbannog.core.mqtt.MqttClientRow
import dev.caerbannog.core.mqtt.MqttHeader
import dev.caerbannog.core.mqtt.MqttSubscription
import dev.caerbannog.core.streams.Retention
import dev.caerbannog.core.streams.StreamSummary
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.mcp.McpServer
import io.cucumber.datatable.DataTable
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
    private val fakeStreamReader = FakeStreamMessageReader()
    private val fakeTopologyCatalog = FakeTopologyCatalog()
    private val fakeMqttCatalog = FakeMqttCatalog()
    private val fakeMqttPublisher = FakeMqttMessagePublisher()

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
            streamMessageReaderProvider = { _, _ -> fakeStreamReader },
            topologyCatalogProvider = { _, _ -> fakeTopologyCatalog },
            mqttCatalogProvider = { _, _ -> fakeMqttCatalog },
            mqttMessagePublisherProvider = { _, _ -> fakeMqttPublisher },
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

    // M5-07: a request line nested too deep for the JSON tree reader must come back as a parse error, not kill the server.
    private var lastRequestLine: String = ""
    private var lastRawResponse: String? = null

    @When("the client sends an MCP request whose params are nested {int} levels deep")
    fun sendDeeplyNestedRequest(depth: Int) {
        lastRequestLine = """{"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": ${"[".repeat(depth)}${"]".repeat(depth)}}"""
        lastRawResponse = server.handleRequest(lastRequestLine)
        assertNotNull(lastRawResponse, "a request that cannot be parsed is answered")
        lastResponseJson = json.parseToJsonElement(lastRawResponse!!).jsonObject
    }

    @Then("the response is a JSON-RPC error with code {int}")
    fun assertJsonRpcErrorCode(expectedCode: Int) {
        val error = lastResponseJson?.get("error")?.jsonObject
        assertNotNull(error, "expected an error object in $lastRawResponse")
        assertEquals(expectedCode, error["code"]?.jsonPrimitive?.intOrNull)
        assertEquals("Parse error", error["message"]?.jsonPrimitive?.content)
        assertTrue(lastResponseJson!!.containsKey("id"), "JSON-RPC 2.0: the key id must be present in $lastRawResponse")
        assertEquals(kotlinx.serialization.json.JsonNull, lastResponseJson!!["id"], "the id must be JSON null in $lastRawResponse")
    }

    @Then("the error message does not contain any part of the request")
    fun assertErrorDoesNotEchoRequest() {
        val message = lastResponseJson?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.content.orEmpty()
        assertFalse(message.contains("[[["), "the message echoes the request: $message")
        assertFalse(message.contains("tools/call"), "the message echoes the request: $message")
        assertFalse(lastRawResponse.orEmpty().contains("[[["), "the response echoes the request")
    }

    @Then("the server answers the next valid request")
    fun assertServerAnswersNextRequest() {
        val response = server.handleRequest("""{"jsonrpc": "2.0", "id": 6, "method": "ping"}""")
        assertNotNull(response, "the server did not answer the next request")
        val obj = json.parseToJsonElement(response).jsonObject
        assertEquals(6, obj["id"]?.jsonPrimitive?.intOrNull)
        assertNotNull(obj["result"])
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

    @Given("the profile {string} has MQTT port {int} and an active client {string} subscribed to {string} with QoS {int}")
    fun profileHasMqttAndActiveClient(profileName: String, port: Int, clientId: String, topic: String, qos: Int) {
        val existing = profileRepository.list().first { it.name == profileName }
        profileRepository.update(existing.copy(mqttPort = port))
        fakeMqttCatalog.clients.add(
            MqttClientRow(
                clientId = clientId,
                connectionName = null,
                vhost = existing.virtualHost,
                username = existing.username,
                peerHost = "127.0.0.1",
                peerPort = 50000,
                protocol = "MQTT 3.1.1",
                connectedAt = 1000L,
                ssl = false,
                cleanSession = true,
                subscriptions = listOf(MqttSubscription(topic, qos = qos)),
                connected = true,
            )
        )
    }

    @Given("the profile {string} has MQTT port {int} and topology control level {string}")
    fun profileHasMqttAndTopologyControlLevel(profileName: String, port: Int, levelDisplayName: String) {
        val existing = profileRepository.list().first { it.name == profileName }
        val level = when (levelDisplayName) {
            "Full control" -> TopologyControlLevel.FULL_CONTROL
            "Declare and edit" -> TopologyControlLevel.DECLARE_AND_EDIT
            else -> TopologyControlLevel.READ_ONLY
        }
        profileRepository.update(existing.copy(mqttPort = port, topologyControlLevel = level))
    }

    @When("the client calls MCP tool {string} with profile {string}, topic {string}, payload {string}, qos {int}")
    fun callPublishMqtt(toolName: String, profile: String, topic: String, payload: String, qos: Int) {
        callTool(toolName, """{"profile": "$profile", "topic": "$topic", "payload": "$payload", "qos": $qos}""")
    }

    @When("the client calls MCP tool {string} with profile {string}, topic {string} and payload {string} and these headers")
    fun callPublishMqttWithHeaders(toolName: String, profile: String, topic: String, payload: String, table: DataTable) {
        val headers = table.asLists().joinToString(", ") { "\"${it[0]}\": \"${it[1]}\"" }
        callTool(toolName, """{"profile": "$profile", "topic": "$topic", "payload": "$payload", "headers": {$headers}}""")
    }

    @When("the client calls MCP tool {string} with profile {string}, topic {string} and payload {string} and the raw headers argument {string}")
    fun callPublishMqttWithRawHeaders(toolName: String, profile: String, topic: String, payload: String, rawHeaders: String) {
        callTool(toolName, """{"profile": "$profile", "topic": "$topic", "payload": "$payload", "headers": $rawHeaders}""")
    }

    @Then("the published MQTT message carries these headers")
    fun assertPublishedHeaders(table: DataTable) {
        assertEquals(table.asLists().map { MqttHeader(it[0], it[1]) }, fakeMqttPublisher.publishedMessages.single().headers)
    }

    @Then("no MQTT message was published")
    fun assertNoMqttPublished() {
        assertTrue(fakeMqttPublisher.publishedMessages.isEmpty(), "published: ${fakeMqttPublisher.publishedMessages}")
    }

    @Then("the tool result includes MQTT client {string} with subscription {string}")
    fun assertMqttClientIncluded(clientId: String, topic: String) {
        assertFalse(lastToolIsError, "Expected success but was error: $lastToolResultText")
        assertTrue(lastToolResultText.contains(clientId), "Missing clientId $clientId in $lastToolResultText")
        assertTrue(lastToolResultText.contains(topic), "Missing topic $topic in $lastToolResultText")
    }

    @Then("the tool result indicates a successful MQTT publish to {string}")
    fun assertMqttPublishSuccess(topic: String) {
        assertFalse(lastToolIsError, "Expected success but was error: $lastToolResultText")
        assertTrue(lastToolResultText.contains("\"success\":true") || lastToolResultText.contains("\"success\": true"))
        assertTrue(lastToolResultText.contains(topic))
    }

    // --- M9-00b-c: the stream message tool returns list and map values as readable strings ---

    @Given("^the stream \"(.*)\" on vhost \"(.*)\" holds a dead letter with the annotation \"(.*)\" holding one death entry$")
    fun streamHoldsDeadLetter(stream: String, vhost: String, annotation: String, table: DataTable) {
        val entry = LinkedHashMap<String, Any?>()
        for ((k, v) in table.asMap(String::class.java, String::class.java)) entry[k] = v.toLongOrNull() ?: v
        // an Object[] holding one map: what the stream client hands over for x-opt-deaths
        fakeStreamReader.addMessage(
            vhost = vhost, stream = stream, offset = 0L, timestamp = 1700000000000L,
            payload = "to-the-stream".encodeToByteArray(),
            properties = mapOf(annotation to arrayOf<Any?>(entry)),
        )
    }

    @When("the client calls MCP tool {string} with profile {string} and stream {string}")
    fun callToolWithProfileAndStream(toolName: String, profile: String, stream: String) {
        callTool(toolName, """{"profile": "$profile", "stream": "$stream"}""")
    }

    private fun firstMessageProperties(): JsonObject {
        assertFalse(lastToolIsError, "the tool reported an error: $lastToolResultText")
        val messages = json.parseToJsonElement(lastToolResultText).jsonObject["messages"]?.jsonArray
        assertNotNull(messages, "no messages in $lastToolResultText")
        return messages.first().jsonObject["properties"]!!.jsonObject
    }

    @Then("the tool result shows property {string} of the first message as a string containing {string}")
    fun assertPropertyStringContaining(name: String, fragment: String) {
        val value = firstMessageProperties()[name]?.jsonPrimitive
        assertNotNull(value, "no property $name in ${firstMessageProperties()}")
        assertTrue(value.isString, "property $name is not a JSON string: $value")
        assertTrue(value.content.contains(fragment), "expected property $name to contain '$fragment' but it was '${value.content}'")
    }

    @Then("no property value of the tool result contains {string}")
    fun assertNoPropertyValueContains(text: String) {
        assertFalse(lastToolResultText.contains(text), "the tool result contains '$text': $lastToolResultText")
    }
}
