package org.koaks.provider.qwen

import org.koaks.framework.entity.chat.ChatMessage
import org.koaks.framework.entity.inner.InnerChatRequest
import org.koaks.framework.model.AbstractChatModel

class QwenChatModel(
    override val baseUrl: String,
    override val apiKey: String,
    override var modelName: String,
) : AbstractChatModel<QwenChatRequest, ChatMessage>(baseUrl, apiKey, modelName) {

    override fun toInnerRequest(innerChatRequest: InnerChatRequest): QwenChatRequest {
        return QwenChatRequest(
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
    }

    override fun toChatResponse(innerResponse: ChatMessage): ChatMessage {
        return innerResponse
    }

    override val responseDeserializer = ChatMessage.serializer()

}

