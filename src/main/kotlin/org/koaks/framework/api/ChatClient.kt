package org.koaks.framework.api

import kotlinx.coroutines.runBlocking
import org.koaks.framework.entity.ChatMessage
import org.koaks.framework.entity.ChatRequest
import org.koaks.framework.entity.ModelResponse
import org.koaks.framework.memory.IMemoryStorage
import org.koaks.framework.model.ChatModel
import org.koaks.framework.service.ChatService

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
