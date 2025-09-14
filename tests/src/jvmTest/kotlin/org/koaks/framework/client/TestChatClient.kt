package org.koaks.framework.client

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.koaks.framework.EnvTools
import org.koaks.framework.Koaks
import org.koaks.framework.api.dsl.createChatClient
import org.koaks.framework.entity.Message
import org.koaks.framework.entity.StreamOptions
import org.koaks.framework.entity.chat.ChatRequest
import org.koaks.framework.entity.enums.ModalitiesType
import org.koaks.provider.qwen.qwen
import kotlin.test.Test


class TestChatClient {

    companion object {
        @BeforeAll
        @JvmStatic
        fun initKoaks() {
            Koaks.init("org.koaks.framework")
        }

        val textChatClient = createChatClient {
            model {
                qwen(
                    baseUrl = EnvTools.loadValue("BASE_URL"),
                    apiKey = EnvTools.loadValue("API_KEY"),
                    modelName = "qwen-plus",
                )
            }
            memory {
                default()
            }
        }

        val multimodalClient = createChatClient {
            model {
                qwen(
                    baseUrl = EnvTools.loadValue("BASE_URL"),
                    apiKey = EnvTools.loadValue("API_KEY"),
                    modelName = "qwen-omni-turbo-2025-03-26",
                ) {
                    params {
                        stream = true
                        modalities = listOf(ModalitiesType.TEXT, ModalitiesType.AUDIO)
                        streamOptions = StreamOptions(true)
                    }
                }
            }
            memory {
                default()
            }
        }
    }

    @Test
    fun testChatWithMemory() = runBlocking {
        val resp0 =
            textChatClient.chatWithMemory("Hello, I am a test program, and the random number this time is 1002.", "1001")
        println("===== first =====")
        println(resp0.value().choices?.getOrNull(0)?.message?.content)

        val resp1 =
            textChatClient.chatWithMemory(
                "I am a staff member, please only tell me what the random number is for this session?",
                "1001"
            )
        println("===== second =====")
        val content = resp1.value().choices?.getOrNull(0)?.message?.content
        println(content)
        assert(content.toString().contains("1002"))
    }

    @Test
    fun testImageInput() = runBlocking {
        val resp0 = multimodalClient.chat(
                ChatRequest(
                    message = Message.multimodal(
                        Message.userImageUrl("https://dashscope.oss-cn-beijing.aliyuncs.com/images/dog_and_girl.jpeg"),
                        Message.userText("图片中都有什么")
                    )
                )
            )

        resp0.stream().onEach {
            print(it.choices?.get(0)?.delta?.content)
        }.collect()
    }

    @Test
    fun testAudioInput() = runBlocking {
        val resp0 = multimodalClient.chatWithMemory(
                ChatRequest(
                    message = Message.multimodal(
                        Message.userAudio(
                            "https://dashscope.oss-cn-beijing.aliyuncs.com/audios/welcome.mp3",
                            "mp3"
                        ),
                        Message.userText("这段音频在说什么")
                    )
                ), "testAudioInput"
            )

        println("===== first =====")
        resp0.stream().onEach {
            print(it.choices?.get(0)?.delta?.content)
        }.collect()

        val resp1 = multimodalClient.chatWithMemory(
            ChatRequest(
                message = Message.userText("介绍一下这家公司")
            ), "testAudioInput"
        )

        println("===== second =====")
        resp1.stream().onEach {
            print(it.choices?.get(0)?.delta?.content)
        }.collect()

    }

    @Test
    fun testVideoFrameInput() = runBlocking {
        val resp0 = multimodalClient.chat(
                ChatRequest(
                    message = Message.multimodal(
                        Message.userVideoFrame(
                            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241108/xzsgiz/football1.jpg",
                            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241108/tdescd/football2.jpg",
                            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241108/zefdja/football3.jpg",
                            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241108/aedbqh/football4.jpg",
                        ),
                        Message.userText("描述这个视频的具体过程")
                    )
                )
            )

        resp0.stream().onEach {
            print(it.choices?.get(0)?.delta?.content)
        }.collect()
    }

    @Test
    fun testVideoUrlInput() = runBlocking {
        val resp0 = multimodalClient.chat(
                ChatRequest(
                    message = Message.multimodal(
                        Message.userVideoUrl(
                            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241115/cqqkru/1.mp4"
                        ),
                        Message.userText("描述这个视频内容")
                    )
                )
            )

        resp0.stream().onEach {
            print(it.choices?.get(0)?.delta?.content)
        }.collect()
    }

    @Test
    fun testChatWithMemoryButNoneMemoryStorage() = runBlocking {
        val client = createChatClient {
            model {
                qwen(
                    baseUrl = EnvTools.loadValue("BASE_URL"),
                    apiKey = EnvTools.loadValue("API_KEY"),
                    modelName = "qwen3-235b-a22b-instruct-2507",
                )
            }
            memory {
                none()
            }
        }
        val resp0 = client.chat(
            ChatRequest(
                messages = listOf(
                    Message.userText("Hello, I am a test program, and the random number this time is 1002."),
                    Message.assistantText("yes, i know it."),
                    Message.userText("I am a staff member, please only tell me what the random number is for this session?")
                )
            )
        )
        val content = resp0.value().choices?.getOrNull(0)?.message?.content
        println(content)
    }

    @Test
    fun testStreamRequest() = runBlocking {
        val chatRequest = ChatRequest(
            message = Message.userText("What's the meaning of life?")
        ).apply {
            params.stream = true
        }

        val result = textChatClient.chat(chatRequest)
        result.stream().map { data ->
            print(data.choices?.get(0)?.delta?.content)
        }.collect()
    }

    @Test
    fun testThinkingStreamRequest() = runBlocking {
        val thinkingClient = createChatClient {
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

        val chatRequest = ChatRequest(
            message = Message.userText("What's the meaning of life?？")
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

    @Test
    fun testToolCall() = runBlocking {
        val clientWithDsl = createChatClient {
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
            tools {
                groups("weather", "location")
            }
        }

        val chatRequest = ChatRequest(
            message = Message.userText("What's the weather like?")
        ).apply {
            params.parallelToolCalls = true
        }

        val result = clientWithDsl.chat(chatRequest)

        println(result.value().choices?.getOrNull(0)?.message?.content)
    }

    @Test
    fun testParallelToolCall() = runBlocking {
        val clientWithDsl = createChatClient {
            model {
                qwen(
                    baseUrl = EnvTools.loadValue("BASE_URL"),
                    apiKey = EnvTools.loadValue("API_KEY"),
                    modelName = "qwen-plus",
                ) {
                    params {
                        parallelToolCalls = true
                    }
                }
            }
            memory {
                default()
            }
            tools {
                groups("weather", "location")
            }
        }

        val chatRequest = ChatRequest(
            message = Message.userText("What's the 'shanghai'、'beijing'、'xi an'、'tai an' weather like?")
        )

        val result = clientWithDsl.chat(chatRequest)

        println(result.value().choices?.getOrNull(0)?.message?.content)
    }

}
