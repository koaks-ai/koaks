package org.koaks.framework

import kotlinx.coroutines.runBlocking
import org.koaks.framework.api.dsl.createChatClient
import org.koaks.framework.entity.ChatRequest
import org.koaks.framework.toolcall.ToolContainer

// currently, it is not supported to build tools using DSL.
fun main() {
    Koaks.init(arrayOf("org.koaks.framework"))
    runBlocking {
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

        val chatRequest = ChatRequest(
            message = "What's the weather like?"
        ).apply {
            // when using tool_call, stream mode is currently not supported
            params.stream = false
            // if the tools are not grouped, the tools in the 'default' group will be used by default.
            params.tools = ToolContainer.getTools("weather")
            params.parallelToolCalls = true
        }

        val result = client.chat(chatRequest)

        println(result.value.choices?.getOrNull(0)?.message?.content)
    }

}