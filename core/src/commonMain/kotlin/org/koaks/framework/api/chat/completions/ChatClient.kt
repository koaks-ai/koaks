package org.koaks.framework.api.chat.completions

import org.koaks.framework.entity.ModelResponse
import org.koaks.framework.entity.chat.ChatResponse
import org.koaks.framework.entity.chat.ChatRequest
import org.koaks.framework.memory.IMemoryStorage
import org.koaks.framework.model.AbstractChatModel
import org.koaks.framework.service.ChatService
import org.koaks.framework.toolcall.ToolDefinition


class ChatClient<TRequest, TResponse>(
    private val model: AbstractChatModel<TRequest, TResponse>,
    private val memory: IMemoryStorage,
    private val tools: List<ToolDefinition>
) {
    private val chatService = ChatService(model, memory)

    suspend fun generate(message: String): String {
        return chatService.execChat(
            ChatRequest(message = message)
        ).value.choices?.getOrNull(0)?.message?.content as String
    }

    suspend fun chat(chatRequest: ChatRequest): ModelResponse<ChatResponse> {
        return chatService.execChat(chatRequest)
    }

    suspend fun chatWithMemory(message: String, memoryId: String): ModelResponse<ChatResponse> {
        return chatService.execChat(ChatRequest(message = message), memoryId)
    }

    suspend fun chatWithMemory(chatRequest: ChatRequest, memoryId: String): ModelResponse<ChatResponse> {
        return chatService.execChat(chatRequest, memoryId)
    }

}