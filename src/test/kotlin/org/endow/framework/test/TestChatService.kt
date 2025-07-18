package org.endow.framework.test

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.endow.framework.EndowFramework
import org.endow.framework.entity.ChatRequest
import org.endow.framework.model.ChatModel
import org.endow.framework.service.ChatService
import org.endow.framework.toolcall.ToolContainer

fun main() {
    EndowFramework.init(arrayOf("org.endow.framework.test"))
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