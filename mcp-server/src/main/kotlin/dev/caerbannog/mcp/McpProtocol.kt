package dev.caerbannog.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
data class McpServerInfo(
    val name: String,
    val version: String,
)

@Serializable
data class McpInitializeResult(
    val protocolVersion: String,
    val capabilities: JsonObject,
    val serverInfo: McpServerInfo,
)

@Serializable
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

@Serializable
data class McpToolsListResult(
    val tools: List<McpTool>,
)

@Serializable
data class McpContent(
    val type: String = "text",
    val text: String,
)

@Serializable
data class McpCallToolResult(
    val content: List<McpContent>,
    val isError: Boolean = false,
)
