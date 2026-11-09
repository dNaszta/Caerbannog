package dev.caerbannog.mcp

import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.MessageFilterCriteria
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.core.browser.StreamMessageReader
import dev.caerbannog.core.browser.StreamScanner
import dev.caerbannog.core.publish.MessagePublisher
import dev.caerbannog.core.publish.PublishMessageRequest
import dev.caerbannog.core.publish.PublishOutcome
import dev.caerbannog.core.publish.PublishTarget
import dev.caerbannog.core.publish.PublishTargetKind
import dev.caerbannog.core.mqtt.MqttCatalog
import dev.caerbannog.core.mqtt.MqttClientRow
import dev.caerbannog.core.mqtt.MqttMessagePublisher
import dev.caerbannog.core.mqtt.MqttHeader
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.MqttSubscription
import dev.caerbannog.core.mqtt.PublishMqttRequest
import kotlinx.coroutines.flow.toList
import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileRepository
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.streams.StreamOffsets
import dev.caerbannog.core.streams.StreamSummary
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintStream

private fun TopologyControlLevel.toDisplayName(): String = when (this) {
    TopologyControlLevel.READ_ONLY -> "Read-only"
    TopologyControlLevel.DECLARE_AND_EDIT -> "Declare and edit"
    TopologyControlLevel.FULL_CONTROL -> "Full control"
}

class McpServer(
    val profileRepository: ConnectionProfileRepository,
    val connectionChecker: BrokerReachabilityChecker,
    val streamCatalogProvider: (profile: ConnectionProfile, vhost: String) -> StreamCatalog,
    val streamOffsetsProvider: (profile: ConnectionProfile, vhost: String) -> StreamOffsets,
    val topologyCatalogProvider: (profile: ConnectionProfile, vhost: String) -> TopologyCatalog,
    val streamMessageReaderProvider: ((profile: ConnectionProfile, vhost: String) -> StreamMessageReader)? = null,
    val messagePublisherProvider: ((profile: ConnectionProfile, vhost: String) -> MessagePublisher)? = null,
    val mqttCatalogProvider: ((profile: ConnectionProfile, vhost: String) -> MqttCatalog)? = null,
    val mqttMessagePublisherProvider: ((profile: ConnectionProfile, vhost: String) -> MqttMessagePublisher)? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    private val toolsList: List<McpTool> = listOf(
        McpTool(
            name = "caerbannog_list_profiles",
            description = "List all configured RabbitMQ connection profiles without exposing passwords or secrets.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            }
        ),
        McpTool(
            name = "caerbannog_check_connection",
            description = "Test reachability, TLS compatibility, and permission levels for a configured connection profile.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("profile") {
                        put("type", "string")
                        put("description", "Name of the connection profile to check")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("profile")) }
            }
        ),
        McpTool(
            name = "caerbannog_list_streams",
            description = "List RabbitMQ streams in a virtual host, including approximate message count, offsets, and retention.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("profile") {
                        put("type", "string")
                        put("description", "Connection profile name")
                    }
                    putJsonObject("vhost") {
                        put("type", "string")
                        put("description", "Virtual host (defaults to profile's configured virtual host)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("profile")) }
            }
        ),
        McpTool(
            name = "caerbannog_get_stream_details",
            description = "Get detailed statistics, node placement, and retention settings for a specific stream.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("profile") {
                        put("type", "string")
                        put("description", "Connection profile name")
                    }
                    putJsonObject("stream") {
                        put("type", "string")
                        put("description", "Stream name")
                    }
                    putJsonObject("vhost") {
                        put("type", "string")
                        put("description", "Virtual host")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("profile"))
                    add(JsonPrimitive("stream"))
                }
            }
        ),
        McpTool(
            name = "caerbannog_list_queues",
            description = "List quorum and classic queues in a virtual host (names, message counts, leaders, health). Note: queue messages are never read or purged.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("profile") {
                        put("type", "string")
                        put("description", "Connection profile name")
                    }
                    putJsonObject("vhost") {
                        put("type", "string")
                        put("description", "Virtual host")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("profile")) }
            }
        ),
        McpTool(
            name = "caerbannog_list_exchanges",
            description = "List exchanges in a virtual host.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("profile") {
                        put("type", "string")
                        put("description", "Connection profile name")
                    }
                    putJsonObject("vhost") {
                        put("type", "string")
                        put("description", "Virtual host")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("profile")) }
            }
        ),
        McpTool(
            name = "caerbannog_list_bindings",
            description = "List bindings between exchanges and queues in a virtual host.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("profile") {
                        put("type", "string")
                        put("description", "Connection profile name")
                    }
                    putJsonObject("vhost") {
                        put("type", "string")
                        put("description", "Virtual host")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("profile")) }
            }
        ),
        McpTool(
            name = "caerbannog_browse_stream",
            description = "Browse messages of a RabbitMQ stream page-by-page starting from first, last, offset, or timestamp without disturbing real consumers. Uses an ephemeral consumer with noTrackingStrategy.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("profile") {
                        put("type", "string")
                        put("description", "Connection profile name")
                    }
                    putJsonObject("stream") {
                        put("type", "string")
                        put("description", "Stream name")
                    }
                    putJsonObject("vhost") {
                        put("type", "string")
                        put("description", "Virtual host (defaults to profile's configured virtual host)")
                    }
                    putJsonObject("start") {
                        put("type", "string")
                        put("description", "Start position: 'first', 'last', numeric offset (e.g. '100'), or timestamp in ms. Default: 'first'")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "Maximum messages to return (1..100, default 20)")
                    }
                    putJsonObject("filterText") {
                        put("type", "string")
                        put("description", "Filter by text within payload (case-insensitive substring match)")
                    }
                    putJsonObject("appPropertyKey") {
                        put("type", "string")
                        put("description", "Application property key to filter by")
                    }
                    putJsonObject("appPropertyValue") {
                        put("type", "string")
                        put("description", "Application property value to filter by")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("profile"))
                    add(JsonPrimitive("stream"))
                }
            }
        ),
        McpTool(
            name = "caerbannog_publish_test_message",
            description = "Publish a single test message to a RabbitMQ stream, exchange, or queue on a configured profile.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("profile") {
                        put("type", "string")
                        put("description", "Connection profile name")
                    }
                    putJsonObject("targetKind") {
                        put("type", "string")
                        put("description", "Target kind: 'stream', 'exchange', or 'queue'")
                    }
                    putJsonObject("targetName") {
                        put("type", "string")
                        put("description", "Target stream, exchange, or queue name")
                    }
                    putJsonObject("vhost") {
                        put("type", "string")
                        put("description", "Virtual host (defaults to profile's configured virtual host)")
                    }
                    putJsonObject("routingKey") {
                        put("type", "string")
                        put("description", "Routing key (for exchanges, optional)")
                    }
                    putJsonObject("payload") {
                        put("type", "string")
                        put("description", "Message body payload")
                    }
                    putJsonObject("messageId") {
                        put("type", "string")
                        put("description", "Standard message-id property (optional)")
                    }
                    putJsonObject("contentType") {
                        put("type", "string")
                        put("description", "Standard content-type property (optional, default 'application/json')")
                    }
                    putJsonObject("applicationProperties") {
                        put("type", "object")
                        put("description", "Key-value application properties (optional)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("profile"))
                    add(JsonPrimitive("targetKind"))
                    add(JsonPrimitive("targetName"))
                    add(JsonPrimitive("payload"))
                }
            }
        ),
        McpTool(
            name = "caerbannog_list_mqtt_clients",
            description = "List active MQTT client sessions, connected protocols, peer addresses, and topic subscriptions with QoS levels on a configured connection profile.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("profile") {
                        put("type", "string")
                        put("description", "Connection profile name")
                    }
                    putJsonObject("vhost") {
                        put("type", "string")
                        put("description", "Virtual host (defaults to profile's configured virtual host)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("profile"))
                }
            }
        ),
        McpTool(
            name = "caerbannog_publish_mqtt",
            description = "Publish a test message over MQTT with QoS and retain flags to a configured connection profile.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("profile") {
                        put("type", "string")
                        put("description", "Connection profile name")
                    }
                    putJsonObject("topic") {
                        put("type", "string")
                        put("description", "MQTT topic path (cannot contain wildcards '+' or '#')")
                    }
                    putJsonObject("payload") {
                        put("type", "string")
                        put("description", "Message payload text or JSON")
                    }
                    putJsonObject("qos") {
                        put("type", "integer")
                        put("description", "Quality of Service level: 0 (At most once), 1 (At least once), or 2 (Exactly once). Defaults to 0.")
                    }
                    putJsonObject("retain") {
                        put("type", "boolean")
                        put("description", "Whether the broker should retain this message. Defaults to false.")
                    }
                    putJsonObject("headers") {
                        put("type", "object")
                        put("description", "Optional message headers, sent as MQTT 5 user properties (string keys and string values). Requires RabbitMQ 4+.")
                        putJsonObject("additionalProperties") { put("type", "string") }
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("profile"))
                    add(JsonPrimitive("topic"))
                    add(JsonPrimitive("payload"))
                }
            }
        )
    )

    fun handleRequest(requestString: String): String? = runBlocking {
        try {
            val req = json.decodeFromString<JsonRpcRequest>(requestString)
            when (req.method) {
                "initialize" -> {
                    val result = McpInitializeResult(
                        protocolVersion = "2024-11-05",
                        capabilities = buildJsonObject {
                            putJsonObject("tools") {}
                        },
                        serverInfo = McpServerInfo(
                            name = "caerbannog-mcp",
                            version = "1.0.0"
                        )
                    )
                    json.encodeToString(
                        JsonRpcResponse(
                            id = req.id,
                            result = json.encodeToJsonElement(result)
                        )
                    )
                }
                "notifications/initialized" -> {
                    null // Notification requires no response
                }
                "ping" -> {
                    json.encodeToString(
                        JsonRpcResponse(
                            id = req.id,
                            result = buildJsonObject {}
                        )
                    )
                }
                "tools/list" -> {
                    val result = McpToolsListResult(tools = toolsList)
                    json.encodeToString(
                        JsonRpcResponse(
                            id = req.id,
                            result = json.encodeToJsonElement(result)
                        )
                    )
                }
                "tools/call" -> {
                    val params = req.params?.jsonObject
                    val toolName = params?.get("name")?.jsonPrimitive?.content
                    val arguments = params?.get("arguments")?.jsonObject ?: buildJsonObject {}
                    val toolResult = executeTool(toolName, arguments)
                    json.encodeToString(
                        JsonRpcResponse(
                            id = req.id,
                            result = json.encodeToJsonElement(toolResult)
                        )
                    )
                }
                else -> {
                    json.encodeToString(
                        JsonRpcResponse(
                            id = req.id,
                            error = JsonRpcError(
                                code = -32601,
                                message = "Method not found"
                            )
                        )
                    )
                }
            }
        } catch (e: Exception) {
            System.err.println("Error processing MCP request: ${e.message}")
            json.encodeToString(
                JsonRpcResponse(
                    error = JsonRpcError(
                        code = -32603,
                        message = "Internal error: ${e.message}"
                    )
                )
            )
        }
    }

    private suspend fun executeTool(name: String?, args: JsonObject): McpCallToolResult {
        return when (name) {
            "caerbannog_list_profiles" -> listProfiles()
            "caerbannog_check_connection" -> checkConnection(args)
            "caerbannog_list_streams" -> listStreams(args)
            "caerbannog_get_stream_details" -> getStreamDetails(args)
            "caerbannog_list_queues" -> listQueues(args)
            "caerbannog_list_exchanges" -> listExchanges(args)
            "caerbannog_list_bindings" -> listBindings(args)
            "caerbannog_browse_stream" -> browseStream(args)
            "caerbannog_publish_test_message" -> publishTestMessage(args)
            "caerbannog_list_mqtt_clients" -> listMqttClients(args)
            "caerbannog_publish_mqtt" -> publishMqtt(args)
            else -> McpCallToolResult(
                content = listOf(McpContent(text = "Unknown tool: $name")),
                isError = true
            )
        }
    }

    private fun listProfiles(): McpCallToolResult {
        val profiles = profileRepository.list()
        val jsonArray = buildJsonArray {
            for (p in profiles) {
                add(buildJsonObject {
                    put("name", p.name)
                    put("host", p.host)
                    put("virtualHost", p.virtualHost)
                    put("username", p.username)
                    put("streamPort", p.streamPort)
                    put("managementPort", p.managementPort)
                    put("tls", p.tls)
                    put("useTls", p.tls)
                    put("topologyControlLevel", p.topologyControlLevel.name)
                    if (p.caCertificatePath != null) {
                        put("caCertificatePath", p.caCertificatePath)
                    }
                })
            }
        }
        return McpCallToolResult(
            content = listOf(McpContent(text = json.encodeToString(jsonArray)))
        )
    }

    private suspend fun checkConnection(args: JsonObject): McpCallToolResult {
        val profileName = args["profile"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'profile' argument")), isError = true)

        val profile = profileRepository.list().firstOrNull { it.name == profileName }
            ?: return McpCallToolResult(listOf(McpContent(text = "Profile '$profileName' not found")), isError = true)

        val result = connectionChecker.check(profile)
        val isReachable = result is ConnectionCheckResult.BothReachable
        val text = buildString {
            append("Connection check for '${profile.name}':\n")
            append("- Status: ${if (isReachable) "reachable" else "unreachable"}\n")
            append("- Topology Level: ${profile.topologyControlLevel.toDisplayName()}\n")
            append("- TLS: ${if (profile.tls) "on" else "off"}\n")
            when (result) {
                is ConnectionCheckResult.BothReachable -> {
                    append("- Stream Port (${profile.streamPort}): reachable\n")
                    append("- Management Port (${profile.managementPort}): reachable\n")
                }
                is ConnectionCheckResult.EndpointUnreachable -> {
                    append("- Endpoint Unreachable: ${result.endpoint.name} (${result.reason})\n")
                }
                is ConnectionCheckResult.ManagementPluginMissing -> {
                    append("- Management Plugin Missing: ${result.pluginName}\n")
                }
                is ConnectionCheckResult.CertificateRejected -> {
                    append("- Certificate Rejected: Certificate was not trusted\n")
                }
                is ConnectionCheckResult.TlsRefused -> {
                    append("- TLS Refused: Broker did not answer TLS handshake\n")
                }

            }
        }
        return McpCallToolResult(
            content = listOf(McpContent(text = text)),
            isError = !isReachable
        )
    }

    private suspend fun listStreams(args: JsonObject): McpCallToolResult {
        val profileName = args["profile"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'profile' argument")), isError = true)
        val profile = profileRepository.list().firstOrNull { it.name == profileName }
            ?: return McpCallToolResult(listOf(McpContent(text = "Profile '$profileName' not found")), isError = true)

        val vhost = args["vhost"]?.jsonPrimitive?.content ?: profile.virtualHost
        val catalog = streamCatalogProvider(profile, vhost)
        val offsetsProvider = streamOffsetsProvider(profile, vhost)

        val result = catalog.listStreams(StreamListRequest(vhost = vhost, pageSize = 500))
        return when (result) {
            is CatalogResult.Success -> {
                val array = buildJsonArray {
                    for (stream in result.value.rows) {
                        val firstOffset = when (val fo = offsetsProvider.firstOffset(vhost, stream.name)) {
                            is FirstOffset.Value -> fo.offset
                            else -> null
                        }
                        val msgs = stream.messages
                        val lastOffset = if (firstOffset != null && msgs != null && msgs > 0L) {
                            firstOffset + msgs - 1L
                        } else null

                        add(buildJsonObject {
                            put("name", stream.name)
                            put("vhost", stream.vhost)
                            put("messages", msgs ?: 0L)
                            if (firstOffset != null) put("firstOffset", firstOffset)
                            if (lastOffset != null) put("lastOffset", lastOffset)
                            put("retention", stream.retention.maxAge ?: "none")
                            put("leader", stream.leader ?: "none")
                            put("node", stream.node ?: "none")
                        })
                    }
                }
                McpCallToolResult(listOf(McpContent(text = json.encodeToString(array))))
            }
            is CatalogResult.NotFound -> {
                McpCallToolResult(listOf(McpContent(text = "Virtual host '$vhost' not found")), isError = true)
            }
            is CatalogResult.NotAuthorised -> {
                McpCallToolResult(listOf(McpContent(text = "Not authorized to list streams on '$vhost'")), isError = true)
            }
            is CatalogResult.Unreachable -> {
                McpCallToolResult(listOf(McpContent(text = "Broker unreachable")), isError = true)
            }
            else -> {
                McpCallToolResult(listOf(McpContent(text = "Failed to list streams: $result")), isError = true)
            }
        }
    }

    private suspend fun getStreamDetails(args: JsonObject): McpCallToolResult {
        val profileName = args["profile"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'profile' argument")), isError = true)
        val streamName = args["stream"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'stream' argument")), isError = true)
        val profile = profileRepository.list().firstOrNull { it.name == profileName }
            ?: return McpCallToolResult(listOf(McpContent(text = "Profile '$profileName' not found")), isError = true)

        val vhost = args["vhost"]?.jsonPrimitive?.content ?: profile.virtualHost
        val catalog = streamCatalogProvider(profile, vhost)

        val listResult = catalog.listStreams(StreamListRequest(vhost = vhost, nameFilter = streamName))
        if (listResult is CatalogResult.Success) {
            val stream = listResult.value.rows.firstOrNull { it.name == streamName }
            if (stream != null) {
                val detailObj = buildJsonObject {
                    put("name", stream.name)
                    put("vhost", stream.vhost)
                    put("messages", stream.messages ?: 0L)
                    put("consumers", stream.consumers)
                    put("leader", stream.leader ?: "none")
                    put("node", stream.node ?: "none")
                    put("retention", stream.retention.maxAge ?: "none")
                    putJsonArray("members") {
                        for (m in stream.members) add(JsonPrimitive(m))
                    }
                    putJsonArray("online") {
                        for (o in stream.online) add(JsonPrimitive(o))
                    }
                }
                return McpCallToolResult(listOf(McpContent(text = json.encodeToString(detailObj))))
            }
        }
        return McpCallToolResult(listOf(McpContent(text = "Stream '$streamName' not found")), isError = true)
    }

    private suspend fun listQueues(args: JsonObject): McpCallToolResult {
        val profileName = args["profile"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'profile' argument")), isError = true)
        val profile = profileRepository.list().firstOrNull { it.name == profileName }
            ?: return McpCallToolResult(listOf(McpContent(text = "Profile '$profileName' not found")), isError = true)

        val vhost = args["vhost"]?.jsonPrimitive?.content ?: profile.virtualHost
        val catalog = topologyCatalogProvider(profile, vhost)

        val result = catalog.queues(vhost, page = 1)
        return when (result) {
            is CatalogResult.Success -> {
                val array = buildJsonArray {
                    for (q in result.value.rows) {
                        add(buildJsonObject {
                            put("name", q.name)
                            put("vhost", q.vhost)
                            put("type", q.type)
                            put("readyMessages", q.readyMessages ?: 0L)
                            put("unackedMessages", q.unackedMessages ?: 0L)
                            put("consumers", q.consumers ?: 0)
                            put("leader", q.leader ?: "none")
                            put("state", q.state ?: "unknown")
                        })
                    }
                }
                McpCallToolResult(listOf(McpContent(text = json.encodeToString(array))))
            }
            else -> {
                McpCallToolResult(listOf(McpContent(text = "Failed to list queues: $result")), isError = true)
            }
        }
    }

    private suspend fun listExchanges(args: JsonObject): McpCallToolResult {
        val profileName = args["profile"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'profile' argument")), isError = true)
        val profile = profileRepository.list().firstOrNull { it.name == profileName }
            ?: return McpCallToolResult(listOf(McpContent(text = "Profile '$profileName' not found")), isError = true)

        val vhost = args["vhost"]?.jsonPrimitive?.content ?: profile.virtualHost
        val catalog = topologyCatalogProvider(profile, vhost)

        val result = catalog.exchanges(vhost)
        return when (result) {
            is CatalogResult.Success -> {
                val array = buildJsonArray {
                    for (ex in result.value) {
                        add(buildJsonObject {
                            put("name", ex.name)
                            put("vhost", ex.vhost)
                            put("type", ex.type)
                            put("durable", ex.durable)
                            put("autoDelete", ex.autoDelete)
                            put("internal", ex.internal)
                            put("superStream", ex.superStream)
                        })
                    }
                }
                McpCallToolResult(listOf(McpContent(text = json.encodeToString(array))))
            }
            else -> {
                McpCallToolResult(listOf(McpContent(text = "Failed to list exchanges: $result")), isError = true)
            }
        }
    }

    private suspend fun listBindings(args: JsonObject): McpCallToolResult {
        val profileName = args["profile"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'profile' argument")), isError = true)
        val profile = profileRepository.list().firstOrNull { it.name == profileName }
            ?: return McpCallToolResult(listOf(McpContent(text = "Profile '$profileName' not found")), isError = true)

        val vhost = args["vhost"]?.jsonPrimitive?.content ?: profile.virtualHost
        val catalog = topologyCatalogProvider(profile, vhost)

        val result = catalog.bindings(vhost)
        return when (result) {
            is CatalogResult.Success -> {
                val array = buildJsonArray {
                    for (b in result.value) {
                        add(buildJsonObject {
                            put("source", b.source)
                            put("destination", b.destination)
                            put("destinationType", b.destinationType.name)
                            put("routingKey", b.routingKey)
                        })
                    }
                }
                McpCallToolResult(listOf(McpContent(text = json.encodeToString(array))))
            }
            else -> {
                McpCallToolResult(listOf(McpContent(text = "Failed to list bindings: $result")), isError = true)
            }
        }
    }

    private suspend fun browseStream(args: JsonObject): McpCallToolResult {
        val profileName = args["profile"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'profile' argument")), isError = true)
        val stream = args["stream"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'stream' argument")), isError = true)

        val profile = profileRepository.list().firstOrNull { it.name == profileName }
            ?: return McpCallToolResult(listOf(McpContent(text = "Profile '$profileName' not found")), isError = true)

        val vhost = args["vhost"]?.jsonPrimitive?.content ?: profile.virtualHost
        val readerProvider = streamMessageReaderProvider
            ?: return McpCallToolResult(listOf(McpContent(text = "Stream message reader is not configured on this server")), isError = true)
        val reader = readerProvider(profile, vhost)

        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val startStr = args["start"]?.jsonPrimitive?.content?.trim()?.lowercase()

        val startSpec = when {
            startStr == null || startStr.isBlank() || startStr == "first" -> BrowseStart.First
            startStr == "last" -> BrowseStart.LastPage(pageSize = limit)
            startStr.startsWith("ts:") -> {
                val ms = startStr.removePrefix("ts:").toLongOrNull()
                    ?: return McpCallToolResult(listOf(McpContent(text = "Invalid timestamp: $startStr")), isError = true)
                BrowseStart.Timestamp(ms)
            }
            startStr.startsWith("@") -> {
                val ms = startStr.removePrefix("@").toLongOrNull()
                    ?: return McpCallToolResult(listOf(McpContent(text = "Invalid timestamp: $startStr")), isError = true)
                BrowseStart.Timestamp(ms)
            }
            startStr.toLongOrNull() != null -> BrowseStart.Offset(startStr.toLong())
            else -> return McpCallToolResult(listOf(McpContent(text = "Unknown start specification '$startStr'. Use 'first', 'last', numeric offset, or '@<epochMs>'")), isError = true)
        }

        val filterText = args["filterText"]?.jsonPrimitive?.content
        val appPropKey = args["appPropertyKey"]?.jsonPrimitive?.content
        val appPropVal = args["appPropertyValue"]?.jsonPrimitive?.content

        val criteria = when {
            filterText != null -> MessageFilterCriteria.Text(filterText)
            appPropKey != null && appPropVal != null -> MessageFilterCriteria.ApplicationProperty(appPropKey, appPropVal)
            else -> MessageFilterCriteria.None
        }
        if (criteria !is MessageFilterCriteria.None) {
            val startOffset = when (startSpec) {
                is BrowseStart.Offset -> startSpec.offset
                else -> 0L
            }
            val updates = StreamScanner(reader).scan(vhost, stream, startOffset, criteria, scanLimit = 10_000).toList()
            val final = updates.lastOrNull()
            val matches = (final?.matches ?: emptyList()).take(limit)
            val jsonResult = buildJsonObject {
                put("stream", stream)
                put("vhost", vhost)
                put("count", matches.size)
                put("scannedCount", final?.scannedCount ?: 0)
                put("limitReached", final?.limitReached ?: false)
                final?.notice?.let { put("notice", it) }
                putJsonArray("messages") {
                    for (msg in matches) {
                        add(buildJsonObject {
                            put("offset", msg.offset)
                            put("timestamp", msg.timestamp)
                            putJsonObject("properties") {
                                for ((k, v) in msg.properties) {
                                    put(k, v?.toString() ?: "")
                                }
                            }
                            putJsonObject("applicationProperties") {
                                for ((k, v) in msg.applicationProperties) {
                                    put(k, v?.toString() ?: "")
                                }
                            }
                            val (preview, truncated) = formatPayload(msg.payload)
                            put("payload", preview)
                            put("payloadSize", msg.payload.size)
                            put("truncated", truncated)
                        })
                    }
                }
            }
            return McpCallToolResult(listOf(McpContent(text = json.encodeToString(jsonResult))))
        }

        return when (val result = reader.readPage(vhost, stream, startSpec, limit)) {
            is BrowseResult.Success -> {
                val jsonResult = buildJsonObject {
                    put("stream", stream)
                    put("vhost", vhost)
                    put("streamFirstOffset", result.streamFirstOffset)
                    put("streamLastOffset", result.streamLastOffset)
                    put("count", result.messages.size)
                    putJsonArray("messages") {
                        for (msg in result.messages) {
                            add(buildJsonObject {
                                put("offset", msg.offset)
                                put("timestamp", msg.timestamp)
                                putJsonObject("properties") {
                                    for ((k, v) in msg.properties) {
                                        put(k, v?.toString() ?: "")
                                    }
                                }
                                putJsonObject("applicationProperties") {
                                    for ((k, v) in msg.applicationProperties) {
                                        put(k, v?.toString() ?: "")
                                    }
                                }
                                val (preview, truncated) = formatPayload(msg.payload)
                                put("payload", preview)
                                put("payloadSize", msg.payload.size)
                                put("truncated", truncated)
                            })
                        }
                    }
                }
                McpCallToolResult(listOf(McpContent(text = json.encodeToString(jsonResult))))
            }
            is BrowseResult.Empty -> {
                val emptyResult = buildJsonObject {
                    put("stream", stream)
                    put("vhost", vhost)
                    put("empty", true)
                    put("streamFirstOffset", result.streamFirstOffset)
                    put("streamLastOffset", result.streamLastOffset)
                    putJsonArray("messages") {}
                }
                McpCallToolResult(listOf(McpContent(text = json.encodeToString(emptyResult))))
            }
            is BrowseResult.OutsideRange -> {
                McpCallToolResult(
                    listOf(McpContent(text = "Requested offset ${result.offset} is outside range ${result.validRange.first}..${result.validRange.last}")),
                    isError = true
                )
            }
            is BrowseResult.NoMessagesAtOrAfterTime -> {
                val noMsgResult = buildJsonObject {
                    put("stream", stream)
                    put("vhost", vhost)
                    put("timestampEpochMs", result.epochMs)
                    put("streamFirstOffset", result.streamFirstOffset)
                    put("streamLastOffset", result.streamLastOffset)
                    putJsonArray("messages") {}
                }
                McpCallToolResult(listOf(McpContent(text = json.encodeToString(noMsgResult))))
            }
            is BrowseResult.NotFound -> {
                McpCallToolResult(listOf(McpContent(text = "Stream '$stream' not found in vhost '$vhost'")), isError = true)
            }
            is BrowseResult.AccessDenied -> {
                McpCallToolResult(listOf(McpContent(text = "Access denied for stream '$stream' in vhost '$vhost'")), isError = true)
            }
            is BrowseResult.Failed -> {
                McpCallToolResult(listOf(McpContent(text = "Failed to browse stream '$stream': ${result.reason}")), isError = true)
            }
        }
    }

    private val payloadFormatter = dev.caerbannog.core.browser.PayloadFormatter()

    private fun formatPayload(payload: ByteArray, maxChars: Int = 4096): Pair<String, Boolean> {
        val inspection = payloadFormatter.inspect(
            dev.caerbannog.core.browser.StreamMessage(
                offset = 0L,
                timestamp = 0L,
                payload = payload,
            ),
            capLimit = maxChars,
            showAll = false,
        )
        return inspection.displayText to inspection.isCapped
    }

    private suspend fun publishTestMessage(args: JsonObject): McpCallToolResult {
        val profileName = args["profile"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'profile' argument")), isError = true)
        val profile = profileRepository.list().firstOrNull { it.name == profileName }
            ?: return McpCallToolResult(listOf(McpContent(text = "Profile '$profileName' not found")), isError = true)

        val targetKindStr = args["targetKind"]?.jsonPrimitive?.content?.uppercase()
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'targetKind' argument")), isError = true)
        val targetKind = try {
            PublishTargetKind.valueOf(targetKindStr)
        } catch (_: Exception) {
            return McpCallToolResult(listOf(McpContent(text = "Invalid targetKind: '$targetKindStr'. Must be 'stream', 'exchange', or 'queue'")), isError = true)
        }

        val targetName = args["targetName"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'targetName' argument")), isError = true)
        val payloadStr = args["payload"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'payload' argument")), isError = true)

        val vhost = args["vhost"]?.jsonPrimitive?.content ?: profile.virtualHost
        val routingKey = args["routingKey"]?.jsonPrimitive?.content
        val messageId = args["messageId"]?.jsonPrimitive?.content
        val contentType = args["contentType"]?.jsonPrimitive?.content ?: "application/json"

        val appProps = buildMap<String, String> {
            args["applicationProperties"]?.let { elem ->
                if (elem is JsonObject) {
                    elem.forEach { (k, v) -> put(k, v.jsonPrimitive.content) }
                }
            }
        }

        // Guardrails: verify control level and reachability
        if (profile.topologyControlLevel == TopologyControlLevel.READ_ONLY) {
            return McpCallToolResult(
                content = listOf(McpContent(text = "Publishing refused: profile '${profile.name}' is set to Read-only topology control level.")),
                isError = true,
            )
        }
        if (profile.declaredPermissions != null && !profile.declaredPermissions!!.write) {
            return McpCallToolResult(
                content = listOf(McpContent(text = "Publishing refused: profile '${profile.name}' has no write permission declared.")),
                isError = true,
            )
        }

        val check = connectionChecker.check(profile)
        if (check !is ConnectionCheckResult.BothReachable) {
            return McpCallToolResult(
                content = listOf(McpContent(text = "Publishing refused: broker connection is unreachable.")),
                isError = true,
            )
        }

        val publisherProvider = messagePublisherProvider
            ?: return McpCallToolResult(listOf(McpContent(text = "Publisher not configured on this MCP server")), isError = true)
        val publisher = publisherProvider(profile, vhost)

        val props = buildMap<String, String> {
            if (!messageId.isNullOrBlank()) put("messageId", messageId)
            if (contentType.isNotBlank()) put("contentType", contentType)
        }

        val request = PublishMessageRequest(
            vhost = vhost,
            target = PublishTarget(kind = targetKind, name = targetName, routingKey = routingKey),
            payload = payloadStr.encodeToByteArray(),
            properties = props,
            applicationProperties = appProps,
        )

        val outcome = publisher.publish(request)
        return when (outcome) {
            is PublishOutcome.Success -> McpCallToolResult(
                content = listOf(McpContent(text = json.encodeToString(buildJsonObject {
                    put("success", true)
                    put("message", outcome.message)
                    putJsonObject("target") {
                        put("kind", targetKind.name.lowercase())
                        put("name", targetName)
                        if (routingKey != null) put("routingKey", routingKey)
                    }
                })))
            )
            is PublishOutcome.Refused -> McpCallToolResult(
                content = listOf(McpContent(text = "Broker refused publish: ${outcome.reason}")),
                isError = true,
            )
            is PublishOutcome.Failed -> McpCallToolResult(
                content = listOf(McpContent(text = "Publish failed: ${outcome.error}")),
                isError = true,
            )
        }
    }

    private suspend fun listMqttClients(args: JsonObject): McpCallToolResult {
        val profileName = args["profile"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'profile' argument")), isError = true)
        val profile = profileRepository.list().firstOrNull { it.name == profileName }
            ?: return McpCallToolResult(listOf(McpContent(text = "Profile '$profileName' not found")), isError = true)

        if (!profile.hasMqtt) {
            return McpCallToolResult(
                listOf(McpContent(text = "Profile '${profile.name}' does not have MQTT configured (mqttPort is null).")),
                isError = true,
            )
        }

        val check = connectionChecker.check(profile)
        if (check !is ConnectionCheckResult.BothReachable) {
            return McpCallToolResult(
                listOf(McpContent(text = "Profile '${profile.name}' is unreachable.")),
                isError = true,
            )
        }

        val catalogProvider = mqttCatalogProvider
            ?: return McpCallToolResult(listOf(McpContent(text = "MQTT catalog not configured on this MCP server")), isError = true)

        val vhost = args["vhost"]?.jsonPrimitive?.content ?: profile.virtualHost
        val catalog = catalogProvider(profile, vhost)

        return when (val result = catalog.listClients(vhost)) {
            is CatalogResult.Success -> {
                val clients = result.value
                val jsonResult = buildJsonObject {
                    put("profile", profile.name)
                    put("vhost", vhost)
                    put("clientCount", clients.size)
                    putJsonArray("clients") {
                        clients.forEach { client ->
                            add(buildJsonObject {
                                put("clientId", client.clientId)
                                put("username", client.username)
                                put("peerHost", client.peerHost)
                                put("peerPort", client.peerPort)
                                put("protocol", client.protocol)
                                put("connected", client.connected)
                                client.cleanSession?.let { put("cleanSession", it) }
                                putJsonArray("subscriptions") {
                                    client.subscriptions.forEach { sub ->
                                        add(buildJsonObject {
                                            put("topic", sub.topic)
                                            put("qos", sub.qos)
                                        })
                                    }
                                }
                            })
                        }
                    }
                }
                McpCallToolResult(content = listOf(McpContent(text = json.encodeToString(jsonResult))))
            }
            CatalogResult.NotAuthorised -> McpCallToolResult(
                listOf(McpContent(text = "Not authorised to list MQTT clients on vhost '$vhost'")),
                isError = true,
            )
            CatalogResult.PluginMissing -> McpCallToolResult(
                listOf(McpContent(text = "MQTT plugin is not available on broker")),
                isError = true,
            )
            CatalogResult.Unreachable -> McpCallToolResult(
                listOf(McpContent(text = "Broker unreachable while listing MQTT clients")),
                isError = true,
            )
            else -> McpCallToolResult(
                listOf(McpContent(text = "Failed to list MQTT clients: $result")),
                isError = true,
            )
        }
    }

    private suspend fun publishMqtt(args: JsonObject): McpCallToolResult {
        val profileName = args["profile"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'profile' argument")), isError = true)
        val profile = profileRepository.list().firstOrNull { it.name == profileName }
            ?: return McpCallToolResult(listOf(McpContent(text = "Profile '$profileName' not found")), isError = true)

        if (!profile.hasMqtt) {
            return McpCallToolResult(
                listOf(McpContent(text = "Publishing refused: profile '${profile.name}' does not have MQTT configured (mqttPort is null).")),
                isError = true,
            )
        }

        val topic = args["topic"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'topic' argument")), isError = true)
        if (topic.isBlank()) {
            return McpCallToolResult(listOf(McpContent(text = "Topic cannot be blank")), isError = true)
        }
        if (topic.contains('+') || topic.contains('#')) {
            return McpCallToolResult(listOf(McpContent(text = "Topic cannot contain wildcards '+' or '#")), isError = true)
        }

        val payloadStr = args["payload"]?.jsonPrimitive?.content
            ?: return McpCallToolResult(listOf(McpContent(text = "Missing 'payload' argument")), isError = true)

        val qosInt = args["qos"]?.jsonPrimitive?.intOrNull ?: 0
        if (qosInt !in 0..2) {
            return McpCallToolResult(listOf(McpContent(text = "Invalid qos '$qosInt'. Must be 0, 1, or 2")), isError = true)
        }
        val qos = MqttQos.fromInt(qosInt)
        val retain = args["retain"]?.jsonPrimitive?.booleanOrNull ?: false
        val headers = parseMqttHeaders(args["headers"])
            ?: return McpCallToolResult(
                listOf(McpContent(text = "Invalid 'headers': must be an object with non-blank string keys and string values")),
                isError = true,
            )

        if (profile.topologyControlLevel == TopologyControlLevel.READ_ONLY) {
            return McpCallToolResult(
                listOf(McpContent(text = "Publishing refused: profile '${profile.name}' is set to Read-only topology control level.")),
                isError = true,
            )
        }
        if (profile.declaredPermissions != null && !profile.declaredPermissions!!.write) {
            return McpCallToolResult(
                listOf(McpContent(text = "Publishing refused: profile '${profile.name}' has no write permission declared.")),
                isError = true,
            )
        }

        val check = connectionChecker.check(profile)
        if (check !is ConnectionCheckResult.BothReachable) {
            return McpCallToolResult(
                listOf(McpContent(text = "Publishing refused: broker connection is unreachable.")),
                isError = true,
            )
        }

        val publisherProvider = mqttMessagePublisherProvider
            ?: return McpCallToolResult(listOf(McpContent(text = "MQTT publisher not configured on this MCP server")), isError = true)
        val vhost = profile.virtualHost
        val publisher = publisherProvider(profile, vhost)

        val request = PublishMqttRequest(
            topic = topic.trim(),
            payload = payloadStr.encodeToByteArray(),
            qos = qos,
            retain = retain,
            headers = headers,
        )

        return when (val outcome = publisher.publish(request)) {
            is PublishOutcome.Success -> McpCallToolResult(
                content = listOf(McpContent(text = json.encodeToString(buildJsonObject {
                    put("success", true)
                    put("message", outcome.message)
                    put("topic", topic.trim())
                    put("qos", qos.value)
                    put("retain", retain)
                    put("headers", headers.size)
                })))
            )
            is PublishOutcome.Refused -> McpCallToolResult(
                content = listOf(McpContent(text = "Broker refused MQTT publish: ${outcome.reason}")),
                isError = true,
            )
            is PublishOutcome.Failed -> McpCallToolResult(
                content = listOf(McpContent(text = "MQTT publish failed: ${outcome.error}")),
                isError = true,
            )
        }
    }

    fun runStdio(input: InputStream, output: PrintStream) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val response = handleRequest(line)
            if (response != null) {
                output.println(response)
                output.flush()
            }
        }
    }
}

/** Parses the optional `headers` argument; null means it is present but malformed. Absent or JSON null means no headers. */
private fun parseMqttHeaders(element: JsonElement?): List<MqttHeader>? {
    if (element == null || element is kotlinx.serialization.json.JsonNull) return emptyList()
    val obj = element as? JsonObject ?: return null
    return obj.entries.map { (key, value) ->
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (key.isBlank()) return null
        MqttHeader(key, text)
    }
}
