# koaks

<div align="right">
🌐 &nbsp English | <a href="/README-zh.md">中文</a>
</div>

> The name **"Koaks"** is homophonic with **"coax"**.  
<div align="center">
  <img width="800" height="250" alt="koaks-all" 
       src="https://github.com/user-attachments/assets/43485b9c-b67f-4446-ab3a-3b3e5e8994ea" />
</div>

![koaks](https://socialify.git.ci/koaks-ai/koaks/image?custom_description=Connect+your+tools%2C+compose+your+logic.&description=1&font=JetBrains+Mono&forks=1&issues=1&language=1&name=1&owner=1&pattern=Circuit+Board&pulls=1&stargazers=1&theme=Light)

🧩 Connect your tools, compose your logic, rule your agents.


## 🚀 Quick Start

### 1. Preparation

* Kotlin 2.x / JDK 21 or higher
* Maven or Gradle build tool
* An available LLM API Key (e.g., OpenAI, DeepSeek)

### 2. Add Dependencies

- Gradle (Kotlin DSL)  
```kotlin
// For Gradle projects, whether it's a JVM project or a Kotlin Multiplatform project, 
// you only need to add the following. Gradle will automatically handle platform adaptation.
implementation("io.github.mynna404:koaks-core:0.0.1-preview6")
implementation("io.github.mynna404:koaks-qwen:0.0.1-preview6")

```

- Maven  
```xml
<!-- For Maven projects, you need to distinguish between different platforms yourself. 
     Of course, if you’re not sure what that means, you can simply add the following to your pom.xml. -->
<dependency>
  <groupId>io.github.mynna404</groupId>
  <artifactId>koaks-core-jvm</artifactId>
  <version>0.0.1-preview6</version>
</dependency>

<dependency>
  <groupId>org.koaks.framework</groupId>
  <artifactId>koaks-qwen-jvm</artifactId>
  <version>0.0.1-preview6</version>
</dependency>
```

### 3. Simple

> **Warning: The current project is in a rapid iteration phase, and the API may change at any time.**

#### Connect to ChatGPT and Send a Message

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

    val result = client.generate("What's the meaning of life?")
    println(result)
}
```

#### Chat with Memory
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

    val result = client.chatWithMemory("What's the meaning of life?", "1001")
    println(resp0.value().choices?.getOrNull(0)?.message?.content)
}
```

#### Streaming Response
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
        message = Message.userText("What's the meaning of life?")
    )

    val result = client.chat(chatRequest)

    result.stream().map { data ->
        print(data.choices?.get(0)?.delta?.content)
    }.collect()

}
```

#### Tool Call (JVM and all platform)
```kotlin
// only JVM
class WeatherToolsForJvm {

    @Tool(
        params = [
            Param(param = "city", description = "city name, like Shanghai", required = true),
            Param(param = "date", description = "date, like 2025-08-17", required = true)
        ],
        group = "weather",
        description = "Get the weather for a specific city today."
    )
    fun getWeather(city: String, date: String): String {
        return "For $city on $date, the weather is cloudy with a high wind warning."
    }

    @Tool(
        group = "location",
        description = "Get the city where the user is located"
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
                        stream = true
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
            message = Message.userText("What's the weather like?")
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
    override val description: String = "get the weather for a specific city today."
    override val group: String = "weather"
    override val serializer: KSerializer<WeatherInput> = WeatherInput.serializer()
    override val returnDirectly: Boolean = false

    override suspend fun execute(input: WeatherInput): String {
        return "For ${input.city} on ${input.date}, the weather is cloudy with a high wind warning."
    }

}

@Serializable
class WeatherInput(
    @Description("city name, like Shanghai")
    val city: String,
    @Description("date, like 2025-08-17")
    val date: String
)

class UserImplTools() : Tool<NoneInput> {

    override val name: String = "userLocation"
    override val description: String = "get the city where the user is located"
    override val group: String = "location"
    override val serializer: KSerializer<NoneInput> = NoneInput.serializer()
    override val returnDirectly: Boolean = false

    override suspend fun execute(input: NoneInput): String {
        return "Shanghai"
    }

}

// ---------------------------------------------
// use dsl
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

#### Use Multimodal Model
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
                Message.userText("What is in the picture?")
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
                Message.userText("What is in the audio?")
            )
        ), "testAudioInput"
    )

    println("===== first =====")
    resp0.stream().onEach {
        print(it.choices?.get(0)?.delta?.content)
    }.collect()

    val resp1 = multimodalClient.chatWithMemory(
        ChatRequest(
            message = Message.userText("introduce the company")
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
                Message.userText("What is in the video?")
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
                Message.userText("What is in the video?")
            )
        )
    )

    resp0.stream().onEach {
        print(it.choices?.get(0)?.delta?.content)
    }.collect()
}
```

## 🤝 Contributing Guide

Thank you for your interest in contributing! You are welcome to contribute code, improve documentation, or submit issues.

	1.	Fork the repository
	2.	Create a new branch (git checkout -b feature-xxx)
	3.	Commit your changes (git commit -m 'Add new feature')
	4.	Push your branch (git push origin feature-xxx)
	5.	Create a Pull Request

## 💖 Acknowledgements
This project makes use of, but is not limited to, the following open-source projects:

| Project | Description |
|---------|-------------|
| [Kotlin](https://github.com/JetBrains/kotlin) | The Kotlin Programming Language. |
| [kotlin-logging](https://github.com/oshai/kotlin-logging) | Lightweight multiplatform logging framework for Kotlin. A convenient and performant logging facade. |

