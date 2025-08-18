package org.koaks.framework.api.chat.responses

import org.koaks.framework.entity.ModelResponse
import org.koaks.framework.entity.chat.ChatMessage
import org.koaks.framework.entity.chat.responsesapi.ResponsesRequest
import org.koaks.framework.memory.IMemoryStorage
import org.koaks.framework.model.ChatModel
import org.koaks.framework.service.ChatService
import org.koaks.framework.toolcall.ToolDefinition

class ResponsesClient(
    private val model: ChatModel,
    private val memory: IMemoryStorage,
    private val tools: List<ToolDefinition>
) {
    private val chatService = ChatService(model, memory)

    fun create(responsesRequest: ResponsesRequest): ModelResponse<ChatMessage> {
        TODO("Not implemented")
    }

}