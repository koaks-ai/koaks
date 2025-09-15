package org.koaks.framework.client

import kotlinx.coroutines.runBlocking
import org.koaks.framework.EnvTools
import org.koaks.framework.api.dsl.createChatClient
import org.koaks.framework.entity.Message
import org.koaks.framework.entity.chat.ChatRequest
import org.koaks.framework.implTools.UserImplTools
import org.koaks.framework.implTools.WeatherImplTools
import org.koaks.framework.implTools.WeatherInput
import org.koaks.framework.toolcall.toolinterface.NoneInput
import org.koaks.framework.toolcall.toolinterface.createTool
import org.koaks.provider.qwen.qwen
import kotlin.test.Test

/**
 * Koaks.init() is not called here, so classes annotated with @Tool will not be scanned
 */
class TestToolInterface {

    @Test
    fun testToolCall() = runBlocking {
        val client = createChatClient {
            model {
                qwen(
                    baseUrl = EnvTools.loadValue("BASE_URL"),
                    apiKey = EnvTools.loadValue("API_KEY"),
                    modelName = "qwen3-235b-a22b-instruct-2507",
                )
            }
            tools {
                addTools(
                    UserImplTools(),
                    WeatherImplTools()
                )
                groups("weather", "location")
            }
        }

        val chatRequest = ChatRequest(
            message = Message.userText("What's the weather like?")
        ).apply {
            params.stream = false
            params.parallelToolCalls = true
        }

        val result = client.chat(chatRequest)

        println(result.value().choices?.getOrNull(0)?.message?.content)
    }

    @Test
    fun testParallelToolCall() = runBlocking {
        val clientWithDsl = createChatClient {
            model {
                qwen(
                    baseUrl = EnvTools.loadValue("BASE_URL"),
                    apiKey = EnvTools.loadValue("API_KEY"),
                    modelName = "qwen3-235b-a22b-instruct-2507",
                ) {
                    params {
                        parallelToolCalls = true
                    }
                }
            }
            tools {
                addTools(
                    UserImplTools(),
                    WeatherImplTools()
                )
                groups("weather", "location")
            }
        }

        val chatRequest = ChatRequest(
            message = Message.userText("What's the 'shanghai'、'beijing'、'xi an'、'tai an' weather like?")
        )

        val result = clientWithDsl.chat(chatRequest)

        println(result.value().choices?.getOrNull(0)?.message?.content)
    }

    @Test
    fun testToolCallDsl() = runBlocking {

        val weatherTool = createTool<WeatherInput>(
            name = "getWeather",
            description = "get the weather for a specific city today.",
            group = "weather"
        ) { input ->
            "The weather in ${input.city} at ${input.date} is windy."
        }

        val locationTool = createTool<NoneInput>(
            name = "userLocation",
            description = "get the city where the user is located",
            group = "location"
        ) { _ ->
            "Shanghai"
        }

        val client = createChatClient {
            model {
                qwen(
                    baseUrl = EnvTools.loadValue("BASE_URL"),
                    apiKey = EnvTools.loadValue("API_KEY"),
                    modelName = "qwen3-235b-a22b-instruct-2507",
                )
            }
            tools {
                addTools(
                    weatherTool, locationTool
                )
                groups("weather", "location")
            }
        }

        val chatRequest = ChatRequest(
            message = Message.userText("What's the weather like?")
        ).apply {
            params.stream = false
            params.parallelToolCalls = true
        }

        val result = client.chat(chatRequest)



        println(result.value().choices?.getOrNull(0)?.message?.content)
    }


}
