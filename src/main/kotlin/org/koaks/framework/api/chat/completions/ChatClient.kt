package org.koaks.framework.api.chat.completions

import kotlinx.coroutines.runBlocking
import org.koaks.framework.entity.ModelResponse
import org.koaks.framework.entity.chat.ChatMessage
import org.koaks.framework.entity.chat.ChatRequest
import org.koaks.framework.memory.IMemoryStorage
import org.koaks.framework.model.ChatModel
import org.koaks.framework.service.ChatService
import org.koaks.framework.toolcall.ToolDefinition


class ChatClient(
    private val model: ChatModel,
    private val memory: IMemoryStorage,
    private val tools: List<ToolDefinition>
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
        mergeToolList(chatRequest)
        return chatService.execChat(chatRequest)
    }

    suspend fun chatWithMemory(message: String, memoryId: String): ModelResponse<ChatMessage> {
        return chatService.execChat(ChatRequest(message = message), memoryId)
    }

    suspend fun chatWithMemory(chatRequest: ChatRequest, memoryId: String): ModelResponse<ChatMessage> {
        mergeToolList(chatRequest)
        return chatService.execChat(chatRequest, memoryId)
    }

    private fun mergeToolList(chatRequest: ChatRequest) {
        if (chatRequest.params.tools.isNullOrEmpty() && tools.isEmpty()) return
        with(chatRequest) {
            params.tools = (params.tools.orEmpty() + tools).distinct()
        }
    }

}