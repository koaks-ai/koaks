package org.koaks.provider.qwen

import org.koaks.framework.entity.chat.ChatMessage
import org.koaks.framework.entity.inner.InnerChatRequest
import org.koaks.framework.model.AbstractChatModel

class QwenChatModel(
    override val baseUrl: String,
    override val apiKey: String,
    override var modelName: String,
) : AbstractChatModel<QwenChatRequest, QwenChatResponse>(baseUrl, apiKey, modelName) {

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


    override fun toChatMessage(innerResponse: QwenChatResponse): ChatMessage {
        return ChatMessage(
            shouldToolCall = innerResponse.shouldToolCall,
            choices = innerResponse.choices?.map {
                toChoice(it)
            },
            created = innerResponse.created,
            id = innerResponse.id,
            model = innerResponse.model,
            chatObject = innerResponse.chatObject,
            promptFilterResults = innerResponse.promptFilterResults?.map {
                it?.let { toPromptFilterResult(it) }
            },
            systemFingerprint = innerResponse.systemFingerprint,
            usage = innerResponse.usage?.let { toUsage(it) }
        )
    }

    private fun toChoice(inner: QwenChatResponse.Choice): ChatMessage.Choice {
        return ChatMessage.Choice(
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

    private fun toDelta(inner: QwenChatResponse.Delta): ChatMessage.Delta {
        return ChatMessage.Delta(
            role = inner.role,
            content = inner.content,
            refusal = inner.refusal,
            toolCalls = inner.toolCalls?.map {
                it?.let { toToolCall(it) }
            },
            reasoningContent = inner.reasoningContent
        )
    }

    private fun toToolCall(inner: QwenChatResponse.ToolCall): ChatMessage.ToolCall {
        return ChatMessage.ToolCall(
            id = inner.id,
            function = inner.function?.let {
                toFunctionCall(it)
            },
            type = inner.type,
            index = inner.index
        )
    }

    private fun toFunctionCall(inner: QwenChatResponse.FunctionCall): ChatMessage.FunctionCall {
        return ChatMessage.FunctionCall(
            name = inner.name,
            arguments = inner.arguments
        )
    }

    private fun toContentFilterResults(inner: QwenChatResponse.ContentFilterResults): ChatMessage.ContentFilterResults {
        return ChatMessage.ContentFilterResults(
            hate = inner.hate?.let { toFilterDetail(it) },
            selfHarm = inner.selfHarm?.let { toFilterDetail(it) },
            sexual = inner.sexual?.let { toFilterDetail(it) },
            violence = inner.violence?.let { toFilterDetail(it) }
        )
    }

    private fun toFilterDetail(inner: QwenChatResponse.FilterDetail): ChatMessage.FilterDetail {
        return ChatMessage.FilterDetail(
            isFiltered = inner.isFiltered,
            severity = inner.severity
        )
    }

    private fun toPromptFilterResult(inner: QwenChatResponse.PromptFilterResult): ChatMessage.PromptFilterResult {
        return ChatMessage.PromptFilterResult(
            contentFilterResults = inner.contentFilterResults?.let {
                toContentFilterResults(it)
            },
            promptIndex = inner.promptIndex
        )
    }

    private fun toUsage(inner: QwenChatResponse.Usage): ChatMessage.Usage {
        return ChatMessage.Usage(
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

    private fun toCompletionTokensDetails(inner: QwenChatResponse.CompletionTokensDetails): ChatMessage.CompletionTokensDetails {
        return ChatMessage.CompletionTokensDetails(
            acceptedPredictionTokens = inner.acceptedPredictionTokens,
            audioTokens = inner.audioTokens,
            reasoningTokens = inner.reasoningTokens,
            rejectedPredictionTokens = inner.rejectedPredictionTokens
        )
    }

    private fun toPromptTokensDetails(inner: QwenChatResponse.PromptTokensDetails): ChatMessage.PromptTokensDetails {
        return ChatMessage.PromptTokensDetails(
            audioTokens = inner.audioTokens,
            cachedTokens = inner.cachedTokens
        )
    }

    override val responseDeserializer = QwenChatResponse.serializer()

}

