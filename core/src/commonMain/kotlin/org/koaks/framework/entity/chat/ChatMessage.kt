package org.koaks.framework.entity.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koaks.framework.entity.Message


@Serializable
data class ChatMessage(
    val shouldToolCall: Boolean = false,
    val choices: List<Choice>? = null,
    val created: Long = 0,
    val id: String? = null,
    val model: String? = null,
    @SerialName("object")
    val chatObject: String? = null,
    @SerialName("prompt_filter_results")
    val promptFilterResults: List<PromptFilterResult?>? = null,
    @SerialName("system_fingerprint")
    val systemFingerprint: String? = null,
    val usage: Usage? = null
) {
    @Serializable
    data class Choice(
        @SerialName("content_filter_results")
        val contentFilterResults: ContentFilterResults? = null,
        @SerialName("finish_reason")
        val finishReason: String? = null,
        val delta: Delta? = null,
        val text: String? = null,
        val index: Int = 0,
        val message: Message? = null
    )

    @Serializable
    data class Delta(
        val role: String? = null,
        val content: String? = null,
        val refusal: String? = null,
        @SerialName("tool_calls")
        val toolCalls: List<ToolCall?>? = null,
        @SerialName("reasoning_content")
        val reasoningContent: String? = null
    )

    @Serializable
    data class ContentFilterResults(
        val hate: FilterDetail? = null,
        @SerialName("self_harm")
        val selfHarm: FilterDetail? = null,
        val sexual: FilterDetail? = null,
        val violence: FilterDetail? = null
    )

    @Serializable
    data class FilterDetail(
        @SerialName("filtered")
        val isFiltered: Boolean = false,
        val severity: String? = null
    )

    @Serializable
    data class ToolCall(
        val id: String,
        val function: FunctionCall? = null,
        val type: String? = null,
        val index: Int? = null,
    )

    @Serializable
    data class FunctionCall(
        val name: String,
        val arguments: String? = null
    )

    @Serializable
    data class PromptFilterResult(
        @SerialName("content_filter_results")
        val contentFilterResults: ContentFilterResults? = null,
        @SerialName("prompt_index")
        val promptIndex: Int? = null,
    )

    @Serializable
    data class Usage(
        @SerialName("completion_tokens")
        val completionTokens: Int? = null,
        @SerialName("completion_tokens_details")
        val completionTokensDetails: CompletionTokensDetails? = null,
        @SerialName("prompt_tokens")
        val promptTokens: Int? = null,
        @SerialName("prompt_tokens_details")
        val promptTokensDetails: PromptTokensDetails? = null,
        @SerialName("total_tokens")
        val totalTokens: Int? = null,
    )

    @Serializable
    data class CompletionTokensDetails(
        @SerialName("accepted_prediction_tokens")
        val acceptedPredictionTokens: Int? = null,
        @SerialName("audio_tokens")
        val audioTokens: Int? = null,
        @SerialName("reasoning_tokens")
        val reasoningTokens: Int? = null,
        @SerialName("rejected_prediction_tokens")
        val rejectedPredictionTokens: Int = 0
    )

    @Serializable
    data class PromptTokensDetails(
        @SerialName("audio_tokens")
        val audioTokens: Int? = null,
        @SerialName("cached_tokens")
        val cachedTokens: Int? = null,
    )
}
