package org.koaks.provider.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Ollama `/api/chat` streaming response chunk (one per NDJSON line).
 *
 * Each chunk carries an incremental [message] (`content` delta). Tool calls arrive
 * as fully-formed objects (Ollama does not fragment them across chunks) with
 * `arguments` as a JSON object. The terminal chunk has `done=true` and the eval
 * counters used for usage.
 */
@Serializable
data class OllamaChatResponse(
    @SerialName("model") val model: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("message") val message: OllamaRespMessage? = null,
    @SerialName("done") val done: Boolean = false,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("error") val error: String? = null,
)

@Serializable
data class OllamaRespMessage(
    @SerialName("role") val role: String? = null,
    @SerialName("content") val content: String = "",
    @SerialName("tool_calls") val toolCalls: List<OllamaRespToolCall>? = null,
)

@Serializable
data class OllamaRespToolCall(
    @SerialName("function") val function: OllamaRespFunction,
)

@Serializable
data class OllamaRespFunction(
    @SerialName("name") val name: String,
    @SerialName("arguments") val arguments: JsonObject,
)
