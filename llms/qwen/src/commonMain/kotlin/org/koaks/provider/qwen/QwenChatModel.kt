package org.koaks.provider.qwen

import org.koaks.framework.entity.chat.ChatResponse
import org.koaks.framework.entity.inner.InnerChatRequest
import org.koaks.framework.model.AbstractChatModel
import org.koaks.framework.model.TypeAdapter

class QwenChatModel(
    override val baseUrl: String,
    override val apiKey: String,
    override var modelName: String,
) : AbstractChatModel<QwenChatRequest, QwenChatResponse>(baseUrl, apiKey, modelName) {

    override val typeAdapter = TypeAdapter(
        serializer = QwenChatRequest.serializer(),
        deserializer = QwenChatResponse.serializer(),
    )

    override fun toChatRequest(innerChatRequest: InnerChatRequest): QwenChatRequest =
        QwenChatRequest(
            modelName = innerChatRequest.modelName,
            messageList = innerChatRequest.messages,
            tools = innerChatRequest.tools,
            parallelToolCalls = innerChatRequest.parallelToolCalls,
            systemMessage = innerChatRequest.systemMessage,
            maxTokens = innerChatRequest.maxTokens,
            temperature = innerChatRequest.temperature,
            topP = innerChatRequest.topP,
            n = innerChatRequest.n,
            stream = innerChatRequest.stream,
            stop = innerChatRequest.stop,
            presencePenalty = innerChatRequest.presencePenalty,
            frequencyPenalty = innerChatRequest.frequencyPenalty,
            logitBias = innerChatRequest.logitBias,
            responseFormat = innerChatRequest.responseFormat
        )


    override fun toChatResponse(providerResponse: QwenChatResponse): ChatResponse {
        return ChatResponse(
            shouldToolCall = providerResponse.shouldToolCall(),
            choices = providerResponse.choices?.map {
                toChoice(it)
            },
            created = providerResponse.created,
            id = providerResponse.id,
            model = providerResponse.model,
            chatObject = providerResponse.chatObject,
            promptFilterResults = providerResponse.promptFilterResults?.map {
                it?.let { toPromptFilterResult(it) }
            },
            systemFingerprint = providerResponse.systemFingerprint,
            usage = providerResponse.usage?.let { toUsage(it) }
        )
    }

    private fun toChoice(inner: QwenChatResponse.Choice): ChatResponse.Choice {
        return ChatResponse.Choice(
            contentFilterResults = inner.contentFilterResults?.let {
                toContentFilterResults(it)
            },
            finishReason = inner.finishReason,
            delta = inner.delta?.let { toDelta(it) },
            text = inner.text,
            index = inner.index,
            message = inner.message
        )
    }

    private fun toDelta(inner: QwenChatResponse.Delta): ChatResponse.Delta {
        return ChatResponse.Delta(
            role = inner.role,
            content = inner.content,
            refusal = inner.refusal,
            toolCalls = inner.toolCalls?.map {
                it?.let { toToolCall(it) }
            },
            reasoningContent = inner.reasoningContent
        )
    }

    private fun toToolCall(inner: QwenChatResponse.ToolCall): ChatResponse.ToolCall {
        return ChatResponse.ToolCall(
            id = inner.id,
            function = inner.function?.let {
                toFunctionCall(it)
            },
            type = inner.type,
            index = inner.index
        )
    }

    private fun toFunctionCall(inner: QwenChatResponse.FunctionCall): ChatResponse.FunctionCall {
        return ChatResponse.FunctionCall(
            name = inner.name,
            arguments = inner.arguments
        )
    }

    private fun toContentFilterResults(inner: QwenChatResponse.ContentFilterResults): ChatResponse.ContentFilterResults {
        return ChatResponse.ContentFilterResults(
            hate = inner.hate?.let { toFilterDetail(it) },
            selfHarm = inner.selfHarm?.let { toFilterDetail(it) },
            sexual = inner.sexual?.let { toFilterDetail(it) },
            violence = inner.violence?.let { toFilterDetail(it) }
        )
    }

    private fun toFilterDetail(inner: QwenChatResponse.FilterDetail): ChatResponse.FilterDetail {
        return ChatResponse.FilterDetail(
            isFiltered = inner.isFiltered,
            severity = inner.severity
        )
    }

    private fun toPromptFilterResult(inner: QwenChatResponse.PromptFilterResult): ChatResponse.PromptFilterResult {
        return ChatResponse.PromptFilterResult(
            contentFilterResults = inner.contentFilterResults?.let {
                toContentFilterResults(it)
            },
            promptIndex = inner.promptIndex
        )
    }

    private fun toUsage(inner: QwenChatResponse.Usage): ChatResponse.Usage {
        return ChatResponse.Usage(
            completionTokens = inner.completionTokens,
            completionTokensDetails = inner.completionTokensDetails?.let {
                toCompletionTokensDetails(it)
            },
            promptTokens = inner.promptTokens,
            promptTokensDetails = inner.promptTokensDetails?.let {
                toPromptTokensDetails(it)
            },
            totalTokens = inner.totalTokens
        )
    }

    private fun toCompletionTokensDetails(inner: QwenChatResponse.CompletionTokensDetails): ChatResponse.CompletionTokensDetails {
        return ChatResponse.CompletionTokensDetails(
            acceptedPredictionTokens = inner.acceptedPredictionTokens,
            audioTokens = inner.audioTokens,
            reasoningTokens = inner.reasoningTokens,
            rejectedPredictionTokens = inner.rejectedPredictionTokens
        )
    }

    private fun toPromptTokensDetails(inner: QwenChatResponse.PromptTokensDetails): ChatResponse.PromptTokensDetails {
        return ChatResponse.PromptTokensDetails(
            audioTokens = inner.audioTokens,
            cachedTokens = inner.cachedTokens
        )
    }

}

