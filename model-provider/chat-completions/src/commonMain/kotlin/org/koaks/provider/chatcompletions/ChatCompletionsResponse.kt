package org.koaks.provider.chatcompletions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionsResponse(
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
    ) {
        val payload: Delta? get() = delta ?: message
    }

    @Serializable
    data class Delta(
        @SerialName("role") val role: String? = null,
        @SerialName("content") val content: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCallChunk>? = null,
        @SerialName("refusal") val refusal: String? = null,
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
        @SerialName("prompt_tokens_details") val promptDetails: PromptDetails? = null,
        @SerialName("completion_tokens_details") val completionDetails: CompletionDetails? = null,
    )

    @Serializable
    data class PromptDetails(
        @SerialName("cached_tokens") val cachedTokens: Int? = null,
    )

    @Serializable
    data class CompletionDetails(
        @SerialName("reasoning_tokens") val reasoningTokens: Int? = null,
    )

    @Serializable
    data class ErrorOutput(
        @SerialName("code") val code: String? = null,
        @SerialName("param") val param: String? = null,
        @SerialName("message") val message: String? = null,
        @SerialName("type") val type: String? = null,
    )
}
