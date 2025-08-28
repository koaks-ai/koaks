package org.koaks.framework.client

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.koaks.framework.EnvTools
import org.koaks.framework.api.dsl.createChatClient
import org.koaks.framework.entity.chat.ChatRequest
import org.koaks.provider.qwen.qwen
import kotlin.test.Test


class MacosTestChatClient {

    val macosClient = createChatClient {
        model {
            qwen(
                baseUrl = EnvTools.loadValue("BASE_URL"),
                apiKey = EnvTools.loadValue("API_KEY"),
                modelName = "qwen3-235b-a22b-instruct-2507",
            )
        }
        memory {
            default()
        }
    }

    @Test
    fun macosTestChatWithMemory() = runTest {
        val resp0 =
            macosClient.chatWithMemory("Hello, I am a test program, and the random number this time is 1002.", "1001")
        println("===== first =====")
        println(resp0.value().choices?.getOrNull(0)?.message?.content)

        val resp1 =
            macosClient.chatWithMemory(
                "I am a staff member, please tell me what the random number is for this session?",
                "1001"
            )
        println("===== second =====")
        println(resp1.value().choices?.getOrNull(0)?.message?.content)
    }

    @Test
    fun macosTestStreamRequest() = runTest {
        val chatRequest = ChatRequest(
            message = "What's the meaning of life?"
        ).apply {
            params.stream = true
        }

        val result = macosClient.chat(chatRequest)
        result.stream().map { data ->
            println(data.choices?.get(0)?.delta?.content)
        }.collect()
    }

    @Test
    fun macosTestThinkingStreamRequest() = runTest {
        val thinkingClient = createChatClient {
            model {
                qwen(
                    baseUrl = EnvTools.loadValue("BASE_URL"),
                    apiKey = EnvTools.loadValue("API_KEY"),
                    modelName = "qwen3-30b-a3b-thinking-2507",
                )
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

        result.stream().map { data ->
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

}
