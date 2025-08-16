package org.koaks.framework.test

import kotlinx.coroutines.runBlocking
import org.koaks.framework.KoaksFramework
import org.koaks.framework.entity.ChatRequest
import org.koaks.framework.model.ChatModel
import org.koaks.framework.service.ChatService
import org.koaks.framework.toolcall.ToolContainer

fun main() {
    KoaksFramework.init(arrayOf("org.koaks.framework.test"))
    runBlocking {
        val chatService = ChatService(
            ChatModel(
                baseUrl = "base-url",
                apiKey = "api-key",
                modelName = "qwen-plus"
            )
        )

        val chatRequest = ChatRequest(
            message = "What is the weather like in the four municipalities?"
        ).apply {
            params.stream = false
            params.tools = ToolContainer.getTools("default")
            params.parallelToolCalls = true
        }
        println(chatService.execChat(chatRequest).value)
//        chatService.execChat(chatRequest).stream
//            .map { data ->
//                print("${data.choices?.getOrNull(0)?.delta?.content}")
//            }
//            .collect()
    }

}