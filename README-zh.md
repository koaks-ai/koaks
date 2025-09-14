# Koaks-ai  

> The name **"Koaks"** is homophonic with **"coax"**.  

<div align="right">
🌐 &nbsp<a href="/README.md">English</a> | 中文
</div>

![koaks](https://socialify.git.ci/koaks-ai/koaks/image?custom_description=Connect+your+tools%2C+compose+your+logic.&description=1&font=JetBrains+Mono&forks=1&issues=1&language=1&name=1&owner=1&pattern=Circuit+Board&pulls=1&stargazers=1&theme=Light)

🧩 Connect your tools, compose your logic, rule your agents.


## 🚀 快速开始

### 1. 准备

* Kotlin 2.x / JDK 21 或更高版本
* 构建工具：Maven 或 Gradle
* 可用的 LLM API 密钥（例如：OpenAI、DeepSeek）

### 2. 引入依赖

- Gradle (Kotlin DSL)  
```kotlin
// 对于 Gradle 项目, 无论是 JVM 项目还是 Kotlin Multiplatform 项目
// 你只需要添加如下内容即可, Gradle 会自动处理不同平台的适配
implementation("io.github.mynna404:koaks-core:0.0.1-preview6")
implementation("io.github.mynna404:koaks-qwen:0.0.1-preview6")
```

- Maven  
```xml
<!-- 对于 Maven 项目来说，你需要自己区分不同的平台。 
     当然，如果你不清楚我在说什么，那么你只需要将以下内容添加到 pom.xml 中即可。 -->
<dependency>
    <groupId>io.github.mynna404</groupId>
    <artifactId>koaks-core-jvm</artifactId>
    <version>0.0.1-preview6</version>
</dependency>

<dependency>
  <groupId>io.github.mynna404</groupId>
  <artifactId>koaks-qwen-jvm</artifactId>
  <version>0.0.1-preview6</version>
</dependency>
```

### 3. 示例

> **注意: 当前项目正在快速迭代期，api随时都有可能发生变化**

#### 连接 ChatGPT 并发送消息

```kotlin
suspend fun main() {
    val client = createChatClient {
        model {
            qwen(
                baseUrl = "base-url",
                apiKey = "api-key",
                modelName = "qwen3-235b-a22b-instruct-2507",
            )
        }
    }

    val result = client.generate("生命的意义是什么?")
    println(result)
}
```

#### 带有记忆的对话
```kotlin
suspend fun main() {
    val client = createChatClient {
        model {
            qwen(
                baseUrl = "base-url",
                apiKey = "api-key",
                modelName = "qwen3-235b-a22b-instruct-2507",
            )
        }
        memory {
            // manually manage message history
            // none()
            default()
        }
    }

    val result = client.chatWithMemory("生命的意义是什么?", "1001")
    println(resp0.value.choices?.getOrNull(0)?.message?.content)
}
```

#### 流式返回
```kotlin
suspend fun main() {
    val client = createChatClient {
        model {
            qwen(
                baseUrl = "base-url",
                apiKey = "api-key",
                modelName = "qwen3-235b-a22b-instruct-2507",
            ) {
                params {
                    stream = true
                }
            }
        }
    }

    val chatRequest = ChatRequest(
        message = Message.userText("生命的意义是什么?")
    )
    
    val result = client.chat(chatRequest)

    result.stream.map { data ->
        print(data.choices?.get(0)?.delta?.content)
    }.collect()

}
```

#### 工具调用 (分为 仅JVM平台 和 全平台)
```kotlin
// 仅 JVM 平台
class WeatherTools {

    @Tool(
        params = [
            Param(param = "city", description = "城市名称, 例如: 上海", required = true),
            Param(param = "date", description = "日期, 例如: 2025-08-17", required = true)
        ],
        group = "weather",
        description = "获取特定城市今天的天气"
    )
    fun getWeather(city: String, date: String): String {
        return "$city 在 $date 的天气为多云，并有大风预警。"
    }

    @Tool(
        group = "location",
        description = "获取用户所在的城市"
    )
    fun getCity(): String {
        return "Shanghai"
    }

}


fun main() {
    Koaks.init(arrayOf("your package"))
    runBlocking {
        val client = createChatClient {
            model {
                qwen(
                    baseUrl = "base-url",
                    apiKey = "api-key",
                    modelName = "qwen3-235b-a22b-instruct-2507",
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
                // 如果没有对工具进行分组，则默认会是为 'default' 的组中的工具
//                default()
                groups("weather", "location")
            }
        }

        val chatRequest = ChatRequest(
            message = Message.userText("今天天气怎么样?")
        )

        val result = client.chat(chatRequest)

        println(result.value.choices?.getOrNull(0)?.message?.content)
    }

}
```

```kotlin
// for all platform

// use Tool interface
class WeatherImplTools : Tool<WeatherInput> {

    override val name: String = "getWeather"
    override val description: String = "获取特定城市今天的天气"
    override val group: String = "weather"
    override val serializer: KSerializer<WeatherInput> = WeatherInput.serializer()
    override val returnDirectly: Boolean = false

    override suspend fun execute(input: WeatherInput): String {
        return "对于 ${input.city} 在 ${input.date}, 天气多云，伴有强风警告。"
    }

}

@Serializable
class WeatherInput(
    @Description("城市名称，例如上海")
    val city: String,
    @Description("日期, 例如 2025-08-17")
    val date: String
)

class UserImplTools() : Tool<NoneInput> {

    override val name: String = "userLocation"
    override val description: String = "获取用户所在的城市"
    override val group: String = "location"
    override val serializer: KSerializer<NoneInput> = NoneInput.serializer()
    override val returnDirectly: Boolean = false

    override suspend fun execute(input: NoneInput): String {
        return "上海"
    }

}

// ---------------------------------------------
// use dsl
val weatherTool = createTool<WeatherInput>(
    name = "getWeather",
    description = "获取特定城市今天的天气",
    group = "weather"
) { input ->
    "${input.city} 在 ${input.date} 的天气为多云，伴有强风警告。"
}

val locationTool = createTool<NoneInput>(
    name = "userLocation",
    description = "获取用户所在的城市",
    group = "location"
) { _ ->
    "Shanghai"
}

fun main() = runBlocking {
    val client = createChatClient {
        model {
            qwen(
                baseUrl = EnvTools.loadValue("BASE_URL"),
                apiKey = EnvTools.loadValue("API_KEY"),
                modelName = "qwen3-235b-a22b-instruct-2507",
            ){
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
            // dsl
//            addTools(
//                weatherTool, locationTool
//            )
            groups("weather", "location")
        }
    }

    val chatRequest = ChatRequest(
        message = Message.userText("What's the 'shanghai'、'beijing'、'xi an'、'tai an' weather like?")
    )

    val result = client.chat(chatRequest)

    println(result.value().choices?.getOrNull(0)?.message?.content)
}
```

#### 使用多模态模型
```kotlin
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

@Test
fun testImageInput() = runBlocking {
    val resp0 = multimodalClient.chat(
        ChatRequest(
            message = Message.multimodal(
                Message.userImageUrl("https://dashscope.oss-cn-beijing.aliyuncs.com/images/dog_and_girl.jpeg"),
                Message.userText("图片里面有什么？")
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
                Message.userText("这段音频的内容是什么?")
            )
        ), "testAudioInput"
    )

    println("===== first =====")
    resp0.stream().onEach {
        print(it.choices?.get(0)?.delta?.content)
    }.collect()

    val resp1 = multimodalClient.chatWithMemory(
        ChatRequest(
            message = Message.userText("请介绍这家公司")
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
                Message.userText("视频的内容是什么?")
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
                Message.userText("视频的内容是什么?")
            )
        )
    )

    resp0.stream().onEach {
        print(it.choices?.get(0)?.delta?.content)
    }.collect()
}
```

## 🤝 贡献指南

感谢你对参与贡献感兴趣！我们欢迎你贡献代码、改进文档，或提交问题反馈。

	1. Fork 仓库
	2. 创建新分支（例如：git checkout -b feature-xxx）
	3. 提交你的更改（例如：git commit -m '添加新功能'）
	4. 推送你的分支（例如：git push origin feature-xxx）
	5. 创建 Pull Request（拉取请求）

## 💖 感谢
本项目使用了但不限于以下开源项目：

| Project | Description |
|---------|-------------|
| [Kotlin](https://github.com/JetBrains/kotlin) | The Kotlin Programming Language. |
| [kotlin-logging](https://github.com/oshai/kotlin-logging) | Lightweight multiplatform logging framework for Kotlin. A convenient and performant logging facade. |

