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
implementation("io.github.mynna404:koaks-core:0.0.1-preview2")
```

- Maven  
```xml
<!-- 对于 Maven 项目来说，你需要自己区分不同的平台。 
     当然，如果你不清楚我在说什么，那么你只需要将以下内容添加到 pom.xml 中即可。 -->
<dependency>
    <groupId>io.github.mynna404</groupId>
    <artifactId>koaks-core-jvm</artifactId>
    <version>0.0.1-preview2</version>
</dependency>
```

### 3. 示例

> **注意: 当前项目正在快速迭代期，api随时都有可能发生变化**

#### 连接 ChatGPT 并发送消息

```kotlin
suspend fun main() {
    val client = createChatClient {
        model {
            baseUrl = "base-url"
            apiKey = "api-key"
            modelName = "gpt-4o"
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
            baseUrl = "base-url"
            apiKey = "api-key"
            modelName = "gpt-4o"
        }
        memory {
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
            baseUrl = "base-url"
            apiKey = "api-key"
            modelName = "gpt-4o"
        }
    }

    val chatRequest = ChatRequest(
        message = "生命的意义是什么?"
    ).apply {
        params.stream = true
    }

    val result = client.chat(chatRequest)

    result.stream.map { data ->
        print(data.choices?.get(0)?.delta?.content)
    }.collect()

}
```

#### 工具调用
```kotlin
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
                baseUrl = "base-url"
                apiKey = "api-key"
                modelName = "qwen-plus"
            }
            memory {
                default()
            }
            tools {
                // 如果没有对工具进行分组，则默认会是为 'default' 的组中的工具
                default()
                groups("weather", "location")
            }
        }

        val chatRequest = ChatRequest(
            message = "今天天气怎么样?"
        ).apply {
            // 在使用 tool_call 时, 暂不支持流式模式
            params.stream = false
            params.parallelToolCalls = true
        }

        val result = client.chat(chatRequest)

        println(result.value.choices?.getOrNull(0)?.message?.content)
    }

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
| [format-print](https://github.com/mynna404/format-print) | A tool for Java and Kotlin developers to enable more readable and structured printing of object contents. |
| [kotlin-logging](https://github.com/oshai/kotlin-logging) | Lightweight multiplatform logging framework for Kotlin. A convenient and performant logging facade. |

