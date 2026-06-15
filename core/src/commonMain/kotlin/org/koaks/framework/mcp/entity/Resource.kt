package org.koaks.framework.mcp.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class Resource(val uri: String, val description: String)

@Serializable
data class ResourceData(val uri: String, val content: String)

/** A tool advertised by an MCP server via `tools/list`. */
@Serializable
data class McpTool(
    val name: String,
    val description: String = "",
    @SerialName("inputSchema") val inputSchema: JsonObject? = null,
)

/** Result of an MCP `tools/call`. */
@Serializable
data class ToolResponse(val result: JsonElement)

/** Wrapper for the `tools/list` RPC result. */
@Serializable
data class ListToolsResult(val tools: List<McpTool> = emptyList())