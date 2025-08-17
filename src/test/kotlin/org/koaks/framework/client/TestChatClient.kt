package org.koaks.framework.client

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.koaks.framework.Koaks
import org.koaks.framework.api.dsl.createChatClient
import org.koaks.framework.entity.ChatRequest
import org.koaks.framework.toolcall.ToolContainer
import kotlin.test.Test


class TestChatClient {

    val globalBaseUrl: String = "base-url"
    val globalApiKey: String = "api-key"

    companion object {
        @BeforeAll
        @JvmStatic
        fun initKoaks() {
            Koaks.init(arrayOf("org.koaks.framework"))
        }
    }

    @Test
    fun testChatWithMemory() = runBlocking {
        val client = createChatClient {
            model {
                baseUrl = globalBaseUrl
                apiKey = globalApiKey
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
            client.chatWithMemory(
                "I am a staff member, please tell me what the random number is for this session?",
                "1001"
            )
        print(resp1.value.choices?.getOrNull(0)?.message?.content)
    }

    @Test
    fun testStreamRequest() = runBlocking {
        val client = createChatClient {
            model {
                baseUrl = globalBaseUrl
                apiKey = globalApiKey
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

    @Test
    fun testToolCall() = runBlocking {
        val client = createChatClient {
            model {
                baseUrl = globalBaseUrl
                apiKey = globalApiKey
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
