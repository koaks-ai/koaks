package org.endowx.framework.api

import kotlinx.coroutines.runBlocking
import org.endowx.framework.entity.ChatMessage
import org.endowx.framework.entity.ChatRequest
import org.endowx.framework.entity.ModelResponse
import org.endowx.framework.memory.IMemoryStorage
import org.endowx.framework.model.ChatModel
import org.endowx.framework.service.ChatService

class ChatClient(
    private val model: ChatModel,
    private val memory: IMemoryStorage,
) {
    private val chatService = ChatService(model, memory)

    fun generate(message: String): String {
        return runBlocking {
            chatService.execChat(
                ChatRequest(message = message)
            ).value.choices?.getOrNull(0)?.message?.content ?: String()
        }
    }

    suspend fun chat(chatRequest: ChatRequest): ModelResponse<ChatMessage> {
        return chatService.execChat(chatRequest)
    }

    suspend fun chatWithMemory(message: String, memoryId: String): ModelResponse<ChatMessage> {
        return chatService.execChat(ChatRequest(message = message), memoryId)
    }

    suspend fun chatWithMemory(chatRequest: ChatRequest, memoryId: String): ModelResponse<ChatMessage> {
        return chatService.execChat(chatRequest, memoryId)
    }

}
