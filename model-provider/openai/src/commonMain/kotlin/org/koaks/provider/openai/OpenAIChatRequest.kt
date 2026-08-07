package org.koaks.provider.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * OpenAI Chat Completions wire request. Self-contained — the provider depends only
 * on `koaks-core`'s public model/transport abstractions, not on internal entities.
 *
 * Notable OpenAI specifics vs. older OpenAI-compatible dialects:
 *  - `max_completion_tokens` replaces the deprecated `max_tokens` (required for the
 *    o-series / GPT-5 reasoning models).
 *  - `reasoning_effort` (`"low" | "medium" | "high"`) tunes reasoning models.
 */
@Serializable
data class OpenAIChatRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<OpenAIMessage>,
    @SerialName("tools") val tools: List<OpenAITool>? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    @SerialName("stream") val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: OpenAIStreamOptions? = null,
    @SerialName("temperature") val temperature: Double? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("stop") val stop: List<String>? = null,
    @SerialName("presence_penalty") val presencePenalty: Double? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("response_format") val responseFormat: Map<String, String>? = null,
)

@Serializable
data class OpenAIStreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true,
)

@Serializable
data class OpenAIMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAIReqToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
data class OpenAIReqToolCall(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String = "function",
    @SerialName("function") val function: OpenAIReqFunction,
)

@Serializable
data class OpenAIReqFunction(
    @SerialName("name") val name: String,
    @SerialName("arguments") val arguments: String,
)

@Serializable
data class OpenAITool(
    @SerialName("type") val type: String = "function",
    @SerialName("function") val function: OpenAIFunctionDef,
)

@Serializable
data class OpenAIFunctionDef(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("parameters") val parameters: JsonObject,
)
