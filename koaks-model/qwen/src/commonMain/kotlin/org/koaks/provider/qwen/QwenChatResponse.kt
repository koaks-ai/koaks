package org.koaks.provider.qwen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Qwen (OpenAI-compatible) streaming response chunk. In streaming mode each chunk
 * carries a [Choice.delta]; the final chunk(s) may carry [usage]. A tool call's name
 * and arguments arrive split across multiple chunks — assembled by `QwenWireDecoder`.
 */
@Serializable
data class QwenChatResponse(
    @SerialName("id") val id: String? = null,
    @SerialName("model") val model: String? = null,
    @SerialName("choices") val choices: List<Choice>? = null,
    @SerialName("usage") val usage: Usage? = null,
    @SerialName("error") val error: ErrorOutput? = null,
) {
    @Serializable
    data class Choice(
        @SerialName("index") val index: Int = 0,
        @SerialName("delta") val delta: Delta? = null,
        @SerialName("message") val message: Delta? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    data class Delta(
        @SerialName("role") val role: String? = null,
        @SerialName("content") val content: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCallChunk>? = null,
    )

    @Serializable
    data class ToolCallChunk(
        @SerialName("index") val index: Int = 0,
        @SerialName("id") val id: String? = null,
        @SerialName("type") val type: String? = null,
        @SerialName("function") val function: FunctionChunk? = null,
    )

    @Serializable
    data class FunctionChunk(
        @SerialName("name") val name: String? = null,
        @SerialName("arguments") val arguments: String? = null,
    )

    @Serializable
    data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int? = null,
        @SerialName("completion_tokens") val completionTokens: Int? = null,
        @SerialName("total_tokens") val totalTokens: Int? = null,
    )

    @Serializable
    data class ErrorOutput(
        @SerialName("code") val code: String? = null,
        @SerialName("param") val param: String? = null,
        @SerialName("message") val message: String? = null,
        @SerialName("type") val type: String? = null,
    )
}
