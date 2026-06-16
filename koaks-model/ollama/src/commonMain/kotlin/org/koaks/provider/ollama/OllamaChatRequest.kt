package org.koaks.provider.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Ollama `/api/chat` wire request. Self-contained — depends only on `koaks-core`'s
 * public model/transport abstractions.
 *
 * Ollama streams NDJSON (one JSON object per line), not SSE. The transport handles
 * that via [org.koaks.framework.transport.StreamFormat.NDJSON].
 */
@Serializable
data class OllamaChatRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<OllamaMessage>,
    @SerialName("tools") val tools: List<OllamaTool>? = null,
    @SerialName("stream") val stream: Boolean = true,
    @SerialName("format") val format: String? = null,
    @SerialName("think") val think: Boolean? = null,
    @SerialName("options") val options: OllamaOptions? = null,
)

@Serializable
data class OllamaOptions(
    @SerialName("temperature") val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("num_predict") val numPredict: Int? = null,
    @SerialName("stop") val stop: List<String>? = null,
)

@Serializable
data class OllamaMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String = "",
    @SerialName("tool_calls") val toolCalls: List<OllamaReqToolCall>? = null,
    @SerialName("images") val images: List<String>? = null,
)

@Serializable
data class OllamaReqToolCall(
    @SerialName("function") val function: OllamaReqFunction,
)

@Serializable
data class OllamaReqFunction(
    @SerialName("name") val name: String,
    // Ollama wants arguments as a JSON object, not a stringified blob (unlike OpenAI).
    @SerialName("arguments") val arguments: JsonObject,
)

@Serializable
data class OllamaTool(
    @SerialName("type") val type: String = "function",
    @SerialName("function") val function: OllamaFunctionDef,
)

@Serializable
data class OllamaFunctionDef(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("parameters") val parameters: JsonObject,
)
