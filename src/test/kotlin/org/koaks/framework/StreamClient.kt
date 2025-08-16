package org.koaks.framework

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import org.koaks.framework.api.dsl.createChatClient
import org.koaks.framework.entity.ChatRequest

suspend fun main() {
    val client = createChatClient {
        model {
            baseUrl = "base-url"
            apiKey = "api-key"
            modelName = "qwen-plus"
        }
    }

    val chatRequest = ChatRequest(
        message = "What's the meaning of life?"
    ).apply {
        params.stream = true
    }

    val result = client.chat(chatRequest)
    result.stream.map { data ->
        print(data.choices?.get(0)?.delta?.content)
    }.collect()

}