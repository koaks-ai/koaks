package org.endow.framework.test

import org.endow.framework.api.dsl.createChatClient

suspend fun main() {
    val client = createChatClient {
        model {
            baseUrl = "base-url"
            apiKey = "api-key"
            modelName = "qwen-plus"
        }
        memory {
            default()
        }
    }
    val resp0 =
        client.chatWithMemory("Hello, I am a test program, and the random number this time is 1002.", "1001")
    print(resp0.value.choices?.getOrNull(0)?.message?.content)

    val resp1 =
        client.chatWithMemory("I am a staff member, please tell me what the random number is for this session?", "1001")
    print(resp1.value.choices?.getOrNull(0)?.message?.content)
}