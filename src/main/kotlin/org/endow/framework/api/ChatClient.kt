package org.endow.framework.api

import kotlinx.coroutines.runBlocking
import org.endow.framework.entity.ChatMessage
import org.endow.framework.entity.ChatRequest
import org.endow.framework.entity.ModelResponse
import org.endow.framework.memory.IMemoryStorage
import org.endow.framework.model.ChatModel
import org.endow.framework.service.ChatService
import org.endow.framework.websearch.ISearch

class ChatClient(
    private val model: ChatModel,
    private val memory: IMemoryStorage,
    private val searchEngine: ISearch
) {
    private val chatService = ChatService(model, memory, searchEngine)

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
