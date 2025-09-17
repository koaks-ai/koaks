package org.koaks.framework.mcp.client

import kotlinx.serialization.json.JsonElement
import org.koaks.framework.mcp.entity.McpMessage

interface McpTransport {
    suspend fun sendRequest(method: String, params: Any? = null): JsonElement
    suspend fun sendNotification(method: String, params: Any? = null)
    suspend fun listenForMessages(onMessage: (McpMessage) -> Unit)
}