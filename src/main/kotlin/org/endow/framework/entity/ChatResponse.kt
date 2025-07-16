package org.endow.framework.entity

import com.google.gson.annotations.SerializedName

open class ChatResponse {

    var choices: MutableList<Choice>? = null
    var created: Long = 0
    var id: String? = null
    var model: String? = null

    @SerializedName("object")
    var chatObject: String? = null

    @SerializedName("prompt_filter_results")
    var promptFilterResults: MutableList<PromptFilterResult?>? = null

    @SerializedName("system_fingerprint")
    var systemFingerprint: String? = null
    var usage: Usage? = null

    override fun toString(): String {
        return "DefaultChatResponse{" +
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

    class Choice {
        @SerializedName("content_filter_results")
        var contentFilterResults: ContentFilterResults? = null

        @SerializedName("finish_reason")
        var finishReason: String? = null
        var delta: Delta? = null
        var text: String? = null
        var index: Int = 0
        var logprobs: Any? = null
        var message: Message? = null

        override fun toString(): String {
            return "Choice{" +
                    "contentFilterResults=" + contentFilterResults +
                    ", finishReason='" + finishReason + '\'' +
                    ", index=" + index +
                    ", logprobs=" + logprobs +
                    ", message=" + message +
                    ", delta=" + delta +
                    ", text='" + text + '\'' +
                    '}'
        }
    }

    class Delta {
        var role: String? = null
        var content: String? = null
        var refusal: String? = null

        @SerializedName("tool_calls")
        var toolCalls: MutableList<ToolCall?>? = null

        @SerializedName("reasoning_content")
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

    class ContentFilterResults {
        var hate: FilterDetail? = null

        @SerializedName("self_harm")
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

    class ToolCall {
        var id: String? = null
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

    class PromptFilterResult {
        @SerializedName("content_filter_results")
        var contentFilterResults: ContentFilterResults? = null

        @SerializedName("prompt_index")
        var promptIndex: Int = 0

        override fun toString(): String {
            return "PromptFilterResult{" +
                    "contentFilterResults=" + contentFilterResults +
                    ", promptIndex=" + promptIndex +
                    '}'
        }
    }

    class Usage {
        @SerializedName("completion_tokens")
        var completionTokens: Int = 0

        @SerializedName("completion_tokens_details")
        var completionTokensDetails: CompletionTokensDetails? = null

        @SerializedName("prompt_tokens")
        var promptTokens: Int = 0

        @SerializedName("prompt_tokens_details")
        var promptTokensDetails: PromptTokensDetails? = null

        @SerializedName("total_tokens")
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

    class CompletionTokensDetails {
        @SerializedName("accepted_prediction_tokens")
        var acceptedPredictionTokens: Int = 0

        @SerializedName("audio_tokens")
        var audioTokens: Int = 0

        @SerializedName("reasoning_tokens")
        var reasoningTokens: Int = 0

        @SerializedName("rejected_prediction_tokens")
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

    class PromptTokensDetails {
        @SerializedName("audio_tokens")
        var audioTokens: Int = 0

        @SerializedName("cached_tokens")
        var cachedTokens: Int = 0

        override fun toString(): String {
            return "PromptTokensDetails{" +
                    "audioTokens=" + audioTokens +
                    ", cachedTokens=" + cachedTokens +
                    '}'
        }
    }
}