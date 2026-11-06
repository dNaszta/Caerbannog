package dev.caerbannog.mcp

import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.StreamMessageReader
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
                }
                putJsonArray("required") {
                    add(JsonPrimitive("profile"))
                    add(JsonPrimitive("stream"))
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
