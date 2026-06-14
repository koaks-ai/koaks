package org.koaks.framework.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An immutable piece of message content. Multimodal input, tool calls and tool
 * results are all modeled as content parts so a [Message] is just a list of them.
 */
@Serializable
sealed interface ContentPart {

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentPart

    @Serializable
    @SerialName("image")
    data class Image(val url: String? = null, val base64: String? = null) : ContentPart

    @Serializable
    @SerialName("audio")
    data class Audio(val url: String? = null, val base64: String? = null, val format: String) : ContentPart

    /** An assistant-issued tool call. */
    @Serializable
    @SerialName("tool_call")
    data class ToolCallPart(val call: ToolCall) : ContentPart

    /** The result of executing a tool call, fed back to the model. */
    @Serializable
    @SerialName("tool_result")
    data class ToolResultPart(
        val callId: String,
        val output: String,
        val isError: Boolean = false,
    ) : ContentPart
}
