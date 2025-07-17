package org.endow.framework.test

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.endow.framework.EndowFramework
import org.endow.framework.entity.DefaultRequest
import org.endow.framework.entity.Message
import org.endow.framework.model.ChatModel
import org.endow.framework.service.ChatService

fun main() {
    EndowFramework.init(arrayOf("org.endow.framework.test"))
    runBlocking {
        val chatService = ChatService(
            ChatModel(
                baseUrl = "base-url",
                apiKey = "api-key",
                modelName = "deepseek-v3"
            )
        )

        val defaultRequest = DefaultRequest(
            messages = mutableListOf(
                Message.of("user", "生命的意义是什么？")
            )
        ).apply {
            stream = true
//            tools = ToolContainer.getTools("default")
        }
//        println(chatService.execChat(defaultRequest).value)
        chatService.execChat(defaultRequest).stream
            .map { data ->
                print("${data.choices?.get(0)?.delta?.content}")
            }
            .collect()
    }

}