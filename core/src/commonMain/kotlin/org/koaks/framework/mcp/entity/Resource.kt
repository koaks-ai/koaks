package org.koaks.framework.mcp.entity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Resource(val uri: String, val description: String)

@Serializable
data class ResourceData(val uri: String, val content: String)

@Serializable
data class McpTool(val name: String, val description: String)

@Serializable
data class ToolResponse(val result: JsonElement)