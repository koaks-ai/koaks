package org.koaks.provider.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Anthropic Messages API wire request (POST /v1/messages). Self-contained — the
 * provider depends only on `koaks-core`'s public model/transport abstractions.
 *
 * Notable Anthropic specifics vs. the OpenAI Chat Completions dialect:
 *  - `max_tokens` is REQUIRED (no implicit default on the wire).
 *  - `system` is a top-level string param, not a `role:"system"` message.
 *  - `messages` only carry user / assistant turns; content is a block array.
 *  - tools use `input_schema` (not `parameters`); `tool_use.input` is a JSON object
 *    (not a stringified JSON like OpenAI's `function.arguments`).
 */
@Serializable
data class AnthropicChatRequest(
    @SerialName("model") val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    @SerialName("messages") val messages: List<AnthropicMessage>,
    @SerialName("system") val system: String? = null,
    @SerialName("tools") val tools: List<AnthropicTool>? = null,
    @SerialName("stream") val stream: Boolean = true,
    @SerialName("temperature") val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("stop_sequences") val stopSequences: List<String>? = null,
    @SerialName("thinking") val thinking: JsonObject? = null, // optional opt-in
)

@Serializable
data class AnthropicMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: List<AnthropicContentBlock>,
)

/**
 * A content block within a message. The polymorphic discriminator is `type`, which
 * matches [org.koaks.framework.utils.json.JsonUtil]'s default class discriminator —
 * no custom Json instance is needed.
 */
@Serializable
sealed interface AnthropicContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(@SerialName("text") val text: String) : AnthropicContentBlock

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        @SerialName("id") val id: String,
        @SerialName("name") val name: String,
        @SerialName("input") val input: JsonElement,
    ) : AnthropicContentBlock

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        @SerialName("content") val content: String,
        @SerialName("is_error") val isError: Boolean = false,
    ) : AnthropicContentBlock
}

@Serializable
data class AnthropicTool(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
)
