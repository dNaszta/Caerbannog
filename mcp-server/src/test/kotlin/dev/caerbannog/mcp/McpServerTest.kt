package dev.caerbannog.mcp

import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileRepository
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeMessagePublisher
import dev.caerbannog.core.connections.fakes.FakeMqttCatalog
import dev.caerbannog.core.connections.fakes.FakeMqttMessagePublisher
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.mqtt.MqttClientRow
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.MqttSubscription
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
    private val streamReader = FakeStreamMessageReader()
    private val publisher = FakeMessagePublisher()
    private val mqttCatalog = FakeMqttCatalog()
    private val mqttPublisher = FakeMqttMessagePublisher()

    private val server = McpServer(
        profileRepository = repo,
        connectionChecker = checker,
        streamCatalogProvider = { _, _ -> streamCatalog },
        streamOffsetsProvider = { _, _ -> streamOffsets },
        topologyCatalogProvider = { _, _ -> topologyCatalog },
        streamMessageReaderProvider = { _, _ -> streamReader },
        messagePublisherProvider = { _, _ -> publisher },
        mqttCatalogProvider = { _, _ -> mqttCatalog },
        mqttMessagePublisherProvider = { _, _ -> mqttPublisher },
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
        assertTrue(toolNames.contains("caerbannog_browse_stream"))
        assertTrue(toolNames.contains("caerbannog_publish_test_message"))
        assertTrue(toolNames.contains("caerbannog_list_mqtt_clients"))
        assertTrue(toolNames.contains("caerbannog_publish_mqtt"))

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

    @Test
    fun `browse stream returns paged messages and bounds`() {
        repo.add(
            ConnectionProfile(
                name = "reader-prof",
                host = "localhost",
                virtualHost = "/",
                username = "guest",
                tls = true
            )
        )
        streamReader.seed(
            "/",
            "invoices",
            (0 until 50).map { i ->
                StreamMessage(
                    offset = i.toLong(),
                    timestamp = 1700000000000L + i * 1000L,
                    payload = """{"invoiceId": "INV-$i"}""".toByteArray(),
                    properties = mapOf("contentType" to "application/json"),
                    applicationProperties = mapOf("traceId" to "trc-$i")
                )
            }
        )

        val req = """{"jsonrpc": "2.0", "id": 20, "method": "tools/call", "params": {"name": "caerbannog_browse_stream", "arguments": {"profile": "reader-prof", "stream": "invoices", "start": "first", "limit": 10}}}"""
        val resp = server.handleRequest(req)
        assertNotNull(resp)
        val text = json.parseToJsonElement(resp!!).jsonObject["result"]?.jsonObject
            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()

        assertTrue(text.contains("\"count\":10") || text.contains("\"count\": 10"))
        assertTrue(text.contains("\"streamFirstOffset\":0") || text.contains("\"streamFirstOffset\": 0"))
        assertTrue(text.contains("\"streamLastOffset\":49") || text.contains("\"streamLastOffset\": 49"))
        assertTrue(text.contains("INV-0"))
        assertTrue(text.contains("INV-9"))
        assertFalse(text.contains("INV-10"))
    }

    @Test
    fun `browse stream with offset outside range returns error`() {
        repo.add(
            ConnectionProfile(
                name = "reader-prof-2",
                host = "localhost",
                virtualHost = "/",
                username = "guest",
                tls = true
            )
        )
        streamReader.seed(
            "/",
            "invoices",
            listOf(
                StreamMessage(0L, 1000L, "hello".toByteArray(), emptyMap(), emptyMap())
            )
        )

        val req = """{"jsonrpc": "2.0", "id": 21, "method": "tools/call", "params": {"name": "caerbannog_browse_stream", "arguments": {"profile": "reader-prof-2", "stream": "invoices", "start": "100"}}}"""
        val resp = server.handleRequest(req)
        assertNotNull(resp)
        val isError = json.parseToJsonElement(resp!!).jsonObject["result"]?.jsonObject
            ?.get("isError")?.jsonPrimitive?.booleanOrNull
        assertTrue(isError == true)
        val text = json.parseToJsonElement(resp).jsonObject["result"]?.jsonObject
            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
        assertTrue(text.contains("outside range"))
    }

    @Test
    fun `browse stream payload is truncated safely when exceeding max length`() {
        repo.add(
            ConnectionProfile(
                name = "reader-prof-3",
                host = "localhost",
                virtualHost = "/",
                username = "guest",
                tls = true
            )
        )
        val bigPayload = "X".repeat(10_000)
        streamReader.seed(
            "/",
            "big-stream",
            listOf(
                StreamMessage(0L, 1000L, bigPayload.toByteArray(), emptyMap(), emptyMap())
            )
        )

        val req = """{"jsonrpc": "2.0", "id": 22, "method": "tools/call", "params": {"name": "caerbannog_browse_stream", "arguments": {"profile": "reader-prof-3", "stream": "big-stream"}}}"""
        val resp = server.handleRequest(req)
        assertNotNull(resp)
        val text = json.parseToJsonElement(resp!!).jsonObject["result"]?.jsonObject
            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
        assertTrue(text.contains("\"truncated\":true") || text.contains("\"truncated\": true"))
        assertTrue(text.contains("\"payloadSize\":10000") || text.contains("\"payloadSize\": 10000"))
    }

    @Test
    fun `browse stream with filterText returns only matching messages`() {
        repo.add(
            ConnectionProfile(
                name = "reader-prof-filter",
                host = "localhost",
                virtualHost = "/",
                username = "guest",
                tls = true
            )
        )
        streamReader.seed(
            "/",
            "search-stream",
            listOf(
                StreamMessage(0L, 1000L, "first order".toByteArray(), emptyMap(), emptyMap()),
                StreamMessage(1L, 1001L, "second order with TARGET".toByteArray(), emptyMap(), emptyMap()),
                StreamMessage(2L, 1002L, "third order".toByteArray(), emptyMap(), emptyMap()),
            )
        )

        val req = """{"jsonrpc": "2.0", "id": 23, "method": "tools/call", "params": {"name": "caerbannog_browse_stream", "arguments": {"profile": "reader-prof-filter", "stream": "search-stream", "filterText": "TARGET"}}}"""
        val resp = server.handleRequest(req)
        assertNotNull(resp)
        val text = json.parseToJsonElement(resp!!).jsonObject["result"]?.jsonObject
            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
        assertTrue(text.contains("\"count\":1") || text.contains("\"count\": 1"))
        assertTrue(text.contains("TARGET"))
        assertFalse(text.contains("third order"))
    }

    @Test
    fun `publish test message tool sends message to publisher and returns confirmation`() {
        repo.add(
            ConnectionProfile(
                name = "publish-prof",
                host = "localhost",
                virtualHost = "/",
                username = "guest",
                tls = true,
                topologyControlLevel = TopologyControlLevel.DECLARE_AND_EDIT,
            )
        )

        val req = """{"jsonrpc": "2.0", "id": 30, "method": "tools/call", "params": {"name": "caerbannog_publish_test_message", "arguments": {"profile": "publish-prof", "targetKind": "stream", "targetName": "orders-events", "payload": "{\"orderId\":\"TEST-1\"}", "messageId": "msg-1", "applicationProperties": {"tenant": "eu"}}}}"""
        val resp = server.handleRequest(req)
        assertNotNull(resp)
        val text = json.parseToJsonElement(resp!!).jsonObject["result"]?.jsonObject
            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()

        assertTrue(text.contains("\"success\":true") || text.contains("\"success\": true"))
        assertTrue(text.contains("Message published to stream 'orders-events'"))

        assertEquals(1, publisher.publishedMessages.size)
        val sent = publisher.publishedMessages.single()
        assertEquals("orders-events", sent.target.name)
        assertEquals("""{"orderId":"TEST-1"}""", sent.payload.decodeToString())
        assertEquals("msg-1", sent.properties["messageId"])
        assertEquals("eu", sent.applicationProperties["tenant"])
    }

    @Test
    fun `publish test message tool is refused when profile is READ_ONLY`() {
        repo.add(
            ConnectionProfile(
                name = "readonly-prof",
                host = "localhost",
                virtualHost = "/",
                username = "guest",
                tls = true,
                topologyControlLevel = TopologyControlLevel.READ_ONLY,
            )
        )

        val req = """{"jsonrpc": "2.0", "id": 31, "method": "tools/call", "params": {"name": "caerbannog_publish_test_message", "arguments": {"profile": "readonly-prof", "targetKind": "stream", "targetName": "orders-events", "payload": "test"}}}"""
        val resp = server.handleRequest(req)
        assertNotNull(resp)
        val isError = json.parseToJsonElement(resp!!).jsonObject["result"]?.jsonObject
            ?.get("isError")?.jsonPrimitive?.booleanOrNull == true
        assertTrue(isError)
        val text = json.parseToJsonElement(resp).jsonObject["result"]?.jsonObject
            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
        assertTrue(text.contains("Read-only"))
    }

    @Test
    fun `list mqtt clients returns client details and subscriptions`() {
        repo.add(
            ConnectionProfile(
                name = "mqtt-prof",
                host = "localhost",
                virtualHost = "/",
                username = "guest",
                tls = true,
                mqttPort = 1883,
            )
        )
        mqttCatalog.clients.addAll(
            listOf(
                MqttClientRow(
                    clientId = "sensor-device-1",
                    connectionName = "127.0.0.1:50000 -> 127.0.0.1:1883",
                    vhost = "/",
                    username = "sensor-user",
                    peerHost = "127.0.0.1",
                    peerPort = 50000,
                    protocol = "MQTT 3.1.1",
                    connectedAt = 1000L,
                    ssl = false,
                    cleanSession = true,
                    subscriptions = listOf(MqttSubscription("sensors/temperature", qos = 1)),
                    connected = true,
                )
            )
        )

        val req = """{"jsonrpc": "2.0", "id": 40, "method": "tools/call", "params": {"name": "caerbannog_list_mqtt_clients", "arguments": {"profile": "mqtt-prof"}}}"""
        val resp = server.handleRequest(req)
        assertNotNull(resp)
        val text = json.parseToJsonElement(resp!!).jsonObject["result"]?.jsonObject
            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()

        assertTrue(text.contains("\"clientCount\":1") || text.contains("\"clientCount\": 1"))
        assertTrue(text.contains("sensor-device-1"))
        assertTrue(text.contains("sensors/temperature"))
        assertTrue(text.contains("\"qos\":1") || text.contains("\"qos\": 1"))
    }

    @Test
    fun `list mqtt clients returns error when profile has no mqttPort`() {
        repo.add(
            ConnectionProfile(
                name = "no-mqtt-prof",
                host = "localhost",
                virtualHost = "/",
                username = "guest",
                tls = true,
                mqttPort = null,
            )
        )

        val req = """{"jsonrpc": "2.0", "id": 41, "method": "tools/call", "params": {"name": "caerbannog_list_mqtt_clients", "arguments": {"profile": "no-mqtt-prof"}}}"""
        val resp = server.handleRequest(req)
        assertNotNull(resp)
        val isError = json.parseToJsonElement(resp!!).jsonObject["result"]?.jsonObject
            ?.get("isError")?.jsonPrimitive?.booleanOrNull == true
        assertTrue(isError)
        val text = json.parseToJsonElement(resp).jsonObject["result"]?.jsonObject
            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
        assertTrue(text.contains("mqttPort is null"))
    }

    @Test
    fun `publish mqtt tool publishes message with QoS and retain`() {
        repo.add(
            ConnectionProfile(
                name = "mqtt-pub-prof",
                host = "localhost",
                virtualHost = "/",
                username = "guest",
                tls = true,
                mqttPort = 1883,
                topologyControlLevel = TopologyControlLevel.DECLARE_AND_EDIT,
            )
        )

        val req = """{"jsonrpc": "2.0", "id": 42, "method": "tools/call", "params": {"name": "caerbannog_publish_mqtt", "arguments": {"profile": "mqtt-pub-prof", "topic": "factory/sensors/temperature", "payload": "24.5", "qos": 1, "retain": true}}}"""
        val resp = server.handleRequest(req)
        assertNotNull(resp)
        val text = json.parseToJsonElement(resp!!).jsonObject["result"]?.jsonObject
            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()

        assertTrue(text.contains("\"success\":true") || text.contains("\"success\": true"))
        assertTrue(text.contains("factory/sensors/temperature"))

        assertEquals(1, mqttPublisher.publishedMessages.size)
        val sent = mqttPublisher.publishedMessages.single()
        assertEquals("factory/sensors/temperature", sent.topic)
        assertEquals("24.5", sent.payload.decodeToString())
        assertEquals(MqttQos.AT_LEAST_ONCE, sent.qos)
        assertTrue(sent.retain)
    }

    @Test
    fun `publish mqtt tool refuses wildcards in topic`() {
        repo.add(
            ConnectionProfile(
                name = "mqtt-wildcard-prof",
                host = "localhost",
                virtualHost = "/",
                username = "guest",
                tls = true,
                mqttPort = 1883,
                topologyControlLevel = TopologyControlLevel.DECLARE_AND_EDIT,
            )
        )

        val req = """{"jsonrpc": "2.0", "id": 43, "method": "tools/call", "params": {"name": "caerbannog_publish_mqtt", "arguments": {"profile": "mqtt-wildcard-prof", "topic": "factory/+/temp", "payload": "24.5"}}}"""
        val resp = server.handleRequest(req)
        assertNotNull(resp)
        val isError = json.parseToJsonElement(resp!!).jsonObject["result"]?.jsonObject
            ?.get("isError")?.jsonPrimitive?.booleanOrNull == true
        assertTrue(isError)
        val text = json.parseToJsonElement(resp).jsonObject["result"]?.jsonObject
            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
        assertTrue(text.contains("Topic cannot contain wildcards"))
    }
}
