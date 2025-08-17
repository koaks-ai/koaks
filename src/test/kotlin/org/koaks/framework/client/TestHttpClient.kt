package org.koaks.framework.client

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.koaks.framework.entity.ChatMessage
import org.koaks.framework.entity.inner.InnerChatRequest
import org.koaks.framework.entity.Message
import org.koaks.framework.net.HttpClient
import kotlin.test.Test


class TestHttpClient {

    val client = HttpClient.create(
        baseUrl = "base-url",
        apiKey = "api-key",
    )

    @Test
    fun testSyncRequest() = runBlocking {
        val request = InnerChatRequest(
            modelName = "qwen-plus",
            messages = mutableListOf(
                Message.user("What is the meaning of life?")
            )
        ).apply {
            stream = false
        }

        val stringResult = client.postAsObject<ChatMessage>(request)

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
    fun testStreamRequest() = runBlocking {
        val request = InnerChatRequest(
            modelName = "qwen-plus",
            messages = mutableListOf(
                Message.user("what's the meaning of life？")
            )
        ).apply {
            stream = true
        }

        var chunkCount = 0
        client.postAsObjectStream<ChatMessage>(request)
            .map { data ->
                chunkCount++
                print("${data.choices?.get(0)?.delta?.content}")
            }
            .collect()

        println("Stream request completed, received in total $chunkCount data block")
    }

}