package org.koaks.provider.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koaks.framework.entity.chat.ChatResponse
import org.koaks.framework.entity.enums.MessageRole

@Serializable
data class OllamaMessage(
    @SerialName("id")
    var id: String? = null,

    @SerialName("content")
    var content: String,

    @SerialName("role")
    var role: MessageRole,

    @SerialName("tool_calls")
    var toolCalls: MutableList<ChatResponse.ToolCall>? = null,

    @SerialName("images")
    var images: List<String>
)