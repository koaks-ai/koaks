package org.koaks.provider.qwen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Qwen (OpenAI-compatible) wire request. Self-contained — the provider depends only
 * on `koaks-core`'s public model/transport abstractions, not on internal entities.
 */
@Serializable
data class QwenChatRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<QwenMessage>,
    @SerialName("tools") val tools: List<QwenTool>? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    @SerialName("stream") val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: QwenStreamOptions? = null,
    @SerialName("temperature") val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("stop") val stop: List<String>? = null,
    @SerialName("presence_penalty") val presencePenalty: Double? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @SerialName("response_format") val responseFormat: Map<String, String>? = null,
)

@Serializable
data class QwenStreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true,
)

@Serializable
data class QwenMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<QwenReqToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
data class QwenReqToolCall(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String = "function",
    @SerialName("function") val function: QwenReqFunction,
)

@Serializable
data class QwenReqFunction(
    @SerialName("name") val name: String,
    @SerialName("arguments") val arguments: String,
)

@Serializable
data class QwenTool(
    @SerialName("type") val type: String = "function",
    @SerialName("function") val function: QwenFunctionDef,
)

@Serializable
data class QwenFunctionDef(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("parameters") val parameters: JsonObject,
)
