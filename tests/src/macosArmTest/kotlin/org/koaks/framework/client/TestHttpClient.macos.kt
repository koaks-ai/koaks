package org.koaks.framework.client

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.koaks.framework.EnvTools
import org.koaks.framework.entity.Message
import org.koaks.framework.entity.chat.ChatResponse
import org.koaks.framework.entity.inner.FullChatRequest
import org.koaks.framework.net.KtorHttpClient
import org.koaks.framework.net.HttpClientConfig
import org.koaks.framework.net.postAsObject
import org.koaks.framework.net.postAsObjectStream

import kotlin.test.Test


class MacosTestHttpClient {

    val macosClient = KtorHttpClient(
        HttpClientConfig(
            baseUrl = EnvTools.loadValue("BASE_URL"),
            apiKey = EnvTools.loadValue("API_KEY"),
        )
    )

    @Test
    fun macosTestSyncRequest() = runTest {
        val request = FullChatRequest(
            modelName = "qwen-plus",
            messages = mutableListOf(
                Message.userText("What is the meaning of life?")
            )
        ).apply {
            stream = false
        }

        val stringResult = macosClient.postAsObject<FullChatRequest, ChatResponse>(request)

        stringResult.fold(
            onSuccess = { result ->
                print("Request successful, the result is:${result.choices?.get(0)?.message?.content}")
            },
            onFailure = { error ->
                println("Request failed, error:$error")
            }
        )

    }

    @Test
    fun macosTestStreamRequest() = runTest {
        val request = FullChatRequest(
            modelName = "qwen-plus",
            messages = mutableListOf(
                Message.userText("what's the meaning of life？")
            )
        ).apply {
            stream = true
        }

        var chunkCount = 0
        macosClient.postAsObjectStream<FullChatRequest, ChatResponse>(request)
            .map { data ->
                chunkCount++
                print("${data.choices?.get(0)?.delta?.content}")
            }
            .collect()

        println("Stream request completed, received in total $chunkCount data block")
    }

}