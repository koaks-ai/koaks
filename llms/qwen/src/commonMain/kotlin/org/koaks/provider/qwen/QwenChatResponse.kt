package org.koaks.provider.qwen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koaks.framework.entity.Message
import org.koaks.framework.model.ToolCallable

@Serializable
class QwenChatResponse : ToolCallable {

    var choices: MutableList<Choice>? = null
    var created: Long = 0
    var id: String? = null
    var model: String? = null

    @SerialName("object")
    var chatObject: String? = null

    @SerialName("prompt_filter_results")
    var promptFilterResults: MutableList<PromptFilterResult?>? = null

    @SerialName("system_fingerprint")
    var systemFingerprint: String? = null
    var usage: Usage? = null

    override fun toString(): String {
        return "ChatMessage{" +
                "choices=" + choices +
                ", created=" + created +
                ", id='" + id + '\'' +
                ", model='" + model + '\'' +
                ", object='" + this.chatObject + '\'' +
                ", promptFilterResults=" + promptFilterResults +
                ", systemFingerprint='" + systemFingerprint + '\'' +
                ", usage=" + usage +
                '}'
    }

    override fun shouldToolCall(): Boolean {
        return (choices?.firstOrNull()?.finishReason == "tool_calls")
                && (choices?.firstOrNull()?.message?.toolCalls != null)
    }

    @Serializable
    class Choice {
        @SerialName("content_filter_results")
        var contentFilterResults: ContentFilterResults? = null

        @SerialName("finish_reason")
        var finishReason: String? = null
        var delta: Delta? = null
        var text: String? = null
        var index: Int = 0

        // TODO: logprobs
//        var logprobs: Any? = null
        var message: Message? = null

        override fun toString(): String {
            return "Choice{" +
                    "contentFilterResults=" + contentFilterResults +
                    ", finishReason='" + finishReason + '\'' +
                    ", index=" + index +
//                    ", logprobs=" + logprobs +
                    ", message=" + message +
                    ", delta=" + delta +
                    ", text='" + text + '\'' +
                    '}'
        }
    }

    @Serializable
    class Delta {
        var role: String? = null
        var content: String? = null
        var refusal: String? = null

        @SerialName("tool_calls")
        var toolCalls: MutableList<ToolCall?>? = null

        @SerialName("reasoning_content")
        var reasoningContent: String? = null

        override fun toString(): String {
            return "Delta{" +
                    "content='" + content + '\'' +
                    ", role='" + role + '\'' +
                    ", refusal='" + refusal + '\'' +
                    ", reasoningContent='" + reasoningContent + '\'' +
                    ", toolCalls=" + toolCalls +
                    '}'
        }
    }

    @Serializable
    class ContentFilterResults {
        var hate: FilterDetail? = null

        @SerialName("self_harm")
        var selfHarm: FilterDetail? = null
        var sexual: FilterDetail? = null
        var violence: FilterDetail? = null

        override fun toString(): String {
            return "ContentFilterResults{" +
                    "hate=" + hate +
                    ", selfHarm=" + selfHarm +
                    ", sexual=" + sexual +
                    ", violence=" + violence +
                    '}'
        }
    }

    @Serializable
    class FilterDetail {
        var isFiltered: Boolean = false
        var severity: String? = null

        override fun toString(): String {
            return "FilterDetail{" +
                    "filtered=" + this.isFiltered +
                    ", severity='" + severity + '\'' +
                    '}'
        }
    }

    @Serializable
    class ToolCall {
        lateinit var id: String
        var function: FunctionCall? = null
        var type: String? = null
        var index: Int = 0

        override fun toString(): String {
            return "ToolCall{" +
                    "id='" + id + '\'' +
                    ", function=" + function +
                    ", type='" + type + '\'' +
                    ", index=" + index +
                    '}'
        }
    }

    @Serializable
    class FunctionCall {
        lateinit var name: String
        var arguments: String? = null

        override fun toString(): String {
            return "FunctionCall{" +
                    "name='" + name + '\'' +
                    ", arguments='" + arguments + '\'' +
                    '}'
        }
    }

    @Serializable
    class PromptFilterResult {
        @SerialName("content_filter_results")
        var contentFilterResults: ContentFilterResults? = null

        @SerialName("prompt_index")
        var promptIndex: Int = 0

        override fun toString(): String {
            return "PromptFilterResult{" +
                    "contentFilterResults=" + contentFilterResults +
                    ", promptIndex=" + promptIndex +
                    '}'
        }
    }

    @Serializable
    class Usage {
        @SerialName("completion_tokens")
        var completionTokens: Int = 0

        @SerialName("completion_tokens_details")
        var completionTokensDetails: CompletionTokensDetails? = null

        @SerialName("prompt_tokens")
        var promptTokens: Int = 0

        @SerialName("prompt_tokens_details")
        var promptTokensDetails: PromptTokensDetails? = null

        @SerialName("total_tokens")
        var totalTokens: Int = 0

        override fun toString(): String {
            return "Usage{" +
                    "completionTokens=" + completionTokens +
                    ", completionTokensDetails=" + completionTokensDetails +
                    ", promptTokens=" + promptTokens +
                    ", promptTokensDetails=" + promptTokensDetails +
                    ", totalTokens=" + totalTokens +
                    '}'
        }
    }

    @Serializable
    class CompletionTokensDetails {
        @SerialName("accepted_prediction_tokens")
        var acceptedPredictionTokens: Int = 0

        @SerialName("audio_tokens")
        var audioTokens: Int = 0

        @SerialName("reasoning_tokens")
        var reasoningTokens: Int = 0

        @SerialName("rejected_prediction_tokens")
        var rejectedPredictionTokens: Int = 0

        override fun toString(): String {
            return "CompletionTokensDetails{" +
                    "acceptedPredictionTokens=" + acceptedPredictionTokens +
                    ", audioTokens=" + audioTokens +
                    ", reasoningTokens=" + reasoningTokens +
                    ", rejectedPredictionTokens=" + rejectedPredictionTokens +
                    '}'
        }
    }

    @Serializable
    class PromptTokensDetails {
        @SerialName("audio_tokens")
        var audioTokens: Int = 0

        @SerialName("cached_tokens")
        var cachedTokens: Int = 0

        override fun toString(): String {
            return "PromptTokensDetails{" +
                    "audioTokens=" + audioTokens +
                    ", cachedTokens=" + cachedTokens +
                    '}'
        }
    }
}