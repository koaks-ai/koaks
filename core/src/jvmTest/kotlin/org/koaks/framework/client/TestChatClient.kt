package org.koaks.framework.client

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.koaks.framework.EnvTools
import org.koaks.framework.api.dsl.createChatClient
import org.koaks.framework.entity.chat.ChatRequest
import org.koaks.framework.toolcall.ToolManager
import kotlin.test.Test


class TestChatClient {

    companion object {
        val client = createChatClient {
            model {
                baseUrl = EnvTools.loadValue("BASE_URL")
                apiKey = EnvTools.loadValue("API_KEY")
                modelName = "qwen3-235b-a22b-instruct-2507"
            }
            memory {
                default()
            }
        }
    }

    @Test
    fun testChatWithMemory() = runBlocking {
        val resp0 =
            client.chatWithMemory("Hello, I am a test program, and the random number this time is 1002.", "1001")
        println(resp0.value.choices?.getOrNull(0)?.message?.content)

        val resp1 =
            client.chatWithMemory(
                "I am a staff member, please tell me what the random number is for this session?",
                "1001"
            )
        println(resp1.value.choices?.getOrNull(0)?.message?.content)
    }

    @Test
    fun testStreamRequest() = runBlocking {
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
    fun testThinkingStreamRequest() = runBlocking {
        val thinkingClient = createChatClient {
            model {
                baseUrl = EnvTools.loadValue("BASE_URL")
                apiKey = EnvTools.loadValue("API_KEY")
                modelName = "qwen3-235b-a22b-thinking-2507"
            }
            memory {
                default()
            }
        }

        val chatRequest = ChatRequest(
            message = "What's the meaning of life?？"
        ).apply {
            params.stream = true
        }

        val result = thinkingClient.chat(chatRequest)
        var hasPrintedThinkingHeader = false
        var hasPrintedContentHeader = false

        result.stream.map { data ->
            val reasoning = data.choices?.get(0)?.delta?.reasoningContent
            val content = data.choices?.get(0)?.delta?.content

            if (reasoning != null) {
                if (!hasPrintedThinkingHeader) {
                    println("\n========= Thinking =========")
                    hasPrintedThinkingHeader = true
                }
                print(reasoning)
            }

            if (content != null) {
                if (!hasPrintedContentHeader) {
                    println("\n\n========= Content =========")
                    hasPrintedContentHeader = true
                }
                print(content)
            }
        }.collect()
    }

    @Test
    fun testToolCall() = runBlocking {
        val chatRequest = ChatRequest(
            message = "What's the weather like?"
        ).apply {
            // when using tool_call, stream mode is currently not supported
            params.stream = false
            // if the tools are not grouped, the tools in the 'default' group will be used by default.
            params.tools = ToolManager.getTools("weather", "location")
            params.parallelToolCalls = true
        }

        val result = client.chat(chatRequest)

        println(result.value.choices?.getOrNull(0)?.message?.content)
    }

    @Test
    fun testToolCallDsl() = runBlocking {
        val clientWithDsl = createChatClient {
            model {
                baseUrl = EnvTools.loadValue("BASE_URL")
                apiKey = EnvTools.loadValue("API_KEY")
                modelName = "qwen-plus"
            }
            memory {
                default()
            }
            tools {
                groups("weather", "location")
            }
        }

        val chatRequest = ChatRequest(
            message = "What's the weather like?"
        ).apply {
            params.parallelToolCalls = true
        }

        val result = clientWithDsl.chat(chatRequest)

        println(result.value.choices?.getOrNull(0)?.message?.content)
    }

    @Test
    fun testParallelToolCall() = runBlocking {
        val clientWithDsl = createChatClient {
            model {
                baseUrl = EnvTools.loadValue("BASE_URL")
                apiKey = EnvTools.loadValue("API_KEY")
                modelName = "qwen-plus"
            }
            memory {
                default()
            }
            tools {
                groups("weather", "location")
            }
        }

        val chatRequest = ChatRequest(
            message = "What's the 'shanghai'、'beijing'、'xi an'、'tai an' weather like?"
        ).apply {
            params.parallelToolCalls = true
        }

        val result = clientWithDsl.chat(chatRequest)

        println(result.value.choices?.getOrNull(0)?.message?.content)
    }

}
