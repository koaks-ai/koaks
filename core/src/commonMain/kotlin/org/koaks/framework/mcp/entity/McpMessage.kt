package org.koaks.framework.mcp.entity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
sealed class McpMessage {
    abstract val jsonrpc: String

    @Serializable
    data class Request(
        override val jsonrpc: String = "2.0",
        val id: Int,
        val method: String,
        val params: JsonElement? = null
    ) : McpMessage()

    @Serializable
    data class Response(
        override val jsonrpc: String = "2.0",
        val id: Int,
        val result: JsonElement? = null,
        val error: McpError? = null
    ) : McpMessage()

    @Serializable
    data class Notification(
        override val jsonrpc: String = "2.0",
        val method: String,
        val params: JsonElement? = null
    ) : McpMessage()
}