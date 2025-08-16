# Koaks-ai  

> The name **"Koaks"** is homophonic with **"coax"**.  

<div align="right">
🌐 &nbsp<a href="/README.md">English</a> | <a href="/README-zh.md">中文</a>
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
implementation("io.github.mynna404:koaks:0.0.1-beta1")
```

- Maven  
```xml
<dependency>
  <groupId>io.github.mynna404</groupId>
  <artifactId>koaks</artifactId>
  <version>0.0.1-beta1</version>
</dependency>
```

### 3. Simple

#### Connect to ChatGPT and Send a Message

```kotlin
suspend fun main() {
    val client = createChatClient {
        model {
            baseUrl = "base-url"
            apiKey = "api-key"
            modelName = "gpt-4o"
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
            baseUrl = "base-url"
            apiKey = "api-key"
            modelName = "gpt-4o"
        }
        memory {
            default()
        }
    }

    val result = client.chatWithMemory("What's the meaning of life?", "1001")
    println(resp0.value.choices?.getOrNull(0)?.message?.content)
}
```

#### Streaming Response
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
        message = "What's the meaning of life?"
    ).apply {
        params.stream = true
    }

    val result = client.chat(chatRequest)

    result.stream.map { data ->
        print(data.choices?.get(0)?.delta?.content)
    }.collect()

}
```

#### Tool Call
```kotlin
class WeatherTools {

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
        group = "weather",
        description = "Get the city where the user is located"
    )
    fun getCity(): String {
        return "Shanghai"
    }

}


fun main() {
    KoaksFramework.init(arrayOf("your package"))
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
| [medivh-publisher](https://github.com/medivh-project/medivh-publisher) | A Gradle plugin that publishes Gradle projects to Sonatype. |
| [format-print](https://github.com/mynna404/format-print) | A tool for Java and Kotlin developers to enable more readable and structured printing of object contents. |
| [kotlin-logging](https://github.com/oshai/kotlin-logging) | Lightweight multiplatform logging framework for Kotlin. A convenient and performant logging facade. |

