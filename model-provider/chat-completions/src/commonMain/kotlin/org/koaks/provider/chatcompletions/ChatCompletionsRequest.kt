package org.koaks.provider.chatcompletions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatCompletionsRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<ChatMessage>,
    @SerialName("tools") val tools: List<ChatTool>? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    @SerialName("stream") val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: ChatStreamOptions? = null,
    @SerialName("temperature") val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("stop") val stop: List<String>? = null,
    @SerialName("presence_penalty") val presencePenalty: Double? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("enable_thinking") val enableThinking: Boolean? = null,
    @SerialName("response_format") val responseFormat: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class ChatStreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true,
)

@Serializable
data class ChatMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChatReqToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
data class ChatReqToolCall(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String = "function",
    @SerialName("function") val function: ChatReqFunction,
)

@Serializable
data class ChatReqFunction(
    @SerialName("name") val name: String,
    @SerialName("arguments") val arguments: String,
)

@Serializable
data class ChatTool(
    @SerialName("type") val type: String = "function",
    @SerialName("function") val function: ChatFunctionDef,
)

@Serializable
data class ChatFunctionDef(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("parameters") val parameters: JsonObject,
)
